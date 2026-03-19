# Encrypted NFC Passphrase

## Problem

The passphrase stored on NFC tags (NDEF RTD_TEXT) is plaintext. Anyone who reads the tag gets the passphrase. The security model should require possession of both the phone and the NFC tag.

## Solution

Encrypt the passphrase using NIP-04 (AES-256-CBC + secp256k1 ECDH) with the app's existing Nostr keypair, encrypting to its own pubkey. The encrypted ciphertext is displayed for the user to copy and write to their NFC tag independently. On read, the app decrypts the tag content before submitting the passphrase.

## Design Decisions

- **Reuse Nostr keypair** — no new key material. The encrypted tag is phone-specific: only the phone that encrypted it can decrypt it. Losing the phone or regenerating the keypair invalidates old tags (re-encrypt on the new phone).
- **NIP-04 format** — `base64(ciphertext)?iv=base64(iv)`. Reuses existing `Nip04` crypto code (shared secret derivation via `pubKeyTweakMul`, AES-256-CBC). Only the encrypt direction needs adding.
- **No backward compatibility** — all NFC tags are assumed encrypted. Plaintext tags are not supported going forward.
- **No NFC write from app** — the app displays the encrypted string; the user writes it to their tag/YubiKey using external tools.

## Size Budget

| Passphrase length | AES-CBC ciphertext | NIP-04 encoded | NDEF overhead | Total |
|---|---|---|---|---|
| 20 chars | 32 bytes | ~72 chars | ~10 bytes | ~82 bytes |
| 50 chars | 64 bytes | ~116 chars | ~10 bytes | ~126 bytes |

Fits on NTAG213 (144 bytes usable), with plenty of room on NTAG215 (504 bytes).

## Components

### 1. Nip04.encrypt()

Add `encrypt(privkey: ByteArray, pubkey: ByteArray, plaintext: String): String` to `Nip04.kt`:

- Derive shared secret via existing `computeSharedSecret(privkey, pubkey)`
- Generate 16-byte random IV via `SecureRandom`
- AES-256-CBC encrypt with PKCS5 padding
- Return `base64(ciphertext)?iv=base64(iv)`

This mirrors the existing `decrypt()` method.

### 2. EncryptPassphrase screen

New state `AppState.EncryptPassphrase` in the state machine. New composable `EncryptPassphraseScreen`.

**UI flow:**
1. Password text field (`KeyboardType.Password`, `autoCorrect = false`)
2. "Encrypt" button
3. Result: NIP-04 ciphertext string with copy-to-clipboard button
4. Display ciphertext length (so user can check tag capacity)
5. Back button returns to Home

**Access:** Button on the Home screen (standalone, not tied to npub display).

### 3. NFC read-side decryption

In the ViewModel's NFC result handling (where `onNfcTagResult` processes `NfcReadResult.Success`):

1. `NdefTextParser.parseNdefTextPayload()` returns raw string (unchanged)
2. Decrypt via `Nip04.decrypt(ownPrivkey, ownPubkey, rawText)`
3. On success: submit decrypted passphrase to `LinkedBlockingQueue`
4. On failure: surface `NfcReadResult.Error("Could not decrypt NFC tag — was it encrypted with this phone's key?")`

`NdefTextParser` stays a pure NDEF format concern — decryption is the ViewModel's responsibility.

## Testing

### JVM unit tests

- `Nip04.encrypt()` produces valid NIP-04 format (`base64?iv=base64`)
- `encrypt()` → `decrypt()` round-trip with known keypair returns original
- Decrypt with wrong keypair fails gracefully (returns null or throws)
- Various passphrase lengths (empty, short, long, special characters)

### Android instrumented tests

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
