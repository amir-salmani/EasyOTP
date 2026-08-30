/**
 * HPKE unsealing (RFC 9180: X25519 + HKDF-SHA256 + ChaCha20-Poly1305).
 *
 * Payloads are sealed on the device to the relay's public key. The `.ir` front
 * door terminates TLS at an Iranian CDN (DECISIONS D2), so everything between
 * device and relay must be ciphertext to intermediaries. This is what makes that
 * leg an acceptable trade rather than a bad one.
 *
 * Note what this does NOT provide: HPKE base mode is public-key encryption, so
 * anyone holding the relay's public key can seal a valid envelope. It gives
 * confidentiality from the network, not authentication of the sender. The
 * credential is the bot token inside the envelope — matching reality, since
 * anyone holding that token could message the bot directly anyway.
 */
import { CipherSuite, HkdfSha256, DhkemX25519HkdfSha256 } from "@hpke/core";
import { Chacha20Poly1305 } from "@hpke/chacha20poly1305";
import { fromB64url } from "./ids";

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Chacha20Poly1305(),
});

export class SealError extends Error {}

let cachedKey: CryptoKey | null = null;

async function privateKey(raw: string): Promise<CryptoKey> {
  // Cached per isolate. The key is a Worker secret, never written anywhere.
  if (!cachedKey) cachedKey = await suite.kem.deserializePrivateKey(fromB64url(raw).buffer as ArrayBuffer);
  return cachedKey;
}

export async function unseal<T>(rawPrivateKey: string, enc: string, ct: string): Promise<T> {
  let plaintext: ArrayBuffer;
  try {
    const recipient = await suite.createRecipientContext({
      recipientKey: await privateKey(rawPrivateKey),
      enc: fromB64url(enc).buffer as ArrayBuffer,
    });
    plaintext = await recipient.open(fromB64url(ct).buffer as ArrayBuffer);
  } catch {
    // Deliberately opaque: a detailed AEAD failure is a decryption oracle.
    throw new SealError("unseal failed");
  }
  try {
    return JSON.parse(new TextDecoder().decode(plaintext)) as T;
  } catch {
    throw new SealError("malformed plaintext");
  }
}

/**
 * Reject envelopes outside the replay window before any unsealing work happens
 * (THREAT-MODEL rule 6) — cheap check first, so a replay flood cannot force
 * expensive asymmetric crypto.
 */
export function withinReplayWindow(ts: number, windowSeconds: number): boolean {
  if (!Number.isFinite(ts)) return false;
  return Math.abs(Date.now() / 1000 - ts) <= windowSeconds;
}
