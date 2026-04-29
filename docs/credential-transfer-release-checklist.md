# Credential transfer — release checklist

Operator-facing checklist for shipping the encrypted credential-transfer
feature across desktop, Android mobile, Android TV, and iOS. The
protocol design is documented separately in
[`credential-transfer-protocol.md`](credential-transfer-protocol.md);
this file is the **what to verify before release** companion.

## Supported flows

All four directions of the matrix are wired and tested. Manual paste
fallback is reachable on every surface and is the same sealed envelope
the relay carries — never plaintext credentials.

| Surface | Send | Receive | QR scan in | QR display out | Manual paste |
|---|---|---|---|---|---|
| Compose Desktop | ✅ | ✅ | (paste only) | ✅ | ✅ |
| Android mobile | ✅ | ✅ | ✅ ML Kit + CameraX | ✅ | ✅ |
| Android TV     | ✅ paste-first | ✅ | (paste only — no rear camera) | ✅ large QR | ✅ |
| iOS            | ✅ | ✅ | ✅ AVFoundation metadata output | ✅ | ✅ |

The receiver-side state machine is identical on every platform and
lives in `shared/.../presentation/transfer/SecretsTransferReceiverViewModel.kt`.
The sender VM is similarly shared. Each platform contributes only the
crypto engine, the QR encoder/decoder bridge, and SwiftUI/Compose
surfaces.

## Backend dependency

Requires the live `/api/v1/transfer/*` family on the Torve backend:

- `POST /api/v1/transfer/sessions` — receiver registration. Returns 201
  with a pending body. 404 means the route family isn't deployed → the
  client surfaces "Auto-import unavailable" and falls back to paste.
- `GET /api/v1/transfer/sessions/{id}` — receiver polling.
- `POST /api/v1/transfer/sessions/{id}/envelope` — sender post (204
  empty on success; 410 with body containing `consumed`/`delivered` on
  re-post).
- `POST /api/v1/transfer/sessions/{id}/consume` — receiver acks.

If the backend is unreachable, every flow gracefully degrades to the
manual paste path. The relay sees opaque ciphertext only — see the
security model section below.

## Security model

### Cryptography

- Key agreement: **X25519** (Curve25519). Each side generates an
  ephemeral key pair; the public key is the 32-byte little-endian
  RFC 7748 §5 u-coordinate.
- Key derivation: **HKDF-SHA256** with a 32-byte zero salt and
  `info = "torve-secrets-transfer-v1"`. Output is a 32-byte AES key.
- Authenticated encryption: **AES-256-GCM** with a random 12-byte
  nonce per envelope and a 128-bit tag. The AAD binds version, sender
  ephemeral public key, nonce, and absolute expiry — any tamper of
  envelope public-clear fields fails AEAD verify on the receiver.

Wire-format compatibility across platforms is verified by:
- Desktop `JvmTransferCryptoEngine` (JDK 11+ JCA).
- Android `AndroidTransferCryptoEngine` (Tink `subtle.X25519` for keypair
  gen + ECDH, JCA AES-GCM, JCA HMAC for HKDF).
- iOS `IOSTransferCryptoEngine` (CryptoKit `Curve25519.KeyAgreement`,
  `SharedSecret.hkdfDerivedSymmetricKey(using: SHA256.self, ...)`,
  `AES.GCM.seal/open` returning `ciphertext || tag` — never CryptoKit's
  `combined`, which prefixes the nonce).

### What the backend sees

The relay only stores opaque sealed envelopes:

```
SealedSecretsEnvelope {
  version: Int,
  senderEphemeralPublicKey: Base64Url(32 bytes),
  aeadNonce:                Base64Url(12 bytes),
  ciphertext:               Base64Url(N + 16 bytes),  // body || GCM tag
  expiresAtEpochMs:         Long,
  senderDeviceId:           String  // opaque, never a hostname
}
```

The backend cannot derive the AES key without one of the X25519
private keys, neither of which leaves its originating device. The
receiver's private key is wiped from memory on cancel, expiry, and
import success. The sender's ephemeral private key is dropped after
the single `seal()` call.

### Manual paste — what's actually being pasted

The "Manual fallback — sealed code" / "Advanced paste" field carries
the **same** `SealedSecretsEnvelope` JSON the relay would carry. It is
not plaintext credentials — sharing it with anyone other than your own
target device just hands them an envelope they cannot decrypt. The UI
copy on every surface says this explicitly:

> "Manual paste is the same sealed envelope shown as text. Copy/paste
> between your own devices when the camera or relay aren't available —
> never share it with anyone else."

### Diagnostics + telemetry redaction

Compile/test-time guarantees, enforced by `TransferTelemetryRedactionTest`
(shared/commonTest) and `TransferDiagnosticsCollectorTest`
(shared/commonTest) and `TransferDiagnosticsCardLabelsTest`
(desktopApp test):

1. Telemetry events are restricted to seven declared constants in
   `TransferTelemetryEvents`. Attribute keys come from
   `TransferTelemetryKeys`, attribute values are closed enums or
   bucketed counts (`0`, `1_3`, `4_10`, `11_25`, `26_50`, `gt_50`).
2. Every emission scanned by the redaction test is verified not to
   contain any of: `torve://transfer/receive/`, `Bearer `,
   `"version"`, `"ciphertext"`, `"senderEphemeralPublicKey"`,
   `"aeadNonce"`, plus per-test extras (raw secret values, envelope
   JSON, session strings, public keys).
3. The diagnostics surface (Android mobile + TV, desktop, iOS) reads
   only `cryptoEngineAvailable: Boolean`, `signedIn: Boolean`,
   `relayReachable: RelayReachability` (closed enum), and an optional
   `TransferAttemptRecord` whose fields are themselves closed enums
   plus an `epoch_ms` long. Backend bodies, raw error strings, session
   strings, envelopes, and access tokens are structurally unable to
   reach the snapshot.
4. Diagnostics test denylist additionally pins: `https://`, `http://`.
5. No `println` / `Logger` / `Log.d` / `NSLog` / `os_log` / `print(...)`
   calls exist in any transfer code path on any platform — verified by
   final-pass grep across `shared/.../transfer`, `androidApp/.../transfer`,
   `desktopApp/.../transfer`, and `iosApp/iosApp/...transfer*`.

## Manual smoke matrix

Run with the live `/api/v1/transfer/*` deployed and a signed-in test
account on every device under test. Each row should pass before
release.

| # | Scenario | Expected result |
|---|---|---|
| 1 | Desktop sender → desktop receiver via relay | Receiver "Auto-import is on" within ~1 s of opening Receive credentials. After Generate sealed code on the sender, receiver auto-imports within `pollIntervalMs` (~3 s) and the sender shows "Delivered to the receiver." |
| 2 | Desktop sender → Android phone receiver | Same shape; Android phone shows the QR + Auto-import banner; on import the success banner replaces the QR. |
| 3 | Android phone sender → desktop receiver | Camera scan populates session string; Generate sealed code → Delivered. |
| 4 | Android phone sender → iOS receiver | Cross-platform envelope round-trip; verifies the wire-format invariants. |
| 5 | iOS sender → Android phone receiver | Same cross-platform check, opposite direction. |
| 6 | Desktop sender → Android TV receiver via paste | TV shows large QR + paste fallback. Phone (or another desktop) scanned/pasted code from TV. Auto-import on TV within poll interval. |
| 7 | Camera permission denied (mobile/iOS) | "Camera permission denied. Use paste below." Paste field stays primary action. |
| 8 | Backend unavailable (kill `/api/v1/transfer/*`) | All receivers show "Auto-import unavailable — Use the paste field below." Manual paste of a sealed code generated by any sender still imports correctly. |
| 9 | Cancel receiver mid-flow | Sender's `postEnvelope` hits 404 → "Relay session not found — the receiver may have cancelled. Copy the sealed code below." Receiver's session memory wiped (private key reference cleared). |
| 10 | Receive code expires (wait past 10 min TTL) | Receiver shows "Receive code expired" with primary `Button` "Generate new code" + explainer copy. Pressing it generates a fresh handshake without a back-out. |
| 11 | Re-post same envelope after delivery | Sender sees "This envelope was already delivered or the receiver has already imported a previous one. Generate a new sealed code if you need to resend." |
| 12 | Diagnostics screens (every platform) | Three status pills (`crypto engine`, `signed in`, `backend relay`) + a last-attempt block + Probe + Refresh buttons. Last-attempt shows only closed-enum tokens (`registered`/`delivered`/`imported`/`failed`/`relay unavailable`) and closed error categories (`relay_not_found`, `network`, etc.). No raw exception messages, no JSON, no session-string fragments. |

## Known non-blockers

- **Desktop webcam scanner deferred.** Manual paste covers the
  camera-less desktop case. A Compose Desktop scanner needs a JNI/V4L2
  /AVFoundation bridge + a barcode lib (e.g. ZXing) and is meaningful
  enough to be its own slice. Not a release blocker because every
  desktop receive flow already works via the QR + paste path.
- **iOS Xcode project file.** `iosApp/iosApp.xcodeproj/project.pbxproj`
  is normally committed by the macOS host. The new Swift files (under
  `Platform/`, `ViewModels/`, `Views/Settings/Transfer/`, `Navigation/`)
  live inside the Xcode source folder so Xcode usually auto-detects
  them on next open. If it doesn't, "Add Files to iosApp" once.
- **Real telemetry sink still NoOp.** `NoOpTelemetryEmitter` is the
  bound default. Wiring a real sink (Firebase, OTel, custom backend)
  is a one-line DI change; the redaction test guarantees no future
  feature-code drift can leak credentials through telemetry.
- **iOS unit-test target not configured in this repo.** The shared
  collector + label-mapping tests on desktop cover the cross-platform
  invariants. When an iOS test target is added, a single `XCTestCase`
  exercising the `TransferDiagnosticsWrapper` label maps + a stubbed
  collector mirrors the desktop test.

## Pre-release verification — quick command line

Run from project root with `JAVA_HOME` set to the Android Studio JBR
(`/c/Program Files/Android/Android Studio/jbr` on Windows):

```bash
./gradlew \
  :shared:desktopTest \
    --tests "com.torve.presentation.transfer.*" \
    --tests "com.torve.data.transfer.*" \
    --tests "com.torve.domain.transfer.*" \
  :desktopApp:test --tests "com.torve.desktop.transfer.*" \
  :desktopApp:compileKotlin \
  :androidApp:testGoogleMobileDebugUnitTest --tests "*transfer*" \
  :androidApp:assembleGoogleMobileDebug \
  :androidApp:assembleGoogleTvDebug
```

iOS build + tests require a macOS host (Kotlin/Native iOS targets only
compile there). On macOS:

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator
```
