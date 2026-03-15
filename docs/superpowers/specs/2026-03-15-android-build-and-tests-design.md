# Android Local Build Setup & Integration Tests Design

**Date:** 2026-03-15
**Status:** Approved

## Goal

Set up a fully functional local Android build environment without Android Studio, and add smoke-level Android integration tests that verify the app launches and renders correctly.

## Context

The project currently has:
- No JDK, Android SDK, or Gradle on the development machine
- No Gradle wrapper committed to the repository
- No Android instrumented tests
- Existing Python desktop tests via pytest (out of scope for this work)
- Homebrew available on macOS (Apple Silicon)

## Part 1: Local Build Setup

### JDK

Install OpenJDK 17 via Homebrew. JDK 17 is the LTS version required by AGP 8.x.

```bash
brew install openjdk@17
```

Set `JAVA_HOME` in shell profile pointing to the Homebrew OpenJDK path.

### Android SDK

Install Android command-line tools via Homebrew:

```bash
brew install --cask android-commandlinetools
```

Then use `sdkmanager` to install required components:

- `platforms;android-35` — target SDK
- `build-tools;35.0.0` — build toolchain
- `platform-tools` — adb, fastboot
- `emulator` — Android emulator
- `system-images;android-35;google_apis;arm64-v8a` — emulator system image (arm64 for Apple Silicon)

Set `ANDROID_HOME` in shell profile.

### Gradle Wrapper

Generate the Gradle wrapper in the project root using a locally installed Gradle or by creating the wrapper files directly. Target Gradle 8.11.1 (compatible with AGP 8.7.3). Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/` to the repository.

### Shell Configuration

Add to `~/.zshrc`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
```

### Verification

`./gradlew assembleDebug` should complete successfully, producing an APK at `app/build/outputs/apk/debug/`.

## Part 2: Android Emulator

Create an AVD using `avdmanager`:

- Device profile: `pixel_7` (or similar)
- System image: `system-images;android-35;google_apis;arm64-v8a`
- AVD name: `test_device`

Provide a convenience script or documented commands for:
- Creating the AVD
- Launching the emulator (headless for CI, windowed for dev)
- Waiting for boot completion

## Part 3: Android Integration Tests (Smoke)

### Framework

- **Compose Testing** (`androidx.compose.ui:ui-test-junit4`) — first-party Compose test APIs
- **JUnit4** runner — standard Android instrumented test runner
- **Compose test manifest** (`ui-test-manifest`) — debug-only test activity

### Dependencies to Add

In `app/build.gradle.kts`:

```kotlin
androidTestImplementation(libs.compose.ui.test.junit4)
debugImplementation(libs.compose.ui.test.manifest)
```

With corresponding entries in `libs.versions.toml`.

### Test Location

`app/src/androidTest/kotlin/com/remotesigner/`

### Test Suite

**1. AppLaunchTest** — Verifies the app process starts and the main activity renders without crash. Asserts the Home screen's key UI elements are visible (app title, scan button or equivalent).

**2. ScreenRenderTest** — Tests each screen composable in isolation using `createComposeRule().setContent {}`. Verifies that:
- `HomeScreen` renders its primary elements
- `TransactionReviewScreen` renders with mock transaction data
- `SigningScreen` renders its progress indicator
- `ResultScreen` renders with mock success/failure states

**3. NavigationTest** — Tests the state-machine-driven navigation by manipulating `AppState` through the ViewModel (or by providing state directly to the UI) and verifying the correct screen composable appears for each state.

### Test Helpers

A `TestFixtures.kt` file providing:
- Mock `AppState` instances for each state variant
- Factory functions for creating test transaction data
- Shared setup utilities

This structure makes it straightforward to add deeper functional tests later — just add new test classes that use the same fixtures and Compose test rules.

### Running Tests

```bash
# Start emulator
emulator -avd test_device -no-window -no-audio &
adb wait-for-device

# Run tests
./gradlew connectedDebugAndroidTest

# Or a specific test class
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.AppLaunchTest
```

## Out of Scope

- Python integration tests (existing pytest suite is sufficient for now)
- GitHub Actions CI pipeline (separate follow-up)
- Android lint / ktlint configuration
- Code coverage reporting
- UI screenshot testing
