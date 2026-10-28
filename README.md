# TouchBridge

A native Kotlin / Compose NFC experiment for Pixel 9a and Galaxy S21 Ultra. Install the **same app on both phones**. One phone emulates a card; the other reads it over ISO-DEP. Android Beam is not used.

## Current checkpoint

Milestones 0–1: project foundation and real NFC reader/HCE implementation, including the versioned short-lived session protocol. **The developer confirmed successful physical exchanges in both directions on Pixel 9a and Galaxy S21 Ultra on 2026-10-28.** Version 0.1.1 fixes the emulator's completion/countdown display; this change needs device retesting. See [validation.md](docs/validation.md) for build results and evidence. Nearby, file transfer, share-sheet integration, contacts, Wi-Fi, and history are subsequent work.

The app shows a real reader result only after SELECT and GET_SESSION both succeed. Unit tests use a simulated transport and do not establish that either phone's NFC hardware works.

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
* Session IDs are generated using SecureRandom, last at most 60 seconds, stay in memory, and are invalidated on Stop, backgrounding, rotation, or process death. A reader receives a relative TTL to avoid depending on synchronized device clocks. It is diagnostic metadata in this build; a future wireless coordinator must enforce its local receipt-time deadline too.
* HCE only serves explicitly opened foreground sessions. Its callback does bounded in-memory work. Reader transceive runs on Dispatchers.IO, closes ISO-DEP in finally, uses a 3-second per-command timeout, and suppresses overlapping/repeated callbacks.
* NFC is proximity metadata, **not authentication**. IDs intended for future Nearby matching are public. Nearby must display its authentication code and require approval on both phones before any transfer. No secrets, filenames, payload contents, IDs, or authentication codes are logged.

Leaving the app or rotating deliberately stops the current role. Restart explicitly after resuming. No locked-screen sharing, tap-to-launch guarantee, background radio services, broad storage access, analytics, or automatic Wi-Fi password access.

## Architecture

`nfc/SessionProtocol.kt` contains the JVM-testable codec, APDU state machine, session registry, and transport interface. `NfcReader` owns ISO-DEP; `TouchBridgeHceService` exposes the APDU service. `NfcSessionRepository` bridges the service and ViewModel in the same process. `ui/LabViewModel` owns user-visible state and cancellation; `MainActivity` owns foreground reader mode and routing. `diagnostics/Capabilities` checks actual NFC, HCE and API support. Compose provides a scrollable Material 3 lab, About screen, diagnostics, light/dark themes, and accessible text buttons. No hardware-dependent code is substituted with fake success.

## Permissions

| Permission / feature | This build | Reason |
| --- | --- | --- |
| `NFC` | Normal install-time permission | Reader + HCE; no runtime prompt is required |
| `BIND_NFC_SERVICE` | Protects exported HCE service | Only the system can bind; not requested as an app permission |
| `dev.touchbridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Generated by AndroidX, signature protected | Protects internal receiver delivery; no runtime prompt |
| NFC / HCE features | Optional | Unsupported devices can open diagnostics/About |
| Bluetooth, location, nearby Wi-Fi, local network, storage, contacts | Not requested | Wireless and content sharing are not implemented at this checkpoint |

## Git, CI and delivery

The private repository is [darshfi/TouchBridge](https://github.com/darshfi/TouchBridge). The Gradle wrapper and its distribution SHA-256 are checked in. CI (`.github/workflows/android-ci.yml`) uses an Ubuntu runner, JDK 21, Android SDK, wrapper validation/caching via setup-gradle, unit tests, lint and a debug build. After success, **touchbridge-debug** is available under the workflow run's Artifacts for seven days. Reports are uploaded even on failure. Check [Actions](https://github.com/darshfi/TouchBridge/actions/workflows/android-ci.yml) for the actual result for each commit. No Play deployment or production signing exists.

To install on your phone, sign into GitHub as the repository owner, open the latest successful Android CI run, and download **touchbridge-debug** from **Artifacts**. Extract the ZIP using your file manager, then open `app-debug.apk`. If prompted, permit APK installation for that browser/file manager. Install the same APK on both phones. GitHub's **Download ZIP** under the Code button contains source code and cannot be installed as an Android app. CI generates a fresh debug signing key on each runner: uninstall the previous experimental build before installing a newer CI APK when the signatures differ. Uninstalling removes app data. These are debug NFC Lab builds; wireless sharing is not implemented yet.

On another development computer, clone the private repository while signed in:

```bash
git clone https://github.com/darshfi/TouchBridge.git
cd TouchBridge
```

Use `git status` before switching computers. Commit or stash changes before `git pull --ff-only`; never overwrite uncommitted work. Commit locally, push, then pull on the other computer. GitLab can host the same Git repository, but this workflow runs on GitHub Actions only. Local debug signing keys differ by OS; generated APKs and private keys stay out of Git.

## Verified reference sources

Toolchain selection was checked against official [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity), [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle), Google Maven metadata, and [Android HCE documentation](https://developer.android.com/develop/connectivity/nfc/hce). AGP 9.4.1 uses built-in Kotlin 2.2.10; the Compose compiler plugin matches it. Gradle 9.6.0 requires a compatible runtime JDK (CI uses 21; initial Windows build uses installed 23). Minimum Android API is 26, compile/target is 37.
