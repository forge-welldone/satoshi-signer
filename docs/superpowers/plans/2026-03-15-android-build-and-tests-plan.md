# Android Local Build & Smoke Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set up a local Android build environment on macOS without Android Studio and add Compose UI smoke tests.

**Architecture:** Homebrew installs JDK 17 and Android command-line tools. `sdkmanager` installs SDK components. Gradle wrapper gets committed to the repo. Smoke tests use Compose Testing framework to verify screens render and state-driven navigation works.

**Tech Stack:** OpenJDK 17, Android SDK 35, Gradle 8.11.1, Compose UI Test JUnit4, JUnit4

**Spec:** `docs/superpowers/specs/2026-03-15-android-build-and-tests-design.md`

---

## Chunk 1: Local Build Environment

### Task 1: Install JDK 17

**Files:** None (system setup)

- [ ] **Step 1: Install OpenJDK 17 via Homebrew**

```bash
brew install openjdk@17
```

- [ ] **Step 2: Verify JDK installed**

```bash
/opt/homebrew/opt/openjdk@17/bin/java -version
```

Expected: `openjdk version "17.x.x"`

- [ ] **Step 3: Set JAVA_HOME in shell profile**

Add to `~/.zshrc`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
```

- [ ] **Step 4: Reload shell and verify**

```bash
source ~/.zshrc
java -version
echo $JAVA_HOME
```

Expected: Java 17, JAVA_HOME pointing to Homebrew OpenJDK.

---

### Task 2: Install Android SDK

**Files:** None (system setup)

- [ ] **Step 1: Install Android command-line tools**

```bash
brew install --cask android-commandlinetools
```

This installs to `$HOMEBREW_PREFIX/share/android-commandlinetools/`.

- [ ] **Step 2: Set ANDROID_HOME and PATH in shell profile**

Add to `~/.zshrc`:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

- [ ] **Step 3: Create SDK directory and install components**

```bash
source ~/.zshrc
mkdir -p "$ANDROID_HOME"
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "platform-tools" \
  "emulator" \
  "system-images;android-35;google_apis;arm64-v8a"
```

Accept licenses when prompted:

```bash
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
```

- [ ] **Step 4: Verify SDK installation**

```bash
sdkmanager --sdk_root="$ANDROID_HOME" --list_installed
```

Expected: All five components listed.

- [ ] **Step 5: Create local.properties**

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > /Users/sasha/Projects/remote_signer/local.properties
```

This file is already in `.gitignore`.

---

### Task 3: Generate Gradle Wrapper

**Files:**
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Install Gradle temporarily to generate wrapper**

```bash
brew install gradle
```

- [ ] **Step 2: Generate wrapper targeting Gradle 8.11.1**

```bash
cd /Users/sasha/Projects/remote_signer
gradle wrapper --gradle-version 8.11.1
```

This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/` contents.

- [ ] **Step 3: Verify wrapper files exist**

```bash
ls -la gradlew gradlew.bat gradle/wrapper/
```

Expected: `gradlew` (executable), `gradlew.bat`, `gradle-wrapper.jar`, `gradle-wrapper.properties`.

- [ ] **Step 4: Verify wrapper version**

```bash
./gradlew --version
```

Expected: `Gradle 8.11.1`

- [ ] **Step 5: Commit Gradle wrapper**

```bash
git add gradlew gradlew.bat gradle/wrapper/
git commit -m "chore: add Gradle 8.11.1 wrapper"
```

---

### Task 4: Verify Android Build

**Files:** None

- [ ] **Step 1: Run assembleDebug**

```bash
cd /Users/sasha/Projects/remote_signer
./gradlew assembleDebug
```

This will download Gradle dependencies and Chaquopy's Python 3.13 on first run. May take several minutes.

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Verify APK output**

```bash
ls -la app/build/outputs/apk/debug/app-debug.apk
```

Expected: APK file exists.

---

### Task 5: Create and Boot Emulator

**Files:** None (system setup)

- [ ] **Step 1: Create AVD**

```bash
avdmanager create avd \
  --name test_device \
  --package "system-images;android-35;google_apis;arm64-v8a" \
  --device "pixel_7"
```

Select default for hardware profile when prompted.

- [ ] **Step 2: Boot emulator**

```bash
emulator -avd test_device -no-audio &
```

- [ ] **Step 3: Wait for boot and verify**

```bash
adb wait-for-device
adb shell getprop sys.boot_completed
```

Expected: `1`

- [ ] **Step 4: Verify device visible to Gradle**

```bash
adb devices
```

Expected: Emulator listed as `emulator-5554  device`.

---

## Chunk 2: Test Dependencies

### Task 6: Add Compose Test Dependencies to Version Catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add test libraries to version catalog**

Add these entries to `gradle/libs.versions.toml`:

In `[libraries]` section, after the existing entries:

```toml
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
```

No explicit version needed — they inherit from the Compose BOM.

- [ ] **Step 2: Add test runner version to versions section**

In `[versions]` section:

```toml
junit = "4.13.2"
test-runner = "1.6.2"
```

In `[libraries]` section:

```toml
junit = { group = "junit", name = "junit", version.ref = "junit" }
test-runner = { group = "androidx.test", name = "runner", version.ref = "test-runner" }
```

---

### Task 7: Add Test Dependencies to App Build

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add test instrumentation runner to defaultConfig**

In `app/build.gradle.kts`, inside `defaultConfig` block, after `versionName`:

```kotlin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

- [ ] **Step 2: Add test dependencies**

In `dependencies` block, after the existing `debugImplementation` line:

```kotlin
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
```

- [ ] **Step 3: Sync and verify**

```bash
./gradlew app:dependencies --configuration androidTestImplementation | head -30
```

Expected: Compose UI test and JUnit4 in dependency tree.

- [ ] **Step 4: Commit dependency changes**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore: add Compose test dependencies"
```

---

## Chunk 3: Smoke Tests

### Task 8: Test Fixtures

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`

- [ ] **Step 1: Create androidTest directory structure**

```bash
mkdir -p app/src/androidTest/kotlin/com/remotesigner
```

- [ ] **Step 2: Write test fixtures**

Create `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`:

```kotlin
package com.remotesigner

import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxOutput

object TestFixtures {

    val sampleOutputs = listOf(
        TxOutput(
            address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            amount = 50_000L,
            isChange = false,
        ),
        TxOutput(
            address = "bc1qrp33g0q5b5698ahp5jnf5yzjmgces69hsy6nt6",
            amount = 12_345L,
            isChange = true,
        ),
    )

    val sampleSigners = listOf(
        SignerInfo(fingerprint = "a1b2c3d4", signed = true, isThisDevice = true),
        SignerInfo(fingerprint = "e5f6a7b8", signed = false, isThisDevice = false),
    )

    val reviewState = AppState.TransactionReview(
        outputs = sampleOutputs,
        fee = 1_500L,
        totalSent = 51_500L,
        status = "Ready to sign",
        signers = sampleSigners,
        warnings = emptyList(),
    )

    val signingState = AppState.Signing(message = "Confirm on your Trezor...")

    val resultComplete = AppState.Result(
        isComplete = true,
        txid = "abc123def456",
        rawHex = "0200000001...",
    )

    val resultPartial = AppState.Result(
        isComplete = false,
        updatedPsbt = ByteArray(16),
    )

    val errorState = AppState.Error(message = "USB device not found")
}
```

- [ ] **Step 3: Commit fixtures**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt
git commit -m "test: add test fixtures for Android smoke tests"
```

---

### Task 9: App Launch Test

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/AppLaunchTest.kt`

- [ ] **Step 1: Write the app launch test**

Create `app/src/androidTest/kotlin/com/remotesigner/AppLaunchTest.kt`:

```kotlin
package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.HomeScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test

class AppLaunchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_displaysTitle() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(onPsbtSelected = {})
            }
        }
        composeTestRule.onNodeWithText("Satoshi Signer").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysOpenButton() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(onPsbtSelected = {})
            }
        }
        composeTestRule.onNodeWithText("Open PSBT File").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run tests on emulator**

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.AppLaunchTest
```

Expected: 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/AppLaunchTest.kt
git commit -m "test: add app launch smoke test"
```

---

### Task 10: Screen Render Tests

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Write screen render tests**

Create `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`:

```kotlin
package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.ResultScreen
import com.remotesigner.ui.SigningScreen
import com.remotesigner.ui.TransactionReviewScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test

class ScreenRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun transactionReviewScreen_displaysDetails() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                TransactionReviewScreen(
                    state = TestFixtures.reviewState,
                    onSign = {},
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun signingScreen_displaysProgress() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(message = "Confirm on your Trezor...")
            }
        }
        composeTestRule.onNodeWithText("Confirm on your Trezor...").assertIsDisplayed()
    }

    @Test
    fun resultScreen_completedTransaction() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = TestFixtures.resultComplete,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
    }

    @Test
    fun resultScreen_partialSignature() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = TestFixtures.resultPartial,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Signature Added").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export Updated PSBT").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run screen render tests**

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest
```

Expected: 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: add screen render smoke tests"
```

---

### Task 11: Navigation Test

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt`

- [ ] **Step 1: Write navigation state machine test**

Create `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt`:

```kotlin
package com.remotesigner

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.ui.HomeScreen
import com.remotesigner.ui.TransactionReviewScreen
import com.remotesigner.ui.SigningScreen
import com.remotesigner.ui.ResultScreen
import com.remotesigner.viewmodel.AppState
import org.junit.Rule
import org.junit.Test

class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stateChange_showsCorrectScreen() {
        val currentState = mutableStateOf<AppState>(AppState.Home)

        composeTestRule.setContent {
            SatoshiSignerTheme {
                when (val state = currentState.value) {
                    is AppState.Home -> HomeScreen(onPsbtSelected = {})
                    is AppState.TransactionReview -> TransactionReviewScreen(
                        state = state,
                        onSign = {},
                        onCancel = {},
                    )
                    is AppState.Signing -> SigningScreen(message = state.message)
                    is AppState.Result -> ResultScreen(
                        state = state,
                        onBroadcast = {},
                        onExportPsbt = {},
                        onHome = {},
                    )
                    is AppState.Error -> {}
                }
            }
        }

        // Home
        composeTestRule.onNodeWithText("Satoshi Signer").assertIsDisplayed()

        // Transition to TransactionReview
        currentState.value = TestFixtures.reviewState
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()

        // Transition to Signing
        currentState.value = TestFixtures.signingState
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Confirm on your Trezor...").assertIsDisplayed()

        // Transition to Result
        currentState.value = TestFixtures.resultComplete
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run navigation test**

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NavigationTest
```

Expected: 1 test passes.

- [ ] **Step 3: Run full test suite**

```bash
./gradlew connectedDebugAndroidTest
```

Expected: 7 tests pass (2 launch + 4 render + 1 navigation).

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt
git commit -m "test: add navigation state machine smoke test"
```
