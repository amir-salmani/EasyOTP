package ir.rhinocloud.easyotp.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification against the messages this product will actually see: Iranian
 * bank and service SMS, in Persian and English, with Persian digits.
 */
class MessageClassifierTest {

    @Test
    fun `persian digits normalise to ascii`() {
        // Iranian senders routinely use Persian digits. A matcher that only knows
        // 0-9 fails silently on a large share of exactly the messages that matter,
        // by classifying them routine rather than by erroring.
        assertEquals("1234", MessageClassifier.normalizeDigits("۱۲۳۴"))
        assertEquals("5678", MessageClassifier.normalizeDigits("٥٦٧٨"))
        assertEquals("code 4821", MessageClassifier.normalizeDigits("code ۴۸۲۱"))
    }

    @Test
    fun `a persian otp with persian digits is urgent`() {
        val body = "رمز یکبار مصرف: ۴۸۲۱۷۳"
        assertTrue(MessageClassifier.isOtp(body))
        assertEquals("482173", MessageClassifier.extractCode(body))
    }

    @Test
    fun `a persian confirmation code is urgent`() {
        assertTrue(MessageClassifier.isOtp("کد تایید شما: ۹۹۳۱"))
    }

    @Test
    fun `an english otp is urgent`() {
        val body = "Your verification code is 483920. Do not share it."
        assertTrue(MessageClassifier.isOtp(body))
        assertEquals("483920", MessageClassifier.extractCode(body))
    }

    @Test
    fun `marketing with a number is not urgent`() {
        // Iranian SIMs drown in these. Racing every one wastes bandwidth on a
        // metered connection for no benefit.
        assertFalse(MessageClassifier.isOtp("Sale! 50000 toman off your next order"))
        assertFalse(MessageClassifier.isOtp("تخفیف ویژه ۵۰۰۰۰ تومان"))
    }

    @Test
    fun `a keyword without any code is not urgent`() {
        assertFalse(MessageClassifier.isOtp("Your password was changed successfully"))
    }

    @Test
    fun `a bare number without a keyword is not urgent`() {
        assertFalse(MessageClassifier.isOtp("12345678"))
    }

    @Test
    fun `long digit runs are not mistaken for codes`() {
        // Card and account numbers must not be picked up as codes, or the app
        // would surface a card fragment as if it were something to type in.
        assertFalse(MessageClassifier.isOtp("Card 6037991234567890 was charged"))
    }

    @Test
    fun `the short candidate wins when a reference number is present`() {
        // Balance and reference numbers are longer than the code the reader is
        // being asked to type.
        val body = "Transaction 9912345678 - your code is 4821"
        assertEquals("4821", MessageClassifier.extractCode(body))
    }

    @Test
    fun `a non-otp message yields no code`() {
        assertNull(MessageClassifier.extractCode("Your delivery 12345 is on the way"))
    }

    @Test
    fun `classification is case insensitive`() {
        assertTrue(MessageClassifier.isOtp("YOUR OTP IS 5566"))
        assertTrue(MessageClassifier.isOtp("Your Code: 5566"))
    }

    @Test
    fun `an empty or tiny message never crashes`() {
        // This runs inside a broadcast receiver, where an exception would kill
        // capture for every subsequent message too.
        assertFalse(MessageClassifier.isOtp(""))
        assertFalse(MessageClassifier.isOtp("hi"))
        assertNull(MessageClassifier.extractCode(""))
    }
}
