# Android E2E & UI Tests with Cassette Replay

**Date:** 2026-03-16
**Status:** Proposed

## Goal

Add two categories of Android instrumented tests:

1. **Chaquopy E2E tests** — Full-flow tests that exercise Kotlin → Chaquopy → Python → PlaybackBridge → Compose UI, proving the entire signing pipeline works on the emulator without Trezor hardware.
2. **UI-only tests** — Cover untested screens and dialogs (error screen, passphrase dialog, signing log/cancel) using the existing state-driven pattern.

## Context

### What exists today

- **Desktop Python E2E tests** (`tests/test_signing_e2e.py`): Replay cassettes via Python `PlaybackBridge` → `sign_psbt()`. Proves signing logic works. No Android involved.
- **Android UI smoke tests** (`app/src/androidTest/`): State-driven Compose tests for HomeScreen, TransactionReview, Signing, Result. Never call Python. Don't test error screen, passphrase dialog, or signing log.

### What's missing

- No test proves Chaquopy + Python modules + PythonBridge JSON round-trip work on Android.
- Error screen, passphrase dialog, and signing screen log/cancel are untested.

### Cassettes

Two recorded cassettes in `tests/cassettes/`:
- `single-sig-p2wpkh.json` — 236 exchanges, testnet, single-sig P2WPKH
- `multisig-testnet3.json` — testnet, multisig (produces complete transaction)

Each cassette contains `metadata.input_psbt_b64` (base64-encoded PSBT) and `exchanges` (array of `{dir: "w"|"r", data: "hex"}` 64-byte USB chunks).

## Design

### 1. `SigningBridge` interface

Extract the implicit 4-method bridge contract into a Kotlin interface:

```kotlin
// app/src/main/kotlin/com/remotesigner/usb/SigningBridge.kt
interface SigningBridge {
    fun open()
    fun close()
    fun writeChunk(data: ByteArray)
    fun readChunk(): ByteArray
}
```

`UsbBridge` implements `SigningBridge`. No behavioral change.

### 2. Widen `PythonBridge.signPsbt()` parameter type

Change `bridge: UsbBridge` to `bridge: SigningBridge`:

```kotlin
fun signPsbt(
    psbtBytes: ByteArray,
    bridge: SigningBridge,  // was: UsbBridge
    callback: SigningCallback,
    network: String = "main",
): Map<String, Any?>
```

Chaquopy proxies the object to Python regardless of Kotlin type — Python duck-types `.writeChunk()` / `.readChunk()`.

### 3. Extract `signWithBridge()` from `SignerViewModel`

Split `signWithTrezor()` into USB acquisition and signing phases. `signWithBridge()` accepts all required parameters explicitly so it does not depend on ViewModel internal state:

```kotlin
fun signWithTrezor() {
    val psbt = currentPsbtBytes ?: return
    // 1. Find Trezor device (poll if needed)
    // 2. Request USB permission
    // 3. Open UsbBridge, log device info, claim interface
    // 4. Set currentUsbBridge = bridge (for cancelSigning())
    // 5. Delegate:
    signWithBridge(bridge, psbt, currentNetwork)
}

@VisibleForTesting
internal fun signWithBridge(bridge: SigningBridge, psbtBytes: ByteArray, network: String) {
    _state.value = AppState.Signing("Signing...", log = "")
    signingJob = viewModelScope.launch {
        fun log(msg: String) { /* same as today */ }
        try {
            val signingCallback = SigningCallbackImpl(
                onStatusUpdate = { status -> viewModelScope.launch { log("Python: $status") } },
                onPassphraseRequest = { availableOnDevice ->
                    _passphraseRequest.value = PassphraseRequest(availableOnDevice, currentSigningCallback!!)
                },
                onPassphraseSubmitted = { _passphraseRequest.value = null },
            )
            currentSigningCallback = signingCallback

            val result = withContext(Dispatchers.IO) {
                pythonBridge.signPsbt(psbtBytes, bridge, signingCallback, network)
            }
            _passphraseRequest.value = null
            currentSigningCallback = null

            // Entire result-handling block moves here from signWithTrezor():
            when (result["status"]) {
                "complete" -> _state.value = AppState.Result(isComplete = true, rawHex = result["raw_tx"]?.toString())
                "partial" -> _state.value = AppState.Result(isComplete = false, updatedPsbt = result["psbt"] as? ByteArray)
                else -> _state.value = AppState.Error(result["message"]?.toString() ?: "Signing failed")
            }
        } catch (e: Exception) {
            val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
            _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
        } finally {
            _passphraseRequest.value = null
            currentSigningCallback = null
            bridge.close()  // no-op for PlaybackBridge, real close for UsbBridge
            currentUsbBridge = null  // clear so cancelSigning() doesn't double-close
        }
    }
}
```

**Key decisions:**
- `signWithBridge()` takes `psbtBytes` and `network` as parameters, making it self-contained and testable without needing to call `loadPsbt()` first.
- `signWithTrezor()` sets `currentUsbBridge = bridge` before delegating (for `cancelSigning()` to work). `signWithBridge()` clears it in `finally`.
- `signWithTrezor()` retains USB-specific logic (device discovery, permission, `dumpDeviceInfo()`, `bridge.open()`). `signWithBridge()` is the testable signing path.

**Thread safety note:** `_state` and `_passphraseRequest` are `MutableStateFlow` (thread-safe). The `onStatusUpdate` lambda is called from Python's IO thread via Chaquopy, while state reads happen on the main thread. This is safe because `StateFlow.value` is atomic.

### 4. Kotlin `PlaybackBridge` (test code)

A `SigningBridge` implementation in `androidTest/` that replays cassette exchanges with strict validation:

```kotlin
// app/src/androidTest/kotlin/com/remotesigner/PlaybackBridge.kt
class PlaybackBridge(cassetteJson: JSONObject) : SigningBridge {
    val inputPsbtB64: String  // from metadata.input_psbt_b64
    val network: String       // from metadata.network

    private data class Exchange(val dir: String, val data: String)  // hex string
    private val exchanges: List<Exchange>
    private var pos = 0

    override fun open() {}
    override fun close() {}

    override fun writeChunk(data: ByteArray) {
        check(pos < exchanges.size) { "Cassette exhausted at position $pos" }
        val expected = exchanges[pos]
        check(expected.dir == "w") { "Expected write at pos $pos, got read" }
        val actualHex = data.joinToString("") { "%02x".format(it) }
        check(actualHex == expected.data) { "Write mismatch at pos $pos" }
        pos++
    }

    override fun readChunk(): ByteArray {
        check(pos < exchanges.size) { "Cassette exhausted at position $pos" }
        val expected = exchanges[pos]
        check(expected.dir == "r") { "Expected read at pos $pos, got write" }
        pos++
        return expected.data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun assertConsumed() {
        check(pos == exchanges.size) { "Cassette not fully consumed: $pos / ${exchanges.size}" }
    }

    companion object {
        fun fromAsset(context: Context, name: String): PlaybackBridge {
            val json = context.assets.open("cassettes/$name").bufferedReader().readText()
            return PlaybackBridge(JSONObject(json))
        }
    }
}
```

### 5. Cassette assets

Copy cassette files from `tests/cassettes/` into `app/src/androidTest/assets/cassettes/`:
- `single-sig-p2wpkh.json`
- `multisig-testnet3.json`

These are static copies. When new cassettes are recorded, they must be copied manually.

### 6. Chaquopy E2E tests

```kotlin
// app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt
@LargeTest
class ChaquopyE2ETest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: SignerViewModel

    @Before
    fun setUp() {
        // Chaquopy initializes on first Python call (~5-10s on emulator).
        // Use the test app's Application context to construct the ViewModel.
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
            viewModel.state.value is AppState.Result
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

        viewModel.loadPsbt(psbtBytes)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            viewModel.state.value is AppState.TransactionReview
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()

        viewModel.signWithBridge(playbackBridge, psbtBytes, playbackBridge.network)
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            viewModel.state.value is AppState.Result
        }
        composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
        playbackBridge.assertConsumed()
    }
}
```

**Timeout handling:** Chaquopy first-import can take 5-10s on an emulator. `waitUntil` with generous timeouts (15s for parse, 30s for sign) prevents flaky failures. Tests are annotated `@LargeTest` per Android convention.

These tests exercise: Chaquopy initialization, Python module imports (`remotesigner.psbt_parser`, `remotesigner.signer`), PythonBridge JSON round-trip, PlaybackBridge proxy via Chaquopy, and Compose state machine with real data.

### 7. Extract `ErrorScreen` composable

The error UI is currently rendered inline in `AppNavigation.kt` (lines 66-88). Extract it into a standalone `ErrorScreen` composable to match the pattern of other screens and make it independently testable:

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt
@Composable
fun ErrorScreen(
    message: String,
    onHome: () -> Unit,
    onCopyError: (String) -> Unit,
)
```

`AppNavigation.kt` then delegates: `is AppState.Error -> ErrorScreen(...)`.

### 8. Fix existing test call sites

The existing `ScreenRenderTest.kt` and `NavigationTest.kt` call `SigningScreen(message = message)` with missing required parameters (`log`, `passphraseRequest`, `onCancel`). Also, `TestFixtures.reviewState` is missing the required `inputs` field.

Fix by adding default parameter values to these signatures would be one approach, but the cleaner fix is to update the test call sites to pass all required parameters. This will be done as part of the implementation.

### 9. UI-only tests

Extend existing `ScreenRenderTest.kt` with new tests using the existing pattern (`createComposeRule()` + `setContent` + fixture data):

**Error screen (using new `ErrorScreen` composable):**
- `errorScreen_displaysMessageAndButtons()` — Error message text, "Back to Home" button, "Copy Error" button
- `errorScreen_withSigningLog()` — Long error message with embedded signing log

**Passphrase dialog (via `SigningScreen`):**
- `signingScreen_passphraseDialog_onDeviceAvailable()` — Shows "Enter on Trezor" + "Enter on phone" choice buttons
- `signingScreen_passphraseDialog_phoneOnly()` — Shows text field directly when `availableOnDevice=false`
- `signingScreen_passphraseDialog_switchToTextField()` — Tap "Enter on phone" → text field appears
- `signingScreen_passphraseDialog_backToChoices()` — Tap "Back" from text field → returns to choice screen

**Signing screen extras:**
- `signingScreen_displaysLogAndCopyButton()` — Debug log text + "Copy" button
- `signingScreen_displaysCancelButton()` — Cancel button present

**New fixtures in `TestFixtures.kt`:**
```kotlin
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

val signingStateWithLog = AppState.Signing(
    message = "Confirm on your Trezor...",
    log = "Opening USB connection...\nClaiming interface...\nPython: Parsing PSBT...\n",
)

// Fix existing reviewState — add missing inputs field:
val reviewState = AppState.TransactionReview(
    inputs = listOf(
        TxInput(address = "tb1q...sender", amount = 6_236_567L),
    ),
    outputs = sampleOutputs,
    fee = 2_100L,
    totalSent = 5_000_000L,
    status = "needs_sig",
    signers = sampleSigners,
    warnings = emptyList(),
)
```

## File changes summary

**Production code (4 files modified, 2 new):**
- `app/src/main/kotlin/com/remotesigner/usb/SigningBridge.kt` — **new** interface
- `app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt` — **new** composable (extracted from AppNavigation.kt)
- `app/src/main/kotlin/com/remotesigner/usb/UsbBridge.kt` — implement `SigningBridge`
- `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt` — `bridge: UsbBridge` → `bridge: SigningBridge`
- `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` — extract `signWithBridge()`
- `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` — delegate error state to `ErrorScreen`

**Test code (5 files modified/new):**
- `app/src/androidTest/kotlin/com/remotesigner/PlaybackBridge.kt` — **new** Kotlin cassette replay
- `app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt` — **new** Chaquopy E2E tests
- `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` — add error + passphrase + log tests, fix existing call sites
- `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt` — fix `SigningScreen` call site
- `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt` — add passphrase/log fixtures, add `inputs` to `reviewState`
- `app/src/androidTest/assets/cassettes/` — **new** copied cassette JSON files

## What this does NOT test

- Real USB communication (covered by desktop `sign_cli.py` + hardware)
- Real Trezor interaction (covered by desktop E2E with hardware)
- Broadcasting (would need network mocking; out of scope)
- PSBT file picker UI (requires SAF intent mocking; out of scope)
