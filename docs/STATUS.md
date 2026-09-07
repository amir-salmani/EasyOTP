# Status — 2026-09-07

Written to be picked up cold. What is true, what is only believed, and what to do
next. Design rationale lives in [DECISIONS.md](DECISIONS.md); this file is state.

---

## 1. Verified working

Proven by a test that runs in CI, not by inspection.

| Area | Evidence |
|---|---|
| Relay: five routes, stateless auth, memory-only mailbox | 20 worker tests |
| **HPKE seal/open across Tink and hpke-js** | cross-language fixture, regenerated each CI run |
| **Identifier chain identical in Kotlin and TypeScript** | cross-language fixture, regenerated each CI run |
| Endpoint failover policy | 11 tests |
| Delivery outcomes, 4xx vs 5xx handling | 8 tests |
| Enrolment and pairing parsing | 9 tests |
| OTP classification incl. Persian digits | 12 tests |
| Outbox dedupe identity and backoff | 6 tests |
| APK builds locally and in CI | artifact `easyotp-debug-apk` |

**57 Android tests, 20 worker tests.** The two cross-language contracts matter
most: both fail silently in production and only a shared fixture catches them.

---

## 2. Built but never run on hardware

Compiles, is unit-tested where it can be, and **has never executed on a phone**.
Treat every line below as unproven:

- `SmsReceiver` firing from a cold process on a real SMS
- `SimRegistry` reading real ICCIDs from two live SIMs
- `KeyVault` against a real Keystore, including the StrongBox fallback
- `Outbox` against real SQLite on-device
- `ForwarderService` surviving as a `specialUse` foreground service
- `BootReceiver` after a real reboot
- Any actual network call to a relay that does not exist yet

The riskiest of these is **not** the code. It is whether the OEM lets the service
live (ARCHITECTURE section 6).

---

## 3. Not built

- **Compose UI.** Only a placeholder screen exists. Needed: BotFather token
  entry, SIM labelling with the consent acknowledgement, on-device approve or
  reject for a pairing request, and a status view.
- **OEM survival wizard and self-test** — the thing that turns "works on a
  Nothing Phone" into "works generally".
- **Worker deployment.** Nothing is deployed. No secret set, no DNS, no webhook.
- **M2** — inbound commands, send-SMS, USSD, `/status`.
- **M3** — rules engine, missed calls, SIM keep-alive, archive search.

---

## 4. Open debts

| Debt | Why it matters | When |
|---|---|---|
| **Gradle dependency verification (D11)** | A mirror can serve different bytes than Google. Currently trusted on reputation alone. CI does not use the mirror, so releases are safe *by construction, not by enforcement*. | Before any release build |
| **AndroidX pinned a generation back** | Compose 1.12 / core-ktx 1.19 / OkHttp 5.5 all require compileSdk 37, which is catalogued but not installable from the stable channel. | Revisit when 37 ships stable |
| **Payment rails (D8)** | Foreign gateway needs the Finnish `Oy`, which follows Tampere residency. No billing code should be written before then. | After relocation |
| **Consent recording** | `SimRoute.consentAcknowledged` exists and is enforced in routing, but no UI sets it. | With the UI |

---

## 5. Environment

Measured, not assumed — see the memory note `iran-network-constraints`.

- **Blocked from this workstation:** `dl.google.com` (Android SDK *and* Google
  Maven), `developer.android.com`, Docker Hub.
- **Works:** `ghcr.io`, Maven Central, `services.gradle.org`, npm,
  `maven.aliyun.com`.
- Consequence: the build image is **built in CI and pulled**, never built
  locally (D10). AndroidX resolves through a mirror gated on `EASYOTP_MIRRORS`,
  which `./dev` sets inside the container only.
- Host runs under 4GB free. Container is capped at **4g**; Gradle heaps total
  ~2GB. An untuned build gets SIGKILLed — this was observed, not predicted.

---

## 6. Resuming

```sh
cd ~/CodeBase/AmirSalmani/easyotp
./dev up                 # pulls from ghcr.io if absent
./dev build testDebugUnitTest assembleDebug
cd worker && npx vitest run
./dev stop               # frees host RAM; do this when walking away
```

Toolchain facts that cost hours and are easy to re-trip:

- AGP 9 has Kotlin **built in**; the standalone `kotlin-android` plugin is
  rejected outright.
- AGP 9 requires Build Tools 36+ and **ignores** an override to an earlier one.
- `platforms;android-37` appears in `sdkmanager --list` but **will not install**.
  Listing is not availability.
- `org.json` is a **stub** in Android unit tests and throws "not mocked". A real
  `org.json` is on the test classpath; without it every JSON path is silently
  untestable.

---

## 7. The next real step

Two candidates, and they are not equivalent:

1. **Compose UI.** Assembly over tested parts. Low risk, unblocks a human
   actually using the thing.
2. **Deploy the Worker and put a real SIM behind it.** Higher information: it is
   the only way to learn whether the OEM lets the service live, whether the
   `.ir` front door behaves as measured, and whether real MCI and Rightel
   messages classify correctly.

Option 2 answers questions that no amount of further building can. Option 1 is
required before anyone but a developer can run it. The UI is likely the right
next move, but the hardware questions should not be deferred far past it — every
week of building on unverified OEM behaviour is a week of possible rework.

**Nothing about this project is validated against a real SMS yet.** That is the
single most important fact on this page.
