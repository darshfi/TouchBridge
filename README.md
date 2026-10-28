# TouchBridge

A native Kotlin / Compose NFC experiment for Pixel 9a and Galaxy S21 Ultra. Install the **same app on both phones**. One phone emulates a card; the other reads it over ISO-DEP. Android Beam is not used.

## Current checkpoint

Version **0.3.0** adds file/photo/video sharing over Nearby FILE payloads, recipient approval, a system save picker, progress, cancellation, size/SHA-256 verification, and a saved-file receipt. **File sharing awaits two-phone testing.** The previous **0.2.2** connection/hello happy path passed developer tests with both NFC roles and message directions on Pixel 9a (API 37) and Galaxy S21 Ultra (API 35); it is preserved at `stable/nearby-verified-v0.2.2`. See [validation.md](docs/validation.md) and [file-transfer-testing.md](docs/file-transfer-testing.md). Offline/failure testing, share-sheet intake, contacts, Wi-Fi credentials, manual discovery and history remain later work.

The app shows a real reader result only after SELECT and GET_SESSION both succeed. Unit tests use a simulated transport and do not establish that either phone's NFC hardware works.

## Connect with Nearby

Startup fix: v0.2.2 on `fix/nearby-startup-diagnostics` removes the API cap from the normal `CHANGE_WIFI_STATE` permission after the Pixel recording identified Nearby status 8033. v0.2.1 also waits for advertising success before enabling NFC. Install the same new APK on both phones and wait for **Ready to tap** on the advertiser. If startup still fails, report the specific Nearby status code plus **Show diagnostics** (API, Play services version, missing permissions).

Install the same APK on both phones, unlock them, and enable NFC, Bluetooth and Wi-Fi. Keep TouchBridge foregrounded. No internet connection or shared router is required. Google Play services must be available; old Android versions also need system Location enabled for discovery.

1. On **Connect**, choose **Advertise + emulate** on one phone. Approve the runtime permissions; the app starts a fresh 60-second session after permission approval.
2. Choose **Scan + connect** on the other phone and grant its permissions. Touch the phones' NFC antennas together. The existing two-APDU exchange stays unchanged.
3. Both phones show a four-digit code. **Compare the codes on the actual phones and tap Codes match · accept on each.** Reject mismatches; an NFC tap alone is not authentication.
4. Wait for **Connected to the tapped phone** on both. The phones have now matched the NFC session over the approved encrypted link. Move them apart.
5. Tap **Send hello** on either phone. The other shows **Received: Hello from the other phone**. Test the reverse direction in the same connection.
6. **Stop session**, leaving the app, locking or rotating disconnects and clears pairing metadata. The explicitly launched system document picker can temporarily retain the approved connection for up to two minutes. Starting again requires a new tap and new approvals. Switch to **NFC Lab** to test NFC independently without wireless permissions.

See [nearby-testing.md](docs/nearby-testing.md) for rejection, timeout, permission, cancellation and offline tests. For files, choose **Choose file, photo, or video** after connecting. The receiver accepts and selects a destination before file bytes start. Both must wait for the verified/saved result. One file at a time; source files use a temporary private disk snapshot. See [file-transfer-testing.md](docs/file-transfer-testing.md) for exact acceptance steps and limitations.

## Build on Windows

Install Android Studio (with a compatible JDK, preferably 21), Android SDK Command-line Tools, platform 37.0, build-tools 36.0.0, and platform-tools. Open this directory as an Android Studio project, or use PowerShell:

```powershell
# Adjust these two local values to your own installed toolchain.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s YOUR_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

SDK tools may recommend the new `android sdk` CLI; `sdkmanager` remains usable for these package commands. Accept the Android SDK license prompts when installing. An ignored `local.properties` with your own `sdk.dir` is also supported. Do not copy it between machines or commit it. The initial Windows environment had Java 11 on PATH and an invalid JAVA_HOME; explicitly select a compatible JDK before building.

Output: `app/build/outputs/apk/debug/app-debug.apk`. Tests: `app/build/reports/tests/testDebugUnitTest/index.html`. Lint: `app/build/reports/lint-results-debug.html`.

## Build on Arch Linux

```bash
sudo pacman -S --needed jdk21-openjdk android-tools android-udev
# Install Android Studio or official Android SDK command-line tools separately.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk" # use your actual local SDK directory
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
sdkmanager --licenses
./gradlew testDebugUnitTest assembleDebug lintDebug
adb devices -l
adb -s PIXEL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SAMSUNG_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected build ending: `BUILD SUCCESSFUL`. Expected installation: `Success` for each device. Enable Developer options → USB debugging on both phones and accept each computer's RSA prompt. `adb devices -l` must list both as `device`, not `unauthorized` or `offline`. On Windows, install Google's USB driver for Pixel or Samsung's official USB driver when required. On Arch, reconnect USB after installing udev rules (or reload rules using `sudo udevadm control --reload-rules`); use a data-capable cable. Do not run adb as root. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a different debug signing key was used on the other OS: uninstall the old experimental app before reinstalling (this removes its local data), or deliberately share your private debug keystore outside Git.

## Two-phone acceptance test

1. Enable NFC on both phones. Unlock both and keep TouchBridge foregrounded. Read the Android API level in **Show diagnostics**; record the Samsung's actual version.
2. On Samsung select **Emulate · share NFC session**. Its countdown starts at 60 seconds.
3. On Pixel select **Scan · read the other phone**.
4. Bring the backs of the phones together. Move them slowly relative to each other to align their NFC antennas; do not assume the antennas are in identical positions. Remove thick/magnetic cases if necessary. Hold briefly through the exchange.
5. Pixel must show **NFC handshake successful**, **Hello from TouchBridge!**, protocol, a redacted session suffix, and elapsed milliseconds. Samsung should show **NFC session sent** with no countdown. Its result should remain visible after the original 60-second deadline; expired session metadata must still be rejected. The emulator has no receipt acknowledgement, so confirm the reader's screen too. NFC exchange does not authenticate the peer.
6. Move the phones apart. Stop both roles, then **swap roles**: Pixel Emulate, Samsung Scan. Require the same real result.
7. Run the failure/recovery matrix in [hardware-testing.md](docs/hardware-testing.md). Record the exact Git commit, device models/API levels, UI result, and sanitized logs. Basic exchanges in both directions have been reported successful; the status fix and remaining reliability scenarios need separate checks.

The app requests foreground routing with `setPreferredService` for its category-other AID and releases it on pause. OEM routing, secure NFC settings, and wallet services can still affect discovery. Keep the app unlocked and active, retry alignment, and check NFC settings; do not replace the default payment app.

## Protocol and boundaries

* AID: `F0544F55434842524944474501` (experimental proprietary F-prefix, category `other`, not a registered payment AID; no global uniqueness guarantee).
* SELECT: `00 A4 04 00 0D F0 54 4F 55 43 48 42 52 49 44 47 45 01 00`; see [protocol.md](docs/protocol.md) for exact framing.
* GET_SESSION: `80 10 00 00 00`.
* Response: protocol version (1 byte), random session identifier (16 bytes), remaining lifetime (4-byte big-endian milliseconds), fixed UTF-8 greeting, then `90 00`.
* Session IDs are generated using SecureRandom, last at most 60 seconds, stay in memory, and are invalidated on Stop, backgrounding, rotation, process death or successful Nearby matching. The reader subtracts elapsed NFC exchange time from the received TTL and enforces a conservative monotonic deadline for discovery/approval/matching. After pairing, the approved foreground Nearby connection can continue past the NFC deadline.
* HCE only serves explicitly opened foreground sessions. Its callback does bounded in-memory work. Reader transceive runs on Dispatchers.IO, closes ISO-DEP in finally, uses a 3-second per-command timeout, and suppresses overlapping/repeated callbacks.
* NFC is proximity metadata, **not authentication**. Nearby endpoint names contain the public ID and role. Nearby's authentication digits and approval on both phones protect against impersonation; a bounded MATCH control frame then verifies both endpoints agree on the NFC session. Only explicit Send hello transmits the user-visible message. Logs contain sanitized phase names, never IDs, codes, secrets or message contents.

Leaving the app or rotating deliberately stops the current role, except for the bounded system document picker flow. Restart explicitly after resuming. No locked-screen sharing, tap-to-launch guarantee, background radio services, broad storage access, analytics, or automatic Wi-Fi password access.

## Architecture

`nfc/SessionProtocol.kt` contains the JVM-testable codec, APDU state machine, session registry, and transport interface. `NfcReader` owns ISO-DEP; `TouchBridgeHceService` exposes the APDU service. `NfcSessionRepository` bridges the service and ViewModel in the same process. `ui/LabViewModel` owns user-visible state and cancellation; `MainActivity` owns foreground reader mode and routing. `diagnostics/Capabilities` checks actual NFC, HCE and API support. Compose provides a scrollable Material 3 lab, About screen, diagnostics, light/dark themes, and accessible text buttons. No hardware-dependent code is substituted with fake success.

`nearby/NearbyCoordinator` is a pure Kotlin state machine with a transport interface, generation guards, monotonic deadlines and bounded control frames. `AndroidNearbyTransport` adapts real Google Nearby callbacks, serializes radio starts across cancelled attempts, tracks actual payload completion, and releases all endpoints on cleanup. The Activity gates wireless starts behind runtime permissions and the ViewModel uses only application context. Auth digits are ephemeral UI state and are cleared when verification completes, expires or is cancelled.

## Permissions

| Permission / feature | This build | Reason |
| --- | --- | --- |
| `NFC` | Normal install-time permission | Reader + HCE; no runtime prompt is required |
| `BIND_NFC_SERVICE` | Protects exported HCE service | Only the system can bind; not requested as an app permission |
| `dev.touchbridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Generated by AndroidX, signature protected | Protects internal receiver delivery; no runtime prompt |
| NFC / HCE features | Optional | Unsupported devices can open diagnostics/About |
| `INTERNET`, Wi-Fi/network state | Normal permissions | Nearby's supported local Wi-Fi transports and radio status; no app backend |
| `CHANGE_WIFI_STATE` | Normal permission, all supported APIs | Required by the observed Nearby startup check on API 37; not a runtime Settings switch |
| Legacy Bluetooth | Through API 30 | Nearby on older Android releases |
| Coarse / fine location | Runtime on API 26–32 only (fine from 29) | Legacy Bluetooth/Wi-Fi discovery; Android 12 requests coarse and fine together |
| Bluetooth scan/connect/advertise | Runtime on API 31+ | Nearby radios; scanning asserts neverForLocation |
| `NEARBY_WIFI_DEVICES` | Runtime on API 33+ | Nearby Wi-Fi; neverForLocation |
| `ACCESS_LOCAL_NETWORK` | Runtime on API 37+ / target 37 | Nearby's default Wi-Fi LAN transport under Android 17 enforcement |
| Broad storage, contacts, background location | Not requested | File access uses explicit SAF picker grants; contacts/background sharing are later work |

## Git, CI and delivery

New work uses separate feature/fix branches and device-tested changes are merged only after developer confirmation. `stable/nfc-verified` preserves the device-tested v0.1.1 NFC implementation at `2cb24ec`. `stable/nearby-verified-v0.2.2` preserves the working connection build at `2762d09`. File sharing is isolated on `feature/file-transfer`; `main` stays unchanged during device testing. Phone screenshots/attachments are ignored by Git.

The private repository is [darshfi/TouchBridge](https://github.com/darshfi/TouchBridge). The Gradle wrapper and its distribution SHA-256 are checked in. CI (`.github/workflows/android-ci.yml`) uses an Ubuntu runner, JDK 21, Android SDK, wrapper validation/caching via setup-gradle, unit tests, lint and a debug build. After success, **touchbridge-debug** is available under the workflow run's Artifacts for seven days. Reports are uploaded even on failure. Check [Actions](https://github.com/darshfi/TouchBridge/actions/workflows/android-ci.yml) for the actual result for each commit. No Play deployment or production signing exists.

To install on your phone, sign into GitHub as the repository owner, open the latest successful Android CI run, and download **touchbridge-debug** from **Artifacts**. Extract the ZIP using your file manager, then open `app-debug.apk`. If prompted, permit APK installation for that browser/file manager. Install the same APK on both phones. GitHub's **Download ZIP** under the Code button contains source code and cannot be installed as an Android app. CI generates a fresh debug signing key on each runner: uninstall the previous experimental build before installing a newer CI APK when the signatures differ. Uninstalling removes app data. Check **About** for the installed version.

On another development computer, clone the private repository while signed in:

```bash
git clone https://github.com/darshfi/TouchBridge.git
cd TouchBridge
```

Use `git status` before switching computers. Commit or stash changes before `git pull --ff-only`; never overwrite uncommitted work. Commit locally, push, then pull on the other computer. GitLab can host the same Git repository, but this workflow runs on GitHub Actions only. Local debug signing keys differ by OS; generated APKs and private keys stay out of Git.

## Verified reference sources

Toolchain selection was checked against official [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity), [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle), Google Maven metadata, and [Android HCE documentation](https://developer.android.com/develop/connectivity/nfc/hce). The Kotlin compiler and Compose compiler are overridden together to 2.3.21 using AGP's [documented KGP override](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-the-kotlin-gradle-plugin), because Nearby 19.5.1's metadata is newer than AGP's bundled compiler can read. Gradle remains 9.6.0; CI uses JDK 21 and Windows uses installed JDK 23. Minimum API is 26, compile/target is 37. Nearby behavior follows Google's [connection management](https://developers.google.com/nearby/connections/android/manage-connections), [permissions](https://developers.google.com/nearby/connections/android/get-started), and Android's [local-network rules](https://developer.android.com/privacy-and-security/local-network-permission).

## Future trusted devices

A persistent per-installation identity and explicitly trusted devices are deferred. The intended identity is backed by a private key, with proof of possession rather than trusting an ID string. See [trusted-devices.md](docs/trusted-devices.md) for the recorded idea, privacy controls and reinstall/reset behavior.
