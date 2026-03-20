# Encrypted Nostr Key Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Nostr secret key storage from plaintext SharedPreferences to EncryptedSharedPreferences, preventing key theft on rooted devices.

**Architecture:** Drop-in replacement — swap `getSharedPreferences()` for `EncryptedSharedPreferences.create()` in `NostrKeyManager`. Old plaintext prefs are deleted (no migration, key regenerates). Tests updated to use `lateinit var` for fresh instances per test.

**Tech Stack:** `androidx.security:security-crypto:1.1.0-alpha06`, Android Keystore, AES-256-GCM/SIV

**Spec:** `docs/superpowers/specs/2026-03-20-encrypted-nostr-key-storage-design.md`

---

### Task 1: Add security-crypto dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version and library entry to version catalog**

In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
security-crypto = "1.1.0-alpha06"
```

And under `[libraries]`:
```toml
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }
```

- [ ] **Step 2: Add implementation dependency to build.gradle.kts**

In `app/build.gradle.kts`, add after the `room-ktx` line (line 85):
```kotlin
implementation(libs.security.crypto)
```

- [ ] **Step 3: Sync and verify it compiles**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add androidx.security:security-crypto dependency"
```

---

### Task 2: Write failing tests for encrypted key storage

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/NostrKeyManagerTest.kt`

- [ ] **Step 1: Rewrite NostrKeyManagerTest with lateinit var and new tests**

Replace the entire file with:

```kotlin
package com.remotesigner

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.NostrKeyManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NostrKeyManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyManager: NostrKeyManager

    @Before
    fun setUp() {
        context.deleteSharedPreferences("nostr_keys_encrypted")
        context.deleteSharedPreferences("nostr_keys")
        keyManager = NostrKeyManager(context)
    }

    @After
    fun cleanup() {
        context.deleteSharedPreferences("nostr_keys_encrypted")
        context.deleteSharedPreferences("nostr_keys")
    }

    @Test
    fun getOrCreateKeyPair_generatesValidKeys() {
        val (privkey, pubkey) = keyManager.getOrCreateKeyPair()
        assertEquals(32, privkey.size)
        assertEquals(32, pubkey.size)
    }

    @Test
    fun getOrCreateKeyPair_returnsSameKeysOnSecondCall() {
        val (priv1, pub1) = keyManager.getOrCreateKeyPair()
        val (priv2, pub2) = keyManager.getOrCreateKeyPair()
        assertArrayEquals(priv1, priv2)
        assertArrayEquals(pub1, pub2)
    }

    @Test
    fun getNpub_startsWithNpub1() {
        val npub = keyManager.getNpub()
        assertTrue("npub should start with npub1, got: $npub", npub.startsWith("npub1"))
        assertEquals(63, npub.length)
    }

    @Test
    fun getHexPubkey_is64HexChars() {
        val hex = keyManager.getHexPubkey()
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun regenerateKeyPair_producesNewKeys() {
        val (priv1, _) = keyManager.getOrCreateKeyPair()
        keyManager.regenerateKeyPair()
        val (priv2, _) = keyManager.getOrCreateKeyPair()
        assertFalse("Regenerated key should differ", priv1.contentEquals(priv2))
    }

    @Test
    fun constructor_deletesOldPlaintextPrefs() {
        // Write a value to old plaintext prefs
        context.getSharedPreferences("nostr_keys", Context.MODE_PRIVATE)
            .edit().putString("nsec_hex", "deadbeef".repeat(8)).apply()

        // Constructing a new NostrKeyManager should delete the old file
        NostrKeyManager(context)

        val oldPrefs = context.getSharedPreferences("nostr_keys", Context.MODE_PRIVATE)
        assertNull("Old plaintext prefs should be cleared", oldPrefs.getString("nsec_hex", null))
    }

    @Test
    fun encryptedPrefs_notReadableViaPlainSharedPreferences() {
        // Generate a key
        keyManager.getOrCreateKeyPair()

        // Try to read via plain SharedPreferences — should not find nsec_hex
        val plain = context.getSharedPreferences("nostr_keys_encrypted", Context.MODE_PRIVATE)
        assertNull(
            "Key should not be readable via plain SharedPreferences",
            plain.getString("nsec_hex", null)
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify the two new tests fail**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrKeyManagerTest 2>&1 | tail -20`

Expected: The 5 existing tests pass (NostrKeyManager still uses plaintext prefs). The 2 new tests FAIL: `constructor_deletesOldPlaintextPrefs` fails because the current constructor does not call `deleteSharedPreferences`. `encryptedPrefs_notReadableViaPlainSharedPreferences` fails because the key is readable via plain prefs (not encrypted yet).

- [ ] **Step 3: Commit the failing tests**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/NostrKeyManagerTest.kt
git commit -m "test: add failing tests for encrypted Nostr key storage"
```

---

### Task 3: Implement EncryptedSharedPreferences in NostrKeyManager

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/nostr/NostrKeyManager.kt`

- [ ] **Step 1: Replace plaintext SharedPreferences with EncryptedSharedPreferences**

Replace the entire file with:

```kotlin
package com.remotesigner.nostr

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * Manages the app's Nostr transport identity keypair.
 * The keypair is a random secp256k1 key stored in EncryptedSharedPreferences.
 * This is a transport identity only — it protects nothing of value,
 * but we encrypt it to prevent PSBT decryption on rooted devices.
 */
class NostrKeyManager(context: Context) {

    private val prefs: SharedPreferences
    private val secp = Secp256k1.get()

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "nostr_keys_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Delete old plaintext prefs (only after encrypted prefs created successfully)
        context.deleteSharedPreferences("nostr_keys")
    }

    /**
     * Returns (privkey 32 bytes, x-only pubkey 32 bytes).
     * Generates and persists a new keypair on first call.
     */
    fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        val stored = prefs.getString("nsec_hex", null)
        val privkey = if (stored != null) {
            stored.hexToByteArray()
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { pk ->
                prefs.edit().putString("nsec_hex", pk.toHex()).apply()
            }
        }
        // pubkeyCreate returns 65-byte uncompressed key (04 || x || y)
        val uncompressed = secp.pubkeyCreate(privkey)
        val xOnly = uncompressed.copyOfRange(1, 33) // 32-byte x-coordinate
        return privkey to xOnly
    }

    fun getNpub(): String {
        val (_, pubkey) = getOrCreateKeyPair()
        return Bech32.npubEncode(pubkey)
    }

    fun getHexPubkey(): String {
        val (_, pubkey) = getOrCreateKeyPair()
        return pubkey.toHex()
    }

    fun regenerateKeyPair() {
        prefs.edit().remove("nsec_hex").apply()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run NostrKeyManagerTest — all tests should pass**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrKeyManagerTest 2>&1 | tail -20`
Expected: All 7 tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrKeyManager.kt
git commit -m "feat: encrypt Nostr key with EncryptedSharedPreferences (#22)"
```

---

### Task 4: Update NostrReceiverTest cleanup

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt`

- [ ] **Step 1: Change keyManager to lateinit var initialized in setUp**

Replace lines 29-30:
```kotlin
    private val keyManager = NostrKeyManager(context)
```

With:
```kotlin
    private lateinit var keyManager: NostrKeyManager
```

- [ ] **Step 2: Update setUp to initialize keyManager and use deleteSharedPreferences**

Replace lines 35-43 (the `@Before fun setUp()` block):
```kotlin
    @Before
    fun setUp() {
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
        // Pre-generate the keypair before any concurrent access.
        // Without this, receiver's onOpen and mock server's onOpen race to call
        // getOrCreateKeyPair(), each generating a different random key.
        keyManager.getOrCreateKeyPair()
        mockServer = MockWebServer()
    }
```

With:
```kotlin
    @Before
    fun setUp() {
        context.deleteSharedPreferences("nostr_keys_encrypted")
        keyManager = NostrKeyManager(context)
        // Pre-generate the keypair before any concurrent access.
        // Without this, receiver's onOpen and mock server's onOpen race to call
        // getOrCreateKeyPair(), each generating a different random key.
        keyManager.getOrCreateKeyPair()
        mockServer = MockWebServer()
    }
```

- [ ] **Step 3: Update tearDown to use deleteSharedPreferences**

Replace line 52:
```kotlin
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
```

With:
```kotlin
        context.deleteSharedPreferences("nostr_keys_encrypted")
```

- [ ] **Step 4: Verify it compiles**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run NostrReceiverTest to verify tests still pass**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrReceiverTest 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt
git commit -m "test: update NostrReceiverTest cleanup for encrypted prefs"
```

---

### Task 5: Update documentation

**Files:**
- Modify: `CLAUDE.md` (line 128)
- Modify: `docs/refactoring-todos.md` (lines 330-338)

- [ ] **Step 1: Update CLAUDE.md**

Replace line 128:
```markdown
- **Nostr keypair is transport identity only** — Random secp256k1 key in SharedPreferences (`nostr_keys`). Not a signing key, protects nothing of value. npub displayed on Home screen as QR + copyable text for sharing with Electrum. Also reused for NFC passphrase encryption (see below).
```

With:
```markdown
- **Nostr keypair is transport identity only** — Random secp256k1 key in EncryptedSharedPreferences (`nostr_keys_encrypted`), backed by Android Keystore (AES-256-GCM values, AES-256-SIV keys). Not a signing key, protects nothing of value, but encrypted to prevent PSBT decryption on rooted devices. npub displayed on Home screen as QR + copyable text for sharing with Electrum. Also reused for NFC passphrase encryption (see below).
```

- [ ] **Step 2: Mark #22 as fixed in refactoring-todos.md**

Replace lines 330-338:
```markdown
### 22. Nostr private key in plaintext SharedPreferences
| | |
|---|---|
| **File** | `nostr/NostrKeyManager.kt:14` |
| **Consensus** | System Architect |

The Nostr secret key (used for NIP-04 PSBT decryption) is stored as hex in `SharedPreferences`. On rooted devices, another app could decrypt all incoming PSBTs.

**Fix:** Use `EncryptedSharedPreferences` from Jetpack Security library.
```

With:
```markdown
### ~~22. Nostr private key in plaintext SharedPreferences~~ ✅ FIXED
| | |
|---|---|
| **File** | `nostr/NostrKeyManager.kt:14` |
| **Consensus** | System Architect |

~~The Nostr secret key (used for NIP-04 PSBT decryption) is stored as hex in `SharedPreferences`. On rooted devices, another app could decrypt all incoming PSBTs.~~

~~**Fix:** Use `EncryptedSharedPreferences` from Jetpack Security library.~~

**Fixed:** Replaced `SharedPreferences` with `EncryptedSharedPreferences` (`androidx.security:security-crypto:1.1.0-alpha06`). Keys encrypted via Android Keystore (AES-256-GCM values, AES-256-SIV key names). Old plaintext prefs deleted on construction (no migration — key regenerates). Tests verify encrypted storage is not readable via plain `SharedPreferences` API.
```

- [ ] **Step 3: Update Key Dependencies section in CLAUDE.md**

Find the `## Key Dependencies` section and add after the Room entry:
```markdown
- **security-crypto 1.1.0-alpha06** (`androidx.security:security-crypto`) — EncryptedSharedPreferences for Nostr key storage, backed by Android Keystore
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/refactoring-todos.md
git commit -m "docs: mark #22 fixed, update CLAUDE.md for encrypted key storage"
```
