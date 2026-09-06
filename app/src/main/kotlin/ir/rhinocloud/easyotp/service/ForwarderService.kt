package ir.rhinocloud.easyotp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import ir.rhinocloud.easyotp.R
import ir.rhinocloud.easyotp.capture.MessageClassifier
import ir.rhinocloud.easyotp.data.Outbox
import ir.rhinocloud.easyotp.data.Settings
import ir.rhinocloud.easyotp.net.EndpointPolicy
import ir.rhinocloud.easyotp.net.ForwardResult
import ir.rhinocloud.easyotp.net.RelayClient
import ir.rhinocloud.easyotp.net.RelaySealer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drains the outbox.
 *
 * A foreground service rather than plain WorkManager because this also has to
 * hold the inbound long-poll open (M2), and because the phone is unattended in
 * another country: the persistent notification is the only signal that the
 * pipeline is alive.
 *
 * Declared `specialUse`, not `dataSync`. Android 15 caps `dataSync` foreground
 * services at six hours per twenty-four, so a forwarder using it dies daily and
 * silently -- see DECISIONS D7.
 */
class ForwarderService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val draining = AtomicBoolean(false)

    private val outbox by lazy { Outbox(applicationContext) }
    private val settings by lazy { Settings(applicationContext) }

    private val client by lazy {
        val relayKey = settings.relayPublicKey
            ?: error("no relay key pinned; enrolment must run before forwarding")
        RelayClient(
            policy = EndpointPolicy(settings.endpoints),
            sealer = RelaySealer.fromBase64Url(relayKey),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // From API 34 the type must be passed explicitly when the manifest
        // declares one, or startForeground throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification(0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(0))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        drain()
        // Restart if killed, but without redelivering the intent: the work to do
        // is whatever is in the outbox, not whatever this particular intent said.
        return START_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun drain() {
        // One drain at a time. Concurrent passes would claim the same rows and
        // deliver duplicates, which is exactly what the dedupe key exists to
        // avoid on the relay side and should not be created here.
        if (!draining.compareAndSet(false, true)) return

        worker.execute {
            try {
                var delivered = 0
                while (true) {
                    val batch = outbox.claimReady(System.currentTimeMillis())
                    if (batch.isEmpty()) break

                    for (message in batch) {
                        val destinations = settings.destinationsFor(message.iccid)
                        if (destinations.isEmpty()) {
                            // Known SIM, no approved destination yet. Leave it
                            // queued: the user may still be finishing pairing,
                            // and discarding it would lose a real message.
                            outbox.markFailed(message.id, "no_destination", System.currentTimeMillis())
                            continue
                        }

                        val urgent = MessageClassifier.isOtp(message.body)
                        val text = format(message.sender, message.body)

                        // All destinations must succeed before the row is done.
                        // Marking sent on partial delivery would silently drop a
                        // second reader's copy.
                        val results = destinations.map { destination ->
                            client.forward(destination.botToken, destination.chatId, text, urgent)
                        }

                        when {
                            results.all { it is ForwardResult.Delivered } -> {
                                outbox.markSent(message.id)
                                delivered++
                            }
                            results.any { it is ForwardResult.Rejected } -> {
                                val reason = results.filterIsInstance<ForwardResult.Rejected>()
                                    .first().reason
                                outbox.markFailed(message.id, reason, System.currentTimeMillis())
                            }
                            else -> outbox.markFailed(
                                message.id,
                                "unreachable",
                                System.currentTimeMillis(),
                            )
                        }
                    }
                    // Every failure path above advances next_attempt_at, so a
                    // batch that fails entirely is not re-claimed and this
                    // terminates instead of spinning against a dead network.
                    updateNotification(outbox.pendingCount())
                }

                outbox.purgeSentBefore(System.currentTimeMillis() - ARCHIVE_RETENTION_MILLIS)
                Log.i(TAG, "drain complete delivered=$delivered pending=${outbox.pendingCount()}")
            } catch (e: Exception) {
                // Never let the drain thread die: the service would stay alive
                // showing a healthy notification while delivering nothing.
                Log.e(TAG, "drain failed: ${e.javaClass.simpleName}")
            } finally {
                draining.set(false)
            }
        }
    }

    /**
     * Renders the Telegram message.
     *
     * A detected code goes in a `<code>` block, which every Telegram client makes
     * tap-to-copy. That is the difference between reading digits off a screen and
     * pasting them.
     */
    private fun format(sender: String, body: String): String {
        val code = MessageClassifier.extractCode(body)
        val header = "<b>${escape(sender)}</b>"
        return if (code != null) {
            "$header\n<code>${escape(code)}</code>\n\n${escape(body)}"
        } else {
            "$header\n${escape(body)}"
        }
    }

    /** Telegram HTML mode: these three characters would otherwise break parsing. */
    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun notification(pending: Long): Notification {
        ensureChannel()
        val text = if (pending == 0L) {
            getString(R.string.notification_idle)
        } else {
            getString(R.string.notification_pending, pending)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(pending: Long) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(pending))
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                // Low: this notification exists because Android requires one for
                // a foreground service, not because it is worth interrupting for.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "EasyOTP"
        private const val CHANNEL_ID = "easyotp.forwarder"
        private const val NOTIFICATION_ID = 1

        /** Delivered rows are history; the archive must not grow without bound. */
        private const val ARCHIVE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Called by the capture path and on boot. Safe to call repeatedly. */
        fun wake(context: Context) {
            val intent = Intent(context, ForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
