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
| HomeScreen | "Satoshi Signer" | No (root screen) | `headlineLarge` title text |
| TransactionReviewScreen | "Transaction Details" | Yes -> `onCancel` | Title text, "Cancel" `OutlinedButton` |
| SigningScreen | "Signing" | Yes -> `onCancel` | Dynamic message as title (stays in body as regular text) , "Cancel" `OutlinedButton` |
| ResultScreen | "Transaction Signed" or "Signature Added" | Yes -> `onHome` | Title text, "Back to Home" `OutlinedButton` |
| ErrorScreen | "Error" | Yes -> `onHome` | Title text, "Back to Home" `Button` |
| EncryptPassphraseScreen | "Encrypt Passphrase" | Yes -> `onBack` | Title text, "Back" `OutlinedButton` |
| ContactsScreen | No change | No change | No change |

### What Stays the Same

- All action buttons remain (Sign with Trezor, Broadcast, Export PSBT, Save PSBT, Copy Error, Encrypt, etc.)
- Screen content and layout unchanged beyond removing redundant titles and back buttons
- State machine navigation architecture unchanged (no NavController)
- `ContactsScreen` untouched — it already follows the target pattern

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

## Testing

Update existing Android UI tests to account for:
- TopAppBar presence (title assertions may change)
- Removed buttons (tests clicking "Cancel"/"Back to Home" need updating)
- Back gesture behavior (optional — verify `BackHandler` works)
