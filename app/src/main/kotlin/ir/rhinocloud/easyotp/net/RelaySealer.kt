package ir.rhinocloud.easyotp.net

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes

/** An HPKE envelope, split the way the relay expects it on the wire. */
data class SealedEnvelope(val enc: ByteArray, val ciphertext: ByteArray) {
    // Value semantics: data class would compare arrays by reference, which makes
    // every test assertion on an envelope silently wrong.
    override fun equals(other: Any?): Boolean =
        other is SealedEnvelope &&
            enc.contentEquals(other.enc) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * enc.contentHashCode() + ciphertext.contentHashCode()
}

/**
 * Seals payloads to the relay's public key with HPKE (RFC 9180).
 *
 * The `.ir` front door terminates TLS at an Iranian CDN (DECISIONS D2), so
 * everything between the device and the relay must be ciphertext to
 * intermediaries. This is what makes routing through that leg an acceptable
 * trade rather than a bad one.
 *
 * The cipher suite must match the Worker exactly -- X25519 + HKDF-SHA256 +
 * ChaCha20-Poly1305 -- and so must two settings that are easy to get wrong:
 *
 *  - **`Variant.NO_PREFIX`**. Tink's default `TINK` variant prepends a five-byte
 *    key-id prefix to the ciphertext. That is a Tink convention, not part of
 *    RFC 9180, and a standards-compliant implementation like hpke-js cannot open
 *    it. This single setting is the difference between interoperating and not.
 *  - **empty `contextInfo`**. Tink passes it as HPKE `info` during key schedule
 *    setup; the Worker uses the hpke-js default, which is empty. A non-empty
 *    value on one side only produces a decrypt failure with no useful diagnostic.
 *
 * Both are covered by an end-to-end fixture: an Android unit test seals with this
 * class, and worker/test/interop.spec.ts unseals the result. A change to either
 * side's settings fails CI instead of silently shipping envelopes the relay
 * cannot open.
 */
class RelaySealer(private val relayPublicKey: ByteArray) {

    init {
        require(relayPublicKey.size == X25519_PUBLIC_KEY_BYTES) {
            "relay public key must be $X25519_PUBLIC_KEY_BYTES bytes, got ${relayPublicKey.size}"
        }
    }

    private val encryptor: HybridEncrypt by lazy {
        HybridConfig.register()
        val parameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()

        val publicKey = HpkePublicKey.create(
            parameters,
            Bytes.copyFrom(relayPublicKey),
            /* idRequirement = */ null,
        )

        KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary())
            .build()
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
    }

    /**
     * Seals a payload.
     *
     * Tink returns the encapsulated key concatenated with the ciphertext. The
     * relay takes them as separate fields, and for X25519 the encapsulated key is
     * always exactly 32 bytes, so the split point is fixed rather than guessed.
     */
    fun seal(plaintext: ByteArray): SealedEnvelope {
        val output = encryptor.encrypt(plaintext, EMPTY_CONTEXT_INFO)
        check(output.size > X25519_PUBLIC_KEY_BYTES) { "HPKE output shorter than its encapsulated key" }
        return SealedEnvelope(
            enc = output.copyOfRange(0, X25519_PUBLIC_KEY_BYTES),
            ciphertext = output.copyOfRange(X25519_PUBLIC_KEY_BYTES, output.size),
        )
    }

    companion object {
        const val X25519_PUBLIC_KEY_BYTES = 32

        /** See the class comment: must stay empty to match the Worker. */
        private val EMPTY_CONTEXT_INFO = ByteArray(0)

        fun fromBase64Url(key: String): RelaySealer = RelaySealer(base64UrlDecode(key))

        fun base64UrlDecode(value: String): ByteArray {
            val padded = value.replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            return java.util.Base64.getDecoder().decode(padded)
        }

        fun base64UrlEncode(bytes: ByteArray): String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
                .replace('+', '-').replace('/', '_')
    }
}
