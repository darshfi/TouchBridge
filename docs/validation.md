# Validation record

Current checkpoint: v0.3.0 file sharing, confirmed working by the developer and approved for merge to `main` on 2026-10-28. Earlier version measurements below are retained as historical build records.

## Developer acceptance and continuation

After installing the v0.3.0 build from CI run 40699854907 (code commit `514f38a`), the developer reported "it worked" and explicitly requested push/merge, with development to continue later. Record this as developer-reported successful physical file sharing, not as a pass for every test in the acceptance matrix. No new recordings, file hashes, sizes, direction-specific results or failure-case results were supplied with this confirmation.

Promote the tested `feature/file-transfer` history to `main` and preserve it as `stable/file-transfer-verified-v0.3.0`. Keep `stable/nfc-verified` and `stable/nearby-verified-v0.2.2` intact. No application code changes accompany this acceptance record; the previous 73-test build, lint and CI evidence still apply.

For the next session: continue on a new feature/fix branch from the merged main checkpoint. Remaining work includes the outstanding reliability/offline matrix, additional sharing types/share-sheet intake and persistent history. The persistent identity/trusted-device idea remains deferred in [trusted-devices.md](trusted-devices.md). Do not infer automatic trust or comprehensive hardware validation from the successful basic transfer.

## v0.3.0 — file sharing software validation

The developer confirmed v0.2.2 worked smoothly, and authorized the next milestone. That build is preserved on `stable/nearby-verified-v0.2.2` at `2762d09`; the original `stable/nfc-verified` and `main` remain unchanged.

Implemented a bounded file control codec (`transfer/FileProtocol.kt`), pure transfer coordinator, scoped Android file storage, Nearby FILE payload callbacks and progress, and explicit sender/receiver UI. Android OpenDocument chooses sources; CreateDocument chooses destinations after recipient approval. The source is snapshotted to private disk with known length/SHA-256. The receiver waits for actual SDK completion, copies through scoped URIs with bounded buffers, checks size/hash, closes the destination stream and sends a saved receipt. Sender success requires both actual payload completion and that receipt. Cancellation/disconnect/error attempts to remove partial output after the saving job has stopped. Session/generation checks reject late callbacks. No new broad storage permissions or identity/trust features were introduced.

The system document picker is a bounded two-minute exception to foreground teardown; other app switches and rotation still stop the session. Both phones must use 0.3.0. Old 0.2.x peers do not understand the file control protocol. One file is supported at a time; cloud-provider stalls, large files, storage behavior and actual picker lifecycle must be tested on both target phones.

Windows `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --console=plain` passed. **73 unit tests, zero failures/errors/skips**: existing 50 plus file framing/validation, actual bounded-buffer content verification (empty/multiple buffers, corruption/truncation/oversize/cancellation), transfer consent, saved receipts, progress/order handling, timeouts, cancellation/cleanup, stale callbacks, concurrent offers and a simulated two-peer transfer in both directions. Lint passed with zero errors (existing toolchain update advisories plus conservative cache-space advice). Packaged APK reports **0.3.0 (6)**, min API 26 / target 37, and `apksigner verify --verbose` confirms a valid v2 debug signature. The existing merged-manifest Wi-Fi permission regression check passes.

At build time, physical file sharing awaited testing; the developer subsequently confirmed it worked as recorded above. The remaining two-phone acceptance matrix, exact build/install commands and byte-for-byte hash comparison are in [file-transfer-testing.md](file-transfer-testing.md). Continue reliability testing and additional sharing types/history in a future session. The requested future persistent identity/trusted-device idea is recorded in [trusted-devices.md](trusted-devices.md); no hardware identifiers or automatic trust were added.

[GitHub CI run 40699854907](https://github.com/darshfi/TouchBridge/actions/runs/40699854907) passed for code commit `514f38a`, uploading `touchbridge-debug` and `android-check-reports`. The downloaded CI APK was independently inspected: 0.3.0 (6), min API 26 / target 37, valid v2 debug signature. The downloaded reports confirm 73 passing tests and lint with zero errors/three advisories. Its APK and reports remain outside Git.

## Real-device happy-path evidence — v0.2.2

Reviewed the developer's two screen recordings supplied on 2026-10-28:

* `screen-20261028-185458-1793193855109.mp4`: Pixel 9a, API 37, TouchBridge 0.2.2, Play services 26.40.32.
* `Screen_Recording_20261028_185457_TouchBridge.mp4`: Samsung Galaxy S21 Ultra, API 35, TouchBridge 0.2.2, Play services 26.34.36.

Both recordings show advertising succeeding, NFC session exchange, Nearby requesting/approval/session verification reaching Connected, a Stop/reset followed by the NFC roles being swapped, and another successful connection. Final frames on **both phones** show Hello sent, Nearby delivery success and Received: Hello from the other phone. This establishes the real-device Nearby hello happy path in both directions, beyond the simulated unit tests. The installed version corresponds to code checkpoint `cc93757`; the APK manifest fix has passed this observed startup test.

These are screen-recording observations, not ADB/logcat results. They do not establish physical distance between phones, internet being disabled, radio-off/permission-denial behavior, rejection handling, long-duration expiry, repeated stress tests or file transfer. No session identifiers or authentication codes are copied into this record. Original videos and extracted frames remain outside Git. Main and `stable/nfc-verified` remain unchanged pending developer merge confirmation.

## Implemented

* Kotlin/Compose Material 3 Android project, Gradle wrapper, optional NFC/HCE capability declarations, NFC Lab / About, runtime capability diagnostics, light/dark themes and settings action.
* Foreground reader using actual NFC-A ISO-DEP SELECT + GET_SESSION, 3-second per-command timeout, overlapping callback suppression, cancellation, safe errors and connection cleanup.
* Protected category-other HostApduService; bounded APDU parsing; random in-memory 60-second sessions; monotonic expiry; invalidation on lifecycle pause; protocol generation checks and interrupted-link recovery.
* JVM protocol/state tests and GitHub Actions build/test/lint + debug APK artifact delivery.
* Nearby advertising/discovery correlated with the NFC session, mandatory matching-code approval on both phones, encrypted session validation, bounded pairing/message timeouts, lifecycle cleanup, runtime permission handling, and an explicitly sent fixed hello in either direction.

## Execution status

Windows, 2026-10-28: Gradle Wrapper 9.6.0, AGP 9.4.1 / built-in Kotlin 2.2.10, installed Oracle JDK 23.0.1, Android platform 37.0 revision 2, build-tools 36.0.0. SDK/toolchain paths are outside the repository. The invalid inherited JAVA_HOME was overridden for build commands only; global user settings were not changed.

Command: `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --console=plain`.

* Kotlin compilation and `assembleDebug`: passed; APK generated.
* `testDebugUnitTest`: **14 tests, zero failures, zero errors, zero skipped**. Includes 10,000 reproducibly randomized malformed APDUs inside one test, wire-byte golden checks, TTL boundaries, cancellation/restart generation checks, interrupted link recovery, status words, and invalid version/length/ID/message responses.
* Final build and tests: **BUILD SUCCESSFUL**. The first full build took 6m 46s; subsequent incremental verification passed after lint cleanup.
* Lint: **passed, zero errors and one advisory warning** that newer Gradle 9.8.2 is available. Gradle 9.6.0 remains pinned to the tested version documented by AGP 9.4's compatibility table. A fresh `lintDebug --rerun-tasks` regenerated the report after cleanup and passed in 16 seconds. No lint suppressions or baseline were introduced.
* `apksigner verify --verbose`: **Verifies**, APK Signature Scheme v2, one debug signer.
* `aapt dump badging`: package `dev.touchbridge`, version `0.1.0` (1), minimum API 26, target/compile API 37, optional NFC/HCE; no storage, location or wireless permissions requested. AndroidX adds its signature-protected internal receiver permission.
* Manifest/resource XML parsing and `git diff --check`: passed.

APK: `app/build/outputs/apk/debug/app-debug.apk` (12,007,952 bytes). SHA-256: `6341796ebd021fc657debb5cd835e64a0ae9565a4fb3736251191a4e758fd8e4`. APKs remain ignored by Git. The AndroidX graphics path native library was packaged without stripping symbols; Gradle emitted a non-fatal warning.

The first environment setup needed official archive downloads with curl after Java downloads timed out. SDK archive checksums matched Google's repository metadata; the Gradle distribution matched its official SHA-256. This affects local setup only; no download mirror or local path was added to project configuration.

The original Windows checkpoint had CI configured but had not run on GitHub. The project has since been published to the private [darshfi/TouchBridge](https://github.com/darshfi/TouchBridge) repository. The first GitHub run stopped during SDK setup because the action's default `tools` package is no longer provided. The workflow now pins current command-line tools and requests only `platform-tools` before installing the required platform/build-tools. See [Actions](https://github.com/darshfi/TouchBridge/actions/workflows/android-ci.yml) for current execution results and downloadable APK artifacts.

**Basic physical NFC exchange passed in both directions, as reported by the developer on 2026-10-28.** Screenshots show successful greeting receipt in 67 ms and emulator response feedback; the developer confirmed both phones also worked with roles reversed. The reverse direction's screenshot was not supplied. Samsung API level and ADB/logcat evidence remain unrecorded; the original Windows build session had no ADB devices attached. Nearby and file-transfer hardware tests have not been performed.

## Version 0.1.1 — emulator status fix

The developer reported successful receiving with intermittent emulator-side completion/countdown confusion. The old screen always displayed the session countdown after response service, and its timer replaced a successful result with Session expired. A process-wide read counter also did not associate completion with a particular session.

The registry now retains response-served status for its generation. Both the observer and periodic UI refresh read that state. Old/cancelled sessions cannot complete a new session; a served result hides the countdown and remains visible after the protocol deadline. Token expiry is still enforced, and Stop/background/rotation still clear the role. UI wording now says NFC session sent because serving a response alone is not an acknowledgement from the reader. The two-APDU wire protocol is unchanged.

Seven regression tests cover late UI observation, post-success TTL expiry, unserved expiry, old-session results, cancellation/role switches, invalid commands and repeated taps. Windows `testDebugUnitTest assembleDebug lintDebug` passed in 2m 25s: **21 tests, zero failures/errors/skips**, successful APK packaging, and lint with zero errors/one Gradle update advisory. APK signature verification also passed. **The developer subsequently confirmed the sender-status fix works on the phones.** The original version 0.1.0 build measurements above are historical.

## Version 0.2.0 — NFC-to-Nearby

Adds a Connect screen, foreground P2P_POINT_TO_POINT advertising/discovery, exact ephemeral endpoint-name matching, four-digit comparison with explicit acceptance on each phone, MATCH verification over the approved encrypted connection, and a Send hello action backed by actual BYTES payload completion callbacks. No user-visible hello is sent automatically. NFC Lab remains independent of wireless permissions. Successful matching releases NFC routing and invalidates the NFC registry while retaining the approved foreground connection.

Pure Kotlin tests exercise both coordinators communicating through a fake transport, both-sided approval, wrong endpoint/session rejection, absent NFC proof, invalid codes/messages, timeouts, out-of-order frames, disconnects, cancellation, late callbacks, duplicate callbacks and API-specific permission sets. These are simulated software checks, not radio tests. Current Windows checks: **44 tests, zero failures/errors/skips**, debug build and lint passed. Final incremental verification passed in 23s. APK Signature Scheme v2 verification passed; packaged version is 0.2.0 (3). Lint has zero errors and two update advisories (Gradle and the intentionally aligned coroutines-test version). Bluetooth, location, Wi-Fi and NFC features are optional in the packaged manifest; runtime checks determine availability. GitHub execution status is available from the published commit's Actions run.

Nearby 19.5.1's metadata could not be read by the original Kotlin 2.2.10 compiler. Kotlin/Compose 2.4.21 compiled but triggered an internal Android lint analysis crash; Kotlin/Compose **2.3.21** reads the library successfully and runs lint without disabling its experimental-API checks. A dependency constraint raises Play services' transitive Fragment 1.1.0 to stable 1.9.1 for Activity Result permission compatibility. Runtime coroutines stay aligned with Nearby's 1.10.2 BOM; the test library matches it. Toolchain pins were checked against official documentation/Maven metadata, and builds use the wrapper with no local SDK/JDK paths committed.

This v0.2.0 milestone originally awaited hardware testing. The v0.2.2 recordings documented above now establish successful Nearby setup and hello delivery in both roles/directions. Offline and failure/recovery testing remains pending in [nearby-testing.md](nearby-testing.md).

## Remaining work

### v0.2.2 — confirmed Wi-Fi manifest defect

The developer's Pixel recording shows **8033 / MISSING_PERMISSION_CHANGE_WIFI_STATE**, TouchBridge 0.2.1, API 37, Play services 26.40.32, and no missing runtime permissions. The app manifest capped this normal permission at API 32, excluding the Pixel. The cap is removed; no additional runtime permission or Settings toggle is requested. A build task now verifies the merged manifest contains uncapped ACCESS_WIFI_STATE and CHANGE_WIFI_STATE, and assembleDebug depends on it.

The Samsung recording shows **API 35** and a generic startup message; the developer subsequently confirmed **0.2.1 on both phones**. A later typed report gives API 25/27, which conflicts with the recorded API 35/37 diagnostics; keep that discrepancy explicit rather than infer an app-version mismatch from layout alone. Both recordings also show successful standalone NFC exchanges in the two roles. Install the same 0.2.2 APK on both devices for the next Nearby test. No successful Nearby connection is claimed from these recordings.

The correction stays on `fix/nearby-startup-diagnostics`; main and the device-verified NFC checkpoint are untouched. Windows debug build, **50 unit tests** and lint passed. The merged-manifest check passed; deliberately restoring the original cap made it fail, and restoring the fix made it pass again. APK inspection confirms CHANGE_WIFI_STATE has no maxSdkVersion, version 0.2.2 (5), and valid debug signature. GitHub CI and a fresh two-device test are separate checks. The videos/frames stay outside Git.

### v0.2.1 startup investigation

Developer screenshots show Nearby startup failure on one phone followed by NFC `idle or expired` on the other. The startup failure path invalidates the NFC session; the second error is consistent with that cleanup. The screenshots contain no SDK status code, so the actual radio/service/permission failure is **not yet diagnosed**. No additional runtime permissions are being requested on speculation.

On `fix/nearby-startup-diagnostics`, startup failures now retain sanitized SDK status codes/names (never raw exception messages). Diagnostics show installed Play services version and missing runtime permissions. NFC sessions are prepared but cannot serve APDUs until Nearby advertising reports success. Generation/expiry guards prevent late readiness callbacks from enabling cancelled sessions. NFC Lab keeps its immediate, standalone behavior. `main` is unchanged; `stable/nfc-verified` preserves `2cb24ec`.

Windows checks pass with **50 unit tests**, including startup error propagation, stale failure rejection, pre-readiness APDU rejection, and expired/cancelled activation. Final build/CI results accompany the test artifact. Device retest and the precise Nearby startup status remain pending. A passing build does not establish that this resolves the underlying SDK failure on the phones.

Next implementation milestone: SAF-backed file transfers, metadata/payload ordering, progress/cancellation and safe saving. Continue the outstanding Nearby reliability/offline tests independently. Share-sheet receiving, additional content types, history, persistent trust and manual discovery fallback are not implemented yet.

See [hardware-testing.md](hardware-testing.md) for exact role-swap tests and required evidence, and [../README.md](../README.md) for Windows/Arch installation commands.
