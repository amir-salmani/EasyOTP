package ir.rhinocloud.easyotp.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover behaviour, which is the part of this app that has to work on the day
 * the network is worst. Every case below is a real failure mode of a handset on
 * an Iranian consumer ISP, not a hypothetical.
 */
class EndpointPolicyTest {

    private val cf = Endpoint("cf", "https://otp.example.com")
    private val ir = Endpoint("ir", "https://otp.example.ir")

    private fun policy() = EndpointPolicy(listOf(cf, ir))

    private fun fail(p: EndpointPolicy, id: String, times: Int, at: Long = NOW) {
        repeat(times) { p.record(id, success = false, latencyMillis = 10_000, now = at) }
    }

    private fun succeed(p: EndpointPolicy, id: String, latency: Long = 100, times: Int = 1, at: Long = NOW) {
        repeat(times) { p.record(id, success = true, latencyMillis = latency, now = at) }
    }

    @Test
    fun `a blocked endpoint is demoted within a few attempts`() {
        // Iranian filtering changes on the scale of minutes. A policy that needed
        // dozens of failures to react would spend an outage talking to a wall.
        val p = policy()
        succeed(p, "ir")
        fail(p, "cf", times = 3)
        assertEquals("ir", p.ranked(NOW).first().id)
    }

    @Test
    fun `reliability beats speed`() {
        // A fast path that drops one in five is worse than a slow one that always
        // works: a dropped OTP is a failed login, a slow OTP is a slow login.
        val p = policy()
        succeed(p, "cf", latency = 50, times = 5)
        fail(p, "cf", times = 2)
        succeed(p, "ir", latency = 800, times = 5)
        assertEquals("ir", p.ranked(NOW).first().id)
    }

    @Test
    fun `among equally reliable endpoints the faster one wins`() {
        val p = policy()
        succeed(p, "cf", latency = 60, times = 5)
        succeed(p, "ir", latency = 900, times = 5)
        assertEquals("cf", p.ranked(NOW).first().id)
    }

    @Test
    fun `a recovered endpoint is re-probed rather than written off forever`() {
        // Without this the app silently degrades to one front door after the
        // first outage, which defeats the reason there are two.
        val p = policy()
        fail(p, "cf", times = 10)
        succeed(p, "ir", times = 10)
        assertEquals("ir", p.ranked(NOW).first().id)

        val later = NOW + EndpointPolicy.DEFAULT_PROBE_INTERVAL_MILLIS + 1
        assertEquals("cf must get another chance", "cf", p.ranked(later).first().id)
    }

    @Test
    fun `urgent traffic races both endpoints`() {
        // An OTP is worthless after about a minute; 2x bandwidth on a sub-kilobyte
        // request to halve tail latency is the correct trade.
        val p = policy()
        succeed(p, "cf", times = 10)
        assertTrue(p.shouldRace(urgent = true))
    }

    @Test
    fun `routine traffic does not race when something is healthy`() {
        val p = policy()
        succeed(p, "cf", times = 5)
        assertFalse(p.shouldRace(urgent = false))
    }

    @Test
    fun `routine traffic races when nothing is known to be healthy`() {
        // During an outage, spraying beats picking wrong and waiting a timeout.
        val p = policy()
        fail(p, "cf", times = 10)
        fail(p, "ir", times = 10)
        assertTrue(p.shouldRace(urgent = false))
    }

    @Test
    fun `a single endpoint never races itself`() {
        val p = EndpointPolicy(listOf(cf))
        assertFalse(p.shouldRace(urgent = true))
    }

    @Test
    fun `an untried endpoint is not written off before its first attempt`() {
        // Starting pessimistic would mean a fresh install prefers whichever
        // endpoint it happened to try first, permanently.
        val p = policy()
        assertEquals(2, p.ranked(NOW).size)
        assertTrue(p.snapshot().all { it.successRate > EndpointPolicy.HEALTHY_THRESHOLD })
    }

    @Test
    fun `failure latency does not make a dead endpoint look merely slow`() {
        // Timeouts must not be folded into the latency average, or a blocked path
        // scores as a slow one and keeps getting picked.
        val p = policy()
        succeed(p, "cf", latency = 50, times = 3)
        val before = p.snapshot().first { it.id == "cf" }.latencyMillis
        fail(p, "cf", times = 3)
        val after = p.snapshot().first { it.id == "cf" }.latencyMillis
        assertEquals("timeouts must not shift latency", before, after)
    }

    @Test
    fun `every endpoint remains reachable in the ranking`() {
        // Ranking must order, never drop: an endpoint missing from the list can
        // never be tried, so a transient failure would become permanent.
        val p = policy()
        fail(p, "cf", times = 50)
        assertEquals(2, p.ranked(NOW).size)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
