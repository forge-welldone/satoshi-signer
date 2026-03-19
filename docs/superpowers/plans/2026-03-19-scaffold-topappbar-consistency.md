# Scaffold + TopAppBar Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor all screens to use Material3 `Scaffold` + `TopAppBar` + `BackHandler` pattern (matching the existing `ContactsScreen`) for consistent status bar insets and back gesture support.

**Architecture:** Each screen replaces its `Surface + Column + manual padding` with `Scaffold(topBar = { TopAppBar(...) }) { padding -> Column(...) }`. Non-root screens add `BackHandler`. Body titles and redundant back/cancel buttons are removed. `ContactsScreen` is unchanged — it already follows the pattern.

**Tech Stack:** Kotlin, Jetpack Compose Material3, `BackHandler` from `androidx.activity.compose`

---

## File Structure

**Modify (6 screen files):**
- `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt` — Add Scaffold + TopAppBar (no back arrow)
- `app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt` — Add Scaffold + TopAppBar + BackHandler, remove Cancel button
- `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt` — Add Scaffold + TopAppBar + BackHandler, keep body Cancel button
- `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt` — Add Scaffold + TopAppBar + BackHandler, remove Back to Home button
- `app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt` — Add Scaffold + TopAppBar + BackHandler, remove Back to Home button
- `app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt` — Add Scaffold + TopAppBar + BackHandler, remove Back button

**Modify (1 test file):**
- `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` — Update assertions for removed buttons

**Note:** `NavigationTest.kt` assertions still pass — TopAppBar titles render as the same text nodes. No changes needed.

---

### Task 1: HomeScreen — Scaffold + TopAppBar

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`

- [ ] **Step 1: Add imports and annotation**

Add at top of file with other imports:

```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
```

Add `@OptIn(ExperimentalMaterial3Api::class)` before the `@Composable` annotation on `HomeScreen`.

- [ ] **Step 2: Replace Surface with Scaffold + TopAppBar**

Replace the `Surface(modifier = Modifier.fillMaxSize()) {` block opening and its `Column` with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(title = { Text("Satoshi Signer") })
    }
) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
```

Close `Scaffold` where `Surface` was closed.

- [ ] **Step 3: Remove the body title and subtitle**

Remove these lines from inside the Column:

```kotlin
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
```

Replace with just:

```kotlin
Text(
    "Sign Bitcoin transactions with your Trezor",
    style = MaterialTheme.typography.bodyLarge,
)

Spacer(modifier = Modifier.height(24.dp))
```

(Keep the subtitle — it provides useful context. Remove only the title since it's now in the TopAppBar.)

- [ ] **Step 4: Note: visual padding change**

HomeScreen changes from `padding(32.dp)` all-around to `padding(horizontal = 16.dp)`. This is intentional — Scaffold handles top inset, and 16dp horizontal matches all other screens. The `Dp` import is still needed (`QrCodeImage` uses it).

- [ ] **Step 5: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt
git commit -m "refactor: HomeScreen to Scaffold + TopAppBar"
```

---

### Task 2: TransactionReviewScreen — Scaffold + TopAppBar + BackHandler

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt`

- [ ] **Step 1: Add imports and annotation**

Add imports:

```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
```

Add `@OptIn(ExperimentalMaterial3Api::class)` before `@Composable`.

- [ ] **Step 2: Add BackHandler at top of composable**

Right after `var showAddDialog by remember { mutableStateOf<SignerInfo?>(null) }`, add:

```kotlin
BackHandler(onBack = onCancel)
```

- [ ] **Step 3: Replace Surface with Scaffold + TopAppBar**

Replace `Surface(modifier = Modifier.fillMaxSize()) {` and its inner `Column` with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text("Transaction Details") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
```

Move the `showAddDialog?.let { ... }` dialog block **outside** the Scaffold (after its closing brace), since dialogs should not be nested inside Scaffold content.

- [ ] **Step 4: Remove body title, Cancel button, and their spacers**

Remove from inside Column:

```kotlin
Text("Transaction Details", style = MaterialTheme.typography.headlineMedium)
Spacer(modifier = Modifier.height(16.dp))
```

Also remove the Cancel button at the bottom:

```kotlin
Spacer(modifier = Modifier.height(8.dp))
OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
    Text("Cancel")
}
```

Keep the "Sign with Trezor" button.

- [ ] **Step 5: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt
git commit -m "refactor: TransactionReviewScreen to Scaffold + TopAppBar + BackHandler"
```

---

### Task 3: SigningScreen — Scaffold + TopAppBar + BackHandler (keep body Cancel)

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt`

- [ ] **Step 1: Add imports and annotation**

Add imports:

```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
```

Add `@OptIn(ExperimentalMaterial3Api::class)` before `@Composable`.

- [ ] **Step 2: Add BackHandler after DisposableEffect**

After the `DisposableEffect(Unit) { ... }` block, add:

```kotlin
BackHandler(onBack = onCancel)
```

- [ ] **Step 3: Replace Surface with Scaffold + TopAppBar**

Replace `Surface(modifier = Modifier.fillMaxSize()) {` with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text("Signing") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
) { padding ->
```

The passphrase and account path dialogs should be moved **outside** the Scaffold (after its closing brace). They are modal dialogs and shouldn't be nested in Scaffold content.

The inner `Column` becomes:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp)
        .verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    Spacer(modifier = Modifier.height(32.dp))
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(16.dp))
    Text(message, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(24.dp))
    OutlinedButton(onClick = onCancel) {
        Text("Cancel")
    }
    // ... rest of log section unchanged ...
```

**Keep the body "Cancel" button** — it's more discoverable during active signing.

- [ ] **Step 4: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt
git commit -m "refactor: SigningScreen to Scaffold + TopAppBar + BackHandler"
```

---

### Task 4: ResultScreen — Scaffold + TopAppBar + BackHandler

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt`

- [ ] **Step 1: Add imports and annotation**

Add imports:

```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
```

Add `@OptIn(ExperimentalMaterial3Api::class)` before `@Composable`.

- [ ] **Step 2: Add BackHandler at top of composable**

After `var broadcastClicked by remember { mutableStateOf(false) }`, add:

```kotlin
BackHandler(onBack = onHome)
```

- [ ] **Step 3: Replace Surface with Scaffold + TopAppBar**

The title is conditional on `state.isComplete`. Replace `Surface(modifier = Modifier.fillMaxSize()) {` with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text(if (state.isComplete) "Transaction Signed" else "Signature Added") },
            navigationIcon = {
                IconButton(onClick = onHome) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
```

- [ ] **Step 4: Remove body titles and Back to Home button**

Remove the two title `Text` composables inside the `if (state.isComplete)` and `else` branches:

```kotlin
// Remove from isComplete branch:
Text("Transaction Signed", style = MaterialTheme.typography.headlineMedium)
Spacer(modifier = Modifier.height(16.dp))

// Remove from else branch:
Text("Signature Added", style = MaterialTheme.typography.headlineMedium)
Spacer(modifier = Modifier.height(16.dp))
```

Remove the Back to Home button at the bottom:

```kotlin
Spacer(modifier = Modifier.height(24.dp))
OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
    Text("Back to Home")
}
```

- [ ] **Step 5: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt
git commit -m "refactor: ResultScreen to Scaffold + TopAppBar + BackHandler"
```

---

### Task 5: ErrorScreen — Scaffold + TopAppBar + BackHandler

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt`

- [ ] **Step 1: Add imports and annotation**

Add imports:

```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
```

Add `@OptIn(ExperimentalMaterial3Api::class)` before `@Composable`.

- [ ] **Step 2: Add BackHandler at top of composable**

After `val context = LocalContext.current`, add:

```kotlin
BackHandler(onBack = onHome)
```

- [ ] **Step 3: Replace Surface with Scaffold + TopAppBar**

Replace `Surface(modifier = Modifier.fillMaxSize()) {` with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text("Error") },
            navigationIcon = {
                IconButton(onClick = onHome) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
```

- [ ] **Step 4: Remove body title and Back to Home button, simplify layout**

Remove:

```kotlin
Text("Error", style = MaterialTheme.typography.headlineSmall)
Spacer(modifier = Modifier.height(8.dp))
```

Replace the `Row` containing both buttons with just the Copy Error button:

```kotlin
Text(message, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
Spacer(modifier = Modifier.height(16.dp))
OutlinedButton(onClick = {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("error", message))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}) {
    Text("Copy Error")
}
```

- [ ] **Step 5: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/ErrorScreen.kt
git commit -m "refactor: ErrorScreen to Scaffold + TopAppBar + BackHandler"
```

---

### Task 6: EncryptPassphraseScreen — Scaffold + TopAppBar + BackHandler

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt`

- [ ] **Step 1: Add imports and annotation**

Add imports:

```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
```

Add `@OptIn(ExperimentalMaterial3Api::class)` before `@Composable`.

- [ ] **Step 2: Add BackHandler at top of composable**

After `val context = LocalContext.current`, add:

```kotlin
BackHandler(onBack = onBack)
```

- [ ] **Step 3: Replace Surface with Scaffold + TopAppBar**

Replace `Surface(modifier = Modifier.fillMaxSize()) {` with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text("Encrypt Passphrase") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
```

- [ ] **Step 4: Remove body title and Back button**

Remove the title:

```kotlin
Text(
    "Encrypt Passphrase for NFC",
    style = MaterialTheme.typography.headlineSmall,
)
Spacer(modifier = Modifier.height(8.dp))
```

Keep the description text ("The encrypted text can be written to an NFC tag...").

Note: Side padding changes from `padding(32.dp)` to `padding(horizontal = 16.dp)`, matching other screens.

Remove the Back button at the bottom:

```kotlin
Spacer(modifier = Modifier.height(24.dp))

OutlinedButton(
    onClick = onBack,
    modifier = Modifier.fillMaxWidth(),
) {
    Text("Back")
}

Spacer(modifier = Modifier.height(32.dp))
```

- [ ] **Step 5: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/EncryptPassphraseScreen.kt
git commit -m "refactor: EncryptPassphraseScreen to Scaffold + TopAppBar + BackHandler"
```

---

### Task 7: Update ScreenRenderTest assertions

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`

- [ ] **Step 1: Update transactionReviewScreen_displaysDetails**

Remove the assertion for the Cancel button (line 39):

```kotlin
// Remove:
composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
```

The "Transaction Details" and "Sign with Trezor" assertions remain — TopAppBar title is still findable as text.

- [ ] **Step 2: Update errorScreen_displaysMessageAndButtons**

Remove the assertion for "Back to Home" (line 159):

```kotlin
// Remove:
composeTestRule.onNodeWithText("Back to Home").assertIsDisplayed()
```

Keep "Error" and "Copy Error" assertions.

- [ ] **Step 3: Update errorScreen_withSigningLog**

Remove the assertion for "Back to Home" (line 176):

```kotlin
// Remove:
composeTestRule.onNodeWithText("Back to Home").assertIsDisplayed()
```

- [ ] **Step 4: Update encryptPassphraseScreen_rendersInitialState**

The title changed from "Encrypt Passphrase for NFC" to "Encrypt Passphrase" (in TopAppBar). Update:

```kotlin
// Change:
composeTestRule.onNodeWithText("Encrypt Passphrase for NFC").assertIsDisplayed()
// To:
composeTestRule.onNodeWithText("Encrypt Passphrase").assertIsDisplayed()
```

Remove the "Back" button assertion (line 462):

```kotlin
// Remove:
composeTestRule.onNodeWithText("Back").assertIsDisplayed()
```

- [ ] **Step 5: Build**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: update ScreenRenderTest for Scaffold refactor"
```

---

### Task 8: Run tests on emulator

- [ ] **Step 1: Run Android instrumented tests**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew connectedDebugAndroidTest 2>&1 | tail -20`
Expected: All tests pass. If any fail, fix and re-run.

- [ ] **Step 2: Run JVM unit tests**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (JVM tests are unaffected by UI changes)

- [ ] **Step 3: Final commit if any fixes were needed**

If test fixes were needed, commit them.
