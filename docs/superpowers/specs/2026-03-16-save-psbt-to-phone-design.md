# Save PSBT to Phone

**Date:** 2026-03-16

## Problem

On the "Signature Added" result screen (partial multisig sign), the only way to export the updated PSBT is via the Android share sheet. Users also need the ability to save the file directly to phone storage.

## Design

Add a "Save to Phone" button on the partial-sign result screen, alongside the existing share button.

### ResultScreen changes

- Add new callback: `onSavePsbt: (ByteArray) -> Unit`
- Rename existing "Export Updated PSBT" button to "Share Updated PSBT"
- Add "Save to Phone" `OutlinedButton` below it
- Both buttons are only visible when `state.updatedPsbt` is non-null (existing guard)

### AppNavigation changes

- Register an `ActivityResultContracts.CreateDocument("application/octet-stream")` launcher
- Store pending PSBT bytes in a `remember` variable so the result callback can access them
- On launcher result, write bytes to the returned URI via `contentResolver.openOutputStream`
- Default suggested filename: `signed.psbt`

### No ViewModel changes

The `updatedPsbt: ByteArray?` field in `AppState.Result` already holds the data needed.

### Test updates

- Update `NavigationTest` and `ScreenRenderTest` to pass the new `onSavePsbt` parameter
