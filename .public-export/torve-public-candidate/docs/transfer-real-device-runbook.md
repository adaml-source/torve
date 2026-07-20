# Credential Transfer — Real-Device Smoke Runbook

Concrete steps to execute the device-paired smoke matrix for the
credential-transfer rewrite. Pairs with `docs/release-hardening.md`.

The unit-testable cases (S4 invalid receiver code, S5 empty receiver
code, S6 expired receiver code, S7 relay-unavailable copy) are
**already verified on this host** by:

```
:shared:testDebugUnitTest \
  --tests "com.torve.presentation.transfer.TransferCopyTest" \
  --tests "com.torve.presentation.transfer.SecretsTransferSenderViewModelEmptyReceiverTest"
# 15 + 6 tests, 0 fail
```

The cases below need a human across two pieces of glass.

---

## Hosts and devices

- **Desktop**: this Windows host. Launch with
  `JAVA_HOME="<jbr>" ./gradlew :desktopApp:run`.
- **Android mobile**: Pixel 8/9 Pro AVD or any USB-attached phone.
  APK: `androidApp/build/outputs/apk/googleMobile/debug/androidApp-google-mobile-debug.apk`.
- **Android TV**: `Television_4K` AVD (already configured in this
  workspace) or a Fire TV / Android TV stick.
  APK: `androidApp/build/outputs/apk/googleTv/debug/androidApp-google-tv-debug.apk`.

### Boot the AVDs

```bash
# List configured AVDs:
"/c/Users/Anwender/AppData/Local/Android/Sdk/emulator/emulator.exe" -list-avds

# Boot a phone + a TV side-by-side (separate terminals):
"/c/Users/Anwender/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_9_Pro_XL
"/c/Users/Anwender/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Television_4K

# Confirm both attached:
adb devices
```

### Install fresh APKs on each device

```bash
# Replace <serial> with each device's adb serial.
adb -s <phone-serial> install -r androidApp/build/outputs/apk/googleMobile/debug/androidApp-google-mobile-debug.apk
adb -s <tv-serial>    install -r androidApp/build/outputs/apk/googleTv/debug/androidApp-google-tv-debug.apk
```

---

## S1 — Desktop sender → Android TV receiver

1. Sign in on TV. Open **Settings → Receive credentials** on the TV.
2. **Verify**: large QR + the receiver code (a `torve://transfer/receive/…`
   URL — currently ~250 chars, not a short pairing code) appear with the
   explainer _"On your desktop or phone, open Send credentials and scan
   this code."_
3. On Desktop: **Settings → Sources → Send credentials**.
4. **Verify** Step 1 header _"Step 1 — Start on the device you want to set up"_
   plus the explainer pointing at TV/phone Settings → Receive credentials.
5. Tap **Copy receiver code** on the TV, then paste it into the desktop's
   "Receiver code from the other device" field. (Or, if a phone camera
   is available, scan the TV QR with the phone instead — see S2.)
6. Open **Step 2** disclosure → leave default categories selected (or
   tick a single category). The collapsed summary line should already
   name what will be sent.
7. **Step 3 — Generate and send.** Expect either:
   - Relay path: banner "Delivered to the receiver" → TV imports
     within a few seconds.
   - Manual path: open the **Advanced — manual sealed-code paste**
     disclosure on desktop, copy the encrypted bundle, paste into TV's
     **Advanced — paste sealed code manually** disclosure, hit Import.
8. **Verify on TV**: green "Credentials imported" banner naming the
   categories that came across.

**Pass criteria**: ≤ 1 "what does this code do?" question. QR or short
code copied without explanation. No "session string" or "sealed
envelope" wording on either screen.

---

## S2 — Android mobile sender → desktop receiver

1. Open Desktop **Settings → Sources → Receive credentials**.
2. **Verify** large QR + the receiver code render in the modal.
3. On phone: **Settings → Send credentials**.
4. **Verify** scanner section appears first (camera-first on mobile).
5. Tap **Open camera** → grant permission → scan the desktop QR.
   - **Verify** the Receiver-code field auto-fills with the scanned
     value.
   - If you deny camera permission instead: **verify** the message
     reads _"Camera is blocked. Type the receiver code instead."_ —
     not a permissions lecture.
6. Open Step 2 disclosure → confirm the summary line lists the
   categories selected by default.
7. Generate and confirm the desktop's receive screen flips to
   "Credentials imported".

---

## S3 — Desktop sender → Android mobile receiver

1. On phone: **Settings → Receive credentials**.
2. **Verify** explainer reads _"On the device that already has these
   set up, open Send credentials and scan this QR — or paste the
   receiver code below."_ (the phone-receiver explainer).
3. Desktop sender: paste the phone's receiver code (no camera on this
   desktop), generate, confirm import.

---

## S4 — Invalid receiver code  ✅ VERIFIED (host)

`SecretsTransferSenderViewModelEmptyReceiverTest`:
- `wrong-prefix paste tells the user where to find the right code` →
  asserts the error is _"This is not a Torve receive code. Open Receive
  credentials on the other device and use the code it shows."_ and
  doesn't leak "base64" / "JSON".
- `corrupted Torve-shaped code lands on the unified corrupted error` →
  asserts the unified _"The receiver code is corrupted. Ask the other
  device to generate a fresh one and try again."_

Operator may still paste literal garbage in the desktop sender for a
quick visual check; the rendered banner must match the strings above.

---

## S5 — Empty receiver code  ✅ VERIFIED (host)

Same test file:
- `generateEnvelope with empty receiver code surfaces the new error copy`
- `error copy mentions the other-device source so the user knows where to look`
- `whitespace-only receiver code is treated as empty`

All three assert the exact string _"Enter the receiver code shown on
the other device first."_

Operator visual check: clear the field on desktop sender and click
Generate — banner must match.

---

## S6 — Expired receiver code  ✅ VERIFIED (host)

Same test file:
- `expired handshake reports the expiry-specific error` → builds a
  receiver handshake with a past `expiresAtEpochMs`, asserts the error
  is _"The receiver code expired. Generate a new one on the receiving
  device."_

Operator visual check: generate a receive code, wait > 10 minutes
(default TTL), then try to send. Banner must match.

---

## S7 — Relay unavailable / manual fallback

**Setup**: take the backend offline (kill the FastAPI process) before
the sender clicks Generate.

1. **Verify** sender's relay banner reads _"Automatic transfer is
   unavailable."_ with the failure reason.
2. **Verify** the **Advanced — manual sealed-code paste** disclosure
   on the sender still expands and shows the encrypted bundle.
3. Copy that bundle to the receiver's **Advanced — paste sealed code
   manually** disclosure, click Import.
4. **Verify** import succeeds without the relay.

Pre-existing tests pin the receiver-side manual-paste flow:
`SecretsTransferReceiverViewModelSharedTest::manualPasteImportSucceedsEvenWhenRelayIsUnavailable`.

---

## S8 — TV receive screen readability from couch distance

**Setup**: connect TV emulator to a real display, or use a Fire
TV / Android TV stick on a TV across the room (≥ 3 m).

1. Open **Settings → Receive credentials** on the TV.
2. **Verify** with a phone camera at 3 m:
   - The QR scans on first try (use Torve's mobile sender; the QR
     should be at least ~30% of the screen height to scan reliably
     at 3 m).
3. **Verify** the receiver code is at least visible (the URL itself is
   long; couch-distance typing isn't realistic — the QR scan is the
   intended mobile-receiver path).
4. **Verify** the explainer _"On your desktop or phone, open Send
   credentials and scan this code."_ is readable at the same
   distance.

If any of these fail, file a UI sizing follow-up — the QR + code +
explainer all live in `androidApp/.../tv/.../SecretsTransferReceiveScreen.kt`.

---

## Acceptance roll-up

| # | Case | Status | Evidence |
|---|------|--------|----------|
| S1 | Desktop → TV | ⚠ Partial — emulator-verified TV-side render; operator must complete the desktop paste + import half | TV receive screen renders QR, explainer (`RECEIVE_PRIMARY_EXPLAINER_TV`), receiver code, "Copy receiver code" button, and manual paste fallback (screenshots `build/tv_18_receive.png`, `build/tv_20_after_back.png`). Signed-out TV exposes "Auto-import unavailable" red banner — relay path validation requires sign-in. |
| S2 | Mobile → Desktop | ⚠ Operator | Visual smoke needed (camera scan) |
| S3 | Desktop → Mobile | ⚠ Operator | Visual smoke needed |
| S4 | Invalid receiver code | ✅ Host-verified | Sender VM tests |
| S5 | Empty receiver code | ✅ Host-verified | Sender VM tests |
| S6 | Expired receiver code | ✅ Host-verified | Sender VM tests |
| S7 | Relay unavailable / manual fallback | ✅ Mostly host-verified; final visual on operator | Receiver VM test for manual-paste; sender copy via TransferCopyTest |
| S8 | TV couch readability | ⚠ Operator | Visual + camera scan from 3 m |
| Legacy wording absent in primary UI | ✅ Host-verified | Grep + `TransferCopyTest` |

### Real-device findings (2026-04-30, host emulator pass)

Four issues discovered + fixed during the Television_4K AVD run:

1. **TV_ONLY setup mode hid the Receive entry.** A fresh TV install
   defaults to `TvSetupMode.TV_ONLY`. The Receive credentials entry
   was nested inside the `ANDROID_PHONE | IOS_PHONE` branch in
   `TvSettingsScreen.kt`, so a TV-only user never saw it. Fixed by
   adding the Receive entry at the top of the TV_ONLY → CONNECTIONS
   section.
2. **"Short receiver code" copy lie.** `TransferCopy` told the user
   the receive screen would show a "short receiver code", but the
   actual code is a ~250-char `torve://transfer/receive/…` URL —
   correct for paste-based transfer, but not "short". Dropped the
   "short" qualifier from `SEND_STEP1_EXPLAINER` and
   `RECEIVE_PRIMARY_EXPLAINER_DESKTOP`, updated the
   `TransferCopyTest` assertion, and updated this runbook.
3. **TV QR filled the entire screen, hiding the receiver code below
   the fold.** `SecretsTransferReceiveScreen` used `fillMaxWidth()`
   for the QR Surface with no width cap — on a 4K TV at 640 dpi
   that meant a ~3700 dp QR that no phone camera can frame at 3 m
   and that buried the countdown, relay banner, receiver code, and
   "Copy receiver code" button under it. Fixed by switching the TV
   layout (`largeQr = true`) to a Row: QR fixed at 320 dp on the
   left, countdown + relay banner + receiver code + copy button
   stacked on the right, so both halves stay above the fold without
   scrolling. Mobile/portrait still uses the original vertical
   stack. Verified on Television_4K AVD —
   `build/tv_30_at_categories.png` shows the new layout end-to-end.
4. **TV Settings category chips rendered with no titles**, just
   tiny pills showing the status badge (e.g. "Connected") and
   nothing for tabs without a badge. Root cause:
   `TvSettingsTopCategoryChip` set the title `Text` to
   `Modifier.weight(1f, fill = false)` inside a horizontally
   unbounded `LazyRow` item — weight in an infinite-width parent
   collapses the child to 0 dp, so "Account / Playback / Appearance
   / Channels / Connections / Advanced / About" simply never drew.
   Fixed by removing the weight modifier; the title now sizes to
   intrinsic width and the chip grows to fit. Verified on
   Television_4K AVD — `build/tv_33_chips_top.png` shows all seven
   category labels rendering with their badges.

A real short pairing code (relay-assigned 6–8 chars, looked up
backend-side) is a future enhancement — out of scope for this slice.

GO for public beta on the host-verifiable cases; operator must
complete S1's desktop-side smoke, S2, S3, S8, and the visual halves
of S7 before the transfer flow can be promoted to public stable.
