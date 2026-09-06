package ir.rhinocloud.easyotp.data

import android.content.Context
import ir.rhinocloud.easyotp.net.Endpoint
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

/** Where one SIM's messages go. A bot token is a credential; treat it as one. */
data class Destination(
    val botToken: String,
    val chatId: String,
    val label: String,
)

/**
 * A SIM and its routing, keyed by ICCID.
 *
 * [consentAcknowledged] records that the operator confirmed they may forward this
 * line's messages. Where a SIM belongs to someone else -- the household case this
 * was built for -- consent recorded at setup is the minimum defensible posture
 * (THREAT-MODEL, "Legal and consent").
 */
data class SimRoute(
    val iccid: String,
    val label: String,
    val destinations: List<Destination>,
    val consentAcknowledged: Boolean = false,
)

/**
 * All device-side configuration.
 *
 * Everything lives here because the relay stores nothing (DECISIONS D3). The blob
 * is sealed with the Keystore key before it touches disk, since it holds bot
 * tokens -- possession of one is enough to read a user's forwarded messages
 * (THREAT-MODEL A5).
 */
class Settings(
    context: Context,
    private val vault: KeyVault = KeyVault(alias = "easyotp.settings.v1"),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("easyotp.settings", Context.MODE_PRIVATE)

    var endpoints: List<Endpoint>
        get() = read().optJSONArray(KEY_ENDPOINTS)
            ?.let { array ->
                (0 until array.length()).map {
                    val o = array.getJSONObject(it)
                    Endpoint(o.getString("id"), o.getString("baseUrl"))
                }
            }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ENDPOINTS
        set(value) = mutate {
            put(
                KEY_ENDPOINTS,
                JSONArray().apply {
                    value.forEach { put(JSONObject().put("id", it.id).put("baseUrl", it.baseUrl)) }
                },
            )
        }

    /**
     * The relay's HPKE public key, pinned at enrolment.
     *
     * A changed key must be a visible, blocking event rather than a silent
     * redirect: swapping it is how a hostile relay would take over (THREAT-MODEL
     * A6). Callers compare before writing; this class only stores.
     */
    var relayPublicKey: String?
        get() = read().optString(KEY_RELAY_KEY).takeIf { it.isNotBlank() }
        set(value) = mutate { put(KEY_RELAY_KEY, value) }

    fun routes(): List<SimRoute> {
        val array = read().optJSONArray(KEY_ROUTES) ?: return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val destinations = o.optJSONArray("destinations") ?: JSONArray()
            SimRoute(
                iccid = o.getString("iccid"),
                label = o.optString("label"),
                consentAcknowledged = o.optBoolean("consent", false),
                destinations = (0 until destinations.length()).map { j ->
                    val d = destinations.getJSONObject(j)
                    Destination(
                        botToken = d.getString("botToken"),
                        chatId = d.getString("chatId"),
                        label = d.optString("label"),
                    )
                },
            )
        }
    }

    /**
     * Destinations for a SIM.
     *
     * Empty when the SIM is unknown or consent has not been acknowledged. A
     * message with nowhere to go stays queued rather than being guessed at -- the
     * same rule the capture path applies to unattributable messages.
     */
    fun destinationsFor(iccid: String): List<Destination> =
        routes().firstOrNull { it.iccid == iccid && it.consentAcknowledged }
            ?.destinations
            .orEmpty()

    fun putRoute(route: SimRoute) = mutate {
        val existing = routes().filter { it.iccid != route.iccid } + route
        put(
            KEY_ROUTES,
            JSONArray().apply {
                existing.forEach { r ->
                    put(
                        JSONObject()
                            .put("iccid", r.iccid)
                            .put("label", r.label)
                            .put("consent", r.consentAcknowledged)
                            .put(
                                "destinations",
                                JSONArray().apply {
                                    r.destinations.forEach { d ->
                                        put(
                                            JSONObject()
                                                .put("botToken", d.botToken)
                                                .put("chatId", d.chatId)
                                                .put("label", d.label),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
    }

    /** Used by the panic path: forget every credential without touching the queue. */
    fun clear() = prefs.edit().clear().apply()

    private fun read(): JSONObject {
        val stored = prefs.getString(KEY_BLOB, null) ?: return JSONObject()
        return runCatching {
            JSONObject(String(vault.open(Base64.decode(stored, Base64.NO_WRAP))))
        }.getOrElse {
            // An unreadable blob means the Keystore key is gone -- an app
            // reinstall, or a restored backup. Start empty rather than crash on
            // launch, which would leave the user with no way to re-enrol.
            JSONObject()
        }
    }

    private fun mutate(block: JSONObject.() -> Unit) {
        val next = read().apply(block)
        val sealed = Base64.encodeToString(vault.seal(next.toString().toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString(KEY_BLOB, sealed).apply()
    }

    companion object {
        private const val KEY_BLOB = "sealed"
        private const val KEY_ENDPOINTS = "endpoints"
        private const val KEY_ROUTES = "routes"
        private const val KEY_RELAY_KEY = "relayPublicKey"

        /**
         * Two front doors on one relay (DECISIONS D2). The `.ir` leg is listed
         * first only for readability; ordering at runtime is EndpointPolicy's job.
         */
        val DEFAULT_ENDPOINTS = listOf(
            Endpoint("ir", "https://otp.services.rhinocloud.ir"),
            Endpoint("cf", "https://otp.services.amirsalmani.com"),
        )
    }
}
