# v0.3.0 — file transfer device acceptance

Branch: `feature/file-transfer`. Baseline `stable/nearby-verified-v0.2.2` preserves the previous device-tested connection build; `stable/nfc-verified` remains untouched. File sharing is implemented but has not been physically verified yet. Install the same **0.3.0** APK on both phones.

## Build and install

Use the Windows toolchain environment from README, then:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
adb devices -l
adb -s PIXEL_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
adb -s SAMSUNG_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

On Arch:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
adb devices -l
adb -s PIXEL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SAMSUNG_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PIXEL_SERIAL shell am start -n dev.touchbridge/.MainActivity
adb -s SAMSUNG_SERIAL shell am start -n dev.touchbridge/.MainActivity
```

Expected: `BUILD SUCCESSFUL`, both authorized devices listed, `Success` for each install. Different CI/OS debug signing keys can prevent in-place updates; this milestone does not change signing. Check About shows 0.3.0 on both.

## First transfer

1. Enable NFC, Bluetooth and Wi-Fi; keep both phones unlocked. Establish the same tap + matching-code connection used in 0.2.2. Verify hello in both directions as a regression check.
2. Sender: **Choose file, photo, or video**, then select a small local file. Returning from this system picker must retain the connection. Preparation makes a private snapshot with a size and SHA-256 hash; large/cloud files can take longer and require temporary local space.
3. Receiver: check the displayed filename and size. Tap **Accept · choose save location**, choose a folder/name, then Save. File bytes must not start before this acceptance. The system picker may temporarily retain the connection for up to two minutes.
4. Both show progress, byte count and average speed. Either may cancel while transferring. Keep both apps open through **Verifying and saving**.
5. Receiver must show **File verified and saved to your chosen location**. Sender must show **The other phone verified and saved the file**. SDK delivery alone must never claim the receiver saved it.
6. Open the saved file from the phone's Files app. Repeat in the opposite direction within the same connection, then stop, swap NFC roles, and repeat.

## Required matrix (all pending physical evidence)

| Case | Expected |
| --- | --- |
| Small text/PDF, JPEG, video; both directions | Exact contents saved; both report completion |
| Zero-byte file | Saved as empty; size/hash verified |
| File larger than 100 MB, then a large video | Progress advances; no full-file memory allocation; usable saved file |
| Internet off; Bluetooth/Wi-Fi on | Local transfer works; record actual result separately |
| Cancel source picker | Connection remains; no file offer |
| Decline offer / cancel destination picker | No file bytes sent; sender sees declined/canceled |
| Cancel on sender and receiver mid-transfer | Both leave transfer state; partial destination deletion attempted; connection can send another file |
| Destination or sender out of space; provider rejects writes | Visible error; no success receipt |
| Both choose files simultaneously | Busy offers declined; no accidental consent; retry one side |
| Stop, Home, lock, rotate or disconnect during transfer/save | Session ends; late callbacks cannot revive transfer; inspect partial-file cleanup |
| Leave picker open past two minutes | Bounded session/offer timeout; late destination is deleted rather than accepted for another transfer |
| Repeated files, duplicate names | SAF chooses destination; an existing file is not silently overwritten |
| NFC Lab regression with roles reversed | Greeting and sender status remain functional |

One transfer at a time. Multiple-file batches, system share-sheet intake, automatic background transfers, persistent history and trusted devices are later milestones. Sources are staged on disk for a stable snapshot, including providers with unknown length; no broad storage permission is requested. SDK incoming files are read through scoped URIs only after successful payload completion. Size/hash failures delete the partial chosen destination where the provider permits. Process death may leave an empty/partial document or an SDK temporary download; inspect and remove it manually. A cloud document provider may itself need internet even though Nearby does not.

For byte-for-byte comparison, use a local Downloads destination and a source generated on the test computer:

```bash
dd if=/dev/urandom of=touchbridge-test.bin bs=1M count=100
sha256sum touchbridge-test.bin
adb -s PIXEL_SERIAL push touchbridge-test.bin /sdcard/Download/
# Send it in the app; save it as touchbridge-received.bin under Samsung Downloads.
adb -s SAMSUNG_SERIAL pull /sdcard/Download/touchbridge-received.bin .
sha256sum touchbridge-received.bin
adb -s PIXEL_SERIAL logcat -d -s TouchBridgeNearby:I TouchBridgeReader:I TouchBridgeHce:I
adb -s SAMSUNG_SERIAL logcat -d -s TouchBridgeNearby:I TouchBridgeReader:I TouchBridgeHce:I
```

Expected: identical hashes, both final completion states, no crash. Logs contain phase names rather than filenames, authentication digits or session identifiers. Record version/commit/API levels and actual outcomes; simulated unit tests do not establish hardware success.

Implementation follows [Nearby FILE payload and completion callbacks](https://developers.google.com/nearby/connections/android/exchange-data) and [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
