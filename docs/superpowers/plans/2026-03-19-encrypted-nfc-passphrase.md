# Encrypted NFC Passphrase Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encrypt passphrases before writing to NFC tags using the existing Nostr keypair, so an attacker needs both the phone and the tag.

**Architecture:** Add `Nip04.encrypt()` as the mirror of the existing `decrypt()`. New `EncryptPassphrase` state and screen for displaying encrypted ciphertext. Modify `onNfcTagResult()` in the ViewModel to decrypt NFC reads before passing to the passphrase dialog.

**Tech Stack:** secp256k1-kmp (ECDH), AES-256-CBC (javax.crypto), Jetpack Compose, Android instrumented tests

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt` | Modify | Add `encrypt()` method |
| `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` | Modify | Add `EncryptPassphrase` state, `encryptForNfc()`, NFC decrypt in `onNfcTagResult()` |
| `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` | Modify | Route `EncryptPassphrase` to new screen |
| `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt` | Modify | Add "Encrypt passphrase for NFC" button |
| `app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt` | Create | Encrypt UI composable |
| `app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt` | Modify | Add encrypt + self-encryption tests |
| `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` | Modify | Add EncryptPassphraseScreen UI tests |

---

### Task 1: Add `Nip04.encrypt()` — tests

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt`

- [ ] **Step 1: Write the encrypt round-trip test**

Add to `Nip04Test.kt`:

```kotlin
@Test
fun encrypt_decrypt_roundTrip() {
    val alicePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val alicePubXOnly = secp.pubkeyCreate(alicePriv).copyOfRange(1, 33)

    val bobPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val bobPubXOnly = secp.pubkeyCreate(bobPriv).copyOfRange(1, 33)

    val plaintext = "correct horse battery staple"
    val encrypted = Nip04.encrypt(alicePriv, bobPubXOnly, plaintext)

    // Verify NIP-04 format
    assertTrue(encrypted.contains("?iv="))
    val parts = encrypted.split("?iv=")
    assertEquals(2, parts.size)

    // Bob can decrypt
    val decrypted = Nip04.decrypt(bobPriv, alicePubXOnly, encrypted)
    assertEquals(plaintext, decrypted)
}
```

- [ ] **Step 2: Write the self-encryption round-trip test**

This is the exact pattern used for NFC — encrypting to your own pubkey:

```kotlin
@Test
fun encrypt_decrypt_selfEncryption_roundTrip() {
    val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val pubXOnly = secp.pubkeyCreate(priv).copyOfRange(1, 33)

    val plaintext = "p@ss wörd!€"
    val encrypted = Nip04.encrypt(priv, pubXOnly, plaintext)
    val decrypted = Nip04.decrypt(priv, pubXOnly, encrypted)
    assertEquals(plaintext, decrypted)
}
```

- [ ] **Step 3: Write the wrong-key failure test**

```kotlin
@Test
fun encrypt_decrypt_wrongKey_fails() {
    val alicePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val alicePubXOnly = secp.pubkeyCreate(alicePriv).copyOfRange(1, 33)

    val evePriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val evePubXOnly = secp.pubkeyCreate(evePriv).copyOfRange(1, 33)

    val encrypted = Nip04.encrypt(alicePriv, alicePubXOnly, "secret")

    try {
        Nip04.decrypt(evePriv, alicePubXOnly, encrypted)
        throw AssertionError("Should have thrown — wrong key")
    } catch (_: Exception) {
        // Expected: BadPaddingException or similar
    }
}
```

- [ ] **Step 4: Write the various-lengths test**

```kotlin
@Test
fun encrypt_decrypt_variousLengths() {
    val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val pub = secp.pubkeyCreate(priv).copyOfRange(1, 33)

    val cases = listOf(
        "a",                          // 1 char
        "short passphrase",           // 16 bytes plaintext (pads to 2 AES blocks)
        "a".repeat(100),              // long
        "emoji \uD83D\uDD11 key",     // unicode
    )

    for (plain in cases) {
        val enc = Nip04.encrypt(priv, pub, plain)
        val dec = Nip04.decrypt(priv, pub, enc)
        assertEquals(plain, dec)
    }
}
```

- [ ] **Step 5: Add missing import**

Add `import org.junit.Assert.assertTrue` to the imports in `Nip04Test.kt` (already has `assertEquals`).

- [ ] **Step 6: Run tests to verify they fail**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.Nip04Test`

Expected: Compilation error — `Nip04.encrypt` does not exist yet.

- [ ] **Step 7: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/Nip04Test.kt
git commit -m "test: add Nip04.encrypt() tests (red)"
```

---

### Task 2: Implement `Nip04.encrypt()`

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt`

- [ ] **Step 1: Add `encrypt()` method**

Add to `Nip04.kt` inside the `object Nip04` block, after the existing `decrypt()`:

```kotlin
/**
 * Encrypt a plaintext string using NIP-04 (AES-256-CBC).
 *
 * @param privkey Our 32-byte secret key
 * @param recipientXOnlyPubkey Recipient's 32-byte x-only public key
 * @param plaintext The string to encrypt
 * @return NIP-04 format: "base64(ciphertext)?iv=base64(iv)"
 */
fun encrypt(privkey: ByteArray, recipientXOnlyPubkey: ByteArray, plaintext: String): String {
    val sharedSecret = computeSharedSecret(privkey, recipientXOnlyPubkey)
    val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

    return Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
        "?iv=" + Base64.encodeToString(iv, Base64.NO_WRAP)
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.Nip04Test`

Expected: All tests PASS (4 new + 3 existing).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/Nip04.kt
git commit -m "feat: add Nip04.encrypt() for NFC passphrase encryption"
```

---

### Task 3: Add `EncryptPassphrase` state, `encryptForNfc()`, and navigation stub

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`

- [ ] **Step 1: Add state variant**

Add to the `sealed class AppState` block (after `data object Contacts : AppState()`):

```kotlin
data object EncryptPassphrase : AppState()
```

- [ ] **Step 2: Add `showEncryptPassphrase()` navigation method**

Add to `SignerViewModel`, near `showContacts()`:

```kotlin
fun showEncryptPassphrase() {
    _state.value = AppState.EncryptPassphrase
}
```

- [ ] **Step 3: Add `encryptForNfc()` method**

Add to `SignerViewModel`:

```kotlin
fun encryptForNfc(plaintext: String): String {
    val (privkey, pubkey) = keyManager.getOrCreateKeyPair()
    return com.remotesigner.nostr.Nip04.encrypt(privkey, pubkey, plaintext)
}
```

- [ ] **Step 4: Add placeholder route in `AppNavigation.kt`**

In the `when (val s = state)` block in `AppRoot`, add a new branch after the `Contacts` branch (line 121-129) so the `when` handles all states at every commit:

```kotlin
is AppState.EncryptPassphrase -> EncryptPassphraseScreen(
    onEncrypt = { plaintext -> viewModel.encryptForNfc(plaintext) },
    onBack = { viewModel.goHome() },
)
```

Note: `EncryptPassphraseScreen` doesn't exist yet, so this will be added in the same commit as the screen (Task 5). For now, add a temporary stub to keep it compiling:

In `AppNavigation.kt`, add this branch instead:

```kotlin
is AppState.EncryptPassphrase -> {
    // Placeholder until EncryptPassphraseScreen is created in Task 5
    androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.material3.Text("Encrypt Passphrase — coming soon")
    }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
      app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: add EncryptPassphrase state, encryptForNfc(), and navigation stub"
```

---

### Task 4: Add NFC read-side decryption in ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Modify `onNfcTagResult()` to decrypt**

Replace the current one-liner `onNfcTagResult` (line 127-129):

```kotlin
fun onNfcTagResult(result: NfcReadResult) {
    _nfcTagResult.value = result
}
```

With:

```kotlin
fun onNfcTagResult(result: NfcReadResult) {
    if (result is NfcReadResult.Success) {
        try {
            val (privkey, pubkey) = keyManager.getOrCreateKeyPair()
            val decrypted = com.remotesigner.nostr.Nip04.decrypt(privkey, pubkey, result.passphrase)
            _nfcTagResult.value = NfcReadResult.Success(decrypted)
        } catch (_: Exception) {
            _nfcTagResult.value = NfcReadResult.Error(
                "Could not decrypt NFC tag \u2014 was it encrypted with this phone\u2019s key?"
            )
        }
    } else {
        _nfcTagResult.value = result
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: decrypt NFC tag content in onNfcTagResult()"
```

---

### Task 5: Create `EncryptPassphraseScreen` composable

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt`

- [ ] **Step 1: Create the screen**

Create `app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt`:

```kotlin
package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EncryptPassphraseScreen(
    onEncrypt: (String) -> String,
    onBack: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var encryptedResult by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Encrypt Passphrase for NFC",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "The encrypted text can be written to an NFC tag. " +
                    "Only this phone can decrypt it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    encryptedResult = null
                },
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

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { encryptedResult = onEncrypt(passphrase) },
                enabled = passphrase.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Encrypt")
            }

            if (encryptedResult != null) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Encrypted (${encryptedResult!!.length} chars):",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    encryptedResult!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("encrypted passphrase", encryptedResult))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy to Clipboard")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
```

- [ ] **Step 2: Replace navigation stub in `AppNavigation.kt`**

Replace the placeholder branch added in Task 3 with the real composable call:

```kotlin
is AppState.EncryptPassphrase -> EncryptPassphraseScreen(
    onEncrypt = { plaintext -> viewModel.encryptForNfc(plaintext) },
    onBack = { viewModel.goHome() },
)
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt \
      app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: add EncryptPassphraseScreen composable and wire navigation"
```

---

### Task 6: UI tests for EncryptPassphraseScreen

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Add render test**

Add to `ScreenRenderTest.kt`. The screen is a standalone composable — test it directly like existing screen tests:

```kotlin
@Test
fun encryptPassphraseScreen_rendersInitialState() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            EncryptPassphraseScreen(
                onEncrypt = { it },
                onBack = {},
            )
        }
    }
    composeTestRule.onNodeWithText("Encrypt Passphrase for NFC").assertIsDisplayed()
    composeTestRule.onNodeWithText("Encrypt").assertIsDisplayed()
    composeTestRule.onNodeWithText("Back").assertIsDisplayed()
}
```

- [ ] **Step 2: Add encrypt-and-show-result test**

```kotlin
@Test
fun encryptPassphraseScreen_showsResultAfterEncrypt() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            EncryptPassphraseScreen(
                onEncrypt = { "fakeCipherText?iv=fakeIv" },
                onBack = {},
            )
        }
    }
    composeTestRule.onNodeWithText("Passphrase").performClick()
    composeTestRule.onNodeWithText("Passphrase")
        .performTextInput("test passphrase")
    composeTestRule.onNodeWithText("Encrypt").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("fakeCipherText?iv=fakeIv").assertIsDisplayed()
    composeTestRule.onNodeWithText("Copy to Clipboard").assertIsDisplayed()
}
```

- [ ] **Step 3: Add import for EncryptPassphraseScreen**

Add to the imports in `ScreenRenderTest.kt`:

```kotlin
import com.remotesigner.ui.EncryptPassphraseScreen
```

Also add these imports if not already present:

```kotlin
import androidx.compose.ui.test.performTextInput
```

- [ ] **Step 4: Run the UI tests**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest`

Expected: All tests pass (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: add EncryptPassphraseScreen UI tests"
```

---

### Task 7: Wire Home screen button

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`

- [ ] **Step 1: Add button in `HomeScreen.kt`**

Add the `onEncryptPassphrase` parameter to `HomeScreen`:

In the function signature, add after `onContacts: () -> Unit = {}`:
```kotlin
onEncryptPassphrase: () -> Unit = {},
```

Add the button after the "Contacts" `OutlinedButton` (after line 108):

```kotlin
Spacer(modifier = Modifier.height(8.dp))
OutlinedButton(
    onClick = onEncryptPassphrase,
    modifier = Modifier.fillMaxWidth(),
) {
    Text("Encrypt Passphrase for NFC")
}
```

- [ ] **Step 2: Pass callback in `AppNavigation.kt`**

In the `HomeScreen` call inside `AppRoot` (line 63-73), add the new parameter:

```kotlin
onEncryptPassphrase = { viewModel.showEncryptPassphrase() },
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt \
      app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: add Encrypt Passphrase for NFC button to Home screen"
```

---

### Task 8: End-to-end verification

- [ ] **Step 1: Run all existing tests**

Run: `./gradlew connectedDebugAndroidTest`

Expected: All tests pass (existing + new Nip04 tests).

- [ ] **Step 2: Run JVM unit tests**

Run: `./gradlew testDebugUnitTest`

Expected: All pass (NdefTextParser tests etc. — no changes to them).

- [ ] **Step 3: Manual smoke test on emulator**

1. Launch app
2. Tap "Encrypt Passphrase for NFC" on Home screen
3. Type a passphrase, tap Encrypt
4. Verify ciphertext appears with length, copy works
5. Tap Back, verify return to Home

- [ ] **Step 4: Commit (if any fixups needed)**

```bash
git add -u
git commit -m "fix: address issues found during E2E verification"
```
