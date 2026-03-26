# Electrum Nostr Plugin: Wider Dialog + Address Book

**Date:** 2026-03-26
**Status:** Approved

## Problem

The Electrum Nostr Signer plugin's "Send via Nostr" dialog is too narrow for npub strings (63 chars), causing them to be clipped. Users must manually paste npubs every time, with no way to save or recall previous recipients. The settings dialog is inaccessible (settings button doesn't appear in Electrum's plugin UI), making it dead code.

## Solution

Consolidate into a single, wider send dialog with an editable combo box and a global address book for saved contacts.

## Design

### Dialog Layout

The "Send via Nostr" dialog (triggered by the "Send via Nostr" button in the transaction dialog) becomes the only dialog. Top to bottom:

1. **Label:** "Send to signer:"
2. **Editable `QComboBox`** — dropdown lists saved contacts as `"Name (npub1abc...xyz)"`. The editable field accepts pasting a raw npub. Pre-selected to the wallet's last-used recipient.
3. **Button row:** "Save Contact" and "Delete Contact"
   - **Save Contact:** enabled when combo text is a valid npub not already in contacts. Opens `QInputDialog` prompting for a required name. Saves to global config, refreshes dropdown, selects new entry.
   - **Delete Contact:** enabled when a saved contact is selected (not raw typed text). Shows confirmation prompt, removes from config, refreshes dropdown, clears field.
4. **"Show relays" toggle** — collapsed by default (unchanged behavior)
5. **OK / Cancel buttons**

**Minimum dialog width:** 650px so a full npub is visible without scrolling.

### Data Model & Storage

**Global address book** stored in Electrum's config under key `nostr_signer_contacts`:

```python
# Read:  self.config.get("nostr_signer_contacts", {})
# Write: self.config.set_key("nostr_signer_contacts", contacts_dict)
# Persists to ~/.electrum/config as JSON, survives restarts.

{
    "npub1abc...": "Sasha's Trezor",
    "npub1def...": "Office signer"
}
```

**Defensive read:** `_get_contacts()` returns `{}` if the config value is missing, not a dict, or has non-string keys/values. Never crash on corrupted config.

**Duplicate names are allowed** — two different npubs may share the same label. The npub suffix in the combo box display (`"Name (npub1...)"`) disambiguates them.

**Last-used tracking:** each wallet continues to store `nostr_signer_recipient_npub` in `wallet.db`. Updated on each successful send via `wallet.save_db()`. Used to pre-select the combo box entry.

**Migration:** existing `nostr_signer_recipient_npub` values in wallet DBs are not auto-migrated to contacts (no name to assign). They appear as raw npubs in the editable field; the user can save them with a name.

### Interaction Flow

**Opening the dialog:**
1. Load contacts dict from `self.config`
2. Populate combo box with `"Name (npub1...)"` entries
3. Pre-select based on wallet's `WK_RECIPIENT_NPUB`: match to a contact if possible, otherwise show as raw text in the editable field

**Sending:**
1. User selects contact or pastes raw npub, clicks OK
2. Extract npub via `_extract_npub(combo_text)`: use `rfind("(npub1")` to locate the last parenthesized npub. If found, extract from that position to the closing `)`. Otherwise treat the entire stripped text as a raw npub. This handles names containing parentheses (e.g. `"My (test) signer (npub1abc...xyz)"`).
3. Validate: starts with `npub1`, decodes to 32 bytes
4. Save as last-used in wallet DB via `wallet.save_db()`
5. Encrypt and publish (unchanged logic)

**Saving a contact:**
1. User has a valid npub in the combo field (not already saved)
2. Clicks "Save Contact" → `QInputDialog` prompts for name (required, non-empty)
3. Stored in config dict, combo refreshed, new entry selected

**Deleting a contact:**
1. User selects a saved contact from dropdown
2. Clicks "Delete Contact" → confirmation: "Remove {name} from contacts?"
3. Removed from config dict, combo refreshed, field cleared

**Button enable/disable:**
- "Save Contact" — enabled when combo text contains a valid npub (`npub1...`) not already in the contacts dict
- "Delete Contact" — enabled when a saved contact is selected from the dropdown

### Code Changes

**Modified:** `nostr_signer/qt.py`
- Import line updated: add `QComboBox`, `QHBoxLayout`, `QInputDialog` to PyQt6 imports; remove `QLineEdit`
- `_confirm_send` → replaced by new `_show_send_dialog` method with combo box + address book buttons. Button row uses `QHBoxLayout`.
- `_do_send` → calls `_show_send_dialog` instead of separate settings + confirm flow
- `_show_settings` → deleted
- `settings_dialog` hook → deleted
- New helper: `_get_contacts()` / `_save_contacts()` for config access (defensive: returns `{}` on bad data)
- New helper: `_extract_npub(combo_text)` using `rfind("(npub1")` to parse npub from display format or raw text
- Combo box: `setCompleter(None)` to disable auto-completion (prevents interference with pasting raw npubs)
- Dialog gets `setMinimumWidth(650)`

**Unchanged:**
- `nostr_signer/nostr_signer.py` (standalone crypto)
- `nostr_signer/__init__.py`
- Keypair management (`_ensure_keypair`, `_get_privkey_hex`, `_get_pubkey_hex`)
- Publishing logic (`_publish`)
- Bech32 decode (`_npub_to_hex`)

### Removed

- `_show_settings` method
- `settings_dialog` hook
- `_confirm_send` method (replaced by `_show_send_dialog`)
