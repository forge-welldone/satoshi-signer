# Encrypted NFC Passphrase

## Problem

The passphrase stored on NFC tags (NDEF RTD_TEXT) is plaintext. Anyone who reads the tag gets the passphrase. The security model should require possession of both the phone and the NFC tag.

## Solution

Encrypt the passphrase using NIP-04 (AES-256-CBC + secp256k1 ECDH) with the app's existing Nostr keypair, encrypting to its own pubkey. The encrypted ciphertext is displayed for the user to copy and write to their NFC tag independently. On read, the app decrypts the tag content before submitting the passphrase.

## Design Decisions

- **Reuse Nostr keypair** — no new key material. The encrypted tag is phone-specific: only the phone that encrypted it can decrypt it. Losing the phone or regenerating the keypair invalidates old tags (re-encrypt on the new phone).
- **NIP-04 format** — `base64(ciphertext)?iv=base64(iv)`. Reuses existing `Nip04` crypto code (shared secret derivation via `pubKeyTweakMul`, AES-256-CBC). Only the encrypt direction needs adding.
- **No backward compatibility** — all NFC tags are assumed encrypted. Plaintext tags are not supported going forward. Users with existing plaintext tags must re-encrypt them using the new Encrypt screen.
- **No NFC write from app** — the app displays the encrypted string; the user writes it to their tag/YubiKey using external tools.

## Size Budget

| Passphrase length | AES-CBC ciphertext | NIP-04 encoded | NDEF overhead | Total |
|---|---|---|---|---|
| 20 chars | 32 bytes | ~72 chars | ~15 bytes | ~87 bytes |
| 50 chars | 64 bytes | ~116 chars | ~15 bytes | ~131 bytes |

NDEF overhead includes TLV header, record header, type, status byte, language code, capability container, and terminator (~14-16 bytes total). Fits on NTAG213 (~137 bytes usable after formatting), with plenty of room on NTAG215 (504 bytes).

## Components

### 1. Nip04.encrypt()

Add `encrypt(privkey: ByteArray, pubkey: ByteArray, plaintext: String): String` to `Nip04.kt`:

- Derive shared secret via existing `computeSharedSecret(privkey, pubkey)`
- Generate 16-byte random IV via `SecureRandom`
- AES-256-CBC encrypt with PKCS5 padding
- Return `base64(ciphertext)?iv=base64(iv)` (use `Base64.NO_WRAP` to avoid line breaks — important for NFC size and user copy-paste)

This mirrors the existing `decrypt()` method.

### 2. EncryptPassphrase screen

New state `data object AppState.EncryptPassphrase` in the state machine (no fields — passphrase and result are local composable state). New composable `EncryptPassphraseScreen`.

The screen calls a ViewModel method `encryptForNfc(plaintext: String): String` that internally accesses `NostrKeyManager.getOrCreateKeyPair()` and calls `Nip04.encrypt()`. This keeps key material out of composable functions.

**UI flow:**
1. Password text field (`KeyboardType.Password`, `autoCorrect = false`) — validates non-empty before enabling encrypt
2. "Encrypt" button
3. Result: NIP-04 ciphertext string with copy-to-clipboard button
4. Display ciphertext length (so user can check tag capacity)
5. Back button returns to Home

**Access:** Button on the Home screen (standalone, not tied to npub display).

### 3. NFC read-side decryption

The ViewModel's `onNfcTagResult()` method (currently a one-line setter) is modified to intercept and decrypt. When it receives an `NfcReadResult.Success`:

1. `NdefTextParser.parseNdefTextPayload()` returns raw string (unchanged, in `MainActivity.onTagDiscovered`)
2. `onNfcTagResult()` intercepts the `Success`, decrypts via `Nip04.decrypt(ownPrivkey, ownPubkey, rawText)`
3. On success: re-emits `NfcReadResult.Success` with the decrypted passphrase
4. On failure: emits `NfcReadResult.Error("Could not decrypt NFC tag — was it encrypted with this phone's key?")`

The `PassphraseDialog` composable consumes the result as before (no changes). `NdefTextParser` stays a pure NDEF format concern. `MainActivity.onTagDiscovered` is NOT modified — decryption is the ViewModel's responsibility.

## Testing

### Android instrumented tests (Nip04)

Note: `Nip04Test.kt` is an Android instrumented test (not JVM) because `Nip04.kt` uses `android.util.Base64`.

- `Nip04.encrypt()` produces valid NIP-04 format (`base64?iv=base64`)
- `encrypt()` → `decrypt()` round-trip with known keypair returns original
- **Self-encryption round-trip** — encrypt with `(ownPrivkey, ownPubkey)` and decrypt with same pair, verifying `computeSharedSecret` is deterministic for self-encryption
- Decrypt with wrong keypair fails gracefully (returns null or throws)
- Various passphrase lengths (short, long, special characters — not empty, since encrypt screen validates non-empty)

### Android instrumented tests (UI)

- `EncryptPassphraseScreen` renders and shows result after encryption
- Copy-to-clipboard works
- NFC read flow: mock encrypted tag content → passphrase submitted correctly
- NFC read with garbage ciphertext → error displayed

### Not affected

Python tests — decryption happens in Kotlin before the passphrase reaches Python. No changes to Python modules.

## State Machine Change

```
Home → EncryptPassphrase (new)
     → TransactionReview → Signing → Result (unchanged)
```

## Files Modified

- `Nip04.kt` — add `encrypt()` method
- `SignerViewModel.kt` — add `EncryptPassphrase` state, NFC decrypt logic
- `AppNavigation.kt` — route `EncryptPassphrase` state to new screen
- `HomeScreen.kt` — add "Encrypt passphrase for NFC" button
- New: `EncryptPassphraseScreen.kt` — the encrypt UI
- `Nip04Test.kt` — encrypt/decrypt round-trip tests
- `NdefTextParserTest.kt` or new test file — encrypted NFC read tests
