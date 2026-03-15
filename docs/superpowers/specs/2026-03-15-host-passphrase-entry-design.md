# Host-Side Passphrase Entry

## Summary

Add the ability to enter a BIP39 passphrase on the phone during Trezor signing, as an alternative to on-device entry. When the Trezor requests a passphrase mid-signing, a dialog appears on the Signing screen giving the user the choice: enter on-device (current behavior) or type it on the phone. This mirrors how Electrum handles passphrase entry.

## Motivation

On-device passphrase entry on Trezor Safe 3 requires tapping through a small touchscreen keyboard, which is slow and error-prone for long passphrases. Allowing phone-side entry provides a faster (but less secure) alternative. The user chooses per-transaction — no global setting.

## Design

### Trigger

The passphrase dialog only appears when trezorlib calls `get_passphrase()` during the signing protocol. If the Trezor doesn't have passphrase enabled, no dialog is shown and the signing flow is unchanged.

### Data Flow

```
Python get_passphrase(available_on_device)
  → calls Java callback.requestPassphrase(availableOnDevice)
  → blocks on responseQueue.take()

Kotlin receives requestPassphrase() on Python's thread
  → posts PassphraseRequest to ViewModel StateFlow (main thread)
  → Signing screen shows passphrase dialog

User responds:
  → "Enter on device": submitPassphrase("")
  → "Enter on phone": submitPassphrase(typed_passphrase)
  → Cancel/dismiss: submitPassphrase(null) → raises exception in Python

Python get_passphrase() unblocks
  → "" returns PASSPHRASE_ON_DEVICE
  → non-empty string returns the passphrase
  → null raises RuntimeError, aborts signing
```

### Python Changes

**`trezor_ui.py`**

The callback interface gains a new method: `requestPassphrase(availableOnDevice: bool) -> str`.

`get_passphrase()` changes from returning `PASSPHRASE_ON_DEVICE` unconditionally to calling the callback and interpreting the response:

- Empty string (`""`) → return `PASSPHRASE_ON_DEVICE` (on-device entry)
- Non-empty string → return it as the passphrase (host-side entry)
- Callback exception → fall back to on-device if available, otherwise raise

The empty-string sentinel is safe because entering an empty passphrase on-phone has no practical use case (on-device empty passphrase produces the same wallet).

**`signer.py`**

No structural changes. `signer.py` passes the callback object to `AndroidTrezorUi(status_callback)`. Since Python uses duck typing, the callback just needs to have the `requestPassphrase` method — `AndroidTrezorUi` will call it via `self._callback.requestPassphrase(...)`. The callback object passed from Kotlin must be the new `SigningCallbackImpl` (which has both `onStatus` and `requestPassphrase`).

### Kotlin Changes

**`PythonBridge.kt`**

The callback interface expands from `StatusCallback` to `SigningCallback`:

```kotlin
interface SigningCallback {
    fun onStatus(status: String)
    fun requestPassphrase(availableOnDevice: Boolean): String
}
```

A `SigningCallbackImpl` class wraps the interface with a `LinkedBlockingQueue<String?>` for the passphrase exchange:

- `requestPassphrase()` — sets `_passphraseRequest` StateFlow (thread-safe, Compose recomposes on main thread), then blocks on `queue.take()`
- `submitPassphrase(value)` — called by the UI, puts the value on the queue to unblock Python. One-shot: clears the queue before putting to prevent double-submission from rapid taps.
- `null` submission signals cancellation
- `cancel()` — called by `cancelSigning()` to unblock the queue if the user cancels signing while the passphrase dialog is showing. Puts `null` on the queue.

**`SignerViewModel.kt`**

New observable state:

```kotlin
data class PassphraseRequest(
    val availableOnDevice: Boolean,
    val callback: SigningCallbackImpl
)

private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest
```

The ViewModel constructs `SigningCallbackImpl` inside `signWithTrezor()`, before the `withContext(Dispatchers.IO)` block. The `onPassphraseRequest` lambda sets `_passphraseRequest`. After submission, it resets to `null`. The `SigningCallbackImpl` does not outlive the signing coroutine.

`cancelSigning()` must call `callback.cancel()` to unblock the passphrase queue if the user cancels while the dialog is showing — otherwise the Python thread hangs on `queue.take()` and coroutine cancellation cannot interrupt it.

The `PythonBridge.signPsbt()` parameter changes from `statusCallback: (String) -> Unit` to accepting a `SigningCallbackImpl` object that implements both `onStatus` and `requestPassphrase`.

**`SigningScreen.kt`**

Observes `passphraseRequest`. When non-null, shows a modal dialog:

- If `availableOnDevice == true`: two options — "Enter on device" button and "Enter on phone" button
  - "Enter on device" → `callback.submitPassphrase("")`
  - "Enter on phone" → reveals a password text field with show/hide toggle + Submit button
- If `availableOnDevice == false`: only the text field (no device option)
- Dismiss/back → `callback.submitPassphrase(null)` (cancels signing)
- Password field uses `PasswordVisualTransformation` with eye toggle

### Desktop Testing Changes

**`tests/sign_cli.py`**

`PrintStatusCallback` gains `requestPassphrase(available_on_device)`:
- If on-device available: prompts user to choose device or phone
- Phone choice: uses `getpass.getpass()` for input
- Device choice: returns empty string

**`tests/test_signing_e2e.py`**

Mock callback gains `requestPassphrase` returning `""` (on-device), matching recorded cassettes. Note: cassettes are tied to the passphrase entry method used during recording. A cassette recorded with on-device entry cannot be replayed with host-side entry (the USB exchanges differ). The mock's return value must match how the cassette was recorded.

### Files NOT Changed

- `psbt_parser.py` — passphrase is orthogonal to PSBT parsing
- `usb_transport.py` — passphrase is in the UI layer, not the transport layer
- `broadcaster.py` — post-signing, unrelated

## Edge Cases

**Cancellation:** Dismissing the dialog submits `null`, which causes `get_passphrase()` to raise `RuntimeError`. The ViewModel catches this and transitions to the Error state. User can retry.

**Timeout:** No explicit timeout on the blocking queue. The user controls the pace. If the app is killed, Python thread dies — no dangling state (app is stateless).

**Empty passphrase as sentinel:** Empty string means "use on-device." An empty BIP39 passphrase is valid but entering it on-phone has no practical benefit — on-device empty passphrase produces the same wallet.

**Screen stays on:** `FLAG_KEEP_SCREEN_ON` is already set on the Signing screen. The passphrase dialog appears on top, so this protection covers it.

**`available_on_device == false`:** Older Trezor models may not support on-device passphrase. The dialog shows only the text field — no "Enter on device" option. This is forward-compatible.

**`button_request(PassphraseEntry)` status message:** trezorlib sends a `ButtonRequestType.PassphraseEntry` button request before calling `get_passphrase()`. The current code displays "Please enter passphrase on your Trezor device." in the log. When the user chooses host-side entry, this message is misleading but harmless — it appears briefly in the debug log before the passphrase dialog opens. No change needed; the dialog itself makes the interaction clear.

**Trezor disconnection mid-dialog:** If the Trezor is unplugged while the passphrase dialog is showing, the next USB I/O after passphrase submission will fail. The ViewModel catches this and transitions to Error state. No special handling needed.

## Security Considerations

Host-side passphrase entry is less secure than on-device: the passphrase transits through the phone's memory and could be exposed by malware or screen recording. The dialog should note this trade-off (e.g., small text: "Less secure than on-device entry"). The choice is the user's, per-transaction.

## Future Extensions

- **NFC tag passphrase entry:** The same `submitPassphrase(string)` interface could accept input from an NFC tag read instead of a text field. No architectural changes needed.
- **`FLAG_SECURE`:** Could add `FLAG_SECURE` to the Activity window during passphrase entry to block screenshots and screen recording. Trade-off: blocks screenshots of the entire signing flow.
