# Android E2E & UI Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Chaquopy E2E tests (cassette-driven full-flow signing on emulator) and UI-only tests for error screen, passphrase dialog, and signing log.

**Architecture:** Extract a `SigningBridge` interface from `UsbBridge`, refactor `SignerViewModel.signWithTrezor()` to delegate to a testable `signWithBridge()`, create a Kotlin `PlaybackBridge` for cassette replay, then add both E2E and UI tests.

**Tech Stack:** Kotlin, Jetpack Compose, Chaquopy, Android Instrumented Tests (Compose UI Test), JUnit 4

**Spec:** `docs/superpowers/specs/2026-03-16-android-e2e-tests-design.md`

---

## Chunk 1: Production Code Refactoring

### Task 1: Create `SigningBridge` interface

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/usb/SigningBridge.kt`

- [ ] **Step 1: Create the interface file**

```kotlin
package com.remotesigner.usb

/**
 * Common interface for Trezor USB communication bridges.
 * Implemented by UsbBridge (production) and PlaybackBridge (tests).
 * Python's AndroidHandle calls writeChunk/readChunk via Chaquopy proxy.
 */
interface SigningBridge {
    fun open()
    fun close()
    fun writeChunk(data: ByteArray)
    fun readChunk(): ByteArray
}
```

- [ ] **Step 2: Make `UsbBridge` implement `SigningBridge`**

In `app/src/main/kotlin/com/remotesigner/usb/UsbBridge.kt:18`, change:
```kotlin
class UsbBridge(
```
to:
```kotlin
class UsbBridge(
```
and add `: SigningBridge` after the closing `)` of the constructor. The existing `open()`, `close()`, `writeChunk()`, `readChunk()` methods already match the interface — add `override` to each.

Specifically change:
- Line 18: `class UsbBridge(` → keep class declaration, add `: SigningBridge` after line 21's `)`
- Line 64: `fun open()` → `override fun open()`
- Line 126: `fun close()` → `override fun close()`
- Line 144-145: `fun writeChunk(data: ByteArray)` → `override fun writeChunk(data: ByteArray)`
- Line 172-173: `fun readChunk(): ByteArray` → `override fun readChunk(): ByteArray`

- [ ] **Step 3: Widen `PythonBridge.signPsbt()` bridge parameter**

In `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`:
- Line 5: Change `import com.remotesigner.usb.UsbBridge` → `import com.remotesigner.usb.SigningBridge`
- Line 29: Change `bridge: UsbBridge,` → `bridge: SigningBridge,`

- [ ] **Step 4: Build to verify no regressions**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/usb/SigningBridge.kt \
       app/src/main/kotlin/com/remotesigner/usb/UsbBridge.kt \
       app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt
git commit -m "refactor: extract SigningBridge interface from UsbBridge"
```

---

### Task 2: Extract `signWithBridge()` from `SignerViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add import and widen `currentUsbBridge` type**

In `SignerViewModel.kt`:
- Add import: `import androidx.annotation.VisibleForTesting`
- Add import: `import com.remotesigner.usb.SigningBridge`
- Line 77: Change `private var currentUsbBridge: UsbBridge? = null` → `private var currentUsbBridge: SigningBridge? = null`

- [ ] **Step 2: Extract `signWithBridge()` as a suspend function**

Add this new method after `signWithTrezor()` (after line 264). This is the signing logic extracted from `signWithTrezor()`. It's a `suspend fun` — the caller provides the coroutine scope. This avoids a double-launch that would break `cancelSigning()` and split the bridge lifecycle.

```kotlin
    @VisibleForTesting
    internal fun signWithBridge(bridge: SigningBridge, psbtBytes: ByteArray, network: String) {
        _state.value = AppState.Signing("Signing...", log = "")
        signingJob = viewModelScope.launch {
            doSignWithBridge(bridge, psbtBytes, network)
        }
    }

    private suspend fun doSignWithBridge(bridge: SigningBridge, psbtBytes: ByteArray, network: String) {
        fun log(msg: String) {
            val current = (_state.value as? AppState.Signing)?.log ?: ""
            _state.value = AppState.Signing(msg, log = current + msg + "\n")
        }

        try {
            log("Starting Python signing (network=$network)...")
            val signingCallback = SigningCallbackImpl(
                onStatusUpdate = { status ->
                    viewModelScope.launch { log("Python: $status") }
                },
                onPassphraseRequest = { availableOnDevice ->
                    _passphraseRequest.value = PassphraseRequest(
                        availableOnDevice, currentSigningCallback!!
                    )
                },
                onPassphraseSubmitted = { _passphraseRequest.value = null },
            )
            currentSigningCallback = signingCallback

            val result = withContext(Dispatchers.IO) {
                pythonBridge.signPsbt(
                    psbtBytes = psbtBytes,
                    bridge = bridge,
                    callback = signingCallback,
                    network = network,
                )
            }
            _passphraseRequest.value = null
            currentSigningCallback = null

            when (result["status"]) {
                "complete" -> {
                    _state.value = AppState.Result(
                        isComplete = true,
                        rawHex = result["raw_tx"]?.toString(),
                    )
                }
                "partial" -> {
                    _state.value = AppState.Result(
                        isComplete = false,
                        updatedPsbt = result["psbt"] as? ByteArray,
                    )
                }
                else -> {
                    _state.value = AppState.Error(
                        result["message"]?.toString() ?: "Signing failed"
                    )
                }
            }
        } catch (e: Exception) {
            val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
            _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
        } finally {
            _passphraseRequest.value = null
            currentSigningCallback = null
            bridge.close()
            currentUsbBridge = null
        }
    }
```

**Design:** `signWithBridge()` is the public test entry point — it sets initial state, assigns `signingJob`, and launches. `doSignWithBridge()` is the `suspend fun` with the actual logic, also called by `signWithTrezor()` within its existing coroutine. Single coroutine = single `signingJob` = `cancelSigning()` works correctly.

- [ ] **Step 3: Refactor `signWithTrezor()` to delegate to `doSignWithBridge()`**

Replace the signing portion of `signWithTrezor()`. The method currently starts the signing coroutine at line 188. Replace lines 186-264 (from `_state.value = AppState.Signing("Connecting to Trezor..."` through the closing `}` of `signingJob = viewModelScope.launch {`) with:

```kotlin
        _state.value = AppState.Signing("Connecting to Trezor...", log = "")

        signingJob = viewModelScope.launch {
            fun log(msg: String) {
                val current = (_state.value as? AppState.Signing)?.log ?: ""
                _state.value = AppState.Signing(msg, log = current + msg + "\n")
            }

            try {
                log("Opening USB connection...")
                val bridge = withContext(Dispatchers.IO) {
                    trezorUsb.openDevice(device)
                        ?: throw IllegalStateException("Failed to open USB device")
                }
                currentUsbBridge = bridge

                log(bridge.dumpDeviceInfo())

                log("Claiming interface & finding endpoints...")
                withContext(Dispatchers.IO) { bridge.open() }
                log("USB bridge opened OK")

                // Delegate signing to shared suspend function
                doSignWithBridge(bridge, psbt, currentNetwork)
            } catch (e: Exception) {
                // Only catches USB open failures — doSignWithBridge has its own try/catch
                if (_state.value is AppState.Signing) {
                    val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
                    _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
                }
            } finally {
                // Safety net: if doSignWithBridge didn't run, ensure bridge is closed
                currentUsbBridge?.close()
                currentUsbBridge = null
            }
        }
```

**Key:** Single `signingJob` coroutine owns the entire lifecycle. `doSignWithBridge()` runs inline (suspend), not as a separate launch. The `finally` in the outer block is a safety net for USB-open failures; `doSignWithBridge()` handles its own `bridge.close()` for the normal path.

- [ ] **Step 4: Build to verify no regressions**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "refactor: extract signWithBridge() from signWithTrezor()"
```

---

### Task 3: Extract `ErrorScreen` composable

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt:66-88`

- [ ] **Step 1: Create `ErrorScreen.kt`**

```kotlin
package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ErrorScreen(
    message: String,
    onHome: () -> Unit,
) {
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Error", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onHome) {
                    Text("Back to Home")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("error", message))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy Error")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Update `AppNavigation.kt` to delegate to `ErrorScreen`**

Replace lines 66-88 in `AppNavigation.kt` (the `is AppState.Error ->` block) with:

```kotlin
        is AppState.Error -> ErrorScreen(
            message = s.message,
            onHome = { viewModel.goHome() },
        )
```

Also remove these now-unused imports from `AppNavigation.kt` (if they become unused — verify after edit):
- `androidx.compose.foundation.rememberScrollState`
- `androidx.compose.foundation.verticalScroll`
- `androidx.compose.ui.unit.sp`

Actually, keep all imports — other screens in `AppRoot` may use them. The `import *` pattern means they won't cause warnings.

- [ ] **Step 3: Build to verify**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt \
       app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "refactor: extract ErrorScreen composable from AppNavigation"
```

---

## Chunk 2: Test Infrastructure

### Task 4: Copy cassette files to androidTest assets

**Files:**
- Create: `app/src/androidTest/assets/cassettes/single-sig-p2wpkh.json`
- Create: `app/src/androidTest/assets/cassettes/multisig-testnet3.json`

- [ ] **Step 1: Create the assets directory and copy cassettes**

```bash
mkdir -p app/src/androidTest/assets/cassettes
cp tests/cassettes/single-sig-p2wpkh.json app/src/androidTest/assets/cassettes/
cp tests/cassettes/multisig-testnet3.json app/src/androidTest/assets/cassettes/
```

- [ ] **Step 2: Commit**

```bash
git add app/src/androidTest/assets/cassettes/
git commit -m "test: copy cassette files to androidTest assets"
```

---

### Task 5: Create Kotlin `PlaybackBridge`

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/PlaybackBridge.kt`

- [ ] **Step 1: Create `PlaybackBridge.kt`**

```kotlin
package com.remotesigner

import android.content.Context
import com.remotesigner.usb.SigningBridge
import org.json.JSONObject

/**
 * Replays recorded Trezor USB exchanges from a cassette JSON file.
 * Implements [SigningBridge] so it can substitute for [UsbBridge] in tests.
 *
 * Strict validation: writeChunk asserts data matches the recording exactly.
 * readChunk returns the next recorded read. Any mismatch throws immediately.
 */
class PlaybackBridge(cassetteJson: JSONObject) : SigningBridge {

    val inputPsbtB64: String
    val network: String

    private data class Exchange(val dir: String, val data: String)

    private val exchanges: List<Exchange>
    private var pos = 0

    init {
        val metadata = cassetteJson.getJSONObject("metadata")
        inputPsbtB64 = metadata.getString("input_psbt_b64")
        network = metadata.optString("network", "main")

        val arr = cassetteJson.getJSONArray("exchanges")
        exchanges = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Exchange(dir = obj.getString("dir"), data = obj.getString("data"))
        }
    }

    override fun open() {}
    override fun close() {}

    override fun writeChunk(data: ByteArray) {
        check(pos < exchanges.size) { "Cassette exhausted at position $pos (total ${exchanges.size})" }
        val expected = exchanges[pos]
        check(expected.dir == "w") { "Expected write at pos $pos, got read" }
        val actualHex = data.joinToString("") { "%02x".format(it) }
        check(actualHex == expected.data) {
            "Write mismatch at pos $pos:\n  expected: ${expected.data.take(32)}...\n  actual:   ${actualHex.take(32)}..."
        }
        pos++
    }

    override fun readChunk(): ByteArray {
        check(pos < exchanges.size) { "Cassette exhausted at position $pos (total ${exchanges.size})" }
        val expected = exchanges[pos]
        check(expected.dir == "r") { "Expected read at pos $pos, got write" }
        pos++
        return expected.data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun assertConsumed() {
        check(pos == exchanges.size) {
            "Cassette not fully consumed: $pos / ${exchanges.size}"
        }
    }

    companion object {
        fun fromAsset(context: Context, name: String): PlaybackBridge {
            val json = context.assets.open("cassettes/$name").bufferedReader().use { it.readText() }
            return PlaybackBridge(JSONObject(json))
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/PlaybackBridge.kt
git commit -m "test: add Kotlin PlaybackBridge for cassette replay"
```

---

### Task 6: Fix existing test fixtures and call sites

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt:34-41`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt:35`

- [ ] **Step 1: Fix `TestFixtures.kt` — add missing `inputs` to `reviewState` and add new fixtures**

Replace the entire file with:

```kotlin
package com.remotesigner

import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.PassphraseRequest
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxInput
import com.remotesigner.viewmodel.TxOutput

object TestFixtures {
    val sampleInputs = listOf(
        TxInput(address = "tb1q...sender", amount = 6_236_567L),
    )

    val sampleOutputs = listOf(
        TxOutput(
            address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            amount = 5_000_000L, // 0.05 BTC
            isChange = false,
        ),
        TxOutput(
            address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            amount = 1_234_567L, // ~0.01234567 BTC change
            isChange = true,
        ),
    )

    val sampleSigners = listOf(
        SignerInfo(fingerprint = "a1b2c3d4", signed = true, isThisDevice = true),
        SignerInfo(fingerprint = "e5f6a7b8", signed = false, isThisDevice = false),
    )

    val reviewState = AppState.TransactionReview(
        inputs = sampleInputs,
        outputs = sampleOutputs,
        fee = 2_100L,
        totalSent = 5_000_000L,
        status = "needs_sig",
        signers = sampleSigners,
        warnings = emptyList(),
    )

    val signingState = AppState.Signing(message = "Confirm on your Trezor...")

    val signingStateWithLog = AppState.Signing(
        message = "Confirm on your Trezor...",
        log = "Opening USB connection...\nClaiming interface...\nPython: Parsing PSBT...\n",
    )

    val resultComplete = AppState.Result(
        isComplete = true,
        txid = "a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1",
        rawHex = "01000000000101deadbeef00000000001976a914abc123def456abc123def456abc123def456abc12388ac00000000",
    )

    val resultPartial = AppState.Result(
        isComplete = false,
        updatedPsbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
    )

    val errorState = AppState.Error(message = "USB device not found")

    val noOpCallback = SigningCallbackImpl(
        onStatusUpdate = { _ -> },
        onPassphraseRequest = { _ -> },
    )

    val passphraseRequestOnDevice = PassphraseRequest(
        availableOnDevice = true,
        callback = noOpCallback,
    )

    val passphraseRequestPhoneOnly = PassphraseRequest(
        availableOnDevice = false,
        callback = noOpCallback,
    )
}
```

- [ ] **Step 2: Fix `ScreenRenderTest.kt` — update `SigningScreen` call**

Replace lines 34-41 (`signingScreen_displaysProgress` test) with:

```kotlin
    @Test
    fun signingScreen_displaysProgress() {
        val state = TestFixtures.signingState
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(state.message).assertIsDisplayed()
    }
```

- [ ] **Step 3: Fix `NavigationTest.kt` — update `SigningScreen` call**

Replace line 35 in `NavigationTest.kt`:
```kotlin
                    is AppState.Signing -> SigningScreen(message = current.message)
```
with:
```kotlin
                    is AppState.Signing -> SigningScreen(
                        message = current.message,
                        log = current.log,
                        passphraseRequest = null,
                        onCancel = {},
                    )
```

- [ ] **Step 4: Build and run existing tests to verify fixes**

Run: `./gradlew compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt \
       app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt \
       app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt
git commit -m "fix: update test fixtures and call sites for current SigningScreen signature"
```

---

## Chunk 3: UI-Only Tests

### Task 7: Add error screen tests

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Add import for `ErrorScreen`**

Add to `ScreenRenderTest.kt` imports:
```kotlin
import com.remotesigner.ui.ErrorScreen
```

- [ ] **Step 2: Add error screen tests**

Append these tests to the `ScreenRenderTest` class:

```kotlin
    @Test
    fun errorScreen_displaysMessageAndButtons() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ErrorScreen(
                    message = TestFixtures.errorState.message,
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText(TestFixtures.errorState.message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy Error").assertIsDisplayed()
    }

    @Test
    fun errorScreen_withSigningLog() {
        val longMessage = "Signing error: timeout\n\n--- Log ---\nOpening USB...\nClaiming interface...\nPython: Parsing PSBT...\nPython: Signing...\nConnection lost"
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ErrorScreen(
                    message = longMessage,
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText(longMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Home").assertIsDisplayed()
    }
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: add error screen render tests"
```

---

### Task 8: Add signing screen log and cancel tests

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Add log and cancel button tests**

Append these tests to the `ScreenRenderTest` class:

```kotlin
    @Test
    fun signingScreen_displaysLogAndCopyButton() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(state.message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Debug Log:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText(state.log).assertIsDisplayed()
    }

    @Test
    fun signingScreen_displaysCancelButton() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: add signing screen log and cancel button tests"
```

---

### Task 9: Add passphrase dialog tests

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Add imports for click and node matching**

Add to `ScreenRenderTest.kt` imports:
```kotlin
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
```

- [ ] **Step 2: Add passphrase dialog tests**

Append these tests to the `ScreenRenderTest` class:

```kotlin
    @Test
    fun signingScreen_passphraseDialog_onDeviceAvailable() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Passphrase Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose where to enter your passphrase:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
        // Both the dialog and SigningScreen have a "Cancel" — assert both exist
        composeTestRule.onAllNodesWithText("Cancel").assertCountEquals(2)
    }

    @Test
    fun signingScreen_passphraseDialog_phoneOnly() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestPhoneOnly,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Passphrase Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Less secure than on-device entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseDialog_switchToTextField() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    onCancel = {},
                )
            }
        }
        // Start on choice screen
        composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
        // Tap "Enter on phone" to switch to text field
        composeTestRule.onNodeWithText("Enter on phone").performClick()
        composeTestRule.waitForIdle()
        // Text field and Submit should now appear
        composeTestRule.onNodeWithText("Less secure than on-device entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseDialog_backToChoices() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    onCancel = {},
                )
            }
        }
        // Switch to text field
        composeTestRule.onNodeWithText("Enter on phone").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Submit").assertIsDisplayed()
        // Tap "Back" to return to choices
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        // Choice screen should be back
        composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
    }
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: add passphrase dialog UI tests"
```

---

## Chunk 4: Chaquopy E2E Tests

### Task 10: Add Chaquopy E2E test class

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt`

- [ ] **Step 1: Create `ChaquopyE2ETest.kt`**

```kotlin
package com.remotesigner

import android.app.Application
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end tests that exercise the full Kotlin → Chaquopy → Python → PlaybackBridge
 * → Compose UI chain on the Android emulator.
 *
 * Uses recorded USB cassettes from androidTest/assets/cassettes/ so no Trezor
 * hardware is needed. Proves that Chaquopy initialization, Python module imports,
 * PythonBridge JSON round-trip, and the Compose state machine all work together.
 */
@LargeTest
class ChaquopyE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: SignerViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = SignerViewModel(app)
    }

    @Test
    fun singleSigP2wpkh_parseThenSign() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val playbackBridge = PlaybackBridge.fromAsset(context, "single-sig-p2wpkh.json")
        val psbtBytes = Base64.decode(playbackBridge.inputPsbtB64, Base64.DEFAULT)

        composeTestRule.setContent {
            SatoshiSignerTheme { AppRoot(viewModel = viewModel) }
        }

        // Load PSBT → Chaquopy parse_psbt() → TransactionReview
        viewModel.loadPsbt(psbtBytes)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            viewModel.state.value is AppState.TransactionReview
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()

        // Sign with cassette replay → Chaquopy sign_psbt() → Result
        viewModel.signWithBridge(playbackBridge, psbtBytes, playbackBridge.network)
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            viewModel.state.value is AppState.Result || viewModel.state.value is AppState.Error
        }

        // Single-sig produces a partial result (one signature added)
        val state = viewModel.state.value
        if (state is AppState.Error) {
            throw AssertionError("Signing failed with error: ${state.message}")
        }
        composeTestRule.onNodeWithText("Signature Added").assertIsDisplayed()
        playbackBridge.assertConsumed()
    }

    @Test
    fun multisigTestnet3_parseThenSign() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val playbackBridge = PlaybackBridge.fromAsset(context, "multisig-testnet3.json")
        val psbtBytes = Base64.decode(playbackBridge.inputPsbtB64, Base64.DEFAULT)

        composeTestRule.setContent {
            SatoshiSignerTheme { AppRoot(viewModel = viewModel) }
        }

        // Load PSBT → Chaquopy parse_psbt() → TransactionReview
        viewModel.loadPsbt(psbtBytes)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            viewModel.state.value is AppState.TransactionReview
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()

        // Sign with cassette replay → Chaquopy sign_psbt() → Result
        viewModel.signWithBridge(playbackBridge, psbtBytes, playbackBridge.network)
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            viewModel.state.value is AppState.Result || viewModel.state.value is AppState.Error
        }

        // Multisig produces a complete transaction
        val state = viewModel.state.value
        if (state is AppState.Error) {
            throw AssertionError("Signing failed with error: ${state.message}")
        }
        composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
        playbackBridge.assertConsumed()
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew compileDebugAndroidTestKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt
git commit -m "test: add Chaquopy E2E tests with cassette replay"
```

---

### Task 11: Run all tests on emulator

- [ ] **Step 1: Run the full Android test suite**

This requires a running emulator (`emulator -avd test_device -no-audio &`).

Run: `./gradlew connectedDebugAndroidTest 2>&1 | tail -20`
Expected: All tests pass. The Chaquopy E2E tests will be slower (10-30s each due to Python initialization).

If any test fails, diagnose and fix before proceeding.

- [ ] **Step 2: Run desktop Python tests to verify no regressions**

Run: `python -m pytest tests/ -v 2>&1 | tail -20`
Expected: All existing tests pass.

- [ ] **Step 3: Final commit if any fixes were needed**

Only if fixes were applied in steps 1-2.
