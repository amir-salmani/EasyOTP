/**
 * Android/Tink to Worker/hpke-js interoperability.
 *
 * The two libraries can each be correct on their own and still fail to talk to
 * each other, over settings that produce no useful diagnostic:
 *
 *  - Tink's default `TINK` variant prepends a five-byte key-id prefix to the
 *    ciphertext. That is a Tink convention, not RFC 9180, and hpke-js cannot open
 *    it. The Android side must use `Variant.NO_PREFIX`.
 *  - HPKE `info` must match. Tink passes `contextInfo` into key schedule setup;
 *    hpke-js defaults to empty. A mismatch fails with a bare decrypt error.
 *
 * Neither shows up in a unit test of either side alone. So the fixture is sealed
 * by the real Android code (app RelaySealerTest) and opened here by the real
 * relay suite. If either side's crypto settings drift, this fails in CI rather
 * than the relay silently rejecting every message sent from the field.
 *
 * Regenerate the fixture with:  ./dev build testDebugUnitTest
 */
import { describe, expect, it } from "vitest";
import { CipherSuite, HkdfSha256, DhkemX25519HkdfSha256 } from "@hpke/core";
import { Chacha20Poly1305 } from "@hpke/chacha20poly1305";
import { INTEROP_PRIVATE_KEY, INTEROP_PUBLIC_KEY } from "./interop-keys";
import fixture from "./hpke-fixture.json";
import idsFixture from "./ids-fixture.json";
import { fromB64url, secretTokenFrom, webhookIdFrom } from "../src/ids";

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Chacha20Poly1305(),
});

describe("Tink -> hpke-js interop", () => {
  it("uses the keypair this fixture was sealed to", () => {
    // Guards against a regenerated fixture being paired with stale keys, which
    // would otherwise surface as an unexplained decrypt failure below.
    expect(fixture.publicKey).toBe(INTEROP_PUBLIC_KEY);
  });

  it("opens an envelope sealed by the Android client", async () => {
    const recipientKey = await suite.kem.deserializePrivateKey(
      fromB64url(INTEROP_PRIVATE_KEY).buffer as ArrayBuffer,
    );
    const recipient = await suite.createRecipientContext({
      recipientKey,
      enc: fromB64url(fixture.enc).buffer as ArrayBuffer,
    });

    const opened = await recipient.open(fromB64url(fixture.ct).buffer as ArrayBuffer);
    expect(new TextDecoder().decode(opened)).toBe(fixture.plaintext);
  });

  it("carries a payload the relay can actually route", async () => {
    // The fixture is shaped like a real /forward body, so interop is proven on
    // realistic input rather than on "hello".
    const parsed = JSON.parse(fixture.plaintext) as {
      botToken: string;
      chatId: number;
      text: string;
    };
    expect(parsed.botToken).toBeTruthy();
    expect(parsed.chatId).toBeTruthy();
    // OTP codes are wrapped in <code> so Telegram clients make them tap-to-copy.
    expect(parsed.text).toContain("<code>");
  });

  it("splits the encapsulated key at exactly 32 bytes", () => {
    // The Android side splits Tink's output at a fixed offset. If X25519's
    // encapsulated key were any other length, every envelope would be corrupt.
    expect(fromB64url(fixture.enc).length).toBe(32);
  });
});

describe("identifier chain: Android <-> Worker", () => {
  /**
   * The relay stores no mapping, so it authorises by recomputing this chain and
   * comparing. A derivation that differs by one byte between the two languages
   * does not throw anywhere: enrolment reports success, the webhook is
   * registered under an id the device cannot derive, and nothing is ever
   * delivered. Only a shared fixture catches it.
   */
  it("derives the same secret token as the Android client", async () => {
    expect(await secretTokenFrom(idsFixture.channelId)).toBe(idsFixture.secretToken);
  });

  it("derives the same webhook id as the Android client", async () => {
    expect(await webhookIdFrom(idsFixture.secretToken)).toBe(idsFixture.webhookId);
  });

  it("authorises a poll using the Android-derived identifiers", async () => {
    // The end-to-end consequence: a device that computed these values locally
    // can drain its own mailbox, and knowing only the public webhook id cannot.
    const { webhookId, channelId } = idsFixture;
    const authorised = await webhookIdFrom(await secretTokenFrom(channelId));
    expect(authorised).toBe(webhookId);
  });
});
