# TouchBridge — Android NFC Touch-to-Share Application

## 1. Project Overview

Build a fully functional native Android application called **TouchBridge**.

The application allows two Android phones to initiate a secure connection by physically touching their NFC antennas together. Once connected, users can exchange files, links, text, contacts, Wi-Fi credentials (where supported), and other types of information.

The inspiration is Apple's NameDrop and AirDrop, but the goal is a more versatile Android application.

This is a personal experimental project, not a college assignment or commercial application. However, implement it using proper software engineering practices, maintainable architecture, and reliable testing.

**Do not stop at writing a plan. Implement the application.**

## 2. Target Devices

The application must be designed and tested for:

- Google Pixel 9a
- Samsung Galaxy S21 Ultra

Both devices have NFC.

Do not assume they use the same Android version or have identical NFC behavior. Detect supported features at runtime.

Both devices should eventually be able to act as either sender or receiver.

## 3. Development Environment

### Initial development — Windows

All initial coding will happen on Windows using Codex.

Responsibilities:

- Create the Android project.
- Write Kotlin application code.
- Implement Jetpack Compose UI.
- Implement NFC reader and Host Card Emulation functionality.
- Implement the Nearby Connections integration.
- Configure Gradle.
- Run available unit tests and automated builds.
- Set up Git and GitHub Actions.

Ensure the application compiles successfully whenever possible.

Do not require physical NFC testing to proceed with tasks that can be implemented and tested independently.

### Physical testing — Arch Linux

The developer will later switch to Arch Linux with Celestia dotfiles.

During this stage:

- Connect both Android phones using ADB.
- Build and install debug APKs.
- Test real NFC communication.
- Test both devices as NFC reader and card emulator.
- Test Nearby Connections.
- Test large file transfers.
- Inspect Android logs and troubleshoot device-specific problems.

Provide exact commands and expected outputs for hardware testing.

### Cross-platform requirements

- Use the Gradle Wrapper (`gradlew` and `gradlew.bat`).
- Avoid hardcoded Windows or Linux paths.
- Never commit `local.properties`.
- Keep local SDK paths outside version control.
- Use platform-independent project configuration wherever possible.

## 4. Technology Stack

Primary language: Kotlin.

UI: Jetpack Compose with Material 3.

Architecture: MVVM, repositories, and clearly separated communication layers.

NFC APIs:

- `NfcAdapter`
- `enableReaderMode()`
- `HostApduService`
- `IsoDep`
- APDU commands

Wireless transfer: Google Nearby Connections API.

Storage: DataStore or Room when persistence becomes necessary.

Asynchronous operations: Kotlin Coroutines and Flow.

Build system: Gradle with Kotlin DSL.

Prefer official Android APIs and well-maintained libraries.

Do not introduce unnecessary frameworks or a backend server.

## 5. NFC Communication Architecture

Android Beam is deprecated and must not be used.

Implement a custom NFC communication protocol.

### Reader device

One device enters NFC reader mode using `NfcAdapter.enableReaderMode()`.

When another phone's card emulation is detected, it communicates using ISO-DEP and APDU commands.

### Card-emulation device

The second device exposes an `HostApduService`.

Define a custom application identifier (AID) and an APDU protocol.

Ensure the custom AID does not conflict with payment applications.

### Initial NFC payload

The first implementation should exchange a simple message:

"Hello from TouchBridge!"

Later, use the NFC communication channel to exchange a short-lived session token, protocol version, and connection metadata.

Do not transfer large files over NFC.

Do not assume arbitrary phone-to-phone NDEF push is available on modern Android.

### Security

- Generate unpredictable session identifiers.
- Use expiring session tokens.
- Do not log sensitive credentials or session secrets.
- Validate incoming APDU commands.
- Handle invalid, incomplete, and interrupted NFC exchanges.
- Require confirmation before transferring sensitive data.
- Avoid assuming that the NFC handshake alone authenticates the other phone.

### NFC limitations

The implementation must acknowledge Android restrictions.

Do not promise automatic communication while both phones are locked or while the application is not active.

Implement a dependable foreground experience first.

## 6. Wireless Connection Architecture

After the NFC handshake, establish a local peer-to-peer connection using Google Nearby Connections.

The NFC session token should allow the devices to associate the Nearby connection with the correct tap session.

Requirements:

- Discover the correct nearby device.
- Establish a secure connection.
- Validate that both devices agree on the session.
- Display connection authentication information when appropriate.
- Require approval before accepting a connection.
- Handle timeouts and connection failures.
- Show connection status to both users.
- Transfer files without requiring internet access.

Use Nearby Connections' supported byte and file payload types.

Large data must travel through the wireless connection, not through NFC.

## 7. Application Features

### Core features

Implement:

1. Touch-to-connect using NFC.
2. Transfer files between phones.
3. Share photos and videos.
4. Share plain text.
5. Share URLs.
6. Receive content from Android's system share sheet.
7. Show transfer progress.
8. Allow users to cancel transfers.
9. Display connection and transfer errors.
10. Maintain basic transfer history.

### Additional features

After core functionality works:

- Share contact cards using vCard.
- Share Wi-Fi credentials through explicitly supported Android flows.
- Share app links.
- Send multiple files in a single session.
- Remember previously trusted devices with appropriate security controls.
- Support manual nearby discovery when NFC is unavailable.
- Offer a shared clipboard experience while both users have explicitly enabled it.

### Wi-Fi credentials

Android normally restricts reading stored Wi-Fi passwords.

Do not attempt to bypass Android security.

Possible approaches:

- Let the user explicitly enter the Wi-Fi credentials.
- Support Wi-Fi QR-code sharing.
- Explore Wi-Fi Easy Connect on compatible devices.

Never claim that arbitrary saved Wi-Fi passwords can be accessed automatically.

## 8. User Interface

Create a modern, minimal Android interface using Jetpack Compose.

Preferred design:

- Clean typography.
- Material 3 components.
- Dark and light themes.
- Smooth, restrained animations.
- Clear connection status.
- Minimal unnecessary navigation.

### Home screen

Display:

- TouchBridge branding.
- A prominent "Ready to connect" state.
- Start NFC reader mode.
- Start NFC card-emulation mode.
- Manual nearby discovery.
- Sharing options.

### Connected screen

After establishing a connection, display the connected device and available actions:

- Files
- Photos
- Text
- Links
- Contacts
- Wi-Fi
- More

### Transfer screen

Show:

- File name.
- File size.
- Transfer progress.
- Transfer speed when measurable.
- Cancellation.
- Success or failure.

All interface actions must be connected to real application functionality. Avoid nonfunctional decorative buttons.

## 9. Development Milestones

Work incrementally and keep each stage buildable.

### Milestone 0 — Project foundation

Create:

- Android Studio/Gradle project.
- Kotlin configuration.
- Jetpack Compose interface.
- Android manifest.
- Appropriate permissions and NFC feature declarations.
- Basic navigation.
- Git configuration.
- GitHub Actions CI workflow.

Run compilation and available unit tests.

### Milestone 1 — NFC handshake

**FIRST CRITICAL FUNCTIONAL MILESTONE.**

Implement both NFC reader mode and Host Card Emulation.

Expected demonstration:

1. Launch TouchBridge on both devices.
2. Select card-emulation mode on the Samsung.
3. Select reader mode on the Pixel.
4. Bring their NFC antennas together.
5. The Pixel displays a message received from the Samsung.
6. Repeat with the roles reversed.

The implementation should use an actual ISO-DEP/APDU exchange.

Create appropriate unit tests with simulated APDU messages.

Clearly distinguish successful compilation and simulated testing from successful real-world NFC testing.

The milestone is not physically verified until both phones successfully exchange the message.

Do not claim that hardware tests passed without actual device evidence.

### Milestone 2 — Session protocol

Replace the demonstration message with a versioned NFC session protocol.

Implement:

- Session token generation.
- Token expiration.
- Protocol validation.
- Error responses.
- Interrupted-session recovery.

### Milestone 3 — Nearby Connections

Implement:

- Nearby device advertising.
- Discovery.
- Connection establishment.
- Authentication/confirmation.
- NFC session matching.
- Connection cleanup.

### Milestone 4 — File transfer

Implement actual file sharing.

Include:

- Android file picker.
- Android Storage Access Framework.
- File metadata exchange.
- Safe destination handling.
- Transfer progress.
- Cancellation.
- Success and error reporting.

Avoid unnecessary broad storage permissions.

### Milestone 5 — Additional sharing types

Add:

- Text.
- URLs.
- Contact cards.
- Android share-sheet integration.
- Wi-Fi sharing using approved methods.

### Milestone 6 — User experience

Improve:

- UI animations.
- Loading indicators.
- Error handling.
- Transfer history.
- Device-role switching.
- Settings.
- Accessibility.
- Dark and light themes.

### Milestone 7 — Reliability

Test:

- Interrupted NFC exchanges.
- Bluetooth or Wi-Fi disabled.
- Permission denial.
- Incorrect device discovery.
- Repeated connections.
- File-transfer cancellation.
- Large file transfers.
- Device rotation.
- App backgrounding.
- Different Android versions.
- Differences between Google and Samsung devices.

## 10. Git and GitHub

Use Git from the beginning.

The project will eventually be hosted in a private GitHub repository.

Use regular commits at meaningful checkpoints.

Suggested commit examples:

- `chore: initialize Android project`
- `feat: add NFC reader mode`
- `feat: implement HCE service`
- `feat: add NFC session handshake`
- `feat: integrate Nearby Connections`
- `feat: implement file transfers`

Configure `.gitignore` for Android and Gradle.

Never commit:

- `local.properties`
- Generated build directories.
- Machine-specific configuration.
- Keystore passwords.
- Tokens.
- API credentials.
- Other sensitive files.

The developer will synchronize code between Windows and Arch Linux using GitHub.

Do not automatically publish the repository or push to a remote unless the developer has configured and authorized that destination.

## 11. Continuous Integration

Set up GitHub Actions.

Create a workflow inside:

`.github/workflows/android-ci.yml`

The workflow should run on pushes and pull requests.

It must:

1. Check out repository code.
2. Set up the appropriate JDK.
3. Configure the Android build environment.
4. Use the Gradle Wrapper.
5. Compile the Android debug application.
6. Run available unit tests.
7. Upload the debug APK as a workflow artifact when compilation succeeds.

Use the Java, Android SDK, and Gradle versions appropriate for the generated project.

Ensure CI does not require a physical Android device for standard build and unit-test jobs.

The workflow should report failures clearly.

Use dependency caching where beneficial.

## 12. Continuous Delivery

Configure lightweight Continuous Delivery through GitHub Actions artifacts.

After a successful build, the developer should be able to download a debug APK from GitHub Actions.

Do not automatically publish the application to Google Play.

Do not configure production signing or deployment infrastructure yet.

The objective is straightforward:

Code changes → GitHub → Automated checks → Downloadable debug APK.

## 13. Testing Requirements

Use unit tests for:

- NFC APDU parsing.
- NFC message encoding.
- Session validation.
- Token expiration.
- Protocol errors.
- Transfer state management.

Use dependency interfaces or test doubles wherever hardware is required.

Provide clear manual testing procedures for physical NFC communication.

Do not silently replace failed hardware functionality with simulated behavior.

Do not invent test results.

After each milestone, report:

- Features implemented.
- Relevant files changed.
- Build results.
- Tests performed.
- Remaining limitations.
- Next milestone.

## 14. Code Quality

Write maintainable, readable Kotlin.

Requirements:

- Use meaningful names.
- Separate UI and communication logic.
- Avoid giant activity classes.
- Prefer small, focused services and components.
- Use lifecycle-aware coroutines.
- Handle permission requests properly.
- Clean up resources.
- Avoid leaking Android contexts.
- Validate external data.
- Avoid hardcoded device-specific assumptions.
- Document important platform limitations.

Do not add unnecessary abstractions purely for architectural complexity.

## 15. Development Instructions for Codex

Begin implementing immediately.

Start with Milestone 0 and Milestone 1.

Write the actual Android project files.

Use the available development tools to compile and test code where possible.

If something fails, investigate and fix it rather than simply reporting the problem.

Do not ask for confirmation at every stage.

Make reasonable implementation decisions independently.

For hardware-dependent behavior, implement the code and provide an explicit test procedure.

The developer is currently coding on Windows and will test on Arch Linux later.

Do not treat hardware implementation as physically verified until real device tests are completed.

Keep the project ready for continued development across operating systems.

The ultimate goal is a reliable, enjoyable Android application that allows two phones to initiate sharing through NFC and transfer content efficiently through a secure local connection.

**Start building TouchBridge now.**