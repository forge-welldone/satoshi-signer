# StateFlow Race Conditions Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate three read-modify-write race conditions in `SignerViewModel` by replacing them with `_state.update { }` atomic API calls with type guards.

**Architecture:** Each race condition site (`reEnrichSigners`, `broadcast`, progress callbacks) gets converted from `val s = _state.value; ...; _state.value = s.copy(...)` to `_state.update { state -> if (state is ExpectedType) state.copy(...) else state }`. No new dependencies. Existing tests must pass unchanged.

**Tech Stack:** Kotlin, MutableStateFlow, kotlinx-coroutines-test (mockk for new tests)

**Spec:** `docs/superpowers/specs/2026-03-20-stateflow-race-conditions-design.md`

---

### Task 1: Write failing tests for broadcast race condition

**Files:**
- Modify: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt`

- [ ] **Step 1: Write test — broadcast completion preserves Home when user navigated away**

Add a section header `// ===== Race Condition Regression =====` after the existing broadcast tests (after line 371), then the test:

```kotlin
@Test
fun `broadcast completion preserves Home state when user navigated away`() = runBlocking {
    loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
    awaitState { it is AppState.Result }

    // Make broadcaster suspend so we can interleave goHome()
    val broadcastStarted = java.util.concurrent.CountDownLatch(1)
    val broadcastContinue = java.util.concurrent.CountDownLatch(1)
    every { broadcaster.broadcast("0200abcd", "testnet4") } answers {
        broadcastStarted.countDown()
        broadcastContinue.await()
        BroadcastResult(status = "ok", txid = "tx123abc")
    }

    vm.broadcast("testnet4")
    broadcastStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)

    // User navigates away while broadcast is in flight
    vm.goHome()
    assertEquals(AppState.Home, vm.state.value)

    // Broadcast completes — should NOT overwrite Home
    broadcastContinue.countDown()

    // Wait for IO continuation to resume and process stale write
    Thread.sleep(100)
    assertEquals(AppState.Home, vm.state.value)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest.broadcast completion preserves Home state when user navigated away"`

Expected: FAIL — stale `state.copy(...)` overwrites Home with Result.

---

### Task 2: Write failing tests for reEnrichSigners race condition

**Files:**
- Modify: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt`

- [ ] **Step 1: Write test — reEnrichSigners preserves Home when user left TransactionReview**

Add in the `// ===== Race Condition Regression =====` section (after the broadcast race test):

```kotlin
@Test
fun `reEnrichSigners preserves Home state when user left TransactionReview`() = runBlocking {
    loadTestPsbt()

    // Make enrichSigners suspend so we can interleave goHome()
    val enrichStarted = java.util.concurrent.CountDownLatch(1)
    val enrichContinue = java.util.concurrent.CountDownLatch(1)
    coEvery { contactRepo.enrichSigners(any()) } coAnswers {
        enrichStarted.countDown()
        enrichContinue.await()
        firstArg()
    }

    vm.saveContact("Alice", "aabbccdd", null)
    enrichStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)

    // User navigates away while enrichment is in flight
    vm.goHome()
    assertEquals(AppState.Home, vm.state.value)

    // Enrichment completes — should NOT overwrite Home
    enrichContinue.countDown()

    // Wait for IO continuation to resume and process stale write
    Thread.sleep(100)
    assertEquals(AppState.Home, vm.state.value)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest.reEnrichSigners preserves Home state when user left TransactionReview"`

Expected: FAIL — stale `currentState.copy(signers = enriched)` overwrites Home with TransactionReview.

---

### Task 3: Write failing test for progress callback race condition

**Files:**
- Modify: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt`

- [ ] **Step 1: Write test — progress callback preserves Result state when signing completed**

Add in the `// ===== Race Condition Regression =====` section (after the reEnrichSigners race test):

```kotlin
@Test
fun `progress callback preserves Result state when signing already completed`() = runBlocking {
    loadTestPsbt()

    // Capture the onProgress callback
    var capturedOnProgress: ((String) -> Unit)? = null
    coEvery { orchestrator.signWithTrezor(any(), any(), any()) } coAnswers {
        capturedOnProgress = thirdArg()
        SigningResult.Complete(rawHex = "0200abcd", network = "test")
    }

    vm.signWithTrezor()
    awaitState { it is AppState.Result }

    // Simulate a late progress callback arriving after signing completed
    capturedOnProgress?.invoke("Late message")

    // State should still be Result, not Signing
    assertTrue(vm.state.value is AppState.Result)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest.progress callback preserves Result state when signing already completed"`

Expected: FAIL — callback creates `AppState.Signing("Late message", ...)` overwriting Result.

---

### Task 4: Fix all three race conditions in SignerViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add the import**

Add `import kotlinx.coroutines.flow.update` to the imports (after line 30, with the other flow imports):

```kotlin
import kotlinx.coroutines.flow.update
```

- [ ] **Step 2: Fix `reEnrichSigners()` (lines 431-437)**

Replace:

```kotlin
private suspend fun reEnrichSigners() {
    val currentState = _state.value
    if (currentState is AppState.TransactionReview) {
        val enriched = contactRepository.enrichSigners(currentState.signers)
        _state.value = currentState.copy(signers = enriched)
    }
}
```

With:

```kotlin
private suspend fun reEnrichSigners() {
    val currentSigners = (_state.value as? AppState.TransactionReview)?.signers ?: return
    val enriched = contactRepository.enrichSigners(currentSigners)
    _state.update { state ->
        if (state is AppState.TransactionReview) state.copy(signers = enriched)
        else state
    }
}
```

- [ ] **Step 3: Fix `broadcast()` (lines 332-365)**

Replace:

```kotlin
fun broadcast(targetNetwork: String) {
    val state = _state.value
    if (state !is AppState.Result || state.rawHex == null) return

    _state.value = state.copy(broadcastStatus = "Broadcasting...")

    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                broadcaster.broadcast(state.rawHex, targetNetwork)
            }

            if (result.status == "ok") {
                _state.value = state.copy(
                    txid = result.txid,
                    broadcastStatus = "Broadcast successful",
                    network = targetNetwork,
                )
                val inboxId = currentSigningInboxId
                if (inboxId != null && result.txid != null) {
                    inboxRepository.updateBroadcast(inboxId, InboxStatus.BROADCAST, result.txid, targetNetwork)
                }
            } else {
                _state.value = state.copy(
                    broadcastStatus = "Broadcast failed: ${result.message}",
                )
            }
        } catch (e: Exception) {
            _state.value = state.copy(
                broadcastStatus = "Broadcast failed: ${e.message}",
            )
        }
    }
}
```

With:

```kotlin
fun broadcast(targetNetwork: String) {
    val rawHex = (_state.value as? AppState.Result)?.rawHex ?: return

    _state.update { state ->
        if (state is AppState.Result) state.copy(broadcastStatus = "Broadcasting...")
        else state
    }

    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                broadcaster.broadcast(rawHex, targetNetwork)
            }

            if (result.status == "ok") {
                _state.update { state ->
                    if (state is AppState.Result) state.copy(
                        txid = result.txid,
                        broadcastStatus = "Broadcast successful",
                        network = targetNetwork,
                    ) else state
                }
                val inboxId = currentSigningInboxId
                if (inboxId != null && result.txid != null) {
                    inboxRepository.updateBroadcast(inboxId, InboxStatus.BROADCAST, result.txid, targetNetwork)
                }
            } else {
                _state.update { state ->
                    if (state is AppState.Result) state.copy(
                        broadcastStatus = "Broadcast failed: ${result.message}",
                    ) else state
                }
            }
        } catch (e: Exception) {
            _state.update { state ->
                if (state is AppState.Result) state.copy(
                    broadcastStatus = "Broadcast failed: ${e.message}",
                ) else state
            }
        }
    }
}
```

- [ ] **Step 4: Fix progress callbacks in `signWithTrezor()` (lines 255-257)**

Replace:

```kotlin
onProgress = { msg ->
    val current = (_state.value as? AppState.Signing)?.log ?: ""
    _state.value = AppState.Signing(msg, log = current + msg + "\n")
},
```

With:

```kotlin
onProgress = { msg ->
    _state.update { current ->
        if (current is AppState.Signing) current.copy(message = msg, log = current.log + msg + "\n")
        else current
    }
},
```

- [ ] **Step 5: Fix progress callbacks in `signWithBridge()` (lines 272-274)**

Replace:

```kotlin
onProgress = { msg ->
    val current = (_state.value as? AppState.Signing)?.log ?: ""
    _state.value = AppState.Signing(msg, log = current + msg + "\n")
},
```

With:

```kotlin
onProgress = { msg ->
    _state.update { current ->
        if (current is AppState.Signing) current.copy(message = msg, log = current.log + msg + "\n")
        else current
    }
},
```

- [ ] **Step 6: Run all new tests**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest"`

Expected: All tests PASS (new race condition tests + all existing tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
       app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt
git commit -m "fix: use atomic _state.update {} for StateFlow race conditions (#19)

Replace read-modify-write patterns with MutableStateFlow.update {} in
reEnrichSigners(), broadcast(), and progress callbacks. Each update
lambda includes a type guard so it becomes a no-op if the state type
changed concurrently. Adds 3 regression tests."
```

---

### Task 5: Update refactoring-todos.md and docs

**Files:**
- Modify: `docs/refactoring-todos.md`
- Modify: `CLAUDE.md` (if any architecture text needs updating)

- [ ] **Step 1: Mark #19 as fixed in refactoring-todos.md**

Replace item 19 header and content to mark it as fixed (same pattern as other fixed items).

- [ ] **Step 2: Check CLAUDE.md for any references that need updating**

The spec notes that CLAUDE.md already documents `_state` as a `StateFlow<AppState>`. No architecture changes needed — this is a pure implementation fix, not an architectural change. Verify and skip if no updates needed.

- [ ] **Step 3: Commit docs**

```bash
git add docs/refactoring-todos.md
git commit -m "docs: mark #19 StateFlow race conditions as fixed"
```
