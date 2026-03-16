# Nostr PSBT Delivery Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the Android app to receive PSBTs from Electrum over Nostr relays, display them in an inbox, and sign them with the existing Trezor signing flow.

**Architecture:** The app generates a Nostr keypair (stored in SharedPreferences), connects to relays via OkHttp WebSocket, receives NIP-04 encrypted kind 4 events, decrypts them using secp256k1-kmp ECDH + AES-256-CBC, and displays decoded PSBTs in an in-memory inbox on the Home screen. Tapping "Sign" feeds the PSBT bytes into the existing `loadPsbt()` pipeline.

**Tech Stack:** OkHttp (WebSocket), secp256k1-kmp-jni-android (ECDH), ZXing (QR generation), javax.crypto (AES), Jetpack Compose (UI)

**Scope note:** This plan covers the Android side only. The Electrum plugin (`nostr_signer/`) is an independent subsystem and should be a separate plan.

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/com/remotesigner/nostr/Bech32.kt` | Bech32 encode/decode for npub/nsec (NIP-19) |
| `app/src/main/kotlin/com/remotesigner/nostr/NostrKeyManager.kt` | Keypair generation, SharedPreferences storage, npub/hex accessors |
| `app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt` | NIP-04 decrypt (ECDH shared secret + AES-256-CBC) |
| `app/src/main/kotlin/com/remotesigner/nostr/NostrEvent.kt` | Nostr event JSON parsing and ID verification |
| `app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt` | OkHttp WebSocket client, relay subscriptions, event handling, reconnection |
| `app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt` | InboxItem data class, InboxStatus enum, time formatting |
| `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt` | Compose UI: InboxItemCard, InboxList composables |
| `app/src/androidTest/kotlin/com/remotesigner/Bech32Test.kt` | Bech32 encode/decode tests with known NIP-19 vectors |
| `app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt` | NIP-04 encrypt/decrypt round-trip tests |
| `app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt` | Mock WebSocket tests for receiver |
| `app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt` | Compose UI tests for inbox rendering |

### Modified Files

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add okhttp, secp256k1-kmp, zxing version entries |
| `app/build.gradle.kts` | Add OkHttp, secp256k1-kmp, ZXing, MockWebServer dependencies |
| `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` | Add `_inboxItems` StateFlow, NostrReceiver lifecycle, inbox actions |
| `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt` | Add QR code, npub display, inbox list, relay status |
| `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` | Pass inbox items and callbacks to HomeScreen |
| `app/src/main/kotlin/com/remotesigner/MainActivity.kt` | Call receiver start/stop in onStart/onStop |

---

## Chunk 1: Dependencies & Crypto Foundation

### Task 1: Add build dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version entries and library aliases to libs.versions.toml**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
compose-bom = "2024.12.01"
activity-compose = "1.9.3"
navigation-compose = "2.8.5"
lifecycle = "2.8.7"
junit = "4.13.2"
test-runner = "1.6.2"
okhttp = "4.12.0"
secp256k1-kmp = "0.22.0"
zxing = "3.5.3"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation-compose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
test-runner = { group = "androidx.test", name = "runner", version.ref = "test-runner" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
secp256k1-kmp = { group = "fr.acinq.secp256k1", name = "secp256k1-kmp-jni-android", version.ref = "secp256k1-kmp" }
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Add dependency lines to app/build.gradle.kts**

Add after the existing `implementation("androidx.core:core-ktx:1.15.0")` line:

```kotlin
    implementation(libs.okhttp)
    implementation(libs.secp256k1.kmp)
    implementation(libs.zxing.core)
```

Add after the existing `debugImplementation(libs.compose.ui.test.manifest)` line:

```kotlin
    androidTestImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 3: Sync and verify build compiles**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add OkHttp, secp256k1-kmp, ZXing dependencies for Nostr"
```

---

### Task 2: Bech32 encoder for npub/nsec

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/Bech32.kt`
- Create: `app/src/androidTest/kotlin/com/remotesigner/Bech32Test.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/kotlin/com/remotesigner/Bech32Test.kt`:

```kotlin
package com.remotesigner

import com.remotesigner.nostr.Bech32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Bech32Test {

    // Jack Dorsey's well-known Nostr pubkey
    private val pubkeyHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val expectedNpub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test
    fun encode_knownPubkey_producesCorrectNpub() {
        val pubkey = pubkeyHex.hexToByteArray()
        val npub = Bech32.npubEncode(pubkey)
        assertEquals(expectedNpub, npub)
    }

    @Test
    fun decode_knownNpub_producesCorrectPubkey() {
        val (hrp, data) = Bech32.decode(expectedNpub)
        assertEquals("npub", hrp)
        assertArrayEquals(pubkeyHex.hexToByteArray(), data)
    }

    @Test
    fun roundTrip_randomBytes_isIdentity() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = Bech32.npubEncode(bytes)
        val (_, decoded) = Bech32.decode(encoded)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun nsecEncode_decodesBackToOriginal() {
        val privkey = ByteArray(32) { (it + 0x80).toByte() }
        val nsec = Bech32.nsecEncode(privkey)
        assert(nsec.startsWith("nsec1"))
        val (hrp, decoded) = Bech32.decode(nsec)
        assertEquals("nsec", hrp)
        assertArrayEquals(privkey, decoded)
    }

    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.Bech32Test 2>&1 | tail -10`
Expected: Compilation error (Bech32 class not found)

- [ ] **Step 3: Write Bech32 implementation**

Create `app/src/main/kotlin/com/remotesigner/nostr/Bech32.kt`:

```kotlin
package com.remotesigner.nostr

/**
 * Bech32 encoding/decoding for NIP-19 (npub/nsec).
 * Implements BIP-173 bech32 (not bech32m).
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.also { rev ->
        CHARSET.forEachIndexed { i, c -> rev[c.code] = i }
    }

    fun npubEncode(pubkey32: ByteArray): String {
        require(pubkey32.size == 32)
        return encode("npub", convertBits(pubkey32, 8, 5, true))
    }

    fun nsecEncode(privkey32: ByteArray): String {
        require(privkey32.size == 32)
        return encode("nsec", convertBits(privkey32, 8, 5, true))
    }

    fun decode(bech32: String): Pair<String, ByteArray> {
        val lower = bech32.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= lower.length) { "Invalid bech32" }
        val hrp = lower.substring(0, pos)
        val dataChars = lower.substring(pos + 1)
        val data = IntArray(dataChars.length) { CHARSET_REV[dataChars[it].code].also { v -> require(v != -1) } }
        require(verifyChecksum(hrp, data)) { "Invalid checksum" }
        val payload = data.sliceArray(0 until data.size - 6)
        return hrp to convertBits(payload.map { it.toByte() }.toByteArray(), 5, 8, false)
    }

    fun encode(hrp: String, data5bit: ByteArray): String {
        val values = data5bit.map { it.toInt() }.toIntArray()
        val checksum = createChecksum(hrp, values)
        val sb = StringBuilder(hrp).append('1')
        for (v in values) sb.append(CHARSET[v])
        for (v in checksum) sb.append(CHARSET[v])
        return sb.toString()
    }

    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val maxV = (1 shl toBits) - 1
        val result = mutableListOf<Byte>()
        for (b in data) {
            acc = (acc shl fromBits) or (b.toInt() and 0xFF)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxV).toByte())
            }
        }
        if (pad && bits > 0) {
            result.add(((acc shl (toBits - bits)) and maxV).toByte())
        } else if (!pad) {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxV) == 0) { "Invalid padding" }
        }
        return result.toByteArray()
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1FFFFFF) shl 5) xor v
            for (i in 0..4) {
                if ((b shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) result[i] = hrp[i].code shr 5
        // result[hrp.length] = 0 (separator)
        for (i in hrp.indices) result[hrp.length + 1 + i] = hrp[i].code and 31
        return result
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        return polymod(hrpExpand(hrp) + data) == 1
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(values) xor 1
        return IntArray(6) { (polymod shr (5 * (5 - it))) and 31 }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.Bech32Test 2>&1 | tail -10`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/Bech32.kt app/src/androidTest/kotlin/com/remotesigner/Bech32Test.kt
git commit -m "feat: add Bech32 encoder/decoder for NIP-19 npub/nsec"
```

---

### Task 3: NostrKeyManager — keypair generation and storage

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/NostrKeyManager.kt`
- Create: `app/src/androidTest/kotlin/com/remotesigner/NostrKeyManagerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/kotlin/com/remotesigner/NostrKeyManagerTest.kt`:

```kotlin
package com.remotesigner

import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.NostrKeyManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NostrKeyManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyManager = NostrKeyManager(context)

    @After
    fun cleanup() {
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
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
        assertEquals(63, npub.length) // bech32 npub is always 63 chars
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrKeyManagerTest 2>&1 | tail -10`
Expected: Compilation error (NostrKeyManager not found)

- [ ] **Step 3: Write NostrKeyManager implementation**

Create `app/src/main/kotlin/com/remotesigner/nostr/NostrKeyManager.kt`:

```kotlin
package com.remotesigner.nostr

import android.content.Context
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * Manages the app's Nostr transport identity keypair.
 * The keypair is a random secp256k1 key stored in SharedPreferences.
 * This is a transport identity only — it protects nothing of value.
 */
class NostrKeyManager(context: Context) {

    private val prefs = context.getSharedPreferences("nostr_keys", Context.MODE_PRIVATE)
    private val secp = Secp256k1.get()

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

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrKeyManagerTest 2>&1 | tail -10`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrKeyManager.kt app/src/androidTest/kotlin/com/remotesigner/NostrKeyManagerTest.kt
git commit -m "feat: add NostrKeyManager for transport keypair generation and storage"
```

---

### Task 4: NIP-04 decryption

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt`
- Create: `app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt`:

```kotlin
package com.remotesigner

import android.util.Base64
import com.remotesigner.nostr.Nip04
import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Nip04Test {

    private val secp = Secp256k1.get()

    @Test
    fun decrypt_roundTrip_recoversPlaintext() {
        val alicePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alicePub = secp.pubkeyCreate(alicePriv)         // 65-byte uncompressed (04 || x || y)
        val alicePubXOnly = alicePub.copyOfRange(1, 33)     // 32-byte x-coordinate

        val bobPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val bobPub = secp.pubkeyCreate(bobPriv)
        val bobPubXOnly = bobPub.copyOfRange(1, 33)

        val plaintext = """{"tx": "cHNidFF...", "label": "Test payment"}"""

        // Alice encrypts to Bob using NIP-04 format
        // NIP-04 convention: always use 0x02 prefix for x-only pubkeys (assumes even parity)
        val sharedSecret = secp.ecdh(alicePriv, byteArrayOf(0x02) + bobPubXOnly)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val nip04Content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        // Bob decrypts using Nip04.decrypt
        val decrypted = Nip04.decrypt(bobPriv, alicePubXOnly, nip04Content)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_ecdhIsSymmetric() {
        // Verify that ECDH(alice_priv, 02||bob_x) == ECDH(bob_priv, 02||alice_x)
        // Both sides use 0x02 prefix per NIP-04 convention (even parity assumption).
        // This works because secp256k1 ECDH only uses the x-coordinate of the result.
        val alicePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alicePubXOnly = secp.pubkeyCreate(alicePriv).copyOfRange(1, 33)

        val bobPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val bobPubXOnly = secp.pubkeyCreate(bobPriv).copyOfRange(1, 33)

        val secret1 = secp.ecdh(alicePriv, byteArrayOf(0x02) + bobPubXOnly)
        val secret2 = secp.ecdh(bobPriv, byteArrayOf(0x02) + alicePubXOnly)
        assertEquals(secret1.toList(), secret2.toList())
    }

    @Test
    fun decrypt_invalidContent_throws() {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = secp.pubkeyCreate(priv).copyOfRange(1, 33)

        try {
            Nip04.decrypt(priv, pub, "not-valid-nip04")
            throw AssertionError("Should have thrown")
        } catch (_: Exception) {
            // Expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.Nip04Test 2>&1 | tail -10`
Expected: Compilation error (Nip04 not found)

- [ ] **Step 3: Write Nip04 implementation**

Create `app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt`:

```kotlin
package com.remotesigner.nostr

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-04 encrypted direct message decryption.
 *
 * Wire format: base64(ciphertext) + "?iv=" + base64(iv)
 * Encryption: AES-256-CBC with PKCS5 padding.
 * Shared secret: x-coordinate of ECDH(our_privkey, sender_pubkey).
 *
 * secp256k1-kmp's ecdh() returns the raw 32-byte x-coordinate
 * of the shared point, which is exactly what NIP-04 expects.
 */
object Nip04 {

    private val secp = Secp256k1.get()

    /**
     * Decrypt a NIP-04 encrypted message.
     *
     * @param ourPrivkey Our 32-byte secret key
     * @param senderXOnlyPubkey Sender's 32-byte x-only public key (from event.pubkey)
     * @param content NIP-04 encrypted content: "base64(ciphertext)?iv=base64(iv)"
     * @return Decrypted plaintext string
     */
    fun decrypt(ourPrivkey: ByteArray, senderXOnlyPubkey: ByteArray, content: String): String {
        val parts = content.split("?iv=")
        require(parts.size == 2) { "Invalid NIP-04 format: missing ?iv= separator" }

        val ciphertext = Base64.decode(parts[0], Base64.DEFAULT)
        val iv = Base64.decode(parts[1], Base64.DEFAULT)

        val sharedSecret = computeSharedSecret(ourPrivkey, senderXOnlyPubkey)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun computeSharedSecret(privkey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        // NIP-04 convention: reconstruct compressed pubkey with 02 prefix (even parity).
        // This works because secp256k1 ECDH only uses the x-coordinate of the shared point,
        // so the y-parity of the input pubkey doesn't affect the result.
        val compressedPubkey = byteArrayOf(0x02) + xOnlyPubkey
        return secp.ecdh(privkey, compressedPubkey)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.Nip04Test 2>&1 | tail -10`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt
git commit -m "feat: add NIP-04 decryption with secp256k1-kmp ECDH"
```

---

## Chunk 2: Nostr Receiver & Inbox Model

### Task 5: Inbox data model

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt`

- [ ] **Step 1: Create the inbox data model**

Create `app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt`:

```kotlin
package com.remotesigner.nostr

enum class InboxStatus { PENDING, SIGNING, SIGNED, FAILED }

data class InboxItem(
    val id: String,
    val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: InboxStatus = InboxStatus.PENDING,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxItem) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

fun formatRelativeTime(unixSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - unixSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

fun formatBtcAmount(satoshis: Long): String = "%.8f BTC".format(satoshis / 100_000_000.0)

fun truncateNpub(npub: String): String {
    return if (npub.length > 16) "${npub.take(12)}...${npub.takeLast(4)}" else npub
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt
git commit -m "feat: add InboxItem data model for Nostr PSBT inbox"
```

---

### Task 6: Nostr event parsing

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/NostrEvent.kt`

- [ ] **Step 1: Create event parsing utility**

Create `app/src/main/kotlin/com/remotesigner/nostr/NostrEvent.kt`:

```kotlin
package com.remotesigner.nostr

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Parsed Nostr event from a relay EVENT message.
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
) {
    companion object {
        /**
         * Parse a relay message like ["EVENT", "sub_id", {event}].
         * Returns null if the message is not an EVENT or is malformed.
         */
        fun fromRelayMessage(text: String): NostrEvent? {
            return try {
                val arr = JSONArray(text)
                if (arr.getString(0) != "EVENT") return null
                val event = arr.getJSONObject(2)
                val parsed = NostrEvent(
                    id = event.getString("id"),
                    pubkey = event.getString("pubkey"),
                    createdAt = event.getLong("created_at"),
                    kind = event.getInt("kind"),
                    content = event.getString("content"),
                )
                // Verify event ID matches hash of serialized event
                if (!parsed.verifyId(event)) return null
                parsed
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Verify that the event ID is SHA-256 of the serialized event.
     * Per NIP-01: id = sha256([0, pubkey, created_at, kind, tags, content])
     *
     * Note: This relies on org.json's toString() for canonical serialization.
     * Android's JSONArray preserves order, but numeric formatting edge cases
     * could cause false negatives. If this rejects valid events from real relays,
     * consider relaxing to a warning log rather than a hard rejection.
     */
    private fun verifyId(raw: JSONObject): Boolean {
        return try {
            val serialized = JSONArray().apply {
                put(0)
                put(pubkey)
                put(createdAt)
                put(kind)
                put(raw.getJSONArray("tags"))
                put(content)
            }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(serialized.toString().toByteArray(Charsets.UTF_8))
            hash.toHex() == id
        } catch (_: Exception) {
            Log.w("NostrEvent", "ID verification failed for event $id, accepting anyway")
            true // Accept event if verification can't be performed
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrEvent.kt
git commit -m "feat: add Nostr event parser with ID verification"
```

---

### Task 7: NostrReceiver — WebSocket client

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt`
- Create: `app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt`:

```kotlin
package com.remotesigner

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.nostr.*
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NostrReceiverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyManager = NostrKeyManager(context)
    private val secp = Secp256k1.get()
    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
        mockServer = MockWebServer()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        context.getSharedPreferences("nostr_keys", 0).edit().clear().apply()
    }

    @Test
    fun receiver_parsesValidEvent_callsOnItem() = runBlocking {
        val received = mutableListOf<InboxItem>()
        val done = CompletableDeferred<Unit>()
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = { received.add(it); done.complete(Unit) },
            scope = CoroutineScope(Dispatchers.IO),
        )

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val event = createTestEvent(keyManager)
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""")
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        withTimeout(5000) { done.await() }
        receiver.disconnect()

        assertEquals(1, received.size)
        assertEquals("Test payment", received[0].label)
    }

    @Test
    fun receiver_deduplicatesByEventId() = runBlocking {
        val received = mutableListOf<InboxItem>()
        val done = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.IO)
        var count = 0
        val receiver = NostrReceiver(
            keyManager = keyManager,
            onItem = {
                received.add(it)
                // Complete after a brief window to let potential duplicates arrive
                if (++count == 1) {
                    scope.launch {
                        kotlinx.coroutines.delay(500)
                        done.complete(Unit)
                    }
                }
            },
            scope = scope,
        )

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val event = createTestEvent(keyManager)
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""")
                    webSocket.send("""["EVENT","psbt-inbox",${event}]""") // duplicate
                }
            }
        ))
        mockServer.start()

        receiver.connectToRelays(listOf(mockServer.url("/").toString().replace("http://", "ws://")))
        withTimeout(5000) { done.await() }
        receiver.disconnect()

        assertEquals("Duplicate should be dropped", 1, received.size)
    }

    /**
     * Create a valid NIP-04 encrypted Nostr kind 4 event targeting the given keyManager.
     */
    private fun createTestEvent(km: NostrKeyManager): String {
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPub = secp.pubkeyCreate(senderPriv)            // 65-byte uncompressed
        val senderPubXOnly = senderPub.copyOfRange(1, 33)        // 32-byte x-coordinate

        val (_, receiverPub) = km.getOrCreateKeyPair()

        // NIP-04 encrypt
        val payload = """{"tx": "cHNidA==", "label": "Test payment"}"""
        val sharedSecret = secp.ecdh(senderPriv, byteArrayOf(0x02) + receiverPub)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(payload.toByteArray())
        val content = Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)

        val createdAt = System.currentTimeMillis() / 1000
        val tags = JSONArray().apply {
            put(JSONArray().apply { put("p"); put(receiverPub.toHex()) })
        }

        // Compute event ID per NIP-01
        val serialized = JSONArray().apply {
            put(0)
            put(senderPubXOnly.toHex())
            put(createdAt)
            put(4)
            put(tags)
            put(content)
        }
        val id = MessageDigest.getInstance("SHA-256")
            .digest(serialized.toString().toByteArray()).toHex()

        return JSONObject().apply {
            put("id", id)
            put("pubkey", senderPubXOnly.toHex())
            put("created_at", createdAt)
            put("kind", 4)
            put("tags", tags)
            put("content", content)
            put("sig", "0".repeat(128)) // Signature verification skipped for relay-provided events
        }.toString()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrReceiverTest 2>&1 | tail -10`
Expected: Compilation error (NostrReceiver not found)

- [ ] **Step 3: Write NostrReceiver implementation**

Create `app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt`:

```kotlin
package com.remotesigner.nostr

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Connects to Nostr relays via WebSocket, subscribes for kind 4 events
 * tagged with our pubkey, decrypts NIP-04 content, and delivers InboxItems.
 */
class NostrReceiver(
    private val keyManager: NostrKeyManager,
    private val onItem: (InboxItem) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NostrReceiver"
        val DEFAULT_RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
        )
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val webSockets = ConcurrentHashMap<String, WebSocket>()
    private val seenIds = mutableSetOf<String>()
    private val backoffMs = ConcurrentHashMap<String, Long>()
    private val _connectedCount = MutableStateFlow(0)
    @Volatile private var active = false

    val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

    fun connect() = connectToRelays(DEFAULT_RELAYS)

    fun connectToRelays(relays: List<String>) {
        active = true
        relays.forEach { connectToRelay(it) }
    }

    private fun connectToRelay(url: String) {
        if (!active) return
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
                _connectedCount.value++
                backoffMs[url] = 1000L
                sendSubscription(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Connection failed to $url: ${t.message}")
                webSockets.remove(url)
                _connectedCount.value = (_connectedCount.value - 1).coerceAtLeast(0)
                reconnect(url)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed $url: $reason")
                webSockets.remove(url)
                _connectedCount.value = (_connectedCount.value - 1).coerceAtLeast(0)
            }
        })
        webSockets[url] = ws
    }

    private fun sendSubscription(ws: WebSocket) {
        val hexPubkey = keyManager.getHexPubkey()
        val since = System.currentTimeMillis() / 1000 - 86400
        val req = """["REQ","psbt-inbox",{"kinds":[4],"#p":["$hexPubkey"],"since":$since}]"""
        ws.send(req)
    }

    private fun handleMessage(text: String) {
        val event = NostrEvent.fromRelayMessage(text) ?: return
        if (event.kind != 4) return

        synchronized(seenIds) {
            if (!seenIds.add(event.id)) return // deduplicate
        }

        try {
            val (privkey, _) = keyManager.getOrCreateKeyPair()
            val plaintext = Nip04.decrypt(privkey, event.pubkey.hexToByteArray(), event.content)
            val payload = JSONObject(plaintext)

            val psbtBase64 = payload.getString("tx")
            val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
            val label = payload.optString("label", "Unsigned transaction")

            val senderNpub = Bech32.npubEncode(event.pubkey.hexToByteArray())

            val item = InboxItem(
                id = event.id,
                psbtBytes = psbtBytes,
                label = label,
                senderNpub = truncateNpub(senderNpub),
                receivedAt = event.createdAt,
            )
            onItem(item)
        } catch (e: Exception) {
            Log.d(TAG, "Dropping malformed event ${event.id}: ${e.message}")
        }
    }

    private fun reconnect(url: String) {
        if (!active) return // Don't reconnect after intentional disconnect
        val delay = backoffMs.getOrPut(url) { 1000L }
        backoffMs[url] = (delay * 2).coerceAtMost(60_000L)
        scope.launch {
            delay(delay)
            connectToRelay(url)
        }
    }

    fun disconnect() {
        active = false
        webSockets.values.forEach { it.close(1000, "App stopped") }
        webSockets.clear()
        _connectedCount.value = 0
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.NostrReceiverTest 2>&1 | tail -10`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrEvent.kt app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt
git commit -m "feat: add NostrReceiver with WebSocket relay client and NIP-04 decryption"
```

---

## Chunk 3: UI Integration

### Task 8: ViewModel changes — inbox StateFlow and receiver lifecycle

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add imports at top of SignerViewModel.kt**

After the existing import block (line 19), add:

```kotlin
import com.remotesigner.nostr.InboxItem
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.nostr.NostrReceiver
import com.remotesigner.nostr.formatBtcAmount
```

- [ ] **Step 2: Add inbox fields and NostrReceiver to SignerViewModel**

After `private var currentSigningCallback: SigningCallbackImpl? = null` (line 82), add:

```kotlin

    // --- Nostr inbox ---
    val keyManager = NostrKeyManager(application)
    private val _inboxItems = MutableStateFlow<List<InboxItem>>(emptyList())
    val inboxItems: StateFlow<List<InboxItem>> = _inboxItems.asStateFlow()
    private var currentSigningInboxId: String? = null

    private val nostrReceiver = NostrReceiver(
        keyManager = keyManager,
        onItem = { item -> handleInboxEvent(item) },
        scope = viewModelScope,
    )

    val relayConnectedCount: StateFlow<Int> = nostrReceiver.connectedCount

    private fun handleInboxEvent(item: InboxItem) {
        viewModelScope.launch {
            // Parse PSBT to extract display amount
            val enrichedItem = try {
                val result = withContext(Dispatchers.IO) {
                    pythonBridge.parsePsbt(item.psbtBytes)
                }
                @Suppress("UNCHECKED_CAST")
                val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
                val totalSent = outputs
                    .filter { it["is_change"] as? Boolean != true }
                    .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
                item.copy(amount = formatBtcAmount(totalSent))
            } catch (_: Exception) {
                item // Keep without amount if parsing fails
            }
            _inboxItems.update { current -> current + enrichedItem }
        }
    }

    fun signInboxItem(item: InboxItem) {
        currentSigningInboxId = item.id
        updateInboxItemStatus(item.id, InboxStatus.SIGNING)
        loadPsbt(item.psbtBytes)
    }

    fun deleteInboxItem(id: String) {
        _inboxItems.update { current -> current.filter { it.id != id } }
    }

    private fun updateInboxItemStatus(id: String, status: InboxStatus) {
        _inboxItems.update { current ->
            current.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    fun startNostrReceiver() = nostrReceiver.connect()
    fun stopNostrReceiver() = nostrReceiver.disconnect()
```

- [ ] **Step 3: Update goHome() to track signing result in inbox**

Replace the existing `goHome()` method (lines 349-352):

```kotlin
    fun goHome() {
        // Update inbox item status based on signing result
        val inboxId = currentSigningInboxId
        if (inboxId != null) {
            val currentState = _state.value
            when (currentState) {
                is AppState.Result -> updateInboxItemStatus(inboxId, InboxStatus.SIGNED)
                is AppState.Error -> updateInboxItemStatus(inboxId, InboxStatus.FAILED)
                else -> {}
            }
            currentSigningInboxId = null
        }
        currentPsbtBytes = null
        _state.value = AppState.Home
    }
```

- [ ] **Step 4: Add kotlinx.coroutines.flow.update import**

Add to the import block (after the existing kotlinx.coroutines imports):

```kotlin
import kotlinx.coroutines.flow.update
```

- [ ] **Step 5: Verify build compiles**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: add Nostr inbox StateFlow and receiver lifecycle to ViewModel"
```

---

### Task 9: InboxSection composable

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt`

- [ ] **Step 1: Create the inbox UI composables**

Create `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt`:

```kotlin
package com.remotesigner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotesigner.nostr.InboxItem
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatRelativeTime

@Composable
fun InboxSection(
    items: List<InboxItem>,
    onSign: (InboxItem) -> Unit,
    onDelete: (InboxItem) -> Unit,
) {
    if (items.isEmpty()) return

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text("Inbox", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    items.forEach { item ->
        InboxItemCard(item = item, onSign = onSign, onDelete = onDelete)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun InboxItemCard(
    item: InboxItem,
    onSign: (InboxItem) -> Unit,
    onDelete: (InboxItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(item.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatRelativeTime(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.amount.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.amount, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "from ${item.senderNpub}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item.status.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (item.status) {
                        InboxStatus.PENDING -> MaterialTheme.colorScheme.primary
                        InboxStatus.SIGNING -> MaterialTheme.colorScheme.tertiary
                        InboxStatus.SIGNED -> MaterialTheme.colorScheme.secondary
                        InboxStatus.FAILED -> MaterialTheme.colorScheme.error
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (item.status == InboxStatus.PENDING || item.status == InboxStatus.FAILED) {
                    OutlinedButton(onClick = { onSign(item) }) {
                        Text("Sign")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onDelete(item) }) {
                    Text("Delete")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt
git commit -m "feat: add InboxSection composable for PSBT inbox display"
```

---

### Task 10: HomeScreen changes — npub, QR code, inbox list

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`

- [ ] **Step 1: Rewrite HomeScreen to include npub, QR, and inbox**

Replace the entire contents of `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`:

```kotlin
package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.remotesigner.nostr.InboxItem

@Composable
fun HomeScreen(
    npub: String,
    relayCount: Int,
    inboxItems: List<InboxItem>,
    onPsbtSelected: (Uri) -> Unit,
    onSignInboxItem: (InboxItem) -> Unit,
    onDeleteInboxItem: (InboxItem) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onPsbtSelected(it) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Satoshi Signer",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Sign Bitcoin transactions with your Trezor",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // QR code of npub
            QrCodeImage(data = npub, size = 200.dp)

            Spacer(modifier = Modifier.height(8.dp))

            // npub with copy button
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    npub,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("npub", npub))
                    Toast.makeText(context, "Copied npub", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { launcher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open PSBT File")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Relay status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "\u25CF ",
                    color = if (relayCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (relayCount > 0) "$relayCount relay${if (relayCount > 1) "s" else ""} connected"
                    else "No relays connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Inbox
            InboxSection(
                items = inboxItems,
                onSign = onSignInboxItem,
                onDelete = onDeleteInboxItem,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun QrCodeImage(data: String, size: Dp) {
    val bitmap = remember(data) {
        val pixels = 512
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, pixels, pixels)
        val bmp = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.RGB_565)
        for (x in 0 until pixels) {
            for (y in 0 until pixels) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Nostr public key QR code",
        modifier = Modifier.size(size),
    )
}
```

- [ ] **Step 2: Verify build compiles** (will fail until AppNavigation is updated in next task)

This step is expected to fail since AppNavigation still passes the old HomeScreen signature. Proceed to Task 11.

- [ ] **Step 3: Commit** (after Task 11 makes it compile)

---

### Task 11: Wire up AppNavigation and MainActivity

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt`

- [ ] **Step 1: Update AppNavigation to pass inbox data to HomeScreen**

Add the new imports and state variables to the existing `AppNavigation.kt`. The goal is to add inbox data flow without disturbing existing functionality (including `onSavePsbt` for ResultScreen).

In the import block, add:

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

(This import likely already exists — verify and skip if so.)

Inside `AppRoot`, after the existing `val passphraseRequest by ...` line, add:

```kotlin
    val inboxItems by viewModel.inboxItems.collectAsStateWithLifecycle()
    val relayCount by viewModel.relayConnectedCount.collectAsStateWithLifecycle()
```

Replace the `is AppState.Home -> HomeScreen(...)` block:

```kotlin
        is AppState.Home -> HomeScreen(
            npub = viewModel.keyManager.getNpub(),
            relayCount = relayCount,
            inboxItems = inboxItems,
            onPsbtSelected = { uri -> viewModel.loadPsbt(uri) },
            onSignInboxItem = { item -> viewModel.signInboxItem(item) },
            onDeleteInboxItem = { item -> viewModel.deleteInboxItem(item.id) },
        )
```

The `TransactionReview`, `Signing`, `Result` (including `onSavePsbt`), and `Error` branches remain **unchanged**.

- [ ] **Step 2: Update MainActivity for NostrReceiver lifecycle**

Replace the entire contents of `app/src/main/kotlin/com/remotesigner/MainActivity.kt`:

```kotlin
package com.remotesigner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.viewmodel.SignerViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SignerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val psbtBytes = readPsbtFromIntent(intent)

        setContent {
            SatoshiSignerTheme {
                AppRoot(viewModel = viewModel, intentPsbtBytes = psbtBytes)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit HomeScreen, AppNavigation, and MainActivity together**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt app/src/main/kotlin/com/remotesigner/MainActivity.kt
git commit -m "feat: integrate Nostr inbox into Home screen with QR code and relay status"
```

---

### Task 12: UI tests for inbox

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`

- [ ] **Step 1: Add inbox fixtures to TestFixtures.kt**

After the existing `passphraseRequestPhoneOnly` fixture (line 76), add:

```kotlin

    val sampleInboxItems = listOf(
        com.remotesigner.nostr.InboxItem(
            id = "event1",
            psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
            label = "Payment to Alice",
            amount = "0.00500000 BTC",
            senderNpub = "npub1a3x7...k9f2",
            receivedAt = System.currentTimeMillis() / 1000 - 120, // 2 min ago
            status = com.remotesigner.nostr.InboxStatus.PENDING,
        ),
        com.remotesigner.nostr.InboxItem(
            id = "event2",
            psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
            label = "Unsigned transaction",
            amount = "0.10000000 BTC",
            senderNpub = "npub1b4y8...m8g3",
            receivedAt = System.currentTimeMillis() / 1000 - 900, // 15 min ago
            status = com.remotesigner.nostr.InboxStatus.FAILED,
        ),
    )
```

- [ ] **Step 2: Write inbox UI test**

Create `app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt`:

```kotlin
package com.remotesigner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.remotesigner.nostr.InboxItem
import com.remotesigner.ui.InboxSection
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test

class InboxScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun inboxSection_displaysItems() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = TestFixtures.sampleInboxItems,
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Payment to Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00500000 BTC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unsigned transaction").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.10000000 BTC").assertIsDisplayed()
    }

    @Test
    fun inboxSection_emptyList_showsNothing() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = emptyList(),
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Inbox").assertDoesNotExist()
    }

    @Test
    fun inboxItemCard_pendingStatus_showsSignButton() {
        val pendingItem = TestFixtures.sampleInboxItems[0] // PENDING
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = listOf(pendingItem), onSign = {}, onDelete = {})
            }
        }

        composeTestRule.onNodeWithText("Sign").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signedStatus_hidesSignButton() {
        val signedItem = TestFixtures.sampleInboxItems[0].copy(
            status = com.remotesigner.nostr.InboxStatus.SIGNED
        )
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = listOf(signedItem), onSign = {}, onDelete = {})
            }
        }

        composeTestRule.onNodeWithText("Sign").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signButton_callsOnSign() {
        var signedItem: InboxItem? = null
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.sampleInboxItems[0]),
                    onSign = { signedItem = it },
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Sign").performClick()
        assert(signedItem != null) { "onSign should have been called" }
    }
}
```

- [ ] **Step 3: Run inbox UI tests**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.InboxScreenTest 2>&1 | tail -10`
Expected: All 5 tests PASS

- [ ] **Step 4: Update AppLaunchTest for new HomeScreen signature**

Replace the `HomeScreen(onPsbtSelected = {})` calls in `app/src/androidTest/kotlin/com/remotesigner/AppLaunchTest.kt`:

```kotlin
HomeScreen(
    npub = "npub1testxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    relayCount = 0,
    inboxItems = emptyList(),
    onPsbtSelected = {},
    onSignInboxItem = {},
    onDeleteInboxItem = {},
)
```

- [ ] **Step 5: Update NavigationTest for new HomeScreen signature**

Replace the `HomeScreen(onPsbtSelected = {})` call in `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt`:

```kotlin
is AppState.Home -> HomeScreen(
    npub = "npub1testxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    relayCount = 0,
    inboxItems = emptyList(),
    onPsbtSelected = {},
    onSignInboxItem = {},
    onDeleteInboxItem = {},
)
```

- [ ] **Step 6: Run all tests to verify no regressions**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest 2>&1 | tail -15`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt app/src/androidTest/kotlin/com/remotesigner/AppLaunchTest.kt app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt
git commit -m "test: add inbox UI tests, update existing tests for new HomeScreen signature"
```

---

## Post-Implementation Notes

### CLAUDE.md Updates

After implementation, add to CLAUDE.md:

- **NostrReceiver lifecycle** — `MainActivity.onStart()` calls `viewModel.startNostrReceiver()`, `onStop()` calls `stopNostrReceiver()`. No background service; PSBTs wait on relay.
- **Nostr keypair** — Random secp256k1 key in SharedPreferences (`nostr_keys`). Transport identity only, protects nothing of value.
- **secp256k1-kmp ECDH** — `Secp256k1.get().ecdh(priv, pub)` returns the raw 32-byte x-coordinate of the ECDH point (not SHA-256 hashed), which is exactly what NIP-04 requires as the AES key.
- **Inbox is in-memory** — `_inboxItems: MutableStateFlow<List<InboxItem>>` in ViewModel. Killed process loses inbox; events re-fetchable from relay within 24h.

### Electrum Plugin (Separate Plan)

The `nostr_signer/` Electrum plugin is an independent subsystem (Python, runs on desktop Electrum). Create a separate implementation plan for it covering:
- Plugin registration (`__init__.py`)
- NIP-04 encryption using Electrum's `ecc` module (`nostr_signer.py`)
- Qt UI: "Send to Signer" button in transaction dialog (`qt.py`)
- Tests: payload construction and NIP-04 encryption
