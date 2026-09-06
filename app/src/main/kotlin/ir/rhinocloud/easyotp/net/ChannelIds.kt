package ir.rhinocloud.easyotp.net

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The identifier chain, device side.
 *
 * Must match worker/src/ids.ts byte for byte. The relay stores nothing, so it
 * authorises by hashing forward and comparing; a derivation that differs by a
 * single byte means enrolment appears to succeed and then nothing is ever
 * delivered, with no error that points at the cause.
 *
 *     channelId  --H-->  secretToken  --H-->  webhookId
 *      (device)          (Telegram)            (public URL)
 *
 * Each link is preimage-resistant, so a later value never yields an earlier one.
 * Telegram holds `secretToken` and can post updates but cannot drain the mailbox;
 * `webhookId` sits in a public URL and grants nothing. Only the device holds
 * `channelId`, which is why pairing can be approved on the handset (DECISIONS D4).
 *
 * The domain separation strings stop a value in one position being replayed into
 * another, and are part of the wire contract: changing one here without changing
 * the Worker breaks every existing installation.
 *
 * Verified against the Worker by a shared fixture -- see ChannelIdsTest and
 * worker/test/interop.spec.ts.
 */
object ChannelIds {

    private const val TG_SECRET_DOMAIN = "easyotp/tg-secret/v1"
    private const val WEBHOOK_ID_DOMAIN = "easyotp/webhook-id/v1"

    /** 32 bytes from a CSPRNG. This value is the capability to read the mailbox. */
    fun newChannelId(random: SecureRandom = SecureRandom()): String =
        RelaySealer.base64UrlEncode(ByteArray(32).also { random.nextBytes(it) })

    fun secretTokenFrom(channelId: String): String =
        hash(RelaySealer.base64UrlDecode(channelId), TG_SECRET_DOMAIN)

    fun webhookIdFrom(secretToken: String): String =
        hash(RelaySealer.base64UrlDecode(secretToken), WEBHOOK_ID_DOMAIN)

    /** Convenience: the public identifier for a channel, two hops along. */
    fun webhookIdForChannel(channelId: String): String =
        webhookIdFrom(secretTokenFrom(channelId))

    /**
     * SHA-256 over raw bytes followed by the UTF-8 domain string.
     *
     * The concatenation order and the fact that the domain is appended rather
     * than prepended are both part of the contract with the Worker.
     */
    private fun hash(input: ByteArray, domain: String): String {
        val domainBytes = domain.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input + domainBytes)
        return RelaySealer.base64UrlEncode(digest)
    }
}
