# Validation record

Current checkpoint: v0.2.0 NFC-to-Nearby handoff. Earlier version measurements below are retained as historical build records.

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

**Nearby physical validation is pending.** The developer's passed NFC/status tests do not establish Nearby discovery, authentication, offline delivery, or OEM permission behavior. Follow [nearby-testing.md](nearby-testing.md) on both devices, record the Samsung API level, and test roles in both directions.

## Remaining work

### v0.2.1 startup investigation

Developer screenshots show Nearby startup failure on one phone followed by NFC `idle or expired` on the other. The startup failure path invalidates the NFC session; the second error is consistent with that cleanup. The screenshots contain no SDK status code, so the actual radio/service/permission failure is **not yet diagnosed**. No additional runtime permissions are being requested on speculation.

On `fix/nearby-startup-diagnostics`, startup failures now retain sanitized SDK status codes/names (never raw exception messages). Diagnostics show installed Play services version and missing runtime permissions. NFC sessions are prepared but cannot serve APDUs until Nearby advertising reports success. Generation/expiry guards prevent late readiness callbacks from enabling cancelled sessions. NFC Lab keeps its immediate, standalone behavior. `main` is unchanged; `stable/nfc-verified` preserves `2cb24ec`.

Windows checks pass with **50 unit tests**, including startup error propagation, stale failure rejection, pre-readiness APDU rejection, and expired/cancelled activation. Final build/CI results accompany the test artifact. Device retest and the precise Nearby startup status remain pending. A passing build does not establish that this resolves the underlying SDK failure on the phones.

Next: verify the real Nearby handoff and hello on Pixel/Samsung in both roles. Then implement SAF-backed file transfers, metadata/payload ordering, progress/cancellation, safe save, sharesheet, additional content types and history. File/content features, persistent trust and manual discovery fallback are not implemented yet.

See [hardware-testing.md](hardware-testing.md) for exact role-swap tests and required evidence, and [../README.md](../README.md) for Windows/Arch installation commands.
