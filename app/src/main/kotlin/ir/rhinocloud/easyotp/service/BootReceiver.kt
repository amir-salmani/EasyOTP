package ir.rhinocloud.easyotp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts forwarding after a reboot or an app update.
 *
 * The handset sits unattended in another country. Without this, a power cut or
 * an OS update stops delivery until someone physically opens the app -- which,
 * for the person relying on it, looks identical to the service being blocked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> ForwarderService.wake(context)
        }
    }
}
