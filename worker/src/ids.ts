/**
 * The identifier chain.
 *
 * The relay stores nothing, so every authorisation decision has to be
 * verifiable from the request alone. That is done with a one-way chain:
 *
 *     channelId  --H-->  secretToken  --H-->  webhookId
 *      (device)          (Telegram)            (public)
 *
 * - `channelId` is 32 random bytes generated on the device. It never leaves the
 *   device except to the relay over TLS, and it is the capability that permits
 *   draining the mailbox.
 * - `secretToken` is what Telegram echoes back in `X-Telegram-Bot-Api-Secret-Token`.
 *   Telegram knows it, so it must not be sufficient to read the mailbox.
 * - `webhookId` appears in the public webhook URL and grants nothing at all.
 *
 * Each link is preimage-resistant, so possession of a later value never yields an
 * earlier one. The relay verifies by hashing forward, which needs no stored state:
 *
 *   - webhook delivery: H(presented secretToken) == webhookId in the path
 *   - mailbox drain:    H(H(presented channelId)) == webhookId in the path
 *
 * Domain separation strings stop a value from one position being replayed into
 * another.
 */
const enc = new TextEncoder();

async function h(input: Uint8Array, domain: string): Promise<string> {
  const buf = new Uint8Array(input.length + domain.length);
  buf.set(input, 0);
  buf.set(enc.encode(domain), input.length);
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return b64url(new Uint8Array(digest));
}

export function b64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromB64url(s: string): Uint8Array {
  const p = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(p + "=".repeat((4 - (p.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export const secretTokenFrom = (channelId: string) => h(fromB64url(channelId), "easyotp/tg-secret/v1");
export const webhookIdFrom = (secretToken: string) => h(fromB64url(secretToken), "easyotp/webhook-id/v1");

/**
 * Constant-time string comparison. These compare secret-derived values, so a
 * length-or-content early exit would leak them a byte at a time.
 */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
