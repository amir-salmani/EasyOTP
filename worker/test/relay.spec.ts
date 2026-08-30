/**
 * These tests exist to hold docs/THREAT-MODEL.md to account. Each block names
 * the rule or adversary it defends, so a future change that quietly weakens a
 * property fails here rather than in production.
 */
import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { CipherSuite, HkdfSha256, DhkemX25519HkdfSha256 } from "@hpke/core";
import { Chacha20Poly1305 } from "@hpke/chacha20poly1305";
import { TEST_RELAY_PUBLIC_KEY } from "./keys";
import { fromB64url, secretTokenFrom, webhookIdFrom, timingSafeEqual, b64url } from "../src/ids";

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Chacha20Poly1305(),
});

/** Seals a payload the way the Android client will. */
async function seal(payload: unknown, ts = Math.floor(Date.now() / 1000)) {
  const pk = await suite.kem.deserializePublicKey(fromB64url(TEST_RELAY_PUBLIC_KEY).buffer as ArrayBuffer);
  const sender = await suite.createSenderContext({ recipientPublicKey: pk });
  const ct = await sender.seal(new TextEncoder().encode(JSON.stringify(payload)));
  return { v: 1, ts, enc: b64url(new Uint8Array(sender.enc)), ct: b64url(new Uint8Array(ct)) };
}

function randomChannelId() {
  return b64url(crypto.getRandomValues(new Uint8Array(32)));
}

describe("identifier chain (THREAT-MODEL A5, A6)", () => {
  it("derives one way only: channelId -> secretToken -> webhookId", async () => {
    const channelId = randomChannelId();
    const secretToken = await secretTokenFrom(channelId);
    const webhookId = await webhookIdFrom(secretToken);

    expect(secretToken).not.toBe(channelId);
    expect(webhookId).not.toBe(secretToken);
    // 32 bytes base64url, unpadded.
    expect(webhookId).toHaveLength(43);
  });

  it("is deterministic, so the relay never has to store the mapping", async () => {
    const channelId = randomChannelId();
    expect(await secretTokenFrom(channelId)).toBe(await secretTokenFrom(channelId));
  });

  it("compares in constant time", () => {
    expect(timingSafeEqual("abc", "abc")).toBe(true);
    expect(timingSafeEqual("abc", "abd")).toBe(false);
    expect(timingSafeEqual("abc", "ab")).toBe(false);
  });
});

describe("mailbox authorisation", () => {
  it("refuses a poll from someone holding only the webhook id", async () => {
    // The webhook id is in a URL Telegram knows and is not secret.
    const webhookId = await webhookIdFrom(await secretTokenFrom(randomChannelId()));
    const res = await SELF.fetch(`https://relay.test/poll/${webhookId}`, {
      headers: { authorization: `Bearer ${webhookId}` },
    });
    expect(res.status).toBe(403);
  });

  it("refuses a poll from someone holding only the Telegram secret token", async () => {
    // Telegram knows the secret token. It must not be able to read the mailbox.
    const channelId = randomChannelId();
    const secretToken = await secretTokenFrom(channelId);
    const webhookId = await webhookIdFrom(secretToken);
    const res = await SELF.fetch(`https://relay.test/poll/${webhookId}`, {
      headers: { authorization: `Bearer ${secretToken}` },
    });
    expect(res.status).toBe(403);
  });

  it("refuses a poll with no credential at all", async () => {
    const webhookId = await webhookIdFrom(await secretTokenFrom(randomChannelId()));
    const res = await SELF.fetch(`https://relay.test/poll/${webhookId}`);
    expect(res.status).toBe(401);
  });

  it("refuses a webhook delivery with a wrong secret token", async () => {
    const webhookId = await webhookIdFrom(await secretTokenFrom(randomChannelId()));
    const res = await SELF.fetch(`https://relay.test/w/${webhookId}`, {
      method: "POST",
      headers: { "x-telegram-bot-api-secret-token": randomChannelId() },
      body: "{}",
    });
    expect(res.status).toBe(403);
  });
});

describe("end-to-end command path", () => {
  it("delivers a Telegram update to the channel holder's poll", async () => {
    const channelId = randomChannelId();
    const secretToken = await secretTokenFrom(channelId);
    const webhookId = await webhookIdFrom(secretToken);

    const polling = SELF.fetch(`https://relay.test/poll/${webhookId}`, {
      headers: { authorization: `Bearer ${channelId}` },
    });

    const delivered = await SELF.fetch(`https://relay.test/w/${webhookId}`, {
      method: "POST",
      headers: { "x-telegram-bot-api-secret-token": secretToken },
      body: JSON.stringify({ update_id: 1, message: { text: "/status" } }),
    });
    expect(delivered.status).toBe(200);

    const body = (await (await polling).json()) as { updates: string[] };
    expect(body.updates).toHaveLength(1);
    expect(JSON.parse(body.updates[0]!).update_id).toBe(1);
  });
});

describe("replay window (THREAT-MODEL rule 6)", () => {
  it("rejects a stale envelope before doing any unsealing work", async () => {
    const stale = await seal({ botToken: "x", channelId: randomChannelId() }, 1000);
    const res = await SELF.fetch("https://relay.test/enroll", {
      method: "POST",
      body: JSON.stringify(stale),
    });
    expect(res.status).toBe(401);
    expect((await res.json() as { error: string }).error).toBe("stale_timestamp");
  });

  it("rejects an envelope sealed to the wrong key without revealing why", async () => {
    const res = await SELF.fetch("https://relay.test/enroll", {
      method: "POST",
      body: JSON.stringify({ v: 1, ts: Math.floor(Date.now() / 1000), enc: b64url(new Uint8Array(32)), ct: b64url(new Uint8Array(48)) }),
    });
    expect(res.status).toBe(400);
    // Opaque on purpose: a descriptive AEAD failure is a decryption oracle.
    expect((await res.json() as { error: string }).error).toBe("unseal_failed");
  });

  it("rejects an unsupported envelope version", async () => {
    const res = await SELF.fetch("https://relay.test/enroll", {
      method: "POST",
      body: JSON.stringify({ v: 99, ts: Math.floor(Date.now() / 1000), enc: "a", ct: "b" }),
    });
    expect(res.status).toBe(400);
  });
});

describe("statelessness (DECISIONS D3, THREAT-MODEL rule 2)", () => {
  it("exposes no persistent storage binding", () => {
    // If someone adds KV, D1 or R2 to wrangler.jsonc, this fails. The relay
    // storing nothing is a threat-model property, not a config preference.
    for (const forbidden of ["EASYOTP_KV", "DB", "BUCKET"]) {
      expect(env).not.toHaveProperty(forbidden);
    }
    const bindings = Object.entries(env as Record<string, unknown>);
    const storageLike = bindings.filter(([, v]) => {
      const n = v?.constructor?.name ?? "";
      return /KvNamespace|D1Database|R2Bucket/i.test(n);
    });
    expect(storageLike).toEqual([]);
  });
});

describe("health", () => {
  it("answers cheaply for the transport racer", async () => {
    const res = await SELF.fetch("https://relay.test/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });
});
