# Architecture

Three components. No database, no accounts, no server-side state at rest.

```
  ┌─────────────────────────────┐
  │  Android device (in Iran)   │      SIM 1 ─┐
  │  ─────────────────────────  │             ├─ dual-SIM handset
  │  SmsReceiver (manifest)     │      SIM 2 ─┘
  │       ↓ persist first       │
  │  Room outbox (encrypted)    │  ← the only durable store in the system
  │       ↓                     │
  │  ForegroundService          │
  │   ├ TransportRacer          │
  │   └ CommandPoller           │
  └───────────┬─────────────────┘
              │  sealed envelope (HPKE), ECDSA-signed
     ┌────────┴────────┐
     ▼                 ▼
 otp.services       otp.services
 .rhinocloud.ir     .amirsalmani.com
 (Arvan CDN)        (Cloudflare)
     │                 │
     └────────┬────────┘
              ▼
  ┌─────────────────────────────┐
  │  Cloudflare Worker          │  stateless; unseals in memory, never logs bodies
  │   ├ /enroll   setWebhook    │
  │   ├ /forward  → Telegram    │
  │   ├ /w/<ch>   ← Telegram    │
  │   └ /poll/<ch> long-poll    │
  │  ChannelMailbox (DO)        │  memory-only, seconds of retention
  └───────────┬─────────────────┘
              ▼
        Telegram Bot API  →  user's own bot  →  user's chat
```

## 1. Capture path (device)

`SmsReceiver` is **manifest-declared**, so it fires even with the app process dead.
It does the minimum and returns fast:

1. Read PDUs; resolve `subscriptionId` from the intent extra.
2. Map `subscriptionId` → slot → **ICCID** via `SubscriptionManager`. ICCID is the
   stable key; slot index and subscription id both change across reboots and swaps.
3. Write to the encrypted Room outbox. **Persist before anything else** — the process
   may be killed the instant we return.
4. Enqueue delivery work.

Iranian carriers generally report an empty MSISDN, so the user labels each SIM once in
the app ("MCI — personal", "Rightel — spouse") and the label binds to the ICCID.

Multipart SMS must be reassembled before classification; an OTP split across two PDUs
is common and a naive per-PDU forward produces two useless fragments.

## 2. Delivery path (device → Worker)

The `ForegroundService` (`specialUse`, see D7) owns delivery. `WorkManager` with a
network constraint is the backstop for when the service is not running.

**Transport racing.** Two endpoints, chosen per message class:

- **OTP-classified: both endpoints in parallel, first success wins.** An OTP is
  worthless after ~60s. Spending 2× on a sub-kilobyte request to halve tail latency is
  obviously correct. The Worker dedupes, so double delivery costs nothing.
- **Everything else: sticky-best.** Per-endpoint EWMA of success rate and latency;
  send to the winner, fail over instantly on error, re-probe the loser periodically so
  a recovered path gets picked back up. Never round-robin — it halves your signal.

**Dedupe key.** SMS has no message id. Use
`SHA256(iccid ‖ sender ‖ body ‖ floor(ts/10s))`, computed on-device and echoed by the
Worker, so retries and raced duplicates collapse to one Telegram message.

**Backpressure.** The outbox is durable and ordered. A national shutdown means messages
queue and flush on restore (see D2). Exponential backoff with jitter, capped; no
unbounded retry storms when the network is half-up.

## 3. Command path (Telegram → device)

The phone is behind CGNAT and cannot be reached inbound. So:

- Telegram POSTs an update to `/w/<channelId>`, authenticated by the webhook
  `secret_token` header set at enrollment.
- The Worker hands it to `ChannelMailbox`, a Durable Object named by `channelId`,
  held **in memory only**.
- The phone holds a long-poll on `/poll/<channelId>` — ~55s server-side hold, signed
  request, immediate re-poll on return. One connection per channel; two bots means two
  idle sockets, which is negligible for battery.

No FCM. Google Play Services reachability from Iran is unreliable and adding it would
make the app depend on a service we cannot guarantee.

## 4. The Worker

Stateless. Every request carries what it needs.

| Route | Direction | Purpose |
|---|---|---|
| `POST /enroll` | device → | Unseal bot token, call Telegram `setWebhook`, return `getMe`, discard token |
| `POST /forward` | device → | Unseal `{botToken, chatId, text}`, call `sendMessage`, return message id |
| `POST /w/<ch>`  | ← Telegram | Verify `secret_token`, park update in mailbox |
| `GET  /poll/<ch>` | device ↔ | Long-poll the mailbox, signed |
| `GET  /health`  | device → | Endpoint liveness for the racer |

Rules:

- **Never log message bodies.** Structured logs carry channel id, outcome, latency —
  nothing else. This is enforced by a lint rule, not by discipline.
- Bot tokens exist only inside a request's isolate lifetime.
- Rate limiting via the Workers rate-limit binding, keyed by channel — no counters to
  persist.
- Reject any request whose timestamp is outside the replay window.

## 5. Registration flow

```
 user ──▶ @BotFather ──▶ bot token
                            │ paste / QR from desktop
                            ▼
 app: generate channel keypair in Keystore, seal token to Worker pubkey
                            │ POST /enroll
                            ▼
 worker: setWebhook(https://<host>/w/<channelId>, secret_token) ──▶ Telegram
 worker: getMe ──▶ bot username ──▶ app shows "connected to @foo_bot"
 worker: discards token
                            │
 user ──▶ /start to their own bot ──▶ Telegram ──▶ /w/<ch> ──▶ mailbox
                            │ phone's long-poll collects it
                            ▼
 app: "chat 12345 (Amir) wants to pair. Approve?"  ← authorization root:
                                                     physical possession
```

`channelId` is 32 random bytes generated on the device. Identifiers descend from it by
a one-way chain, so the relay can authorise every request without storing anything:

```
channelId  --H-->  secretToken  --H-->  webhookId
 (device only)     (Telegram knows)     (public URL)
```

Each link is preimage-resistant, so a later value never yields an earlier one. Telegram
holds `secretToken` and can therefore post updates but cannot drain the mailbox; the
`webhookId` in the public URL grants nothing at all. Only the device holds `channelId`.
Verification is a forward hash and a constant-time compare.

## 6. Vendor survival

The code is identical across OEMs. What differs is the setup ritual, so the app ships
a wizard that detects the manufacturer, deep-links the right settings intent (MIUI
autostart, Samsung "Unrestricted", Huawei protected apps, Oppo/Vivo startup manager),
and then **self-tests**: injects a synthetic message through the real pipeline and
reports whether it survived to Telegram. The self-test is what makes it general — we
verify instead of guessing per-vendor.

Nothing Phone 2 (Nothing OS, near-AOSP, dual nano-SIM) is the reference device and the
easy case. Development must not target only it, or the app dies instantly on MIUI.

## 7. Permissions

`RECEIVE_SMS`, `READ_SMS`, `READ_PHONE_STATE` (subscription info), `INTERNET`,
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`,
`POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

Later milestones add `SEND_SMS`, `READ_CALL_LOG`, `READ_PHONE_NUMBERS`, `CALL_PHONE`
(USSD). Each is requested at the point of use, never up front — an OTP forwarder that
asks for the world on first launch is indistinguishable from spyware.

## 8. Milestones

**M1 — the spine.** Dual-SIM capture pinned to ICCID → encrypted outbox → transport
racer → Worker → user's bot. Enrollment and on-device pairing. Heartbeat alerting.
OEM wizard + self-test. This is the shippable product.

**M2 — control.** Long-poll command channel: send SMS per-SIM from Telegram, `/status`
(battery, signal, network, queue depth), USSD (`/ussd *140#`) for balance and package
checks. This turns monitoring into a remote hand on the device.

**M3 — hygiene.** Sender rules engine (Iranian SIMs drown in marketing), missed-call
notification per SIM, on-device searchable archive, SIM keep-alive scheduler for the
inactivity-deactivation problem, remote config so rules change without a rebuild.
