# Host-Side Passphrase Entry Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to enter a BIP39 passphrase on the phone during Trezor signing, as an alternative to on-device entry.

**Architecture:** When trezorlib calls `get_passphrase()`, Python blocks on a Java `LinkedBlockingQueue`, Kotlin shows a Compose dialog, and the user's response unblocks Python. The `SigningCallbackImpl` bridges both `onStatus` and `requestPassphrase` across the Chaquopy boundary.

**Tech Stack:** Kotlin/Compose, Python/trezorlib, Chaquopy, `java.util.concurrent.LinkedBlockingQueue`

**Spec:** `docs/superpowers/specs/2026-03-15-host-passphrase-entry-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/main/python/remotesigner/trezor_ui.py` | Modify | `get_passphrase()` calls callback instead of hardcoded `PASSPHRASE_ON_DEVICE` |
| `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt` | Modify | Replace `StatusCallback` with `SigningCallback` interface + `SigningCallbackImpl` with blocking queue |
| `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` | Modify | Add `passphraseRequest` StateFlow, wire `SigningCallbackImpl`, update `cancelSigning()` |
| `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt` | Modify | Add passphrase dialog composable |
| `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` | Modify | Pass `passphraseRequest` to `SigningScreen` |
| `tests/sign_cli.py` | Modify | Add `requestPassphrase` to `PrintStatusCallback` |
| `tests/test_signing_e2e.py` | Modify | Add `requestPassphrase` to mock callback |
| `tests/test_trezor_ui.py` | Create | Unit tests for `get_passphrase()` logic |

---

## Chunk 1: Python — trezor_ui.py + tests

### Task 1: Write unit tests for the new `get_passphrase()` behavior

**Files:**
- Create: `tests/test_trezor_ui.py`

- [ ] **Step 1: Write the test file**

```python
"""Unit tests for AndroidTrezorUi passphrase handling."""

import pytest
from unittest.mock import Mock
from remotesigner.trezor_ui import AndroidTrezorUi, PASSPHRASE_ON_DEVICE


class TestGetPassphrase:
    """Tests for get_passphrase() with the new callback-based flow."""

    def test_on_device_when_callback_returns_empty(self):
        """Empty string from callback means on-device entry."""
        callback = Mock()
        callback.requestPassphrase.return_value = ""
        ui = AndroidTrezorUi(callback)

        result = ui.get_passphrase(available_on_device=True)

        assert result is PASSPHRASE_ON_DEVICE
        callback.requestPassphrase.assert_called_once_with(True)

    def test_host_passphrase_returned_as_string(self):
        """Non-empty string from callback is returned as the passphrase."""
        callback = Mock()
        callback.requestPassphrase.return_value = "my secret"
        ui = AndroidTrezorUi(callback)

        result = ui.get_passphrase(available_on_device=True)

        assert result == "my secret"

    def test_host_passphrase_when_not_available_on_device(self):
        """When on-device is not available, callback must provide a passphrase."""
        callback = Mock()
        callback.requestPassphrase.return_value = "typed on phone"
        ui = AndroidTrezorUi(callback)

        result = ui.get_passphrase(available_on_device=False)

        assert result == "typed on phone"
        callback.requestPassphrase.assert_called_once_with(False)

    def test_empty_string_raises_when_not_available_on_device(self):
        """Empty string + no on-device = error (can't use on-device sentinel)."""
        callback = Mock()
        callback.requestPassphrase.return_value = ""
        ui = AndroidTrezorUi(callback)

        with pytest.raises(RuntimeError, match="not available"):
            ui.get_passphrase(available_on_device=False)

    def test_callback_exception_falls_back_to_on_device(self):
        """If callback raises, fall back to on-device when available."""
        callback = Mock()
        callback.requestPassphrase.side_effect = Exception("bridge error")
        ui = AndroidTrezorUi(callback)

        result = ui.get_passphrase(available_on_device=True)

        assert result is PASSPHRASE_ON_DEVICE

    def test_callback_exception_raises_when_no_on_device(self):
        """If callback raises and on-device not available, propagate error."""
        callback = Mock()
        callback.requestPassphrase.side_effect = Exception("bridge error")
        ui = AndroidTrezorUi(callback)

        with pytest.raises(RuntimeError, match="failed"):
            ui.get_passphrase(available_on_device=False)

    def test_no_callback_falls_back_to_on_device(self):
        """When no callback is provided, fall back to on-device."""
        ui = AndroidTrezorUi(callback=None)

        result = ui.get_passphrase(available_on_device=True)

        assert result is PASSPHRASE_ON_DEVICE

    def test_no_callback_raises_when_no_on_device(self):
        """When no callback and no on-device, raise error."""
        ui = AndroidTrezorUi(callback=None)

        with pytest.raises(RuntimeError):
            ui.get_passphrase(available_on_device=False)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_trezor_ui.py -v`
Expected: FAIL — current `get_passphrase()` doesn't call `requestPassphrase`

- [ ] **Step 3: Commit test file**

```bash
git add tests/test_trezor_ui.py
git commit -m "test: add unit tests for passphrase callback in trezor_ui"
```

### Task 2: Implement the new `get_passphrase()` in trezor_ui.py

**Files:**
- Modify: `app/src/main/python/remotesigner/trezor_ui.py` (the `get_passphrase` method + module docstring)

- [ ] **Step 1: Update the module docstring**

Change the module docstring at the top of the file to reflect that passphrase can now be entered on the host:

```python
"""
Trezor UI callbacks for Trezor Safe 3.

Implements the TrezorClientUI protocol (trezorlib.ui.TrezorClientUI) for
Android use.  PIN is always entered on the device.  Passphrase entry is
delegated to a Java callback object that can show a UI dialog — the user
chooses between on-device and host-side entry at runtime.

The callback object (bridged via Chaquopy) must implement:
  - onStatus(str) — for status messages
  - requestPassphrase(bool) -> str — for passphrase entry
    Returns "" for on-device, non-empty string for host-side entry.
"""
```

- [ ] **Step 2: Replace `get_passphrase()` method**

Replace the `get_passphrase` method with:

```python
    def get_passphrase(self, available_on_device: bool) -> Union[str, object]:
        """
        Called by trezorlib when a passphrase is required.

        Delegates to the callback's ``requestPassphrase(availableOnDevice)``
        method.  The callback returns:
        - ``""`` (empty string) → on-device entry (returns PASSPHRASE_ON_DEVICE)
        - non-empty string → host-side entry (returns the string)

        Falls back to on-device entry if the callback is missing or fails.
        """
        if self._callback is not None:
            try:
                response = str(self._callback.requestPassphrase(available_on_device))
            except Exception:
                if available_on_device:
                    self._send_status("Please enter passphrase on your Trezor device.")
                    return PASSPHRASE_ON_DEVICE
                raise RuntimeError(
                    "Passphrase entry failed and on-device entry is not available."
                )

            if response == "":
                if available_on_device:
                    self._send_status("Please enter passphrase on your Trezor device.")
                    return PASSPHRASE_ON_DEVICE
                raise RuntimeError(
                    "On-device passphrase requested but not available on this device."
                )
            return response

        # No callback — fall back to on-device if possible
        if available_on_device:
            self._send_status("Please enter passphrase on your Trezor device.")
            return PASSPHRASE_ON_DEVICE

        raise RuntimeError(
            "On-device passphrase entry is not available and no callback provided."
        )
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `python -m pytest tests/test_trezor_ui.py -v`
Expected: All 8 tests PASS

- [ ] **Step 4: Run all Python tests to check for regressions**

Run: `python -m pytest tests/ -v`
Expected: All existing tests still pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/python/remotesigner/trezor_ui.py
git commit -m "feat: add host-side passphrase entry support to trezor_ui"
```

---

## Chunk 2: Kotlin — PythonBridge callback refactor

### Task 3: Refactor PythonBridge.kt — replace StatusCallback with SigningCallback

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`

- [ ] **Step 1: Replace the entire callback interface and signPsbt method**

Replace the `StatusCallback` interface and `signPsbt` method with:

```kotlin
    /**
     * Callback interface for signing operations.
     * Methods are called from Python's thread via Chaquopy.
     */
    interface SigningCallback {
        fun onStatus(status: String)
        /** Blocks the calling (Python) thread until the user responds. */
        fun requestPassphrase(availableOnDevice: Boolean): String
    }

    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: UsbBridge,
        callback: SigningCallback,
        network: String = "main",
    ): Map<String, Any?> {
        val result = signerModule.callAttr(
            "sign_psbt", psbtBytes, bridge, callback, network
        )
        return pyDictToMap(result)
    }
```

Remove the old `signPsbt` overload and `StatusCallback` interface entirely.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: Compilation errors in `SignerViewModel.kt` (expected — we'll fix in next task)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt
git commit -m "refactor: replace StatusCallback with SigningCallback in PythonBridge"
```

### Task 4: Add SigningCallbackImpl with blocking queue

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`

- [ ] **Step 1: Add SigningCallbackImpl class after the interface**

Add this class inside `PythonBridge.kt` (as a top-level class, outside `PythonBridge`), after the import block:

```kotlin
import java.util.concurrent.LinkedBlockingQueue
```

Then after the `PythonBridge` class closing brace, add:

```kotlin
/**
 * Bridges Python signing callbacks to Kotlin UI.
 *
 * [requestPassphrase] blocks the Python thread on a [LinkedBlockingQueue]
 * until the UI calls [submitPassphrase] or [cancel].
 */
class SigningCallbackImpl(
    private val onStatusUpdate: (String) -> Unit,
    private val onPassphraseRequest: (availableOnDevice: Boolean) -> Unit,
) : PythonBridge.SigningCallback {

    private val passphraseQueue = LinkedBlockingQueue<String?>(1)

    override fun onStatus(status: String) = onStatusUpdate(status)

    /**
     * Called from Python's thread. Triggers the UI prompt, then blocks
     * until [submitPassphrase] or [cancel] is called.
     */
    override fun requestPassphrase(availableOnDevice: Boolean): String {
        onPassphraseRequest(availableOnDevice)
        val response = passphraseQueue.take()  // blocks Python thread
        if (response == null) {
            throw RuntimeException("Passphrase entry cancelled")
        }
        return response
    }

    /** Called by the UI when the user submits a passphrase or chooses on-device. */
    fun submitPassphrase(passphrase: String) {
        passphraseQueue.clear()  // prevent double-submission
        passphraseQueue.put(passphrase)
    }

    /** Called by cancelSigning() to unblock the Python thread. */
    fun cancel() {
        passphraseQueue.clear()
        passphraseQueue.put(null)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: Still errors in `SignerViewModel.kt` (expected — fixed next)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt
git commit -m "feat: add SigningCallbackImpl with blocking queue for passphrase"
```

---

## Chunk 3: Kotlin — ViewModel wiring

### Task 5: Wire SigningCallbackImpl into SignerViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add imports and PassphraseRequest data class**

Add import at the top:

```kotlin
import com.remotesigner.bridge.SigningCallbackImpl
```

Add `PassphraseRequest` data class after the `AppState` sealed class:

```kotlin
data class PassphraseRequest(
    val availableOnDevice: Boolean,
    val callback: SigningCallbackImpl,
)
```

- [ ] **Step 2: Add passphraseRequest StateFlow**

Add these fields inside `SignerViewModel`, after `private var signingJob: Job? = null`:

```kotlin
    private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
    val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest.asStateFlow()
    private var currentSigningCallback: SigningCallbackImpl? = null
```

- [ ] **Step 3: Rewrite the signing section in signWithTrezor()**

Replace the signing section — from `log("Starting Python signing...")` through the `val result = withContext(...)` block — with:

```kotlin
                log("Starting Python signing (network=$currentNetwork)...")
                val signingCallback = SigningCallbackImpl(
                    onStatusUpdate = { status ->
                        viewModelScope.launch { log("Python: $status") }
                    },
                    onPassphraseRequest = { availableOnDevice ->
                        _passphraseRequest.value = PassphraseRequest(
                            availableOnDevice, currentSigningCallback!!
                        )
                    },
                )
                currentSigningCallback = signingCallback

                val result = withContext(Dispatchers.IO) {
                    pythonBridge.signPsbt(
                        psbtBytes = psbt,
                        bridge = bridge,
                        callback = signingCallback,
                        network = currentNetwork,
                    )
                }
                _passphraseRequest.value = null
                currentSigningCallback = null
```

Note: The lambda uses `currentSigningCallback!!` (class property) instead of the local `signingCallback` to avoid a self-reference in the initializer. This is safe because `currentSigningCallback` is assigned on the very next line, before `onPassphraseRequest` can be called.

- [ ] **Step 4: Update cancelSigning() to unblock the passphrase queue**

Replace `fun cancelSigning()` with:

```kotlin
    fun cancelSigning() {
        currentSigningCallback?.cancel()
        currentSigningCallback = null
        _passphraseRequest.value = null
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

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: PASS (all Kotlin compilation errors resolved)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: wire SigningCallbackImpl into SignerViewModel with passphrase state"
```

---

## Chunk 4: Kotlin — Compose UI dialog

### Task 6: Add passphrase dialog to SigningScreen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt`

- [ ] **Step 1: Add imports**

Add these imports to the top of `SigningScreen.kt`:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.viewmodel.PassphraseRequest
```

- [ ] **Step 2: Add PassphraseDialog composable**

Add this composable function after the `SigningScreen` function:

```kotlin
@Composable
private fun PassphraseDialog(
    request: PassphraseRequest,
    onDismiss: () -> Unit,
) {
    var showTextField by remember { mutableStateOf(!request.availableOnDevice) }
    var passphrase by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passphrase Required") },
        text = {
            Column {
                if (showTextField) {
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
                } else {
                    Text("Choose where to enter your passphrase:")
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
            } else {
                Column {
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
                }
            }
        },
        dismissButton = {
            if (showTextField && request.availableOnDevice) {
                TextButton(onClick = { showTextField = false; passphrase = "" }) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
```

- [ ] **Step 3: Update SigningScreen signature to accept passphraseRequest**

Change the `SigningScreen` function signature from:

```kotlin
fun SigningScreen(message: String, log: String, onCancel: () -> Unit) {
```

to:

```kotlin
fun SigningScreen(
    message: String,
    log: String,
    passphraseRequest: PassphraseRequest?,
    onCancel: () -> Unit,
) {
```

- [ ] **Step 4: Add dialog display inside SigningScreen**

Add this right after the opening of the `Surface(modifier = Modifier.fillMaxSize())` block, before the `Column`:

```kotlin
        // Show passphrase dialog when Trezor requests it
        if (passphraseRequest != null) {
            PassphraseDialog(
                request = passphraseRequest,
                onDismiss = { passphraseRequest.callback.cancel() },
            )
        }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: Compilation error in `AppNavigation.kt` (missing parameter — fixed next)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt
git commit -m "feat: add PassphraseDialog composable to SigningScreen"
```

### Task 7: Wire passphraseRequest through AppNavigation

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`

- [ ] **Step 1: Collect passphraseRequest in AppRoot**

Add after `val state by viewModel.state.collectAsStateWithLifecycle()`:

```kotlin
    val passphraseRequest by viewModel.passphraseRequest.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Pass passphraseRequest to SigningScreen**

Change the `AppState.Signing` branch from:

```kotlin
        is AppState.Signing -> SigningScreen(
            message = s.message,
            log = s.log,
            onCancel = { viewModel.cancelSigning() },
        )
```

to:

```kotlin
        is AppState.Signing -> SigningScreen(
            message = s.message,
            log = s.log,
            passphraseRequest = passphraseRequest,
            onCancel = { viewModel.cancelSigning() },
        )
```

- [ ] **Step 3: Verify full project compiles**

Run: `./gradlew compileDebugKotlin`
Expected: PASS — no compilation errors

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: wire passphraseRequest from ViewModel to SigningScreen"
```

---

## Chunk 5: Desktop testing updates

### Task 8: Update sign_cli.py with requestPassphrase

**Files:**
- Modify: `tests/sign_cli.py`

- [ ] **Step 1: Add import and update PrintStatusCallback**

Add `import getpass` to the imports (after `import sys`).

Replace the `PrintStatusCallback` class with:

```python
class PrintStatusCallback:
    """Status callback that prints to stdout and handles passphrase prompts."""

    def onStatus(self, message):
        print(f"  [{message}]")

    def requestPassphrase(self, available_on_device):
        if available_on_device:
            choice = input("  Enter passphrase on (d)evice or (p)hone? [d]: ").strip().lower()
            if choice != "p":
                return ""  # empty string = on-device
        passphrase = getpass.getpass("  Passphrase: ")
        return passphrase
```

- [ ] **Step 2: Run to verify it doesn't crash on import**

Run: `python -c "import sys; sys.path.insert(0, 'tests'); from sign_cli import PrintStatusCallback; print('OK')"`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add tests/sign_cli.py
git commit -m "feat: add requestPassphrase to desktop CLI callback"
```

### Task 9: Update test_signing_e2e.py mock callback

**Files:**
- Modify: `tests/test_signing_e2e.py`

- [ ] **Step 1: Check how sign_psbt is called in the E2E test**

In `test_signing_e2e.py` line 58, `sign_psbt` is called without a `status_callback` argument. The callback is `None`, so `AndroidTrezorUi` gets `callback=None`. The new `get_passphrase()` code handles `None` callback by falling back to on-device — but the cassette was recorded with on-device entry, so this works.

However, if future cassettes involve passphrase flows, a mock callback will be needed. For now, the E2E tests need no changes because `sign_psbt` is called with `status_callback=None` (which still works via the no-callback fallback path).

- [ ] **Step 2: Verify E2E tests still pass**

Run: `python -m pytest tests/test_signing_e2e.py -v`
Expected: All tests PASS (or skip if no cassettes recorded)

- [ ] **Step 3: Run full test suite**

Run: `python -m pytest tests/ -v`
Expected: All tests PASS including new `test_trezor_ui.py`

- [ ] **Step 4: Commit (if any changes were needed)**

No changes expected to `test_signing_e2e.py`. Skip this commit if no changes.

---

## Chunk 6: Android smoke test + final build

### Task 10: Verify Android build and smoke tests

**Files:** None (verification only)

- [ ] **Step 1: Build debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run Android smoke tests (if emulator available)**

Run: `./gradlew connectedDebugAndroidTest`
Expected: All existing smoke tests pass. The passphrase dialog doesn't affect existing tests because it only appears when Trezor requests a passphrase during signing.

- [ ] **Step 3: Update CLAUDE.md**

Update the design decision about passphrase in `CLAUDE.md`. Change the line:

```
- **Safe 3 PIN/passphrase is on-device only** — No host-side PIN matrix. `get_pin()` raises; `get_passphrase()` returns `PASSPHRASE_ON_DEVICE`.
```

to:

```
- **Safe 3 PIN is on-device only** — No host-side PIN matrix. `get_pin()` raises. Passphrase entry is user's choice: on-device (default) or on-phone. When trezorlib calls `get_passphrase()`, a dialog lets the user choose. `SigningCallbackImpl` bridges the UI via a `LinkedBlockingQueue`.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md to reflect host-side passphrase entry"
```
