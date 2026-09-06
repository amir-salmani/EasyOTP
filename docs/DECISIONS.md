# Decision log

Format: one decision per entry. Date, what was chosen, what was rejected, why.
Decisions here are settled — reopen only with new evidence, don't re-derive.

---

## D1 — Native Kotlin, not Tauri (2026-08-30)

**Chosen:** 100% native Android (Kotlin + Jetpack Compose). No Tauri, no WebView.

**Rejected:** Tauri v2 Android shell with a Kotlin plugin for the capture path.

**Why:** A Tauri app is a WebView + Rust process. When Android kills that process —
and it will, unattended, within hours — the Rust and JS are gone. A manifest-declared
`BroadcastReceiver` for `SMS_RECEIVED` still fires with the app dead, because it is
exempt from the implicit-broadcast ban. That means the entire critical path had to be
Kotlin regardless, leaving Tauri as a UI skin over a native engine: added toolchain,
added build surface, added APK size, zero benefit. Compose does the same job natively.

---

## D2 — Two front doors, one origin (2026-08-30)

**Chosen:** `otp.services.amirsalmani.com` (Cloudflare zone → Worker custom domain) and
`otp.services.rhinocloud.ir` (ArvanCloud CDN → origin `*.workers.dev`). Both terminate
at the same Worker.

**Rejected:** SMS fallback to a foreign number; independent second origin on an
Iranian VPS.

**Why (and the limit):** The dominant real-world failure is Iranian consumer ISPs
DPI/SNI-blocking Cloudflare edge. The Arvan leg defeats that, because Arvan's
international transit is not the path a consumer handset takes. It is fast (domestic,
sub-50ms) and survives most disruption.

It does **not** survive a full international cutoff — then Arvan→Cloudflare fails too.
These are two front doors on one origin, not two failure domains. With SMS fallback
declined, a national shutdown means messages queue on-device and deliver on restore.
This is an accepted trade, made knowingly, not an oversight.

**Escape hatch if it ever matters:** a second origin hosted inside Iran makes the two
legs genuinely independent failure domains. Deferred, not designed away.

**To verify before building the .ir leg:** whether ArvanCloud permits a foreign origin
at all, and whether it can override the Host header to the `workers.dev` hostname
(Workers route by Host; without the override the origin fetch 404s). Arvan's own docs
are unreachable from outside Iran — check from inside the country, or via the panel.

---

## D3 — Stateless relay, no database (2026-08-30)

**Chosen:** Worker persists nothing. All state — message archive, routing rules,
destinations, credentials — lives on the phone. One Durable Object per channel acts
as a **memory-only mailbox** for the seconds between a Telegram webhook arriving and
the phone's long-poll collecting it. No storage writes.

**Rejected:** D1 archive, KV-backed config, server-side dashboard.

**Why:** Bidirectional messaging needs a rendezvous point, because the phone is behind
CGNAT and cannot be reached inbound. A memory-only DO is the minimum structure that
provides one. Anything persistent turns the relay into a target holding other people's
OTPs, which is the exact thing the product exists to avoid. "We store nothing" has to
be literally true or it is worthless.

---

## D4 — Bring-your-own bot; pairing approved on-device (2026-08-30)

**Chosen:** Each user creates their own bot via @BotFather and pastes the token into
the app. The app seals it and calls `/enroll`; the **Worker** calls Telegram
`setWebhook` on the phone's behalf, then discards the token. The token rides inside
the sealed envelope on every subsequent forward and is used transiently.

Destination pairing: user messages their own bot, the update lands at the Worker, is
parked in the DO mailbox, and the phone's long-poll retrieves it. The **app** then
prompts the holder to approve that chat_id.

**Rejected:** Server-side account system; server-held ACL; app calling Telegram directly.

**Why:** `api.telegram.org` is blocked in Iran, so the phone cannot call `setWebhook`
itself — the Worker must proxy it. And approving destinations on the device means the
authorization root is physical possession of the handset, with no server-side list to
breach or misconfigure. No accounts also means no PII and no user database.

---

## D5 — Crypto primitives (2026-08-30)

**Chosen:**
- Device identity: **ECDSA P-256** in Android Keystore, hardware-backed (StrongBox
  when available, TEE otherwise). Signs every request over a canonical body+timestamp.
- Payload sealing: **HPKE (RFC 9180)** — X25519 + HKDF-SHA256 + ChaCha20-Poly1305 —
  to the Worker's public key.
- Replay defence: timestamp window plus nonce.

**Rejected:** Ed25519 in Keystore.

**Why:** Android Keystore's Ed25519 support is narrow and version-dependent; ECDSA
P-256 is hardware-backed on effectively every device we target. HPKE means Arvan and
the Cloudflare edge relay opaque bytes — the `.ir` leg transits Iranian infrastructure
under Iranian jurisdiction, and sealing is what makes that an acceptable trade rather
than a bad one.

**Amended 2026-08-30, during implementation.** The per-request ECDSA signature was
**removed**. On a relay that stores nothing there is no device registry, so a signature
can only prove that *someone* holds *some* key — it verifies against no known identity
and is therefore decoration. Shipping unverifiable crypto is worse than shipping none,
because it invites the belief that authentication exists.

What replaced it is a one-way hash chain that *is* verifiable without state:

```
channelId  --H-->  secretToken  --H-->  webhookId
 (device)          (Telegram)            (public URL)
```

The relay authorises by hashing forward: a webhook delivery is genuine if
`H(presented secret) == webhookId` in the path, and a mailbox drain is authorised if
`H(H(presented channelId)) == webhookId`. Telegram knows `secretToken` and so can post
updates, but cannot read the mailbox. Anyone who scrapes the public webhook URL gets
`webhookId`, which grants nothing. No stored mapping is required at any point.

Authentication for `/forward` is the **bot token inside the sealed envelope**, which
matches reality — anyone holding that token could message the bot directly regardless.

Keystore-backed ECDSA returns when licensing does (D9): a license token bound to a
device public key makes proof-of-possession meaningful, because then the relay has
something to verify *against*.

---

## D6 — Multiple bots, not forum topics (2026-08-30)

**Chosen:** Routing table on-device: SIM (pinned to **ICCID**, so it survives a slot
swap) → N destinations, each `(bot, chat_id, label)`. Different SIMs can target
entirely different bots owned by different people.

**Rejected:** One bot, per-SIM forum topics in a shared supergroup.

**Why:** The two-SIM household case is the small case. The product case is N people
who each want their own line in their own bot, not shared visibility into one group.
Separate bots make the trust boundary per-person and need no supergroup setup. Topics
remain possible for anyone who prefers a single bot; they are not the default.

---

## D7 — Foreground service type `specialUse` (2026-08-30)

**Chosen:** `android:foregroundServiceType="specialUse"`.

**Rejected:** `dataSync`.

**Why:** Android 15 caps `dataSync` foreground services at 6 hours per 24h. A
persistent forwarder using it dies daily, silently. `specialUse` has no such cap. The
Play Store justification requirement for `specialUse` does not bind us — distribution
is sideload/direct, and SMS permissions would disqualify us from Play anyway.

---

## D8 — Monetization and payment rails (2026-08-30)

**Chosen:** AGPL-3.0 for everything. Revenue is a subscription to the **hosted relay**,
billed through a **merchant of record** (Paddle or equivalent) once the Finnish `Oy`
exists. No billing code is written before then.

**Rejected:** routing payments through another person's foreign payment account;
open-core with paid modules; Stripe-direct.

**Why:** A foreign payment gateway is not available to an Iran-resident individual.
That constraint is temporary and already scheduled away — the Finland company decision
records that the `Oy` is registered *after* Tampere residency, and relocation is weeks
out. A Finnish `Oy` makes Paddle or Stripe routine.

Borrowing someone else's account is the tempting shortcut and the wrong trade: it
misrepresents beneficial ownership to the provider, moves legal and tax liability onto
whoever's name is on it, and typically ends in frozen funds when the mismatch surfaces.
For a product sold on "don't trust a random OTP forwarder — here is exactly who we
are," opaque ownership destroys the asset being sold. Weeks of delay is the cheaper
side of that trade.

**Merchant of record over Stripe-direct** because MoR absorbs global VAT/sales-tax
registration, invoicing, and chargebacks. Selling a small subscription into thirty
countries as a solo operator, tax compliance is the headache, not card processing.

**Sanctions reality:** paying customers are diaspora holding non-Iranian cards, which
is exactly the target market. Customers still inside Iran cannot be billed by any
Western processor and are served by the free self-hosted path — which the AGPL
guarantees rather than merely permits.

**Consequence:** ship free and self-hostable now. Revenue work starts when the `Oy`
does.

---

## D9 — Entitlement without a database (2026-08-30)

**Chosen:** Offline-verifiable **signed license tokens**. The billing system issues a
short-lived (≈7 day) Ed25519-signed token to the app; the app presents it with each
relay request; the relay verifies signature and expiry against a public key in its
environment. No lookup, no user record.

**Rejected:** the relay querying a subscriptions table.

**Why:** D3 says the relay stores nothing, and a paid tier is the obvious pressure to
break that. A subscriptions table on the relay reintroduces precisely the user database
this design exists to avoid, and parks billing identity next to message traffic — the
two things that must never be correlatable.

Signed tokens keep the relay stateless and keep billing in a **separate Worker** with
its own storage that never sees message content or bot tokens. The cost is revocation
lag bounded by the token TTL: a cancelled subscription keeps working until the token
expires. That is an acceptable price for not holding a customer database next to
other people's OTPs.

---

## D10 — The build environment is published, not built locally (2026-08-30)

**Chosen:** CI builds the Android build image and pushes it to
`ghcr.io/<owner>/easyotp-build`. Workstations pull it. `./dev pull` is the normal
path; `./dev build-image` exists as a fallback.

**Rejected:** each developer building the image from the Dockerfile.

**Why:** the primary development machine is in Iran, and measured from it:

| Endpoint | Result |
|---|---|
| `dl.google.com` (Android SDK) | 404 — including permanently valid URLs |
| `developer.android.com` | 403 |
| Docker Hub | 403 |
| `ghcr.io` | reachable, pulls succeed |

Google restricts Android developer downloads from Iranian addresses and Docker Inc.
blocks Iran outright, so a local build fails at the base image before it ever reaches
the SDK. A proxy may or may not cover these depending on its routing rules; depending
on one is not a build system.

Publishing the image is also better on its own terms: the environment CI tests in is
byte-identical to the one on the workstation, which is the usual reason to do this
anyway. The blockade only forced a good practice earlier than convenient.

**Consequence:** contributors inside Iran need a `read:packages` token for ghcr.io and
nothing else. Contributors elsewhere can use either path. This is worth stating in
CONTRIBUTING — a project for Iranians that cannot be built from Iran would be a poor
joke.

---

## D11 — Dependency mirrors, and the supply-chain debt they create (2026-08-30)

**Chosen:** builds may resolve AndroidX and Gradle plugins through a third-party
mirror, opt-in per machine via `EASYOTP_MIRRORS=true`. CI and unrestricted
contributors use Google and Maven Central directly.

**Rejected:** making the mirror the default; vendoring artifacts into the repo.

**Why:** Google's Maven repository is served from `dl.google.com`, which is blocked
from the primary development machine (D10). Without a mirror the Android build cannot
resolve a single AndroidX artifact, so there is no build at all.

**The debt, stated plainly.** A mirror is an entity that can serve a *different* artifact
than the one the authoritative repository holds. For an app that handles other people's
one-time passwords, silently trusting one is exactly the supply-chain compromise the
threat model is supposed to care about — and it is invisible in a successful build.

Three things keep it honest rather than hidden:

1. **Opt-in and environment-scoped.** It cannot be switched on by a committed file, so
   a contributor never uses a mirror without having typed it.
2. **CI never uses it.** The published APK is built from the authoritative repositories.
3. **Gradle dependency verification is the real fix** — `gradle/verification-metadata.xml`
   with checksums generated in CI from Google and Maven Central, then enforced locally.
   The mirror may then serve bytes, but not *different* bytes.

Item 3 is **not yet implemented** and is the outstanding debt. It should land once the
dependency set stops moving, and before any release build. Until then, a mirrored local
build is a development convenience and must never produce a shipped artifact.

---

## D12 — Hand-written SQLite for the outbox, not Room (2026-08-30)

**Chosen:** a single `SQLiteOpenHelper` and a small DAO, roughly 150 lines.

**Rejected:** Room with KSP.

**Why:** the outbox is **one table**. Room's value is code generation across a schema
of many entities and relations; here it buys almost nothing and costs an annotation
processor. That processor is not free right now: AGP 9 provides Kotlin support built
in, KSP versions are pinned to exact Kotlin releases, and this project has already
spent several cycles on toolchain drift it did not choose. Adding a codegen plugin to
the critical path of a build that must work from a network where half the ecosystem is
blocked is a poor trade.

It also helps the security story. The outbox is where other people's messages sit at
rest, and a reviewer can read every statement that touches it without first knowing
what Room generated.

**Revisit if** the schema grows past two or three tables with relations between them.

---

## D13 — Encrypt the payload, not the database (2026-08-30)

**Chosen:** message content is sealed with an AES-256-GCM key held in the Android
Keystore and stored as an opaque blob. Queue metadata — the SIM's ICCID, timestamps,
delivery state, attempt counts — is stored in the clear.

**Rejected:** SQLCipher for whole-database encryption.

**Why:** SQLCipher encrypts everything including the columns the queue must sort and
filter on, costs several megabytes of native libraries per ABI, and puts a third-party
crypto implementation on the critical path. Sealing just the payload gives the property
that actually matters — an attacker with the database file gets no message bodies — and
leaves the queue ordinary SQL.

**What this deliberately does not protect:** the metadata. Someone with the file learns
that a given SIM received a message at a given time, and how many messages are pending.
On a device whose physical possession is already game over (THREAT-MODEL A4), spending
complexity to hide timing metadata from an attacker who is holding the SIM would be
theatre.

The Keystore key is non-exportable and hardware-backed where the device offers it, so
the blobs are useless if the database is copied off the device without the key.
