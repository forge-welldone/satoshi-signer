# Hide Signing Debug Log by Default

## Problem

The signing screen always displays a debug log (USB device info, connection steps, Python status messages) once any log content exists. This is noisy for normal use — users don't need to see `dumpDeviceInfo()` output or `Python: ButtonRequest` messages during routine signing.

## Solution

Hide the debug log section behind a "Show Log" toggle on the signing screen. The log is still collected in `AppState.Signing.log` — it's just not rendered by default.

## Design

### UI Change (SigningScreen.kt, lines 105-129)

Replace the always-visible debug log block with:

1. A local `var showLog by remember { mutableStateOf(false) }` — starts collapsed every signing session.
2. When `log.isNotBlank()`, render a `TextButton("Show Log")` / `TextButton("Hide Log")` toggle instead of immediately showing the log.
3. When `showLog == true`, render the existing debug log section (divider, "Debug Log:" label, copy button, monospace text).
4. When `showLog == false`, only the toggle button is visible.

### What stays the same

- Log accumulation in `AppState.Signing.log` — unchanged.
- Copy-to-clipboard — still available when expanded.
- Error screen, transaction review screen — unchanged.
- No ViewModel changes, no new state fields, no persistence.

## Testing

- Compose UI test: debug log text is not displayed by default when log content exists.
- Compose UI test: tapping "Show Log" reveals the log content.
- Compose UI test: tapping "Hide Log" hides it again.
