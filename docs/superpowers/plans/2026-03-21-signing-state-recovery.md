# Signing State Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bug where connecting a Trezor mid-signing backgrounds the app and leaves inbox items permanently stuck in SIGNING status.

**Architecture:** Three-part fix — (1) DAO query + repository call to reset SIGNING→PENDING on startup, (2) UI change to show Sign button for SIGNING items, (3) onNewIntent guard to suppress USB_DEVICE_ATTACHED during active signing. TDD throughout.

**Tech Stack:** Kotlin, Room, Jetpack Compose, mockk, JUnit 4

**Spec:** `docs/superpowers/specs/2026-03-21-signing-state-recovery-design.md`

---

### Task 1: Startup Recovery — DAO and Repository

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt:48` (add query after `deleteExpired`)
- Modify: `app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt:16-23` (call `resetSigning()` in `cleanupAndSeedIds`)
- Create: `app/src/test/kotlin/com/remotesigner/data/InboxRepositoryTest.kt`

- [ ] **Step 1: Write the failing test for `resetSigning` in repository**

Create `app/src/test/kotlin/com/remotesigner/data/InboxRepositoryTest.kt`:

```kotlin
package com.remotesigner.data

import com.remotesigner.bridge.PythonBridgeInterface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class InboxRepositoryTest {

    private val dao: InboxDao = mockk(relaxed = true)
    private val pythonBridge: PythonBridgeInterface = mockk()
    private val repo = InboxRepository(dao, pythonBridge)

    @Test
    fun `cleanupAndSeedIds resets signing items before deleting expired`() = runBlocking {
        coEvery { dao.getAllOnce() } returns emptyList()

        repo.cleanupAndSeedIds()

        coVerifyOrder {
            dao.resetSigning()
            dao.deleteExpired(any(), any())
        }
    }

    @Test
    fun `cleanupAndSeedIds returns all item ids after cleanup`() = runBlocking {
        val items = listOf(
            InboxItemEntity(
                id = "event1",
                psbtBytes = byteArrayOf(1),
                label = "test",
                amount = "",
                senderNpub = "npub1test",
                receivedAt = 1000L,
                status = InboxStatus.PENDING,
            ),
        )
        coEvery { dao.getAllOnce() } returns items

        val ids = repo.cleanupAndSeedIds()

        assert(ids == setOf("event1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.data.InboxRepositoryTest" 2>&1 | tail -20`

Expected: FAIL — `dao.resetSigning()` does not exist yet.

- [ ] **Step 3: Add `resetSigning()` to InboxDao**

In `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt`, add after the `deleteExpired` query (after line 48):

```kotlin
@Query("UPDATE inbox_items SET status = 'PENDING' WHERE status = 'SIGNING'")
suspend fun resetSigning()
```

- [ ] **Step 4: Call `resetSigning()` in `InboxRepository.cleanupAndSeedIds()`**

In `app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt`, modify `cleanupAndSeedIds()` to call `resetSigning()` before `deleteExpired()`:

```kotlin
suspend fun cleanupAndSeedIds(): Set<String> {
    inboxDao.resetSigning()
    val now = System.currentTimeMillis() / 1000
    inboxDao.deleteExpired(
        pendingCutoff = now - 86_400,
        signedCutoff = now - 86_400 * 7,
    )
    return inboxDao.getAllOnce().map { it.id }.toSet()
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.data.InboxRepositoryTest" 2>&1 | tail -20`

Expected: PASS — both tests green.

- [ ] **Step 6: Run full test suite to check for regressions**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`

Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/data/InboxDao.kt \
       app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt \
       app/src/test/kotlin/com/remotesigner/data/InboxRepositoryTest.kt
git commit -m "fix: reset stuck SIGNING inbox items to PENDING on startup

Add InboxDao.resetSigning() query and call it in
InboxRepository.cleanupAndSeedIds() before deleteExpired().
If the app starts and an item is in SIGNING status, the previous
signing was interrupted (process death, crash) — safe to reset.

Generated with [Claude Code](https://claude.ai/code)
via [Happy](https://happy.engineering)

Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Happy <yesreply@happy.engineering>"
```

---

### Task 2: ViewModel Test — signInboxItem Accepts SIGNING Items

**Files:**
- Modify: `app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt`

- [ ] **Step 1: Write the test for signing a SIGNING-status inbox item**

Add this test to `SignerViewModelTest.kt` after the existing `signInboxItem sets status to SIGNING and loads PSBT` test (after line 537):

```kotlin
@Test
fun `signInboxItem works for item already in SIGNING status`() = runBlocking {
    val item = testInboxItem(status = InboxStatus.SIGNING)
    coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
    coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

    vm.signInboxItem(item)
    awaitState { it is AppState.TransactionReview }

    val state = vm.state.value as AppState.TransactionReview
    assertEquals("test", state.network)
    coVerify { inboxRepo.updateStatus("event1", InboxStatus.SIGNING) }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.viewmodel.SignerViewModelTest.signInboxItem works for item already in SIGNING status" 2>&1 | tail -20`

Expected: PASS — `signInboxItem()` already works for any status since it doesn't check the incoming status. This test documents that behavior.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/com/remotesigner/viewmodel/SignerViewModelTest.kt
git commit -m "test: verify signInboxItem works for SIGNING-status items

Documents that signInboxItem() accepts items in any status,
which is needed for the re-signing recovery flow.

Generated with [Claude Code](https://claude.ai/code)
via [Happy](https://happy.engineering)

Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Happy <yesreply@happy.engineering>"
```

---

### Task 3: UI — Show Sign Button for SIGNING Items

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt:108`

- [ ] **Step 1: Update the Sign button condition**

In `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt`, change line 108 from:

```kotlin
if (item.status == InboxStatus.PENDING || item.status == InboxStatus.FAILED) {
```

to:

```kotlin
if (item.status in listOf(InboxStatus.PENDING, InboxStatus.FAILED, InboxStatus.SIGNING)) {
```

- [ ] **Step 2: Run full test suite to check for regressions**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`

Expected: All tests pass. (InboxSection is a Composable — no JVM unit tests for UI, verified manually.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt
git commit -m "fix: show Sign button for inbox items stuck in SIGNING status

Previously only PENDING and FAILED items showed the Sign button.
SIGNING items (interrupted by process death) had no action button,
leaving them permanently stuck.

Generated with [Claude Code](https://claude.ai/code)
via [Happy](https://happy.engineering)

Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Happy <yesreply@happy.engineering>"
```

---

### Task 4: onNewIntent Guard — Suppress USB Intent During Signing

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt:2-3` (add import)
- Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt:62-65` (guard onNewIntent)

- [ ] **Step 1: Add UsbManager import**

In `app/src/main/kotlin/com/remotesigner/MainActivity.kt`, add this import after the existing imports (after line 2):

```kotlin
import android.hardware.usb.UsbManager
```

- [ ] **Step 2: Update onNewIntent to guard against USB intent during signing**

In `app/src/main/kotlin/com/remotesigner/MainActivity.kt`, replace the existing `onNewIntent` (lines 62-65):

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
}
```

with:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Suppress USB_DEVICE_ATTACHED when signing or reviewing a transaction —
    // the SigningOrchestrator polling loop already handles device discovery,
    // and processing this intent could disrupt the active flow.
    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
        val currentState = viewModel.state.value
        if (currentState is AppState.Signing || currentState is AppState.TransactionReview) {
            return
        }
    }
    setIntent(intent)
}
```

Also add the missing import for `AppState`:

```kotlin
import com.remotesigner.viewmodel.AppState
```

- [ ] **Step 3: Run full test suite to check for regressions**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`

Expected: All tests pass. (onNewIntent is Activity-level — covered by manual testing only.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/MainActivity.kt
git commit -m "fix: suppress USB_DEVICE_ATTACHED intent during active signing

When the process survives backgrounding (USB chooser dialog),
onNewIntent fires with USB_DEVICE_ATTACHED. Guard against this
when in Signing or TransactionReview state — the polling loop
already handles device discovery.

Generated with [Claude Code](https://claude.ai/code)
via [Happy](https://happy.engineering)

Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Happy <yesreply@happy.engineering>"
```

---

### Task 5: Update Docs and Remove TODO Item

**Files:**
- Modify: `TODO.md:3` (remove the fixed bug)
- Modify: `CLAUDE.md` (no architecture changes needed — this fix doesn't change architecture)

- [ ] **Step 1: Remove the fixed bug from TODO.md**

In `TODO.md`, remove the first item:

```
- BUG: 1) Start signing with no trezor 2) Connect Trezor 3) Redirected to Home Screen 4) Tx is in signing state and cannot be signed
```

- [ ] **Step 2: Commit**

```bash
git add TODO.md
git commit -m "docs: mark signing state recovery bug as fixed

Generated with [Claude Code](https://claude.ai/code)
via [Happy](https://happy.engineering)

Co-Authored-By: Claude <noreply@anthropic.com>
Co-Authored-By: Happy <yesreply@happy.engineering>"
```
