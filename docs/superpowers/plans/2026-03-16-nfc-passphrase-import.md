# NFC Passphrase Import Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NFC tag reading as a third passphrase entry method in the Trezor signing flow.

**Architecture:** NFC reading is Kotlin-only. A pure function parses NDEF text payloads. The ViewModel bridges NFC reader mode (Activity-level API) to Compose UI via StateFlows. The parsed passphrase feeds into the existing `submitPassphrase()` → `LinkedBlockingQueue` mechanism — zero Python changes.

**Tech Stack:** Android NFC framework (`android.nfc.*`), Kotlin, Jetpack Compose, existing SigningCallbackImpl.

**Spec:** `docs/superpowers/specs/2026-03-16-nfc-passphrase-import-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `app/src/main/kotlin/com/remotesigner/nfc/NdefTextParser.kt` | Pure function `parseNdefTextPayload()` + `NfcReadResult` sealed class |
| Create: `app/src/test/kotlin/com/remotesigner/nfc/NdefTextParserTest.kt` | JVM unit tests for NDEF parsing (no Android framework needed) |
| Modify: `app/src/main/AndroidManifest.xml` | NFC permission + feature |
| Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` | `nfcWaitingForTag` + `nfcTagResult` StateFlows |
| Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt` | enableReaderMode / disableReaderMode lifecycle |
| Modify: `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt` | PassphraseDialog NFC option + waiting screen |
| Modify: `app/build.gradle.kts` | Add `testImplementation(libs.junit)` for JVM unit tests |
| Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` | Compose UI tests for NFC dialog states |

---

## Chunk 1: NDEF Text Parsing (Pure Function)

### Task 1: NDEF text payload parser

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nfc/NdefTextParser.kt`
- Create: `app/src/test/kotlin/com/remotesigner/nfc/NdefTextParserTest.kt`

- [ ] **Step 0: Add `testImplementation` JUnit dependency**

The project only has `androidTestImplementation(libs.junit)`. JVM unit tests need their own dependency.

In `app/build.gradle.kts`, in the `dependencies` block, add:

```kotlin
testImplementation(libs.junit)
```

- [ ] **Step 1: Write failing tests for `parseNdefTextPayload`**

Create `app/src/test/kotlin/com/remotesigner/nfc/NdefTextParserTest.kt`:

```kotlin
package com.remotesigner.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdefTextParserTest {

    @Test
    fun utf8_english_text() {
        // Status byte: 0x02 = UTF-8 encoding, language code length 2
        // Language: "en"
        // Text: "MyPassphrase"
        val payload = byteArrayOf(0x02) + "en".toByteArray() + "MyPassphrase".toByteArray()
        assertEquals("MyPassphrase", parseNdefTextPayload(payload))
    }

    @Test
    fun utf8_empty_language_code() {
        // Status byte: 0x00 = UTF-8, language code length 0
        val payload = byteArrayOf(0x00) + "secret123".toByteArray()
        assertEquals("secret123", parseNdefTextPayload(payload))
    }

    @Test
    fun utf8_long_language_code() {
        // Status byte: 0x05 = UTF-8, language code length 5
        // Language: "en-US"
        val payload = byteArrayOf(0x05) + "en-US".toByteArray() + "pass".toByteArray()
        assertEquals("pass", parseNdefTextPayload(payload))
    }

    @Test
    fun utf16_text() {
        // Status byte: 0x82 = bit 7 set (UTF-16), language code length 2
        // Language: "en"
        // Text: "AB" in UTF-16BE = 0x00 0x41 0x00 0x42
        val payload = byteArrayOf(0x82.toByte()) + "en".toByteArray() +
            byteArrayOf(0x00, 0x41, 0x00, 0x42)
        assertEquals("AB", parseNdefTextPayload(payload))
    }

    @Test
    fun empty_payload_returns_null() {
        assertNull(parseNdefTextPayload(byteArrayOf()))
    }

    @Test
    fun payload_too_short_for_language_code_returns_null() {
        // Status byte says language code is 5 bytes, but payload is only 3 bytes total
        assertNull(parseNdefTextPayload(byteArrayOf(0x05, 0x65, 0x6E)))
    }

    @Test
    fun text_portion_empty_returns_empty_string() {
        // Status byte: 0x02 = UTF-8, language code length 2
        // Language: "en", no text after
        val payload = byteArrayOf(0x02) + "en".toByteArray()
        assertEquals("", parseNdefTextPayload(payload))
    }

    @Test
    fun special_characters_preserved() {
        val passphrase = "p@ss wörd!€"
        val payload = byteArrayOf(0x02) + "en".toByteArray() + passphrase.toByteArray(Charsets.UTF_8)
        assertEquals(passphrase, parseNdefTextPayload(payload))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.remotesigner.nfc.NdefTextParserTest" --info`
Expected: Compilation error — `parseNdefTextPayload` not found.

- [ ] **Step 3: Implement `parseNdefTextPayload` and `NfcReadResult`**

Create `app/src/main/kotlin/com/remotesigner/nfc/NdefTextParser.kt`:

```kotlin
package com.remotesigner.nfc

/**
 * Result of reading an NFC tag for passphrase content.
 */
sealed class NfcReadResult {
    data class Success(val passphrase: String) : NfcReadResult()
    data class Error(val message: String) : NfcReadResult()
}

/**
 * Parse an NDEF RTD_TEXT payload into its text content.
 *
 * RTD_TEXT format:
 *   payload[0]       = status byte (bit 7: 0=UTF-8, 1=UTF-16; bits 5-0: language code length)
 *   payload[1..n]    = language code (e.g., "en")
 *   payload[n+1..end] = actual text
 *
 * Returns null if the payload is empty or malformed.
 */
fun parseNdefTextPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null

    val statusByte = payload[0].toInt() and 0xFF
    val isUtf16 = (statusByte and 0x80) != 0
    val langLen = statusByte and 0x3F

    if (payload.size < 1 + langLen) return null

    val textBytes = payload.copyOfRange(1 + langLen, payload.size)
    val charset = if (isUtf16) Charsets.UTF_16BE else Charsets.UTF_8
    return String(textBytes, charset)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.remotesigner.nfc.NdefTextParserTest" --info`
Expected: All 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nfc/NdefTextParser.kt \
       app/src/test/kotlin/com/remotesigner/nfc/NdefTextParserTest.kt
git commit -m "feat: add NDEF text payload parser for NFC passphrase import"
```

---

## Chunk 2: Android Manifest + ViewModel NFC State

### Task 2: Add NFC permission and feature to manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:1-6`

- [ ] **Step 1: Add NFC permission and optional feature**

In `AndroidManifest.xml`, after line 4 (`<uses-feature android:name="android.hardware.usb.host" ...>`), add:

```xml
<uses-feature android:name="android.hardware.nfc" android:required="false" />
<uses-permission android:name="android.permission.NFC" />
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add NFC permission and optional feature to manifest"
```

### Task 3: Add NFC state to ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:78-94`

- [ ] **Step 1: Add NFC StateFlows and methods to SignerViewModel**

Add imports at top of file:

```kotlin
import com.remotesigner.nfc.NfcReadResult
```

Add after `_accountPathRequest` / `accountPathRequest` declarations (after line 93):

```kotlin
private val _nfcWaitingForTag = MutableStateFlow(false)
val nfcWaitingForTag: StateFlow<Boolean> = _nfcWaitingForTag.asStateFlow()
private val _nfcTagResult = MutableStateFlow<NfcReadResult?>(null)
val nfcTagResult: StateFlow<NfcReadResult?> = _nfcTagResult.asStateFlow()

fun startNfcWaiting() {
    _nfcTagResult.value = null
    _nfcWaitingForTag.value = true
}

fun stopNfcWaiting() {
    _nfcWaitingForTag.value = false
    _nfcTagResult.value = null
}

fun onNfcTagResult(result: NfcReadResult) {
    _nfcTagResult.value = result
}

fun clearNfcResult() {
    _nfcTagResult.value = null
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: add NFC waiting state and result flows to ViewModel"
```

---

## Chunk 3: MainActivity NFC Reader Mode

### Task 4: Wire NFC reader mode to Activity lifecycle

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt`

- [ ] **Step 1: Add NFC reader mode logic to MainActivity**

Replace the full content of `MainActivity.kt` with the following (adds NFC imports, adapter init, reader mode enable/disable on lifecycle, and tag reading callback):

```kotlin
package com.remotesigner

import android.content.Intent
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.nfc.parseNdefTextPayload
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.viewmodel.SignerViewModel
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val viewModel: SignerViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val psbtBytes = readPsbtFromIntent(intent)

        setContent {
            SatoshiSignerTheme {
                AppRoot(viewModel = viewModel, intentPsbtBytes = psbtBytes)
            }
        }

        // Observe nfcWaitingForTag and enable/disable reader mode accordingly
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.nfcWaitingForTag.collect { waiting ->
                    if (waiting) enableNfcReaderMode() else disableNfcReaderMode()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startNostrReceiver()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopNostrReceiver()
    }

    override fun onPause() {
        super.onPause()
        // Safety net: disable reader mode when Activity pauses
        disableNfcReaderMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(this, ::onTagDiscovered, flags, null)
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    private fun onTagDiscovered(tag: Tag) {
        // Runs on binder thread — only post to StateFlow, no UI operations
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            viewModel.onNfcTagResult(
                NfcReadResult.Error("Tag does not contain a passphrase. Use an NDEF-configured tag.")
            )
            return
        }

        try {
            ndef.connect()
            val message = ndef.ndefMessage
            if (message == null) {
                viewModel.onNfcTagResult(NfcReadResult.Error("No passphrase found on tag"))
                return
            }

            val textRecord = message.records.firstOrNull { record ->
                record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_TEXT)
            }

            if (textRecord == null) {
                viewModel.onNfcTagResult(NfcReadResult.Error("No text found on tag"))
                return
            }

            val text = parseNdefTextPayload(textRecord.payload)
            if (text == null) {
                viewModel.onNfcTagResult(NfcReadResult.Error("Failed to read tag — try again"))
                return
            }

            if (text.isEmpty()) {
                viewModel.onNfcTagResult(NfcReadResult.Error("Tag contains an empty passphrase"))
                return
            }

            viewModel.onNfcTagResult(NfcReadResult.Success(text))
        } catch (e: IOException) {
            viewModel.onNfcTagResult(NfcReadResult.Error("Failed to read tag — try again"))
        } finally {
            try { ndef.close() } catch (_: IOException) {}
        }
    }

    private fun readPsbtFromIntent(intent: Intent): ByteArray? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return try {
            contentResolver.openInputStream(uri)?.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/MainActivity.kt
git commit -m "feat: wire NFC reader mode to Activity lifecycle"
```

---

## Chunk 4: PassphraseDialog NFC UI

### Task 5: Update PassphraseDialog with NFC option and waiting screen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt:1-200`

- [ ] **Step 1: Update SigningScreen to accept NFC-related parameters**

Update the `SigningScreen` composable signature and body. Add parameters for NFC state:

In `SigningScreen.kt`, add imports at top:

```kotlin
import android.nfc.NfcAdapter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.remotesigner.nfc.NfcReadResult
import kotlinx.coroutines.delay
```

Update the `SigningScreen` composable signature (line 33-40) to add NFC parameters:

```kotlin
@Composable
fun SigningScreen(
    message: String,
    log: String,
    passphraseRequest: PassphraseRequest?,
    accountPathRequest: AccountPathRequest?,
    nfcAvailable: Boolean = false,
    nfcTagResult: NfcReadResult? = null,
    onStartNfcWaiting: () -> Unit = {},
    onStopNfcWaiting: () -> Unit = {},
    onClearNfcResult: () -> Unit = {},
    onCancel: () -> Unit,
) {
```

Update the `PassphraseDialog` call inside `SigningScreen` (line 60-63) to pass NFC params:

```kotlin
if (passphraseRequest != null) {
    PassphraseDialog(
        request = passphraseRequest,
        onDismiss = { passphraseRequest.callback.cancel() },
        nfcAvailable = nfcAvailable,
        nfcTagResult = nfcTagResult,
        onStartNfcWaiting = onStartNfcWaiting,
        onStopNfcWaiting = onStopNfcWaiting,
        onClearNfcResult = onClearNfcResult,
    )
}
```

- [ ] **Step 2: Rewrite PassphraseDialog with NFC support**

Replace the `PassphraseDialog` composable (lines 119-200) with:

```kotlin
@Composable
private fun PassphraseDialog(
    request: PassphraseRequest,
    onDismiss: () -> Unit,
    nfcAvailable: Boolean = false,
    nfcTagResult: NfcReadResult? = null,
    onStartNfcWaiting: () -> Unit = {},
    onStopNfcWaiting: () -> Unit = {},
    onClearNfcResult: () -> Unit = {},
) {
    // Dialog sub-screens
    var showTextField by remember { mutableStateOf(!request.availableOnDevice) }
    var showNfcWaiting by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var nfcErrorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // 60-second timeout for NFC waiting
    LaunchedEffect(showNfcWaiting) {
        if (showNfcWaiting) {
            delay(60_000L)
            if (showNfcWaiting) {
                onStopNfcWaiting()
                showNfcWaiting = false
                nfcErrorMessage = null
                Toast.makeText(context, "Timed out waiting for NFC tag", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // React to NFC tag read results
    LaunchedEffect(nfcTagResult) {
        when (nfcTagResult) {
            is NfcReadResult.Success -> {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onStopNfcWaiting()
                showNfcWaiting = false
                nfcErrorMessage = null
                request.callback.submitPassphrase(nfcTagResult.passphrase)
                onClearNfcResult()
            }
            is NfcReadResult.Error -> {
                nfcErrorMessage = nfcTagResult.message
                onClearNfcResult()
            }
            null -> {}
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (showNfcWaiting) onStopNfcWaiting()
            onDismiss()
        },
        title = { Text("Passphrase Required") },
        text = {
            Column {
                when {
                    showNfcWaiting -> {
                        // NFC waiting screen
                        Text(
                            "Hold NFC tag to back of phone",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (nfcErrorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                nfcErrorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    showTextField -> {
                        // Phone text entry screen (existing)
                        Text(
                            "Less secure than on-device entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrect = false,
                            ),
                            visualTransformation = if (passwordVisible)
                                VisualTransformation.None
                            else
                                PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        // Choice screen
                        Text("Choose where to enter your passphrase:")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { request.callback.submitPassphrase("") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enter on Trezor")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showTextField = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enter on phone")
                        }
                        if (nfcAvailable) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    showNfcWaiting = true
                                    nfcErrorMessage = null
                                    onStartNfcWaiting()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Read from NFC tag")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showTextField) {
                Button(onClick = {
                    request.callback.submitPassphrase(passphrase)
                }) {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            when {
                showNfcWaiting -> {
                    TextButton(onClick = {
                        onStopNfcWaiting()
                        showNfcWaiting = false
                        nfcErrorMessage = null
                    }) {
                        Text("Cancel")
                    }
                }
                showTextField && request.availableOnDevice -> {
                    TextButton(onClick = { showTextField = false; passphrase = "" }) {
                        Text("Back")
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        },
    )
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The existing callers of `SigningScreen` use default values for the new NFC parameters, so no other files break.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt
git commit -m "feat: add NFC tag option and waiting screen to PassphraseDialog"
```

---

## Chunk 5: Wire AppNavigation to pass NFC state

### Task 6: Pass NFC state from ViewModel through AppNavigation to SigningScreen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt:72-78`

- [ ] **Step 1: Collect NFC state in AppRoot and pass to SigningScreen**

In `AppNavigation.kt`, add import:

```kotlin
import android.nfc.NfcAdapter
```

After the existing `collectAsStateWithLifecycle()` calls (after line 33), add:

```kotlin
val nfcTagResult by viewModel.nfcTagResult.collectAsStateWithLifecycle()
val nfcAvailable = remember { NfcAdapter.getDefaultAdapter(context) != null }
```

Note: `nfcWaitingForTag` is NOT collected here — MainActivity observes it directly for reader mode control. Only `nfcTagResult` and `nfcAvailable` are needed by the Compose UI.

Update the `AppState.Signing` branch (lines 72-78) to pass NFC params:

```kotlin
is AppState.Signing -> SigningScreen(
    message = s.message,
    log = s.log,
    passphraseRequest = passphraseRequest,
    accountPathRequest = accountPathRequest,
    nfcAvailable = nfcAvailable,
    nfcTagResult = nfcTagResult,
    onStartNfcWaiting = { viewModel.startNfcWaiting() },
    onStopNfcWaiting = { viewModel.stopNfcWaiting() },
    onClearNfcResult = { viewModel.clearNfcResult() },
    onCancel = { viewModel.cancelSigning() },
)
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: wire NFC state from ViewModel through AppNavigation to SigningScreen"
```

---

## Chunk 6: UI Tests

### Task 7: Add Compose UI tests for NFC passphrase dialog

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Add NFC dialog UI tests**

Add the following tests to `ScreenRenderTest.kt`, after the existing `passphraseDialog_backToChoices` test:

```kotlin
@Test
fun signingScreen_passphraseDialog_showsNfcOption_whenAvailable() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            SigningScreen(
                message = "Signing...",
                log = "",
                passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                accountPathRequest = null,
                nfcAvailable = true,
                onCancel = {},
            )
        }
    }
    composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
    composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
    composeTestRule.onNodeWithText("Read from NFC tag").assertIsDisplayed()
}

@Test
fun signingScreen_passphraseDialog_hidesNfcOption_whenUnavailable() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            SigningScreen(
                message = "Signing...",
                log = "",
                passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                accountPathRequest = null,
                nfcAvailable = false,
                onCancel = {},
            )
        }
    }
    composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
    composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
    composeTestRule.onNodeWithText("Read from NFC tag").assertDoesNotExist()
}

@Test
fun signingScreen_passphraseDialog_nfcWaitingScreen() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            SigningScreen(
                message = "Signing...",
                log = "",
                passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                accountPathRequest = null,
                nfcAvailable = true,
                onCancel = {},
            )
        }
    }
    // Tap "Read from NFC tag"
    composeTestRule.onNodeWithText("Read from NFC tag").performClick()
    composeTestRule.waitForIdle()
    // NFC waiting screen should appear
    composeTestRule.onNodeWithText("Hold NFC tag to back of phone").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Cancel").assertCountEquals(2)
}

@Test
fun signingScreen_passphraseDialog_nfcCancelReturnsToChoices() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            SigningScreen(
                message = "Signing...",
                log = "",
                passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                accountPathRequest = null,
                nfcAvailable = true,
                onCancel = {},
            )
        }
    }
    // Go to NFC waiting screen
    composeTestRule.onNodeWithText("Read from NFC tag").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Hold NFC tag to back of phone").assertIsDisplayed()
    // Cancel NFC — find the Cancel in the dialog dismiss button area
    // Both SigningScreen and the dialog have "Cancel". The dialog's Cancel is a TextButton.
    // Click the first Cancel node (dialog's dismiss button)
    composeTestRule.onAllNodesWithText("Cancel")[0].performClick()
    composeTestRule.waitForIdle()
    // Should be back to choice screen
    composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
    composeTestRule.onNodeWithText("Read from NFC tag").assertIsDisplayed()
}
```

Also add a test for NFC error display. This requires passing `nfcTagResult` directly since we can't simulate a real NFC tap. Add an `import` for `NfcReadResult`:

```kotlin
import com.remotesigner.nfc.NfcReadResult
```

```kotlin
@Test
fun signingScreen_passphraseDialog_nfcErrorShown() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            SigningScreen(
                message = "Signing...",
                log = "",
                passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                accountPathRequest = null,
                nfcAvailable = true,
                nfcTagResult = NfcReadResult.Error("No text found on tag"),
                onCancel = {},
            )
        }
    }
    // Select NFC to enter waiting screen
    composeTestRule.onNodeWithText("Read from NFC tag").performClick()
    composeTestRule.waitForIdle()
    // Error should appear inline
    composeTestRule.onNodeWithText("No text found on tag").assertIsDisplayed()
    composeTestRule.onNodeWithText("Hold NFC tag to back of phone").assertIsDisplayed()
}
```

- [ ] **Step 2: Run tests on emulator**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest`
Expected: All tests PASS (existing + 4 new NFC tests).

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: add Compose UI tests for NFC passphrase dialog"
```

---

## Chunk 7: Clean up ViewModel NFC state on signing completion

### Task 8: Ensure NFC state is cleared when signing ends

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Clear NFC state in signing cleanup paths**

In `doSignWithBridge()`, in the `finally` block (around line 376), add NFC cleanup alongside existing cleanup:

```kotlin
} finally {
    _passphraseRequest.value = null
    _accountPathRequest.value = null
    _nfcWaitingForTag.value = false
    _nfcTagResult.value = null
    currentSigningCallback = null
    bridge.close()
    currentUsbBridge = null
}
```

Also in `cancelSigning()` (around line 408), add NFC cleanup:

```kotlin
fun cancelSigning() {
    currentSigningCallback?.cancel()
    currentSigningCallback = null
    _passphraseRequest.value = null
    _accountPathRequest.value = null
    _nfcWaitingForTag.value = false
    _nfcTagResult.value = null
    signingJob?.cancel()
    signingJob = null
    currentUsbBridge?.close()
    currentUsbBridge = null
    if (currentPsbtBytes != null) {
        viewModelScope.launch { parsePsbt(currentPsbtBytes!!) }
    } else {
        _state.value = AppState.Home
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "fix: clear NFC state on signing completion and cancellation"
```

---

## Final Verification

- [ ] **Full build:** `./gradlew assembleDebug`
- [ ] **JVM tests:** `./gradlew test --tests "com.remotesigner.nfc.NdefTextParserTest"`
- [ ] **Android tests:** `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest`
- [ ] **Manual test (if YubiKey/NFC tag available):** Install on device, start a signing flow, choose "Read from NFC tag", tap tag, verify passphrase is submitted.
