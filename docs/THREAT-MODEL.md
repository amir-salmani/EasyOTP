# Threat model

Written before the code, so the code can be checked against it.

An OTP forwarder is a credential-interception tool pointed at yourself. That is
exactly what an attacker wants to install on your phone. Every design choice below
follows from taking that seriously.

## Assets

| Asset | Where it lives | Impact if lost |
|---|---|---|
| OTP codes in transit | phone → Worker → Telegram | account takeover: bank, gov, exchange |
| Bot token | phone (Keystore-wrapped); transiently in Worker memory | attacker impersonates the bot, reads and injects |
| Device signing key | Android Keystore, non-exportable | attacker forges forwards or commands |
| SMS archive | phone, encrypted Room DB | historical codes, contacts, financial pattern |
| Routing config | phone | attacker redirects OTPs to their own chat |

## Adversaries

**A1 — Network observer (Iranian ISP, DPI, ArvanCloud).** Sees TLS metadata and,
because Arvan terminates TLS on the `.ir` leg, the HTTP body. **This is why payloads
are HPKE-sealed**: the body is ciphertext to everyone between the phone and the
Worker. Residual exposure is metadata — timing, size, endpoint, frequency. An observer
learns that a device forwards messages and roughly when. Not mitigated; padding to
fixed buckets is a possible later hardening.

**A2 — Relay operator (Rhinocloud, on the hosted tier).** The Worker unseals in
memory to call Telegram, so **the hosted relay operator can technically read
plaintext**. Mitigations are bodies never logged (lint-enforced), tokens never
persisted, no storage bindings that could retain anything. But these are promises
about code, and the honest answer to "can Rhinocloud read my OTPs?" is *the deployed
code decides, and you should read it*. This asymmetry is the entire reason the source
is open and self-hosting is a first-class path — not a nice-to-have.

**A3 — Telegram.** Sees every forwarded message in plaintext. **Bot chats are not
end-to-end encrypted and cannot be made so** while remaining readable in a Telegram
client. Any product claiming an E2E Telegram OTP forwarder is lying. Stated in the
README, not buried. Mitigations are the user's: private chat, 2FA on the account, and
knowing the trade. A "sealed mode" — ciphertext to Telegram, decrypted in a companion
viewer — is possible and destroys the usability that makes the product worth having.

**A4 — Attacker with physical access to the handset.** Wins. It is the SIM. Nothing in
software fixes possession of the device holding the SIM. Reduce blast radius: app lock
(biometric/PIN) on config changes, no plaintext archive at rest, remote `/wipe` from
an approved chat.

**A5 — Attacker who steals the bot token.** Can read forwarded messages and send as
the bot. Cannot inject commands to the device — those require an **approved chat_id**,
and approval happens on the phone. Recovery is revoking the token in @BotFather and
re-enrolling.

**A6 — Malicious or hostile Worker deployment.** A user pointed at a hostile relay
loses everything. Mitigation: the app pins the Worker's HPKE public key at enrollment
and refuses a silent key change — a swapped relay is a visible, blocking event, not a
quiet redirect.

**A7 — Replay / injection on the forward path.** Every request is ECDSA-signed over a
canonical body plus timestamp, with a nonce and a bounded acceptance window. A
replayed forward is rejected; a forged one fails signature.

## Non-goals

- **Hiding that the app exists** from someone inspecting the phone. It runs a
  persistent notification by design and by Android policy.
- **Protecting against a compromised Android OS** (rootkit, hostile OEM firmware).
- **Surviving a national internet shutdown.** Messages queue on-device and flush on
  restore. SMS fallback was considered and declined (see D2).
- **Preventing the device owner from reading their own messages.**

## Rules the code must hold

These are testable claims, not aspirations. Each gets a test or a lint rule.

1. No message body, sender, or bot token ever reaches a log sink — Worker or app.
2. The Worker declares no storage bindings capable of persistence beyond the
   memory-only mailbox.
3. Bot tokens are never written to Worker storage of any kind.
4. The device signing key is generated in Keystore with `setUserAuthenticationRequired`
   where practical, and is non-exportable.
5. The Room database is encrypted at rest with a Keystore-wrapped key.
6. A request whose timestamp falls outside the replay window is rejected before any
   unsealing work happens.
7. A command from an unapproved chat_id is dropped and surfaced to the user as a
   security event — never silently ignored.
8. Enrollment pins the relay's public key; a changed key blocks and prompts.

## Legal and consent

The product forwards messages from SIMs the operator physically holds. Where a SIM
belongs to someone else — the household case this was built for — the app records an
explicit per-SIM consent acknowledgement at setup, naming whose line it is. This is a
product requirement, not legal advice: jurisdictions differ on intercepting another
person's communications, and consent recorded at setup is the minimum defensible
posture. Sold commercially, this needs actual review under both Finnish and EU law
before launch.
