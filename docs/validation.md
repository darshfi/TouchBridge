# Validation record

Checkpoint: project foundation + NFC reader/HCE/session protocol.

## Implemented

* Kotlin/Compose Material 3 Android project, Gradle wrapper, optional NFC/HCE capability declarations, NFC Lab / About, runtime capability diagnostics, light/dark themes and settings action.
* Foreground reader using actual NFC-A ISO-DEP SELECT + GET_SESSION, 3-second per-command timeout, overlapping callback suppression, cancellation, safe errors and connection cleanup.
* Protected category-other HostApduService; bounded APDU parsing; random in-memory 60-second sessions; monotonic expiry; invalidation on lifecycle pause; protocol generation checks and interrupted-link recovery.
* JVM protocol/state tests and GitHub Actions build/test/lint + debug APK artifact delivery.

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

Seven regression tests cover late UI observation, post-success TTL expiry, unserved expiry, old-session results, cancellation/role switches, invalid commands and repeated taps. Windows `testDebugUnitTest assembleDebug lintDebug` passed in 2m 25s: **21 tests, zero failures/errors/skips**, successful APK packaging, and lint with zero errors/one Gradle update advisory. APK signature verification also passed. **Retesting the status fix on both physical devices is pending.** The original version 0.1.0 build measurements above are historical.

## Remaining work

Next: Nearby advertising correlated with the NFC public session identifier, discovery, Play Services checks, correctly scoped runtime permissions, two-sided authentication confirmation, expiry enforcement, cancellation/timeouts, and an explicitly sent hello. Then SAF-backed file transfers, metadata/payload ordering, progress/cancellation, safe save, sharesheet, additional content types and history. These features are not implemented by this checkpoint.

See [hardware-testing.md](hardware-testing.md) for exact role-swap tests and required evidence, and [../README.md](../README.md) for Windows/Arch installation commands.
