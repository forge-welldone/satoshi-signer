# Transaction Description on Review Screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the Nostr inbox item's label (transaction description) on the Transaction Review screen, below the title.

**Architecture:** Thread `InboxItemEntity.label` through `SignerViewModel` into `AppState.TransactionReview.description`, render conditionally in `TransactionReviewScreen`. No Python or Room changes needed.

**Tech Stack:** Kotlin, Jetpack Compose, Room (read-only)

---

### Task 1: Add `description` field to `AppState.TransactionReview`

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:56` (the `TransactionReview` data class)

- [ ] **Step 1: Add the field**

In the `TransactionReview` data class inside `AppState`, add `description` with a null default:

```kotlin
data class TransactionReview(
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val fee: Long,
    val totalSent: Long,
    val status: String,
    val signers: List<SignerInfo>,
    val warnings: List<String>,
    val requiredSigs: Int = 0,
    val totalSigs: Int = 0,
    val network: String = "main",
    val description: String? = null,
) : AppState()
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (default null means no call sites break)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: add description field to TransactionReview state"
```

---

### Task 2: Thread inbox label through ViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add `currentDescription` property**

After `currentNetwork` (line 102), add:

```kotlin
private var currentDescription: String? = null
```

- [ ] **Step 2: Set `currentDescription` in `signInboxItem()`**

In `signInboxItem()` (line 202), set it before `loadPsbt()`:

```kotlin
fun signInboxItem(item: InboxItemEntity) {
    currentSigningInboxId = item.id
    currentDescription = item.label.ifBlank { null }
    viewModelScope.launch { inboxDao.updateStatus(item.id, InboxStatus.SIGNING) }
    loadPsbt(item.psbtBytes)
}
```

- [ ] **Step 3: Clear `currentDescription` in file-picker path only**

In `loadPsbt(uri: Uri)` (line 225), add `currentDescription = null` as the first line:

```kotlin
fun loadPsbt(uri: Uri) {
    currentDescription = null
    viewModelScope.launch {
        // ... existing code
    }
}
```

**Do NOT clear `currentDescription` in `loadPsbt(bytes: ByteArray)`** — `signInboxItem()` sets the description then calls `loadPsbt(bytes)`, so clearing there would wipe it.

- [ ] **Step 4: Clear `currentDescription` in `goHome()`**

In `goHome()` (line 631), add `currentDescription = null` alongside the existing `currentPsbtBytes = null` at line 648:

```kotlin
currentPsbtBytes = null
currentDescription = null
_state.value = AppState.Home
```

This prevents stale descriptions from leaking into subsequent transactions loaded via USB intent (`loadPsbt(bytes)` from AppNavigation).

- [ ] **Step 5: Pass `currentDescription` into TransactionReview state**

In `parsePsbt()`, where `_state.value = AppState.TransactionReview(...)` is built (line 296), add the description parameter:

```kotlin
_state.value = AppState.TransactionReview(
    inputs = inputs,
    outputs = outputs,
    fee = fee,
    totalSent = totalSent,
    status = result["status"]?.toString() ?: "unknown",
    signers = signers,
    warnings = warnings,
    requiredSigs = (result["required_sigs"] as? Number)?.toInt() ?: 0,
    totalSigs = (result["total_sigs"] as? Number)?.toInt() ?: 0,
    network = currentNetwork,
    description = currentDescription,
)
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: thread inbox label into TransactionReview state"
```

---

### Task 3: Render description in TransactionReviewScreen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt`
- Test: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`
- Test fixture: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`

- [ ] **Step 1: Add test fixture for review state with description**

In `TestFixtures.kt`, add after `reviewStateWithOpReturn`:

```kotlin
val reviewStateWithDescription = AppState.TransactionReview(
    inputs = sampleInputs,
    outputs = sampleOutputs,
    fee = 2_100L,
    totalSent = 5_000_000L,
    status = "needs_sig",
    signers = sampleSigners,
    warnings = emptyList(),
    description = "Payment for server hosting — March 2026",
)
```

- [ ] **Step 2: Write the failing test — description shown**

In `ScreenRenderTest.kt`, add:

```kotlin
@Test
fun transactionReviewScreen_displaysDescription() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            TransactionReviewScreen(
                state = TestFixtures.reviewStateWithDescription,
                contacts = emptyList(),
                onSign = {},
                onCancel = {},
                onSaveContact = { _, _, _ -> },
            )
        }
    }
    composeTestRule.onNodeWithText("Payment for server hosting — March 2026")
        .assertIsDisplayed()
}
```

- [ ] **Step 3: Write the failing test — description hidden when null**

In `ScreenRenderTest.kt`, add:

```kotlin
@Test
fun transactionReviewScreen_hidesDescriptionWhenNull() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            TransactionReviewScreen(
                state = TestFixtures.reviewState,
                contacts = emptyList(),
                onSign = {},
                onCancel = {},
                onSaveContact = { _, _, _ -> },
            )
        }
    }
    // reviewState has description = null (default)
    // Verify the screen renders without crashing and shows core content
    composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest 2>&1 | tail -20`
Expected: `transactionReviewScreen_displaysDescription` FAILS (text not found), `transactionReviewScreen_hidesDescriptionWhenNull` PASSES (it's the existing behavior)

- [ ] **Step 5: Implement description rendering**

In `TransactionReviewScreen.kt`, add the import at the top:

```kotlin
import androidx.compose.ui.text.style.TextOverflow
```

Then after the title `Text` and `Spacer(16.dp)` (after line 40), add:

```kotlin
if (!state.description.isNullOrBlank()) {
    Text(
        state.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(8.dp))
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest 2>&1 | tail -20`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt \
       app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt \
       app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt
git commit -m "feat: show transaction description on review screen"
```
