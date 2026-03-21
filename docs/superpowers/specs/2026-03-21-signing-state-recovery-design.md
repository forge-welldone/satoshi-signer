# Fix: Trezor Connect Mid-Signing Redirects to Home

## Problem

When signing is initiated without a Trezor connected, the app enters `AppState.Signing` and the `SigningOrchestrator` polls for the device. When the user connects the Trezor, Android fires a `USB_DEVICE_ATTACHED` intent and shows a device chooser dialog (because multiple apps match the Trezor USB filter). This backgrounds the app.

When the user selects "Satoshi Signer" from the chooser, Android may have killed the process while it was backgrounded. A fresh process starts, the ViewModel is recreated with `_state = AppState.Home`, and the inbox item remains stuck in `InboxStatus.SIGNING` — with no Sign button shown (the UI only shows Sign for `PENDING` and `FAILED`).

**Result:** The transaction is permanently stuck in "signing" status with no way to retry.

## Root Cause

Two independent failures combine:

1. **Process death during USB chooser** — Android can kill the backgrounded process. On restart, the ViewModel is fresh (`AppState.Home`), the signing coroutine is gone, and the PSBT bytes are lost. The inbox item's `SIGNING` status was persisted to Room but never reset.

2. **No recovery path for SIGNING items** — The UI only shows the Sign button for `PENDING` and `FAILED` statuses. `SIGNING` items show a "signing" chip but no action button, so the user cannot retry.

## Design

Three-part fix: startup recovery, UI recovery, and intent guard.

### Part 1: Startup Recovery

On app startup, reset any `SIGNING` items back to `PENDING`. If the app is starting and an item is in `SIGNING` status, the signing was interrupted — it's always safe to reset.

**InboxDao** — add a new query:

```kotlin
@Query("UPDATE inbox_items SET status = 'PENDING' WHERE status = 'SIGNING'")
suspend fun resetSigning()
```

**InboxRepository.cleanupAndSeedIds()** — call `resetSigning()` before the existing `deleteExpired()`:

```kotlin
suspend fun cleanupAndSeedIds(): Set<String> {
    dao.resetSigning()       // ← new
    dao.deleteExpired(...)
    return dao.getAllOnce().map { it.id }.toSet()
}
```

### Part 2: UI — Allow Re-Signing SIGNING Items

In `InboxSection.kt`, add `SIGNING` to the condition that shows the Sign button:

```kotlin
// Before:
if (item.status == InboxStatus.PENDING || item.status == InboxStatus.FAILED) {

// After:
if (item.status in listOf(InboxStatus.PENDING, InboxStatus.FAILED, InboxStatus.SIGNING)) {
```

Belt-and-suspenders: even if the startup reset didn't run (e.g., Activity recreated but process alive), the user can always tap Sign.

### Part 3: Suppress USB_DEVICE_ATTACHED in onNewIntent

When the process survives backgrounding and `onNewIntent()` fires with a `USB_DEVICE_ATTACHED` intent while signing is active, suppress it. The `SigningOrchestrator` polling loop already handles device discovery.

Requires adding `import android.hardware.usb.UsbManager` to `MainActivity.kt`.

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
        val currentState = viewModel.state.value
        if (currentState is AppState.Signing || currentState is AppState.TransactionReview) {
            return
        }
    }
    setIntent(intent)
}
```

Guards both `Signing` and `TransactionReview` states: a user reviewing a PSBT before signing could also be disrupted by the intent. The `setIntent()` call is harmless today (nothing reads the stored USB intent), but suppressing it during active flows prevents subtle issues if `onNewIntent` logic grows in the future.

## Files Changed

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt` | Add `resetSigning()` query |
| `app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt` | Call `resetSigning()` in `cleanupAndSeedIds()` |
| `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt` | Show Sign button for SIGNING items |
| `app/src/main/kotlin/com/remotesigner/MainActivity.kt` | Guard `onNewIntent()` against USB intent during signing |

## Testing

- **JVM unit test (InboxRepository):** Verify `cleanupAndSeedIds()` calls `dao.resetSigning()` and that SIGNING items become PENDING after the call. This is the most critical part of the fix.
- **JVM unit test (ViewModel):** Verify that `signInboxItem()` on a SIGNING-status item transitions to `TransactionReview` the same as a PENDING item. The existing `testInboxItem()` helper accepts a `status` parameter — add a variant with `status = InboxStatus.SIGNING`.
- **Python tests:** No changes needed (Python layer unaffected)
- **Manual test (onNewIntent guard):** Start signing without Trezor → connect Trezor → select app from chooser → verify signing continues or item is recoverable. The onNewIntent guard is Activity-level and covered by manual testing only.
