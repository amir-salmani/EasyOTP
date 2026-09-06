package ir.rhinocloud.easyotp.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enrolment and pairing, including the parsing of Telegram updates that arrive
 * second-hand through the relay's mailbox.
 */
class EnrollmentClientTest {

    private val cf = Endpoint("cf", "https://otp.example.com")
    private val ir = Endpoint("ir", "https://otp.example.ir")
    private val sealer = RelaySealer.fromBase64Url("cPBUlcXX9VZOze4ZSgxhSk3rUs3a1w92okcGQO2Xk04")

    private class Fake(
        url: String,
        private val code: Int,
        private val body: String,
        private val fails: Boolean = false,
    ) : HttpURLConnection(URL(url)) {
        val sent = ByteArrayOutputStream()
        override fun connect() { if (fails) throw IOException("blocked") }
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = if (fails) throw IOException("blocked") else sent
        override fun getResponseCode(): Int = if (fails) throw IOException("blocked") else code
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    }

    private data class Spec(val code: Int = 200, val body: String = "{}", val fails: Boolean = false)

    private fun client(vararg specs: Pair<String, Spec>): EnrollmentClient {
        val byHost = specs.toMap()
        return EnrollmentClient(
            endpoints = listOf(cf, ir),
            sealer = sealer,
            clock = { 1_700_000_000_000L },
            open = { url ->
                val spec = byHost.entries.first { url.startsWith(it.key) }.value
                Fake(url, spec.code, spec.body, spec.fails)
            },
        )
    }

    @Test
    fun `a successful enrolment returns the bot username`() {
        val c = client(
            cf.baseUrl to Spec(200, """{"ok":true,"botUsername":"amir_otp_bot","webhookId":"abc"}"""),
            ir.baseUrl to Spec(200, "{}"),
        )
        val result = c.enroll("123:TOKEN", ChannelIds.newChannelId())
        assertTrue(result is EnrollResult.Enrolled)
        assertEquals("amir_otp_bot", (result as EnrollResult.Enrolled).botUsername)
    }

    @Test
    fun `a bad token is refused rather than retried on the other door`() {
        // Telegram's verdict on a token is the same at either front door, so
        // failing over would just show the user the same error twice as slowly.
        val c = client(
            cf.baseUrl to Spec(401, """{"error":"bot_token_rejected"}"""),
            ir.baseUrl to Spec(401, """{"error":"bot_token_rejected"}"""),
        )
        assertEquals(
            EnrollResult.Refused("bot_token_rejected"),
            c.enroll("nope", ChannelIds.newChannelId()),
        )
    }

    @Test
    fun `enrolment fails over when one door is blocked`() {
        val c = client(
            cf.baseUrl to Spec(fails = true),
            ir.baseUrl to Spec(200, """{"botUsername":"b","webhookId":"w"}"""),
        )
        assertTrue(c.enroll("123:TOKEN", ChannelIds.newChannelId()) is EnrollResult.Enrolled)
    }

    @Test
    fun `both doors down reports unreachable`() {
        val c = client(cf.baseUrl to Spec(fails = true), ir.baseUrl to Spec(fails = true))
        assertEquals(EnrollResult.Unreachable, c.enroll("123:TOKEN", ChannelIds.newChannelId()))
    }

    @Test
    fun `a pairing request is parsed from a mailbox update`() {
        val update = """{"update_id":1,"message":{"chat":{"id":4242},"from":{"id":99,"first_name":"Amir","username":"amir"},"text":"/start"}}"""
        val c = client(
            cf.baseUrl to Spec(200, """{"updates":[${quote(update)}]}"""),
            ir.baseUrl to Spec(200, "{}"),
        )
        val requests = c.poll(cf, "webhook", "channel")
        assertEquals(1, requests.size)
        assertEquals("4242", requests[0].chatId)
        assertEquals("99", requests[0].fromId)
        assertTrue(requests[0].displayName.contains("Amir"))
    }

    @Test
    fun `chat ids are read as exact integers not floating point`() {
        // Telegram group chat ids exceed 2^53 in magnitude. Parsed as a double
        // they lose precision, and the app would then pair to a chat that does
        // not exist -- silently, since the relay just forwards what it is given.
        val update = """{"message":{"chat":{"id":-1001234567890123},"from":{"id":1},"text":"hi"}}"""
        val c = client(
            cf.baseUrl to Spec(200, """{"updates":[${quote(update)}]}"""),
            ir.baseUrl to Spec(200, "{}"),
        )
        assertEquals("-1001234567890123", c.poll(cf, "w", "c")[0].chatId)
    }

    @Test
    fun `a quiet poll returns empty rather than failing`() {
        // The relay holds the connection open and returns nothing on a quiet
        // interval. That is the normal case, not an error.
        val c = client(cf.baseUrl to Spec(200, """{"updates":[]}"""), ir.baseUrl to Spec(200, "{}"))
        assertTrue(c.poll(cf, "w", "c").isEmpty())
    }

    @Test
    fun `a malformed update is skipped without losing the rest`() {
        // One unparseable update must not discard a legitimate pairing request
        // sitting next to it in the same batch.
        val good = """{"message":{"chat":{"id":7},"from":{"id":8},"text":"/start"}}"""
        val c = client(
            cf.baseUrl to Spec(200, """{"updates":["not json",${quote(good)}]}"""),
            ir.baseUrl to Spec(200, "{}"),
        )
        val requests = c.poll(cf, "w", "c")
        assertEquals(1, requests.size)
        assertEquals("7", requests[0].chatId)
    }

    @Test
    fun `an update with no chat is ignored`() {
        val c = client(
            cf.baseUrl to Spec(200, """{"updates":[${quote("""{"update_id":5}""")}]}"""),
            ir.baseUrl to Spec(200, "{}"),
        )
        assertTrue(c.poll(cf, "w", "c").isEmpty())
    }

    private fun quote(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
