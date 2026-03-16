# Hide Signing Debug Log by Default

## Problem

The signing screen always displays a debug log (USB device info, connection steps, Python status messages) once any log content exists. This is noisy for normal use — users don't need to see `dumpDeviceInfo()` output or `Python: ButtonRequest` messages during routine signing.

## Solution

Hide the debug log section behind a "Show Log" toggle on the signing screen. The log is still collected in `AppState.Signing.log` — it's just not rendered by default.

## Design

### UI Change (SigningScreen.kt, `if (log.isNotBlank())` block)

Replace the always-visible debug log block with:

1. A local `var showLog by remember { mutableStateOf(false) }` — starts collapsed every signing session.
2. When `log.isNotBlank()`, render a `TextButton("Show Log")` / `TextButton("Hide Log")` toggle below the Cancel button (same 24dp spacer position as the current log section). No divider when collapsed — just the text button.
3. When `showLog == true`, render the existing debug log section below the toggle (divider, "Debug Log:" label, copy button, monospace text).
4. When `showLog == false`, only the toggle button is visible.
5. When `log.isBlank()`, neither the toggle nor the log section appears (unchanged behavior).

### What stays the same

- Log accumulation in `AppState.Signing.log` — unchanged.
- Copy-to-clipboard — still available when expanded.
- Error screen, transaction review screen — unchanged.
- No ViewModel changes, no new state fields, no persistence.

## Testing

Update existing `signingScreen_displaysLogAndCopyButton` test — it currently asserts the log is immediately visible, which will break. Replace it with:

- Test: when log content exists, "Show Log" button is visible but debug log text and "Copy" button are not displayed.
- Test: tapping "Show Log" reveals the debug log text, "Copy" button, and changes toggle to "Hide Log".
- Test: tapping "Hide Log" hides the log content again.
- Preserve: existing `signingScreen_displaysProgress` test (empty log case) should continue to pass unchanged.
