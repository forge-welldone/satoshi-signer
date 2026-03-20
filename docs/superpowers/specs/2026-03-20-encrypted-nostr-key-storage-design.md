# Encrypted Nostr Key Storage — Design Spec

> Date: 2026-03-20
> Refactoring TODO: #22

## Problem

`NostrKeyManager` stores the Nostr secret key (used for NIP-04 PSBT decryption and NFC passphrase encryption) as plaintext hex in `SharedPreferences("nostr_keys")`. On rooted devices, another app can read this file and decrypt all incoming PSBTs.

## Decision

Use `EncryptedSharedPreferences` from Jetpack Security (`androidx.security:security-crypto:1.1.0-alpha06`) as a drop-in replacement. No migration — old plaintext keys are deleted, forcing regeneration.

## Design

### NostrKeyManager changes

1. Replace `context.getSharedPreferences("nostr_keys", MODE_PRIVATE)` with:
   ```kotlin
   val masterKey = MasterKey.Builder(context)
       .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
       .build()
   EncryptedSharedPreferences.create(
       context,
       "nostr_keys_encrypted",
       masterKey,
       EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
       EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
   )
   ```
2. **After** encrypted prefs are successfully created, delete old plaintext prefs file:
   ```kotlin
   context.deleteSharedPreferences("nostr_keys")
   ```
   Ordering matters: if `EncryptedSharedPreferences.create()` throws, the old key is not lost.
3. No API changes — `getOrCreateKeyPair()`, `getNpub()`, `getHexPubkey()`, `regenerateKeyPair()` unchanged.

### Error handling

`EncryptedSharedPreferences.create()` can throw `GeneralSecurityException` or `IOException` if the Android Keystore is locked or corrupted. This is accepted risk — the constructor will propagate the exception. Rationale: this is a personal phone used for Bitcoin signing; if the Keystore is broken, the user has bigger problems. No fallback to plaintext storage (that would silently defeat the purpose).

### Dependency

- Add `security-crypto = "1.1.0-alpha06"` to `libs.versions.toml`
- Add library entry and `implementation` in `build.gradle.kts`

### Test changes

- `NostrKeyManagerTest`: use `context.deleteSharedPreferences("nostr_keys_encrypted")` in `@After` cleanup (safe because MasterKey survives in Keystore and creates a new empty file next time). Add tests:
  - Old plaintext prefs file is deleted after construction
  - Encrypted prefs are not readable via plain `getSharedPreferences()` API
- `NostrReceiverTest`: update both `@Before setUp()` and `@After tearDown()` to use `deleteSharedPreferences("nostr_keys_encrypted")` instead of `getSharedPreferences("nostr_keys", 0).edit().clear()`
- **Both test classes:** `keyManager` is currently a `private val` (initialized once per class). Since `@After` deletes the backing file, change to `lateinit var` initialized in `@Before` so each test gets a fresh `EncryptedSharedPreferences` instance.

### Doc updates

- CLAUDE.md: update "Nostr keypair is transport identity only" bullet
- `docs/refactoring-todos.md`: mark #22 as fixed

### User-facing consequences

Regenerating the key means:
- The app's npub changes — Electrum users must re-pair
- Previously encrypted NFC passphrase tags become undecryptable (they were encrypted to the old pubkey via NIP-04) — users must re-encrypt

Both are accepted trade-offs (user confirmed no migration needed).

## Transitive dependency note

`security-crypto:1.1.0-alpha06` pulls in Google's Tink library (~1-2 MB APK impact). Acceptable for the security benefit.

## Why alpha06

The `1.1.0-alpha06` release is the standard recommendation. The `1.0.0` stable has a known `MasterKey` initialization bug on some Samsung/Xiaomi devices. `1.1.0-alpha06` has been stable in production across the ecosystem since 2021.

## Scope

- Files modified: `NostrKeyManager.kt`, `build.gradle.kts`, `libs.versions.toml`, `NostrKeyManagerTest.kt`, `NostrReceiverTest.kt`, `CLAUDE.md`, `refactoring-todos.md`
- No Python changes
- No UI changes
- No database changes
