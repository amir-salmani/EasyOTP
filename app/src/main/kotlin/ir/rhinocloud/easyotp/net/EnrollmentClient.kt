package ir.rhinocloud.easyotp.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of registering a bot with the relay. */
sealed class EnrollResult {
    /** The relay accepted the token and pointed Telegram's webhook at itself. */
    data class Enrolled(val botUsername: String, val webhookId: String) : EnrollResult()

    /** Telegram or the relay refused. Retrying the same token will not help. */
    data class Refused(val reason: String) : EnrollResult()

    data object Unreachable : EnrollResult()
}

/** A pairing request seen on the mailbox: someone messaged the user's bot. */
data class PairingRequest(
    val chatId: String,
    val fromId: String,
    val displayName: String,
    val text: String,
)

/**
 * Enrolment and pairing.
 *
 * Both halves exist because the device cannot reach `api.telegram.org` from Iran:
 * the relay calls `setWebhook` on the phone's behalf, and inbound updates come
 * back over a long-poll because the phone is behind CGNAT (DECISIONS D4).
 *
 * The bot token is sealed before it leaves the device and the relay discards it
 * after use. It is the credential that would let someone read every forwarded
 * message (THREAT-MODEL A5), so it never travels or rests in the clear.
 */
class EnrollmentClient(
    private val endpoints: List<Endpoint>,
    private val sealer: RelaySealer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {

    /**
     * Registers a bot.
     *
     * Tried against each front door in turn rather than raced: enrolment is a
     * one-off the user is watching, so ordinary failover is enough and racing
     * would register the same webhook twice.
     */
    fun enroll(botToken: String, channelId: String): EnrollResult {
        val sealed = sealer.seal(
            JSONObject()
                .put("botToken", botToken)
                .put("channelId", channelId)
                .toString()
                .toByteArray(),
        )
        val envelope = JSONObject()
            .put("v", 1)
            .put("ts", clock() / 1000)
            .put("enc", RelaySealer.base64UrlEncode(sealed.enc))
            .put("ct", RelaySealer.base64UrlEncode(sealed.ciphertext))
            .toString()

        var last: EnrollResult = EnrollResult.Unreachable
        for (endpoint in endpoints) {
            when (val result = postEnroll(endpoint, envelope)) {
                is EnrollResult.Enrolled -> return result
                // A refusal is a verdict on the token, identical at either door.
                is EnrollResult.Refused -> return result
                EnrollResult.Unreachable -> last = result
            }
        }
        return last
    }

    private fun postEnroll(endpoint: Endpoint, envelope: String): EnrollResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("${endpoint.baseUrl}/enroll").apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("content-type", "application/json")
            }
            connection.outputStream.use { it.write(envelope.toByteArray()) }

            val code = connection.responseCode
            if (code in 200..299) {
                val body = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
                EnrollResult.Enrolled(
                    botUsername = body.optString("botUsername"),
                    webhookId = body.optString("webhookId"),
                )
            } else if (code >= 500) {
                EnrollResult.Unreachable
            } else {
                val body = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                EnrollResult.Refused(reasonOf(body, code))
            }
        } catch (e: Exception) {
            EnrollResult.Unreachable
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Collects pairing requests waiting in the mailbox.
     *
     * The relay holds the connection open for about a minute, so the read timeout
     * must exceed that or every poll would look like a failure. Returns empty on
     * a quiet interval, which is the normal case rather than an error.
     */
    fun poll(endpoint: Endpoint, webhookId: String, channelId: String): List<PairingRequest> {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("${endpoint.baseUrl}/poll/$webhookId").apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = POLL_READ_TIMEOUT_MILLIS
                // The channelId proves the caller is the device that enrolled.
                // Telegram knows the secret token but cannot derive this.
                setRequestProperty("authorization", "Bearer $channelId")
            }
            if (connection.responseCode !in 200..299) return emptyList()
            val body = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
            parseUpdates(body.optJSONArray("updates") ?: JSONArray())
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseUpdates(updates: JSONArray): List<PairingRequest> =
        (0 until updates.length()).mapNotNull { i ->
            runCatching {
                val message = JSONObject(updates.getString(i)).optJSONObject("message")
                    ?: return@runCatching null
                val chat = message.optJSONObject("chat") ?: return@runCatching null
                val from = message.optJSONObject("from")
                PairingRequest(
                    chatId = chat.opt("id")?.toString().orEmpty(),
                    fromId = from?.opt("id")?.toString().orEmpty(),
                    displayName = listOfNotNull(
                        from?.optString("first_name")?.takeIf { it.isNotBlank() },
                        from?.optString("username")?.takeIf { it.isNotBlank() }?.let { "@$it" },
                    ).joinToString(" ").ifBlank { "unknown" },
                    text = message.optString("text"),
                )
            }.getOrNull()
        }.filter { it.chatId.isNotBlank() }

    private fun reasonOf(payload: String, code: Int): String =
        runCatching { JSONObject(payload).optString("error").takeIf { it.isNotBlank() } }
            .getOrNull() ?: "http_$code"

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 20_000

        /**
         * Must exceed the relay's hold (about 55s) or every quiet poll would be
         * read as a network failure and back off for no reason.
         */
        const val POLL_READ_TIMEOUT_MILLIS = 75_000
    }
}
