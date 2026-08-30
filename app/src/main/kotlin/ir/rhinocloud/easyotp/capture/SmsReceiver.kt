package ir.rhinocloud.easyotp.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Catches incoming SMS. Manifest-declared, so it fires with the app process dead.
 *
 * This runs on the main thread with roughly ten seconds before the system may
 * kill the process, so it does the least possible: reassemble, attribute, hand
 * off, return. No network, no crypto, no disk beyond the outbox write.
 *
 * Ordering matters. The message is persisted **before** anything else is
 * attempted, because the process can be killed the moment this returns. Anything
 * done before the write is work that can be lost along with the message.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Multipart messages arrive as several PDUs sharing an originating
        // address. Forwarding each PDU separately produces fragments, and an OTP
        // split across two of them is useless in both halves.
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val sender = parts.first().displayOriginatingAddress ?: return
        val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val receivedAt = parts.first().timestampMillis

        val subscriptionId = intent.getIntExtra(
            SUBSCRIPTION_EXTRA,
            SimRegistry.INVALID_SUBSCRIPTION_ID,
        )
        val sim = SimRegistry(context).resolve(subscriptionId)
        if (sim == null) {
            // Never guess. An unattributed message routed under the wrong SIM's
            // rules goes to the wrong person's Telegram, silently.
            Log.w(TAG, "unattributable message dropped from routing; sub=$subscriptionId")
            return
        }

        // TODO(M1): persist to the encrypted outbox, then enqueue delivery.
        // Deliberately not logging sender or body -- THREAT-MODEL rule 1.
        Log.i(TAG, "captured len=${body.length} slot=${sim.slotIndex} at=$receivedAt")
    }

    private companion object {
        const val TAG = "EasyOTP"

        /**
         * Not public API, but it is what the platform actually puts on the
         * intent and it is stable across every version we support. The
         * SubscriptionManager constant covers the same key.
         */
        const val SUBSCRIPTION_EXTRA = "subscription"
    }
}
