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
 * Delivery outcomes and how they feed back into endpoint health.
 *
 * The distinction these cover is easy to get wrong and expensive to get wrong: a
 * 4xx means the payload was refused, a 5xx or a socket error means the path
 * failed. Counting a 4xx against an endpoint would let one bad bot token demote
 * a perfectly healthy front door, and keep demoting it on every retry until both
 * doors score as dead and nothing gets delivered at all.
 */
class RelayClientTest {

    private val cf = Endpoint("cf", "https://otp.example.com")
    private val ir = Endpoint("ir", "https://otp.example.ir")

    /** Public half of the interop keypair. Test-only. */
    private val sealer = RelaySealer.fromBase64Url("cPBUlcXX9VZOze4ZSgxhSk3rUs3a1w92okcGQO2Xk04")

    private class FakeConnection(
        url: String,
        private val code: Int,
        private val body: String,
        private val throwOnConnect: Boolean = false,
    ) : HttpURLConnection(URL(url)) {
        val sent = ByteArrayOutputStream()

        override fun connect() { if (throwOnConnect) throw IOException("blocked") }
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream {
            if (throwOnConnect) throw IOException("blocked")
            return sent
        }
        override fun getResponseCode(): Int {
            if (throwOnConnect) throw IOException("blocked")
            return code
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    }

    private fun clientReturning(
        vararg responses: Pair<String, FakeConnectionSpec>,
    ): Pair<RelayClient, EndpointPolicy> {
        val byHost = responses.toMap()
        val policy = EndpointPolicy(listOf(cf, ir))
        val client = RelayClient(
            policy = policy,
            sealer = sealer,
            clock = { NOW },
            open = { url ->
                val spec = byHost.entries.first { url.startsWith(it.key) }.value
                FakeConnection(url, spec.code, spec.body, spec.fails)
            },
        )
        return client to policy
    }

    data class FakeConnectionSpec(val code: Int = 200, val body: String = "{}", val fails: Boolean = false)

    @Test
    fun `a successful forward reports the relay message id`() {
        val (client, _) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(200, """{"ok":true,"messageId":77}"""),
            ir.baseUrl to FakeConnectionSpec(200, """{"ok":true,"messageId":77}"""),
        )
        val result = client.forward("tok", "42", "code", urgent = false)
        assertTrue(result is ForwardResult.Delivered)
        assertEquals(77L, (result as ForwardResult.Delivered).messageId)
    }

    @Test
    fun `a 4xx does not count against the endpoint`() {
        // One bad bot token must not poison the health of a working front door.
        val (client, policy) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(401, """{"error":"bot_token_rejected"}"""),
            ir.baseUrl to FakeConnectionSpec(401, """{"error":"bot_token_rejected"}"""),
        )
        repeat(5) { client.forward("bad", "42", "code", urgent = false) }
        assertTrue(
            "a refused payload must leave endpoint health intact",
            policy.snapshot().all { it.successRate > EndpointPolicy.HEALTHY_THRESHOLD },
        )
    }

    @Test
    fun `a 5xx does count against the endpoint`() {
        val (client, policy) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(503, "{}"),
            ir.baseUrl to FakeConnectionSpec(503, "{}"),
        )
        repeat(5) { client.forward("tok", "42", "code", urgent = false) }
        assertTrue(
            "a failing path must be demoted",
            policy.snapshot().all { it.successRate < EndpointPolicy.HEALTHY_THRESHOLD },
        )
    }

    @Test
    fun `a rejection is returned rather than retried on the other door`() {
        val (client, _) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(400, """{"error":"stale_timestamp"}"""),
            ir.baseUrl to FakeConnectionSpec(400, """{"error":"stale_timestamp"}"""),
        )
        val result = client.forward("tok", "42", "code", urgent = false)
        assertEquals(ForwardResult.Rejected("stale_timestamp"), result)
    }

    @Test
    fun `an unreachable door fails over to the working one`() {
        val (client, _) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(fails = true),
            ir.baseUrl to FakeConnectionSpec(200, """{"messageId":5}"""),
        )
        val result = client.forward("tok", "42", "code", urgent = false)
        assertTrue("must fail over, not give up", result is ForwardResult.Delivered)
    }

    @Test
    fun `all doors down reports unreachable so the message stays queued`() {
        // Must not be Rejected: that would mark the row undeliverable and drop a
        // message that a returning network would have carried fine.
        val (client, _) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(fails = true),
            ir.baseUrl to FakeConnectionSpec(fails = true),
        )
        assertEquals(ForwardResult.Unreachable, client.forward("tok", "42", "code", urgent = false))
    }

    @Test
    fun `an urgent message still delivers when one door is blocked`() {
        // The racing path must tolerate a losing endpoint that throws.
        val (client, _) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(fails = true),
            ir.baseUrl to FakeConnectionSpec(200, """{"messageId":9}"""),
        )
        val result = client.forward("tok", "42", "code", urgent = true)
        assertTrue(result is ForwardResult.Delivered)
    }

    @Test
    fun `an unparseable error body still yields a safe reason code`() {
        // The reason is written to the outbox and the log, so it must never be
        // free upstream text that could echo message content.
        val (client, _) = clientReturning(
            cf.baseUrl to FakeConnectionSpec(418, "not json at all"),
            ir.baseUrl to FakeConnectionSpec(418, "not json at all"),
        )
        val result = client.forward("tok", "42", "code", urgent = false)
        assertEquals(ForwardResult.Rejected("http_418"), result)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
