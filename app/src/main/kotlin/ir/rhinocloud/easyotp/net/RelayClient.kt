package ir.rhinocloud.easyotp.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the caller needs to decide whether to retry. Never carries message content. */
sealed class ForwardResult {
    data class Delivered(val messageId: Long, val endpointId: String) : ForwardResult()

    /** The relay or Telegram refused it. Retrying the same bytes will not help. */
    data class Rejected(val reason: String) : ForwardResult()

    /** No front door answered. Worth retrying when the network returns. */
    data object Unreachable : ForwardResult()
}

/**
 * Sends sealed payloads to the relay over whichever front door is working.
 *
 * Policy decisions live in [EndpointPolicy]; this class only performs I/O and
 * reports outcomes back.
 *
 * Racing uses [ExecutorService.invokeAny], which returns the first successful
 * result and cancels the rest -- exactly the semantics wanted, straight from the
 * JDK, without a coroutines dependency for one call site.
 *
 * HTTP is the platform client rather than OkHttp; the reasoning is DECISIONS D14.
 */
class RelayClient(
    private val policy: EndpointPolicy,
    private val sealer: RelaySealer,
    private val racePool: ExecutorService = Executors.newCachedThreadPool(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {

    /**
     * Forwards one message.
     *
     * @param urgent true for anything that looks like a one-time code, which
     *   races both front doors. See ARCHITECTURE section 2.
     */
    fun forward(
        botToken: String,
        chatId: String,
        text: String,
        urgent: Boolean,
    ): ForwardResult {
        val body = JSONObject()
            .put("botToken", botToken)
            .put("chatId", chatId)
            .put("text", text)
            .toString()
            .toByteArray()

        val envelope = envelope(sealer.seal(body))
        val endpoints = policy.ranked(clock())

        return if (policy.shouldRace(urgent)) {
            race(endpoints, envelope)
        } else {
            sequential(endpoints, envelope)
        }
    }

    /** First success wins; losing requests are cancelled. */
    private fun race(endpoints: List<Endpoint>, envelope: String): ForwardResult {
        val tasks = endpoints.map { endpoint ->
            Callable {
                when (val r = post(endpoint, envelope)) {
                    is ForwardResult.Delivered -> r
                    // invokeAny only reports a task that returns normally, so a
                    // failed attempt must throw or it would win the race.
                    else -> throw RelayAttemptFailed(r)
                }
            }
        }
        return try {
            racePool.invokeAny(tasks, RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Every endpoint failed. Prefer a definite rejection over a timeout:
            // a rejection means retrying these bytes is pointless.
            (e.cause as? RelayAttemptFailed)?.result ?: ForwardResult.Unreachable
        }
    }

    /** Healthiest first, failing over immediately. */
    private fun sequential(endpoints: List<Endpoint>, envelope: String): ForwardResult {
        var last: ForwardResult = ForwardResult.Unreachable
        for (endpoint in endpoints) {
            when (val result = post(endpoint, envelope)) {
                is ForwardResult.Delivered -> return result
                // A rejection is the relay's verdict on the payload, not on the
                // path. The other door would give the same answer.
                is ForwardResult.Rejected -> return result
                ForwardResult.Unreachable -> last = result
            }
        }
        return last
    }

    private fun post(endpoint: Endpoint, envelope: String): ForwardResult {
        val started = clock()
        var connection: HttpURLConnection? = null
        return try {
            connection = open("${endpoint.baseUrl}/forward").apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("accept", "application/json")
            }
            connection.outputStream.use { it.write(envelope.toByteArray()) }

            val code = connection.responseCode
            val elapsed = clock() - started

            if (code in 200..299) {
                policy.record(endpoint.id, success = true, latencyMillis = elapsed, now = clock())
                val payload = connection.inputStream.use { it.readBytes().decodeToString() }
                ForwardResult.Delivered(
                    messageId = runCatching { JSONObject(payload).optLong("messageId") }.getOrDefault(0L),
                    endpointId = endpoint.id,
                )
            } else {
                // A 5xx is the path failing; a 4xx is the payload being refused.
                // Only the former counts against the endpoint -- otherwise one bad
                // bot token would demote a perfectly healthy front door, and keep
                // demoting it on every retry until both look dead.
                val pathFailure = code >= 500
                policy.record(endpoint.id, success = !pathFailure, latencyMillis = elapsed, now = clock())
                if (pathFailure) {
                    ForwardResult.Unreachable
                } else {
                    val payload = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                    ForwardResult.Rejected(reasonOf(payload, code))
                }
            }
        } catch (e: Exception) {
            policy.record(endpoint.id, success = false, latencyMillis = clock() - started, now = clock())
            ForwardResult.Unreachable
        } finally {
            connection?.disconnect()
        }
    }

    /** Envelope shape must match worker/src/index.ts. */
    private fun envelope(sealed: SealedEnvelope) = JSONObject()
        .put("v", 1)
        .put("ts", clock() / 1000)
        .put("enc", RelaySealer.base64UrlEncode(sealed.enc))
        .put("ct", RelaySealer.base64UrlEncode(sealed.ciphertext))
        .toString()

    /**
     * Extracts the relay's machine-readable reason code.
     *
     * Only the known `error` field, never free text. An upstream message can echo
     * message content, and this value is written to the outbox and to the log
     * (THREAT-MODEL rule 1).
     */
    private fun reasonOf(payload: String, code: Int): String =
        runCatching { JSONObject(payload).optString("error").takeIf { it.isNotBlank() } }
            .getOrNull() ?: "http_$code"

    private class RelayAttemptFailed(val result: ForwardResult) :
        Exception(null, null, false, false)

    companion object {
        /**
         * Short by design. This runs on a phone that is either reachable quickly
         * or blocked outright; a long timeout only delays failover past the life
         * of the code being delivered.
         */
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val RACE_TIMEOUT_SECONDS = 25L
    }
}
