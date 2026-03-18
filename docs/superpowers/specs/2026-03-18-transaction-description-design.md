# Transaction Description on Review Screen

**Date:** 2026-03-18
**Status:** Draft

## Problem

When a PSBT arrives via Nostr inbox, the sending wallet (Electrum) includes a description/label in the Nostr payload. This label is shown on the home screen inbox cards but is lost when the user taps to review the transaction — `signInboxItem()` calls `loadPsbt(item.psbtBytes)` which only parses the PSBT bytes, discarding the inbox metadata.

## Design

Thread the inbox item's `label` field through to `AppState.TransactionReview` and display it below the "Transaction Details" heading. When no description is available (file-picked PSBTs, clipboard), the field is null and nothing is shown. Empty-string labels from the inbox entity are treated the same as null (hidden).

### Data flow

```
InboxItemEntity.label
  → signInboxItem() stores label before calling loadPsbt()
  → parsePsbt() reads stored label
  → AppState.TransactionReview(description = label)
  → TransactionReviewScreen renders it below the title
```

### Changes

1. **`AppState.TransactionReview`** — Add `val description: String? = null`

2. **`SignerViewModel`** — Store the inbox label so `parsePsbt()` can include it:
   - Add `private var currentDescription: String? = null`
   - In `signInboxItem()`: set `currentDescription = item.label` before `loadPsbt()`
   - In `parsePsbt()`: pass `currentDescription` into the `TransactionReview` state
   - In `loadPsbt(Uri)` (file picker path): set `currentDescription = null`. Do NOT clear in `loadPsbt(ByteArray)` — `signInboxItem()` sets description then calls that method
   - `currentDescription` is intentionally preserved across cancel-and-re-parse cycles (e.g. `cancelSigning()` re-parses the same PSBT) — the inbox label should survive cancellation

3. **`TransactionReviewScreen`** — After the title and `Spacer(16.dp)`, before warnings:
   - When `state.description` is non-null and non-blank: render the text in `bodyMedium` typography, `onSurfaceVariant` color
   - `maxLines = 3`, `overflow = TextOverflow.Ellipsis` to guard against excessively long labels
   - Followed by `Spacer(8.dp)`

### Out of scope

- `openInboxResult()` navigates directly to `AppState.Result` — description is not relevant there
- No changes to Python PSBT parser
- No changes to `InboxItemEntity` or Room schema
- No changes to `NostrReceiver`
- No new fields on file-picked or clipboard PSBTs

### Tests

- JVM unit test: `signInboxItem()` produces `TransactionReview` with correct `description`; file-picker `loadPsbt()` produces `description = null`
- Compose UI test: `TransactionReviewScreen` renders description text when non-null, hides it when null
