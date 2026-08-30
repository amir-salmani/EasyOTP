/**
 * EasyOTP relay.
 *
 * Stateless by construction: no KV, no D1, no R2, and the one Durable Object
 * holds updates in memory for seconds. Every authorisation decision is derived
 * from the request via the hash chain in ids.ts.
 *
 * See ../../docs/ARCHITECTURE.md and ../../docs/THREAT-MODEL.md.
 */
import { ChannelMailbox } from "./mailbox";
import { chTag, log } from "./log";
import { secretTokenFrom, timingSafeEqual, webhookIdFrom } from "./ids";
import { SealError, unseal, withinReplayWindow } from "./seal";
import { getMe, sendMessage, setWebhook } from "./telegram";

export { ChannelMailbox };

export interface Env {
  MAILBOX: DurableObjectNamespace<ChannelMailbox>;
  /** HPKE X25519 private key, base64url. Worker secret. */
  RELAY_PRIVATE_KEY: string;
  /**
   * Public origin Telegram posts webhooks to. Pinned rather than derived from
   * the request: the device may enrol through either front door, but Telegram
   * must always be pointed at the Cloudflare one. The `.ir` leg exists for
   * Iranian *egress*, where consumer-ISP blocking of Cloudflare is the problem;
   * Telegram's servers have no such difficulty and should not depend on an
   * Iranian CDN to deliver.
   */
  WEBHOOK_BASE: string;
  REPLAY_WINDOW_SECONDS: string;
  POLL_HOLD_SECONDS: string;
}

/** Outer envelope. Cleartext fields are routing only — never content. */
interface Envelope {
  v: number;
  ts: number;
  enc: string;
  ct: string;
}

interface EnrollPayload {
  botToken: string;
  channelId: string;
}

interface ForwardPayload {
  botToken: string;
  chatId: number | string;
  text: string;
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

/** Uniform rejection. Callers get a stable reason; details stay in the relay. */
const reject = (route: string, reason: string, status = 400, ch?: string) => {
  log({ route, outcome: "rejected", reason, status, ch });
  return json({ error: reason }, status);
};

async function readEnvelope(req: Request, env: Env, route: string): Promise<Envelope | Response> {
  let e: Envelope;
  try {
    e = (await req.json()) as Envelope;
  } catch {
    return reject(route, "malformed_envelope");
  }
  if (e.v !== 1) return reject(route, "unsupported_version");
  if (!e.enc || !e.ct) return reject(route, "malformed_envelope");
  // Cheap check before any asymmetric crypto, so replay floods stay cheap.
  if (!withinReplayWindow(e.ts, Number(env.REPLAY_WINDOW_SECONDS))) {
    return reject(route, "stale_timestamp", 401);
  }
  return e;
}

async function handleEnroll(req: Request, env: Env): Promise<Response> {
  const started = Date.now();
  const envelope = await readEnvelope(req, env, "enroll");
  if (envelope instanceof Response) return envelope;

  let payload: EnrollPayload;
  try {
    payload = await unseal<EnrollPayload>(env.RELAY_PRIVATE_KEY, envelope.enc, envelope.ct);
  } catch (err) {
    return reject("enroll", err instanceof SealError ? "unseal_failed" : "internal", 400);
  }
  if (!payload.botToken || !payload.channelId) return reject("enroll", "incomplete_payload");

  const secretToken = await secretTokenFrom(payload.channelId);
  const webhookId = await webhookIdFrom(secretToken);
  const ch = chTag(webhookId);

  const me = await getMe(payload.botToken);
  if (!me.ok || !me.result) {
    return reject("enroll", "bot_token_rejected", 401, ch);
  }

  const hook = await setWebhook(payload.botToken, `${env.WEBHOOK_BASE}/w/${webhookId}`, secretToken);
  if (!hook.ok) {
    return reject("enroll", "set_webhook_failed", 502, ch);
  }

  log({ route: "enroll", outcome: "ok", ch, ms: Date.now() - started });
  // The bot token goes out of scope here and is never written anywhere.
  return json({ ok: true, botUsername: me.result.username, webhookId });
}

async function handleForward(req: Request, env: Env): Promise<Response> {
  const started = Date.now();
  const envelope = await readEnvelope(req, env, "forward");
  if (envelope instanceof Response) return envelope;

  let payload: ForwardPayload;
  try {
    payload = await unseal<ForwardPayload>(env.RELAY_PRIVATE_KEY, envelope.enc, envelope.ct);
  } catch (err) {
    return reject("forward", err instanceof SealError ? "unseal_failed" : "internal", 400);
  }
  if (!payload.botToken || !payload.chatId || !payload.text) {
    return reject("forward", "incomplete_payload");
  }

  const sent = await sendMessage(payload.botToken, payload.chatId, payload.text);
  if (!sent.ok || !sent.result) {
    // `description` can echo message content, so it is not logged or returned.
    log({ route: "forward", outcome: "upstream_error", status: sent.error_code, ms: Date.now() - started });
    return json({ error: "telegram_rejected", code: sent.error_code ?? 0 }, 502);
  }

  log({ route: "forward", outcome: "ok", ms: Date.now() - started });
  return json({ ok: true, messageId: sent.result.message_id });
}

/** Telegram → relay. Authorised by hashing the presented secret forward to the path id. */
async function handleWebhook(req: Request, env: Env, webhookId: string): Promise<Response> {
  const presented = req.headers.get("x-telegram-bot-api-secret-token") ?? "";
  const derived = presented ? await webhookIdFrom(presented) : "";
  if (!presented || !timingSafeEqual(derived, webhookId)) {
    return reject("webhook", "bad_secret", 403, chTag(webhookId));
  }

  const body = await req.text();
  await env.MAILBOX.get(env.MAILBOX.idFromName(webhookId)).deliver(body);

  log({ route: "webhook", outcome: "ok", ch: chTag(webhookId) });
  // Telegram retries on non-2xx, so acknowledge as soon as it is queued.
  return json({ ok: true });
}

/** Device → relay long-poll. Only the channelId holder can drain; Telegram cannot. */
async function handlePoll(req: Request, env: Env, webhookId: string): Promise<Response> {
  const channelId = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!channelId) return reject("poll", "missing_credential", 401, chTag(webhookId));

  const derived = await webhookIdFrom(await secretTokenFrom(channelId));
  if (!timingSafeEqual(derived, webhookId)) {
    return reject("poll", "bad_credential", 403, chTag(webhookId));
  }

  const holdMs = Number(env.POLL_HOLD_SECONDS) * 1000;
  const updates = await env.MAILBOX.get(env.MAILBOX.idFromName(webhookId)).drain(holdMs);

  log({ route: "poll", outcome: updates.length ? "ok" : "timeout", ch: chTag(webhookId) });
  return json({ updates });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;

    // Liveness for the device-side transport racer. Must stay trivially cheap:
    // it is polled by every device on both front doors.
    if (path === "/health") return json({ ok: true });

    if (req.method === "POST" && path === "/enroll") return handleEnroll(req, env);
    if (req.method === "POST" && path === "/forward") return handleForward(req, env);

    const webhook = path.match(/^\/w\/([A-Za-z0-9_-]{43})$/);
    if (req.method === "POST" && webhook) return handleWebhook(req, env, webhook[1]!);

    const poll = path.match(/^\/poll\/([A-Za-z0-9_-]{43})$/);
    if (req.method === "GET" && poll) return handlePoll(req, env, poll[1]!);

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;
