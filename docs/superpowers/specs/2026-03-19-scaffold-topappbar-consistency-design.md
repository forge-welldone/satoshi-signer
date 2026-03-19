# Scaffold + TopAppBar Consistency Refactor

## Problem

Most screens use `Surface + Column + padding(16.dp)` with no system inset handling. Only `ContactsScreen` uses the standard Material3 `Scaffold + TopAppBar` pattern. This causes:

1. View titles overlap with the Android status bar (no top padding for system insets)
2. Back gesture (predictive back) doesn't work on most screens
3. Inconsistent navigation — some screens have body buttons ("Cancel", "Back to Home"), others have a TopAppBar back arrow

## Design

Refactor all screens to use `Scaffold` + `TopAppBar` + `BackHandler`, matching the existing `ContactsScreen` pattern.

### Per-Screen Changes

| Screen | TopAppBar Title | Back Arrow | Body Removals |
|--------|----------------|------------|---------------|
| HomeScreen | "Satoshi Signer" | No (root screen) | `headlineLarge` title text, subtitle text stays |
| TransactionReviewScreen | "Transaction Details" | Yes -> `onCancel` | Title text, "Cancel" `OutlinedButton` |
| SigningScreen | "Signing" | Yes -> `onCancel` | Dynamic message stays in body as regular text. **Keep** body "Cancel" button (more discoverable during active signing) |
| ResultScreen | "Transaction Signed" or "Signature Added" (conditional on `state.isComplete`) | Yes -> `onHome` | Title text, "Back to Home" `OutlinedButton` |
| ErrorScreen | "Error" | Yes -> `onHome` | Title text, "Back to Home" `Button`. "Copy Error" button stays but no longer needs a `Row` wrapper |
| EncryptPassphraseScreen | "Encrypt Passphrase" | Yes -> `onBack` | Title text. Subtitle/description text stays in body |
| ContactsScreen | No change | No change | No change |

### What Stays the Same

- All action buttons remain (Sign with Trezor, Broadcast, Export PSBT, Save PSBT, Copy Error, Encrypt, etc.)
- Screen content and layout unchanged beyond removing redundant titles and back buttons
- State machine navigation architecture unchanged (no NavController)
- `ContactsScreen` untouched — it already follows the target pattern
- HomeScreen preserves its centered layout (`horizontalAlignment = CenterHorizontally`)
- SigningScreen preserves its `DisposableEffect` for `FLAG_KEEP_SCREEN_ON`

### Pattern

Each screen follows this structure (matching `ContactsScreen`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExampleScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Title") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // screen content
        }
    }
}
```

`HomeScreen` omits `BackHandler` and the `navigationIcon` parameter since it's the root screen.

### Padding Notes

- After Scaffold padding, body content uses `.padding(horizontal = 16.dp)` consistently
- HomeScreen currently uses `padding(32.dp)` all-around — this changes to match other screens (Scaffold handles top inset, `horizontal = 16.dp` for sides)
- Vertical spacing between body elements preserved via existing `Spacer`s

## Testing

Specific test assertions that need updating in `ScreenRenderTest.kt`:

- `transactionReviewScreen_displaysDetails`: Remove assertion for "Cancel" button
- `signingScreen_passphraseDialog_onDeviceAvailable`: "Cancel" `assertCountEquals(2)` stays correct (one in dialog, one in body — body cancel is kept)
- `errorScreen_displaysMessageAndButtons`: Remove assertion for "Back to Home" button
- `encryptPassphraseScreen_rendersInitialState`: Remove assertion for "Back" button
- `resultScreen_completedTransaction`: "Back to Home" assertion needs removal

Title text assertions (e.g. "Transaction Details", "Satoshi Signer") should still pass since TopAppBar renders the same text nodes.
