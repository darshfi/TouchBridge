# Physical validation and Arch testing procedure

Status, 2026-10-28: **the developer reports successful NFC exchanges with each phone acting as reader and emulator.** Attached screenshots show a successful greeting in 67 ms and emulator-side response feedback (API 37). The developer explicitly confirmed the reverse direction also worked; its screenshot was not supplied. Samsung's API level and ADB/logcat results are still unrecorded. The OS used for the test was not specified. Unit tests remain distinct from this user-reported hardware evidence.

The developer also reported intermittent emulator status/countdown confusion despite successful receipt. Version 0.1.1 retains response-served state per session, hides the countdown after serving GET_SESSION, and preserves the result after TTL expiry. **Device retest of this fix is pending.**

## Record evidence

```bash
git rev-parse HEAD
adb devices -l
adb -s PIXEL_SERIAL shell getprop ro.product.model
adb -s PIXEL_SERIAL shell getprop ro.build.version.release
adb -s PIXEL_SERIAL shell getprop ro.build.version.sdk
adb -s SAMSUNG_SERIAL shell getprop ro.product.model
adb -s SAMSUNG_SERIAL shell getprop ro.build.version.release
adb -s SAMSUNG_SERIAL shell getprop ro.build.version.sdk
adb -s PIXEL_SERIAL shell am start -n dev.touchbridge/.MainActivity
adb -s SAMSUNG_SERIAL shell am start -n dev.touchbridge/.MainActivity
```

Expected: model/version values from each phone (do not substitute assumed API values); `am start` prints `Starting: Intent ...` or notes the already-running activity. Keep both unlocked, enable NFC, and ensure neither uses Scan when it should emulate.

Open two terminals to capture sanitized events:

```bash
adb -s PIXEL_SERIAL logcat -v time 'TouchBridgeReader:I' 'TouchBridgeHce:I' '*:S'
adb -s SAMSUNG_SERIAL logcat -v time 'TouchBridgeReader:I' 'TouchBridgeHce:I' '*:S'
```

Success evidence: reader UI shows `NFC handshake successful` plus `Hello from TouchBridge!`; reader logs `TouchBridgeReader: NFC handshake successful`; emulator logs `TouchBridgeHce: Session APDU served`. The HCE log alone means a response was produced, not that the reader received it. Require reader confirmation. Do not share full logcat dumps containing unrelated private device data.

## Test matrix

| Test | Actions | Expected result | Actual |
| --- | --- | --- | --- |
| Pixel reads Samsung | Samsung Emulate, Pixel Scan, align backs within 60s | Reader greeting + elapsed time, HCE response feedback | Developer confirmed pass on 2026-10-28; emulator status issue reported |
| Samsung reads Pixel | Pixel Emulate, Samsung Scan | Same real result | Developer confirmed pass on 2026-10-28; emulator status issue reported |
| Emulator completion display (0.1.1) | Complete tap in each direction, separate phones, wait >60s | Emulator shows NFC session sent, hides countdown, retains result; unserved sessions still expire | Pending |
| Both scan | Scan on both | No fabricated success; choose complementary roles | Pending |
| Idle emulator | Stop emulator, keep reader scanning | No valid session / no handshake; no background session creation | Pending |
| Expiry | Wait >60s on emulator | Session expired; reader cannot get a valid session | Pending |
| Interrupted exchange | Separate during tap; realign and retry | Visible retry message or no callback; next valid tap succeeds | Pending |
| Stop and repeat | Stop both; restart fresh roles; repeat 10 times | New sessions; no stuck reader or crash | Pending |
| Background | Home/lock emulator during session, then return | Session cancelled; explicit restart required | Pending |
| Rotation | Rotate either phone while role active | Role cancelled; no old success after cancellation | Pending |
| NFC off | Disable NFC in system settings, return | Disabled-state explanation; settings button; retry after enabling | Pending |
| Unsupported tag | Present an ordinary non-ISO-DEP NFC tag | Unsupported-tech message when delivered; no success | Pending |
| Wallet/routing conflict | Keep wallet installed; retry foreground emulator | Category-other service routes; record any OEM prompt/conflict | Pending |
| Different API levels | Record both versions and test both directions | Runtime capability checks; no model-specific branch | Pending |
| No NFC/HCE | Launch emulator/device without feature | About/diagnostics remain usable; unavailable role disabled | Pending |
| Airplane/offline | Enable airplane mode, then enable NFC where permitted | NFC lab can exchange without WAN, Bluetooth or Wi-Fi | Pending |

If there is no callback, check NFC enabled, unlocked screens, foreground roles, countdown, cases, and antenna alignment. Some device NFC controllers need the phones held in a different relative position. Secure NFC / wallet routing must be checked on the actual devices. Do not interpret a system NFC sound as a successful application handshake.

## Later milestones (not implemented/testable in this APK)

Nearby acceptance will require matched public session metadata, unexpired receipt-time TTL, Play Services availability, API-appropriate runtime permissions, matching authentication codes and approval on both screens, rejection, timeout, disabled radios, disconnection, and offline hello receipt after moving apart. File acceptance will require image/PDF/large video transfers in both directions, SHA-256 equality, cancellation, 0-byte files, reconnect, safe save destinations, out-of-order metadata, scoped URI access and share-sheet inputs. Add real evidence when those layers are implemented.
