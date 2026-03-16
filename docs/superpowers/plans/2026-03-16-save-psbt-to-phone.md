# Save PSBT to Phone — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Save to Phone" button on the partial-sign result screen so users can save the updated PSBT to device storage via the system file picker.

**Architecture:** `ResultScreen` gets a new `onSavePsbt` callback and button. `AppRoot` registers a `CreateDocument` activity result launcher and wires it to write bytes to the user-chosen URI. No ViewModel changes.

**Tech Stack:** Kotlin, Jetpack Compose, `ActivityResultContracts.CreateDocument`

---

## File Map

- **Modify:** `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt` — add `onSavePsbt` param, rename share button, add save button
- **Modify:** `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` — register `CreateDocument` launcher, wire `onSavePsbt`
- **Modify:** `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` — add `onSavePsbt` param, update text assertion
- **Modify:** `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt` — add `onSavePsbt` param

---

### Task 1: Add `onSavePsbt` callback and button to ResultScreen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt:16-20` (function signature)
- Modify: `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt:70-83` (partial-sign UI branch)

- [ ] **Step 1: Update `ResultScreen` function signature**

Add `onSavePsbt` parameter:

```kotlin
@Composable
fun ResultScreen(
    state: AppState.Result,
    onBroadcast: () -> Unit,
    onExportPsbt: (ByteArray) -> Unit,
    onSavePsbt: (ByteArray) -> Unit,
    onHome: () -> Unit,
)
```

- [ ] **Step 2: Rename share button and add save button**

Replace the partial-sign branch (lines 70–83) with:

```kotlin
            } else {
                Text("Signature Added", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("The transaction needs more signatures before it can be broadcast.")
                Spacer(modifier = Modifier.height(16.dp))

                state.updatedPsbt?.let { psbt ->
                    Button(
                        onClick = { onExportPsbt(psbt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share Updated PSBT")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onSavePsbt(psbt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save to Phone")
                    }
                }
            }
```

- [ ] **Step 3: Verify build compiles (expect test failures)**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -20`
Expected: compilation errors in `ScreenRenderTest.kt`, `NavigationTest.kt`, and `AppNavigation.kt` due to missing `onSavePsbt` argument.

---

### Task 2: Wire `CreateDocument` launcher in AppNavigation

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`

- [ ] **Step 1: Add imports**

Add to the imports at the top of `AppNavigation.kt`:

```kotlin
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
```

- [ ] **Step 2: Register launcher and state at the top of `AppRoot`**

Add before the `when (val s = state)` block (after `val context = ...` line):

```kotlin
    var pendingSavePsbt by remember { mutableStateOf<ByteArray?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingSavePsbt ?: return@rememberLauncherForActivityResult)
                } ?: throw IllegalStateException("Could not open output stream")
                Toast.makeText(context, "PSBT saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingSavePsbt = null
    }
```

- [ ] **Step 3: Pass `onSavePsbt` to `ResultScreen`**

Update the `is AppState.Result` branch to include the new callback:

```kotlin
        is AppState.Result -> ResultScreen(
            state = s,
            onBroadcast = { viewModel.broadcast() },
            onExportPsbt = { psbt ->
                val file = File(context.cacheDir, "signed.psbt")
                file.writeBytes(psbt)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export PSBT"))
            },
            onSavePsbt = { psbt ->
                pendingSavePsbt = psbt
                saveLauncher.launch("partially-signed.psbt")
            },
            onHome = { viewModel.goHome() },
        )
```

- [ ] **Step 4: Verify build compiles (expect test failures only)**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -20`
Expected: compilation errors only in test files (`ScreenRenderTest.kt`, `NavigationTest.kt`) due to missing `onSavePsbt`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: add Save to Phone button for partially-signed PSBTs"
```

---

### Task 3: Fix tests

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt:54-82`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt:41-45`

- [ ] **Step 1: Update `ScreenRenderTest`**

In `resultScreen_completedTransaction` (line 60), add `onSavePsbt = {},`:

```kotlin
                ResultScreen(
                    state = TestFixtures.resultComplete,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onSavePsbt = {},
                    onHome = {},
                )
```

In `resultScreen_partialSignature` (line 75), add `onSavePsbt = {},` and update the text assertion:

```kotlin
                ResultScreen(
                    state = TestFixtures.resultPartial,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onSavePsbt = {},
                    onHome = {},
                )
```

Update the assertion on line 81:

```kotlin
        composeTestRule.onNodeWithText("Share Updated PSBT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save to Phone").assertIsDisplayed()
```

- [ ] **Step 2: Update `NavigationTest`**

In `stateChange_showsCorrectScreen` (line 44), add `onSavePsbt = {},`:

```kotlin
                    is AppState.Result -> ResultScreen(
                        state = current,
                        onBroadcast = {},
                        onExportPsbt = {},
                        onSavePsbt = {},
                        onHome = {},
                    )
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt
git commit -m "test: update ScreenRenderTest and NavigationTest for Save to Phone button"
```
