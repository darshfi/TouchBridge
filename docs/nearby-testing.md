# Nearby milestone — two-phone test

## Startup diagnostic build 0.2.1

Use the artifact from `fix/nearby-startup-diagnostics`, leaving `main` and `stable/nfc-verified` intact. On the advertiser, wait for **Ready to tap** before touching phones. During **Starting Nearby**, the NFC session is deliberately unavailable. If advertising fails, do not keep tapping: capture the full error code/name and expand Show diagnostics to capture Android API, Play services version and missing runtime permissions. This identifies the SDK failure without broadening permissions or guessing from the generic 0.2.0 error. Retest NFC Lab separately as a regression check.

Version: 0.2.0. **Implemented; physical Nearby verification pending.** NFC-only exchanges and the sender-status fix were confirmed by the developer in both roles on 2026-10-28. Automated transports do not replace real Nearby testing.

## Build and install

Use the Windows or Arch commands in the README. Both phones must have the same 0.2.0 APK; check About. Prior CI APKs use different debug signing keys, so uninstall the old experimental build if Android rejects an update, then install on both phones:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
adb devices -l
adb -s PIXEL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SAMSUNG_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PIXEL_SERIAL shell am start -n dev.touchbridge/.MainActivity
adb -s SAMSUNG_SERIAL shell am start -n dev.touchbridge/.MainActivity
```

Expected: BUILD SUCCESSFUL, device list containing both authorized phones, Success for each installation. Record both Android versions/API levels and the exact Git commit. The app detects features/API levels at runtime; Samsung's version has not yet been supplied.

## Happy path

1. Enable NFC, Bluetooth and Wi-Fi on both. Wi-Fi need not be connected to internet or the same router. Keep both phones unlocked with TouchBridge foregrounded. Google Play services must be installed/available.
2. Samsung: Connect → Advertise + emulate → grant requested permissions. On Android 12L or earlier, also enable system Location for legacy discovery. Nearby should display Advertising and NFC should show Hold phones together.
3. Pixel: Connect → Scan + connect → grant permissions. Align NFC antennas and tap while Samsung's 60-second session is valid.
4. Pixel shows NFC handshake successful, then searches for the tapped session. Both phones display Compare connection codes. **Check that the four digits match physically.** Tap Codes match · accept on both phones. Do not accept mismatched codes.
5. Both must reach Connected to the tapped phone. Only now move phones apart. This requires two user approvals plus matching control messages on the encrypted channel, not just NFC success.
6. Samsung: Send hello. Pixel must show Received: Hello from the other phone. Samsung's Hello sent state is driven by Nearby's successful payload-transfer callback.
7. Pixel: Send hello. Samsung must show the same receipt. No file, URL or private text is sent by this build.
8. Stop session on both. Swap NFC roles and repeat all steps. Record actual outcome for each direction; do not infer one direction from the other.

## Failure/recovery matrix

| Scenario | Expected result | Result |
| --- | --- | --- |
| Tap, move apart, approve both, hello each direction | Both connected, both receive hello | Pending |
| Only one user accepts | No connected/send state; bounded wait, then timeout | Pending |
| Reject on either phone | Connection closed; no hello | Pending |
| Codes differ | Reject manually; do not proceed | Pending |
| Session waits over 60s before pairing | Pairing expires; new session and tap required | Pending |
| Permission denied/permanently denied | Explanation and App permissions action; NFC Lab remains usable | Pending |
| Bluetooth off / Wi-Fi off | Specific settings guidance; no silent radio enabling | Pending |
| System Location off on older Android | Enable Location guidance before discovery | Pending |
| Play services missing/outdated | Availability error; NFC Lab unaffected | Pending |
| Stop during advertise/discover/verification | Clears code/session, stops searches/endpoints; later callback cannot restore UI | Pending |
| Cancel and immediately start again | New attempt works; no stale peer or old approval | Pending |
| Background, lock or rotate | Both NFC and Nearby cleaned up; explicitly restart | Pending |
| Disconnect/out of range during hello | Visible disconnect/failure, or bounded delivery timeout; no false success | Pending |
| Pair within TTL, then wait over 60s before hello | Approved foreground channel remains usable | Pending |
| Nearby endpoint with different session | Ignored/rejected; no approval or payload for that peer | Pending |
| Offline test | Same exchange with internet disconnected but Bluetooth/Wi-Fi enabled | Pending |
| NFC Lab regression | Each phone still works as reader and emulator without wireless permissions | Pending |

## Sanitized log evidence

Run in separate terminals:

```bash
adb -s PIXEL_SERIAL logcat -v time 'TouchBridgeReader:I' 'TouchBridgeHce:I' 'TouchBridgeNearby:I' '*:S'
adb -s SAMSUNG_SERIAL logcat -v time 'TouchBridgeReader:I' 'TouchBridgeHce:I' 'TouchBridgeNearby:I' '*:S'
```

Nearby logs contain only phase names, such as ADVERTISING, DISCOVERING, VERIFYING, MATCHING and CONNECTED. Capture screenshots of received hello/status and record the path to reproduce failures. Do not include full device logs, active session identifiers, authentication codes or unrelated private data. An emulator APDU log alone does not establish Nearby success.

## Protocol and safeguards

One public NFC ID appears in endpoint names `TB1:A:<id>` (advertiser) and `TB1:R:<id>` (reader). Service ID is `dev.touchbridge.nearby.v1`; strategy is P2P_POINT_TO_POINT. Reader endpoint IDs are discovered using the real API, never invented from NFC. Advertisers reject incoming requests until their current NFC session has actually served GET_SESSION. IDs are correlation metadata, not secrets or authentication.

After both users compare digits and approve, a bounded UTF-8 `TB1|MATCH|<id>` control frame verifies the tap session. The user-visible fixed hello uses `TB1|HELLO|<id>`. Only these frame types are accepted, with exact field/ID validation and a 128-byte bound. One early hello can wait for MATCH to handle out-of-order BYTES delivery. No early frame is displayed before the session is matched. Non-BYTES payloads are rejected/cancelled; files are not supported here.

The whole pairing must fit within the 60-second tap deadline; request/approval has a further 30-second bound. The reader conservatively subtracts NFC exchange duration from received TTL. Hello delivery times out after 10 seconds. Successful matching invalidates the NFC registry and releases NFC routing; the approved Nearby connection remains until foreground lifecycle cleanup or Stop. There is no automatic reconnection, persistent trust, background sharing or stored message history.
