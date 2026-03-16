# NFC Passphrase Import — Design Spec

**Date:** 2026-03-16
**Status:** Draft

## Overview

Add a third passphrase entry method — "Read from NFC tag" — alongside the existing "Enter on Trezor" and "Enter on phone" options. The app reads an NDEF text record from any NFC tag (YubiKey static password, generic NTAG, etc.) and submits it as the Trezor passphrase.

## Motivation

- **Convenience:** Long/complex passphrases are painful to type on a phone keyboard during every signing session.
- **Security:** Avoids any residual IME leakage risk (despite existing `KeyboardType.Password` + `autoCorrect = false` mitigations).
- **Trade-off accepted:** Passphrase moves from "something you know" to "something you have." An attacker still needs Trezor + PIN + phone + NFC tag to sign — the PIN remains required on-device.

## Scope

- **Read-only.** The app reads NDEF text from NFC tags. Writing/programming tags is the user's responsibility (YubiKey Manager for YubiKeys, NFC Tools or similar for generic tags).
- **NFC is optional.** The "Read from NFC tag" option only appears if the device has NFC hardware. App installs fine on NFC-less devices.
- **No Python changes.** NFC reading happens entirely in Kotlin/Compose and feeds into the existing `submitPassphrase()` entry point.

## Compatible NFC Tags

| Tag Type | How passphrase is stored | Security |
|----------|------------------------|----------|
| YubiKey (static password slot via NDEF) | Secure element, emitted on tap | Can't be cloned; emits to any NFC reader |
| Generic NDEF tag (NTAG, Mifare, etc.) | Plaintext NDEF text record | Trivially cloneable by anyone with a phone |

The app does not distinguish between tag types — it reads whatever NDEF text payload the tag emits.

**Note:** YubiKey must be configured with NDEF output (the default for slot 1). If the YubiKey is set to raw OTP mode without NDEF, the tag will not present an NDEF message and the app will show an error.

## UX Flow

### Passphrase Dialog (Updated)

The existing two-stage passphrase dialog gains a third option:

```
┌─────────────────────────────┐
│   Enter passphrase          │
│                             │
│   ○ Enter on Trezor         │
│   ○ Enter on phone          │
│   ○ Read from NFC tag       │
│                             │
└─────────────────────────────┘
```

- "Enter on Trezor" and "Enter on phone" behave exactly as today.
- "Read from NFC tag" transitions to a waiting screen.
- If `NfcAdapter.getDefaultAdapter(context)` returns null, the NFC option is hidden.

### NFC Waiting Screen

```
┌─────────────────────────────┐
│                             │
│   Hold NFC tag to back      │
│   of phone                  │
│                             │
│   [Cancel]                  │
│                             │
└─────────────────────────────┘
```

- On successful read: brief haptic pulse (`HapticFeedbackConstants.CONFIRM`), passphrase is submitted, dialog closes, signing continues.
- On error (no NDEF, no text record, read failure): toast with error message, stay on waiting screen for retry.
- Cancel returns to the choice screen.

## Technical Design

### Android Manifest Changes

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="false" />
```

No NFC intent filters — the app should not launch when a tag is tapped outside the passphrase flow.

### NFC Reader Mode

Use `NfcAdapter.enableReaderMode()` on the Activity rather than foreground dispatch. This is cleaner because:
- No intent/activity lifecycle complexity.
- Reader mode runs a callback directly — no need to handle `onNewIntent`.
- Can be enabled/disabled precisely when the NFC waiting screen is active.

Flags: `FLAG_READER_NFC_A or FLAG_READER_NFC_B or FLAG_READER_NFC_F or FLAG_READER_NFC_V` (covers all standard NFC tag types: ISO 14443A/B, FeliCa, ISO 15693).

### NFC ↔ Compose Bridge

`enableReaderMode` is an Activity-level API. The passphrase dialog is a Composable. Bridge via ViewModel:

1. **ViewModel** exposes:
   - `nfcWaitingForTag: StateFlow<Boolean>` — true when NFC waiting screen is displayed.
   - `nfcTagResult: MutableStateFlow<NfcReadResult?>` — sealed class: `Success(passphrase: String)` or `Error(message: String)`.

2. **MainActivity** enables reader mode unconditionally in `onResume()`, disables in `onPause()`:
   - Reader mode is always active while the Activity is in the foreground. This prevents other NFC-handling apps (e.g. Wallet of Satoshi) from intercepting tags via normal NFC dispatch and stealing focus.
   - The `onTagDiscovered` callback checks `nfcWaitingForTag.value` and silently ignores tags when not waiting — the tag is consumed (no dispatch to other apps) but not acted on.

3. **Reader callback** (runs on binder thread — must not touch UI-thread-only state):
   - Opens `Ndef` tech on the tag (if `Ndef.get(tag)` returns null, tag is not NDEF-capable → post error).
   - Reads `NdefMessage`, finds first `NdefRecord` with TNF_WELL_KNOWN + RTD_TEXT.
   - Parses the RTD_TEXT payload (see NDEF Text Record Parsing below).
   - Posts result to `nfcTagResult` (StateFlow `.value` assignment is thread-safe).

4. **PassphraseDialog** observes `nfcTagResult`:
   - On `Success` → calls `callback.submitPassphrase(passphrase)`, clears state.
   - On `Error` → shows toast, stays on waiting screen.

### Data Flow

```
Reader mode is always active while Activity is resumed (prevents other apps stealing NFC)

PassphraseDialog: user selects "Read from NFC tag"
  → ViewModel sets nfcWaitingForTag = true
  → User taps NFC tag
  → Reader callback checks nfcWaitingForTag == true → processes tag
  → Reader callback reads NDEF text record
  → ViewModel sets nfcTagResult = Success(passphrase)
  → PassphraseDialog calls callback.submitPassphrase(passphrase)
  → LinkedBlockingQueue unblocks Python thread
  → trezorlib receives passphrase, signing continues
  → ViewModel sets nfcWaitingForTag = false

(Tags tapped when nfcWaitingForTag == false are silently consumed)
```

### NDEF Text Record Parsing

RTD_TEXT payloads are not raw strings. The format is:

```
payload[0]       = status byte (bit 7: 0=UTF-8, 1=UTF-16; bits 5-0: language code length)
payload[1..n]    = language code (e.g., "en") where n = langLen from status byte
payload[n+1..end] = the actual text content
```

Extract into a pure function `parseNdefTextPayload(payload: ByteArray): String?` for testability:
1. Read status byte, extract language code length.
2. Skip language code bytes.
3. Decode remaining bytes as UTF-8 (or UTF-16 if bit 7 is set).
4. Return null if payload is too short or malformed.

### Error Handling

| Scenario | Behavior |
|----------|----------|
| Tag is not NDEF-capable (`Ndef.get(tag)` returns null) | Show "Tag does not contain a passphrase. Use an NDEF-configured tag." toast, stay on waiting screen |
| Tag has no NDEF records | Show "No passphrase found on tag" toast, stay on waiting screen |
| Tag has NDEF but no text record | Show "No text found on tag" toast, stay on waiting screen |
| NFC read throws IOException | Show "Failed to read tag — try again" toast, stay on waiting screen |
| Empty string read from tag | Show "Tag contains an empty passphrase" toast, stay on waiting screen (user chose NFC explicitly — silently falling through to on-device entry would be confusing) |
| User cancels | Return to choice screen (reader mode stays active to block other apps) |
| Timeout (60 seconds) | Auto-cancel back to choice screen with "Timed out waiting for NFC tag" message |

### Files Changed

| File | Change |
|------|--------|
| `AndroidManifest.xml` | Add NFC permission and feature |
| `SignerViewModel.kt` | Add `nfcWaitingForTag`, `nfcTagResult` state flows and methods |
| `MainActivity.kt` | Enable reader mode in `onResume()`/`onPause()`, ignore tags when not waiting, post results |
| `SigningScreen.kt` | Update `PassphraseDialog` with NFC option and waiting screen |

### Files NOT Changed

| File | Reason |
|------|--------|
| `PythonBridge.kt` / `SigningCallbackImpl` | NFC feeds into existing `submitPassphrase()` — no changes needed |
| `trezor_ui.py` / `signer.py` | Python layer is unaware of how the passphrase was entered |
| `usb_transport.py` / `UsbBridge.kt` | NFC reads a passphrase, not a Trezor transport channel |

## Testing Strategy

- **Pure function test:** `parseNdefTextPayload(payload: ByteArray): String?` tested with raw byte arrays — UTF-8 text records, UTF-16 text records, empty payloads, malformed status bytes, various language code lengths. No mocking needed.
- **Compose UI test:** Render `PassphraseDialog` with NFC available vs. unavailable, verify the "Read from NFC tag" option appears/disappears. Test screen transitions: choice → NFC waiting → cancel → back to choice (similar to existing `passphraseDialog_switchToTextField` and `passphraseDialog_backToChoices` tests in `ScreenRenderTest.kt`).
- **Manual test:** Tap YubiKey with static password during signing flow on a real device. Tap a generic NDEF tag with passphrase text. Tap a blank/empty tag to verify error handling.
- **Emulator limitation:** Android emulator does not support NFC hardware. All NFC logic that touches `android.nfc.*` is untestable on emulator — hence the pure function extraction for the parsing layer.

## Dependencies

No new library dependencies. Uses Android framework NFC APIs (`android.nfc.*`) only.

## Security Considerations

- The passphrase travels over NFC in cleartext — this is inherent to NDEF and acceptable because the read range is ~4cm.
- Generic NFC tags are trivially cloneable. Users who want clone resistance should use a YubiKey.
- The app never stores or logs the passphrase read from NFC.
- An attacker who obtains the Trezor + phone + NFC tag still needs the Trezor PIN to sign.
