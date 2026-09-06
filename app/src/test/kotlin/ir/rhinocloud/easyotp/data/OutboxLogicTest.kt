package ir.rhinocloud.easyotp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the outbox that decide identity and retry timing.
 *
 * Both fail silently when wrong: a bad dedupe key either drops a real message or
 * sends the same OTP twice, and a bad backoff leaves a queue asleep through the
 * window in which a code is still valid. Neither shows up in a crash report.
 */
class OutboxLogicTest {

    private fun message(
        iccid: String = "8998100000000000001",
        sender: String = "BANKMELLI",
        body: String = "Your code is 4821",
        receivedAt: Long = 1_700_000_000_000,
    ) = CapturedMessage(iccid, sender, body, receivedAt)

    @Test
    fun `redelivery of the same message collapses to one key`() {
        // The platform can deliver the same SMS more than once; the user must not
        // see the same code twice.
        val a = Outbox.dedupeKey(message(receivedAt = 1_700_000_000_000))
        val b = Outbox.dedupeKey(message(receivedAt = 1_700_000_003_000))
        assertEquals("same message 3s apart must share a key", a, b)
    }

    @Test
    fun `an identical code sent again later stays distinct`() {
        // Banks legitimately send the same text twice. Collapsing those would
        // swallow a code the user is waiting for -- the worse failure of the two.
        val a = Outbox.dedupeKey(message(receivedAt = 1_700_000_000_000))
        val b = Outbox.dedupeKey(message(receivedAt = 1_700_000_060_000))
        assertNotEquals("same text a minute later is a new message", a, b)
    }

    @Test
    fun `the same text on a different SIM is a different message`() {
        // Two SIMs in one handset can receive identical text from the same
        // shortcode. They route to different people and must not collapse.
        assertNotEquals(
            Outbox.dedupeKey(message(iccid = "8998100000000000001")),
            Outbox.dedupeKey(message(iccid = "8998100000000000002")),
        )
    }

    @Test
    fun `different senders do not collide`() {
        assertNotEquals(
            Outbox.dedupeKey(message(sender = "BANKMELLI")),
            Outbox.dedupeKey(message(sender = "SNAPP")),
        )
    }

    @Test
    fun `backoff grows but is capped at five minutes`() {
        assertEquals(5_000, Outbox.backoffMillis(1))
        assertEquals(10_000, Outbox.backoffMillis(2))
        assertTrue(Outbox.backoffMillis(4) > Outbox.backoffMillis(3))

        // The ceiling is the point: this device sits on a network that vanishes
        // for hours, and an uncapped curve would still be asleep when it returns.
        assertEquals(300_000, Outbox.backoffMillis(8))
        assertEquals(300_000, Outbox.backoffMillis(50))
    }

    @Test
    fun `backoff never returns zero or negative`() {
        // A zero would spin the drain loop against a dead network, burning the
        // battery of a phone nobody is there to charge.
        for (attempt in 0..64) {
            assertTrue("attempt $attempt", Outbox.backoffMillis(attempt) > 0)
        }
    }
}
