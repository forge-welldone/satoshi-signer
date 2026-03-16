# Hide Signing Debug Log Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide the signing screen debug log behind a "Show Log" / "Hide Log" toggle, collapsed by default.

**Architecture:** Single-file UI change — add a local `remember` state to `SigningScreen` that gates rendering of the debug log section. Update one existing test and add two new tests.

**Tech Stack:** Kotlin, Jetpack Compose, Compose UI Testing

---

## Chunk 1: Tests and Implementation

### Task 1: Update existing test to expect collapsed log

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt:121-139`

- [ ] **Step 1: Update `signingScreen_displaysLogAndCopyButton` to assert log is hidden by default**

Replace the existing test at line 121-139 with:

```kotlin
    @Test
    fun signingScreen_logHiddenByDefault() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(state.message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Show Log").assertIsDisplayed()
        composeTestRule.onNodeWithText("Debug Log:").assertDoesNotExist()
        composeTestRule.onNodeWithText("Copy").assertDoesNotExist()
        composeTestRule.onNodeWithText(state.log).assertDoesNotExist()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest`
Expected: FAIL — "Show Log" does not exist yet, and "Debug Log:" is currently displayed.

### Task 2: Add test for expanding the log

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` (add after the test from Task 1)

- [ ] **Step 1: Add `signingScreen_showLogRevealsDebugLog` test**

Add after the test from Task 1:

```kotlin
    @Test
    fun signingScreen_showLogRevealsDebugLog() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Show Log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hide Log").assertIsDisplayed()
        composeTestRule.onNodeWithText("Debug Log:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText(state.log).assertIsDisplayed()
    }
```

### Task 3: Add test for collapsing the log

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` (add after the test from Task 2)

- [ ] **Step 1: Add `signingScreen_hideLogCollapsesDebugLog` test**

Add after the test from Task 2:

```kotlin
    @Test
    fun signingScreen_hideLogCollapsesDebugLog() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Show Log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hide Log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Show Log").assertIsDisplayed()
        composeTestRule.onNodeWithText("Debug Log:").assertDoesNotExist()
        composeTestRule.onNodeWithText(state.log).assertDoesNotExist()
    }
```

- [ ] **Step 2: Commit test changes**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt
git commit -m "test: update signing screen tests for collapsible debug log"
```

### Task 4: Implement the toggle in SigningScreen

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt:105-129`

- [ ] **Step 1: Replace the `if (log.isNotBlank())` block (lines 105-129) with the toggle implementation**

Replace lines 105-129:

```kotlin
            if (log.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                var showLog by remember { mutableStateOf(false) }
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "Hide Log" else "Show Log", fontSize = 12.sp)
                }
                if (showLog) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Debug Log:", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("debug log", log))
                            Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                    Text(
                        log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
```

- [ ] **Step 2: Run all signing screen tests to verify they pass**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.ScreenRenderTest`
Expected: ALL PASS — including the existing `signingScreen_displaysProgress` (empty log) and `signingScreen_displaysCancelButton` tests.

- [ ] **Step 3: Commit implementation**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt
git commit -m "feat: hide signing debug log behind Show Log toggle"
```
