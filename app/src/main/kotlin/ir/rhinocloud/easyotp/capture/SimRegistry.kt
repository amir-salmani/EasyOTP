package ir.rhinocloud.easyotp.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * Resolves an incoming message to the SIM that received it.
 *
 * The identity we key on is the **ICCID**, not the slot index and not the
 * subscription id. Both of those are reassigned across reboots, SIM swaps and
 * dual-SIM toggles; keying on either silently misattributes a message to the
 * wrong person's Telegram after an unrelated reboot. On a product that routes a
 * spouse's bank codes, that is the worst failure the app can have -- it is
 * silent, and it sends a credential to the wrong reader.
 *
 * Iranian carriers generally report an empty MSISDN, so the phone number is not
 * available to identify a line. The user labels each SIM once and the label is
 * bound to the ICCID.
 */
data class SimIdentity(
    /** Stable across reboots and slot changes. The routing key. */
    val iccid: String,
    val subscriptionId: Int,
    val slotIndex: Int,
    /** Carrier-reported, for display only. Never used for routing. */
    val carrierName: String?,
    /** Usually null on Iranian carriers. Display only. */
    val number: String?,
)

class SimRegistry(private val context: Context) {

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** Every SIM currently active in the device. Empty if permission is not granted. */
    fun activeSims(): List<SimIdentity> {
        if (!hasPhoneStatePermission()) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()

        @Suppress("MissingPermission") // guarded above
        val active = sm.activeSubscriptionInfoList ?: return emptyList()

        return active.map { info ->
            SimIdentity(
                iccid = info.iccId.orEmpty(),
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrierName = info.carrierName?.toString(),
                number = info.number?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Maps the subscription id carried on an SMS_RECEIVED intent to a SIM.
     *
     * Returns null when the subscription cannot be resolved -- a message that
     * cannot be attributed must not be guessed at. Delivering it under the wrong
     * SIM's routing rules would send it to the wrong person.
     */
    fun resolve(subscriptionId: Int): SimIdentity? {
        if (subscriptionId == INVALID_SUBSCRIPTION_ID) return null
        return activeSims().firstOrNull { it.subscriptionId == subscriptionId }
    }

    companion object {
        val INVALID_SUBSCRIPTION_ID: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            } else {
                -1
            }
    }
}
