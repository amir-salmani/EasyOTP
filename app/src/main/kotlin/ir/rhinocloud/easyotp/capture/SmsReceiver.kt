package ir.rhinocloud.easyotp.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import ir.rhinocloud.easyotp.data.CapturedMessage
import ir.rhinocloud.easyotp.data.Outbox
import ir.rhinocloud.easyotp.service.ForwarderService
import java.util.concurrent.Executors

/**
 * Catches incoming SMS. Manifest-declared, so it fires with the app process dead.
 *
 * Ordering is the whole design here. The message is persisted before anything
 * else is attempted, because the process can be killed the moment this returns.
 * Any work done before the write is work that can be lost along with the message.
 * No network and no routing decisions happen on this path.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Multipart messages arrive as several PDUs sharing an originating
        // address. Forwarding each separately produces fragments, and an OTP
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

        // Keystore and SQLite off the main thread. A broadcast receiver gets
        // roughly ten seconds, and blocking here risks an ANR on a device that
        // nobody is holding to dismiss it.
        val pending = goAsync()
        WORKER.execute {
            try {
                val sim = SimRegistry(context).resolve(subscriptionId)
                if (sim == null) {
                    // Never guess. A message routed under the wrong SIM's rules
                    // goes to the wrong person's Telegram, and silently.
                    Log.w(TAG, "unattributable message dropped; sub=$subscriptionId")
                    return@execute
                }

                val stored = Outbox(context.applicationContext).enqueue(
                    CapturedMessage(
                        iccid = sim.iccid,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                    ),
                )

                // Never the sender or the body (THREAT-MODEL rule 1). Length and
                // slot are enough to tell a working pipeline from a stalled one.
                Log.i(TAG, "captured len=${body.length} slot=${sim.slotIndex} new=$stored")

                // Persist first, then nudge. If the service cannot start the
                // message is already durable and the next wake will carry it.
                if (stored) ForwarderService.wake(context.applicationContext)
            } catch (e: Exception) {
                // Swallow rather than crash: an exception escaping a manifest
                // receiver kills the app for every subsequent message too.
                Log.e(TAG, "capture failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "EasyOTP"

        /**
         * Not public API, but it is what the platform puts on the intent and it
         * is stable across every version we support.
         */
        const val SUBSCRIPTION_EXTRA = "subscription"

        /**
         * Single thread, shared across receiver instances. Each broadcast creates
         * a new receiver object, so a per-instance executor would leak a thread
         * per message.
         */
        val WORKER = Executors.newSingleThreadExecutor()
    }
}
