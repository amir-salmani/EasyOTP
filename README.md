# EasyOTP

Forward SMS from SIM cards you hold to a Telegram bot you own, over a relay that
stores nothing.

Built for a problem every Iranian immigrant has and few products solve: your bank,
your government portal, your exchange, and your old employer all send OTPs to an
Iranian number, and you no longer live where that number works. The usual answers are
to leave a phone with family, or to trust a third-party SMS-forwarding service with
every credential you own. Neither is good.

## What it is

- An **Android app** on a phone that holds the SIMs, in the country the SIMs belong to.
- A **Cloudflare Worker** that relays, holds no database, and stores nothing at rest.
- **Your own Telegram bot**, created by you with @BotFather. The token never leaves
  your device except sealed, and the relay discards it after use.

Multiple SIMs, each routed to its own bot and chat. Your line to you, your partner's
line to them.

## What it is honest about

**Telegram bot chats are not end-to-end encrypted.** Telegram can read every message
this forwards. That is a property of Telegram, not a flaw we can patch, and any
product claiming otherwise is lying to you. Use a private chat, enable 2FA, and decide
knowingly.

**On the hosted relay, the operator could technically read plaintext** — the Worker
unseals in memory to call Telegram. Bodies are never logged and tokens are never
persisted, but those are promises about code. That is why the source is open and why
self-hosting is a first-class path, not a footnote. Read the code, or run your own.

Full analysis: [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md).

## How it works

Design: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Settled decisions and what was rejected: [`docs/DECISIONS.md`](docs/DECISIONS.md).

Short version: a manifest-declared `BroadcastReceiver` catches SMS even with the app
process dead, persists to an encrypted on-device outbox before anything else, and a
foreground service races the message over two independent front doors to a stateless
Worker, which calls your bot. Commands travel back over a long-poll, because the phone
is behind CGNAT. Nothing is stored anywhere but the phone.

## Status

Relay implemented and tested. Android app builds and produces a signed debug APK;
M1 capture path in progress.

## License

**AGPL-3.0-only.** Client and relay both. A security tool nobody can audit is a
security tool nobody should install, and the AGPL means a hosted fork owes its users
the same source you get.

The hosted relay is the paid product; self-hosting is free and always will be.
