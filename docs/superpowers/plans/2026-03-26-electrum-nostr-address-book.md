# Electrum Nostr Address Book Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Electrum Nostr plugin's two dialogs with a single wider send dialog featuring an editable combo box and a global address book.

**Architecture:** Single file change (`nostr_signer/qt.py`). Add `_get_contacts()`/`_save_contacts()` helpers for Electrum config access, `_extract_npub()` for parsing combo text, and `_show_send_dialog()` to replace both `_confirm_send` and `_show_settings`. Pure helper functions are extracted as module-level statics for testability.

**Tech Stack:** PyQt6 (QComboBox, QHBoxLayout, QInputDialog), Electrum config API (`config.get`/`config.set_key`)

**Spec:** `docs/superpowers/specs/2026-03-26-electrum-nostr-address-book-design.md`

---

### Task 1: Add `_extract_npub` helper with tests

**Files:**
- Modify: `nostr_signer/qt.py` (add static method to Plugin class)
- Test: `tests/test_nostr_signer.py` (new test class)

- [ ] **Step 1: Write failing tests for `_extract_npub`**

Add to `tests/test_nostr_signer.py`:

```python
from nostr_signer.qt import Plugin


class TestExtractNpub:

    def test_raw_npub(self):
        npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"
        assert Plugin._extract_npub(npub) == npub

    def test_contact_display_format(self):
        text = "Sasha's Trezor (npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc)"
        assert Plugin._extract_npub(text) == "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"

    def test_name_with_parentheses(self):
        text = "My (test) signer (npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc)"
        assert Plugin._extract_npub(text) == "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"

    def test_whitespace_stripped(self):
        assert Plugin._extract_npub("  npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc  ") == \
            "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"

    def test_empty_string(self):
        assert Plugin._extract_npub("") == ""

    def test_no_npub_returns_text(self):
        assert Plugin._extract_npub("random text") == "random text"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_nostr_signer.py::TestExtractNpub -v`
Expected: ImportError or AttributeError — `_extract_npub` doesn't exist yet.

- [ ] **Step 3: Implement `_extract_npub`**

Add to `Plugin` class in `nostr_signer/qt.py`:

```python
@staticmethod
def _extract_npub(combo_text: str) -> str:
    """Extract npub from combo display format or raw text.

    Handles: "Name (npub1...)" and raw "npub1..." strings.
    Uses rfind to handle names containing parentheses.
    """
    text = combo_text.strip()
    pos = text.rfind("(npub1")
    if pos != -1:
        end = text.rfind(")")
        if end > pos:
            return text[pos + 1:end]
    return text
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_nostr_signer.py::TestExtractNpub -v`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add nostr_signer/qt.py tests/test_nostr_signer.py
git commit -m "feat(electrum): add _extract_npub helper for combo box text parsing"
```

---

### Task 2: Add `_get_contacts` / `_save_contacts` helpers with tests

**Files:**
- Modify: `nostr_signer/qt.py` (add two methods to Plugin class)
- Test: `tests/test_nostr_signer.py` (new test class)

Note: These helpers access `self.config`, which is an Electrum config object. For testing, we mock it with a simple dict-based stub since we can't import Electrum in desktop tests.

- [ ] **Step 1: Write failing tests**

Add to `tests/test_nostr_signer.py`:

```python
class FakeConfig:
    """Minimal Electrum config stub for testing."""
    def __init__(self, data=None):
        self._data = data or {}

    def get(self, key, default=None):
        return self._data.get(key, default)

    def set_key(self, key, value):
        self._data[key] = value


class TestContacts:

    def _make_plugin(self, config_data=None):
        """Create a Plugin-like object with a fake config."""
        plugin = object.__new__(Plugin)
        plugin.config = FakeConfig(config_data)
        return plugin

    def test_get_contacts_empty(self):
        p = self._make_plugin()
        assert p._get_contacts() == {}

    def test_get_contacts_valid(self):
        contacts = {"npub1abc": "Alice", "npub1def": "Bob"}
        p = self._make_plugin({"nostr_signer_contacts": contacts})
        assert p._get_contacts() == contacts

    def test_get_contacts_corrupted_not_dict(self):
        p = self._make_plugin({"nostr_signer_contacts": "garbage"})
        assert p._get_contacts() == {}

    def test_get_contacts_corrupted_non_string_values(self):
        p = self._make_plugin({"nostr_signer_contacts": {"npub1abc": 123}})
        assert p._get_contacts() == {}

    def test_save_contacts(self):
        p = self._make_plugin()
        contacts = {"npub1abc": "Alice"}
        p._save_contacts(contacts)
        assert p.config.get("nostr_signer_contacts") == contacts
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_nostr_signer.py::TestContacts -v`
Expected: FAIL — `_get_contacts` / `_save_contacts` don't exist yet.

- [ ] **Step 3: Implement helpers**

Add to `Plugin` class in `nostr_signer/qt.py`, add config key constant at module level:

```python
# Module-level constant (near other WK_ constants):
CK_CONTACTS = "nostr_signer_contacts"
```

```python
# In Plugin class:
def _get_contacts(self) -> dict:
    """Load address book from global config. Returns {} on bad data."""
    raw = self.config.get(CK_CONTACTS, {})
    if not isinstance(raw, dict):
        return {}
    if not all(isinstance(k, str) and isinstance(v, str)
               for k, v in raw.items()):
        return {}
    return raw

def _save_contacts(self, contacts: dict):
    """Persist address book to global config."""
    self.config.set_key(CK_CONTACTS, contacts)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_nostr_signer.py::TestContacts -v`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add nostr_signer/qt.py tests/test_nostr_signer.py
git commit -m "feat(electrum): add _get_contacts/_save_contacts with defensive config access"
```

---

### Task 3: Replace dialogs with `_show_send_dialog`

**Files:**
- Modify: `nostr_signer/qt.py` (replace `_confirm_send`, `_show_settings`, `settings_dialog`; update imports; update `_do_send`)

This task modifies the Qt dialog code which cannot be tested without Electrum. Manual verification is needed.

- [ ] **Step 1: Update imports**

In `nostr_signer/qt.py`, **replace** the PyQt6 import line (removing `QLineEdit`, adding `QComboBox`, `QHBoxLayout`, `QInputDialog`, `QMessageBox`):

```python
# OLD:
from PyQt6.QtWidgets import QPushButton, QLabel, QLineEdit, QVBoxLayout

# NEW (QLineEdit removed — no longer used after _confirm_send and _show_settings are deleted):
from PyQt6.QtWidgets import (
    QPushButton, QLabel, QComboBox, QVBoxLayout, QHBoxLayout,
    QInputDialog, QMessageBox,
)
```

Also: the existing `_on_send` method has a local `from PyQt6.QtWidgets import QMessageBox` (line ~106) as a fallback error handler. This becomes redundant with the module-level import. Remove the local import line.

- [ ] **Step 2: Add `_show_send_dialog` method**

Replace `_confirm_send` and `_show_settings` with:

```python
def _show_send_dialog(self, window: 'ElectrumWindow',
                      wallet: 'Abstract_Wallet') -> tuple:
    """Unified send dialog with address book.

    Returns (confirmed: bool, npub: str or None).
    """
    d = WindowModalDialog(window, _("Send via Nostr"))
    d.setMinimumWidth(650)
    layout = QVBoxLayout(d)

    layout.addWidget(QLabel(_("Send to signer:")))

    # --- Editable combo box with contacts ---
    combo = QComboBox()
    combo.setEditable(True)
    combo.setCompleter(None)

    contacts = self._get_contacts()
    npub_list = []  # parallel list of npubs for index lookup
    for npub, name in contacts.items():
        combo.addItem(f"{name} ({npub})")
        npub_list.append(npub)

    # Pre-select last-used recipient
    last_npub = wallet.db.get(WK_RECIPIENT_NPUB) or ""
    if last_npub in contacts:
        idx = npub_list.index(last_npub)
        combo.setCurrentIndex(idx)
    elif last_npub:
        combo.setCurrentText(last_npub)
    else:
        combo.setCurrentText("")

    layout.addWidget(combo)

    # --- Save / Delete buttons ---
    btn_row = QHBoxLayout()
    save_btn = QPushButton(_("Save Contact"))
    delete_btn = QPushButton(_("Delete Contact"))
    btn_row.addWidget(save_btn)
    btn_row.addWidget(delete_btn)
    layout.addLayout(btn_row)

    def _update_buttons():
        text = combo.currentText().strip()
        npub = self._extract_npub(text)
        can_save = npub.startswith("npub1") and npub not in contacts
        save_btn.setEnabled(can_save)
        # Delete enabled only when a saved contact is selected
        idx = combo.currentIndex()
        can_delete = 0 <= idx < len(npub_list) and combo.currentText() == combo.itemText(idx)
        delete_btn.setEnabled(can_delete)

    combo.currentTextChanged.connect(lambda _: _update_buttons())
    combo.currentIndexChanged.connect(lambda _: _update_buttons())
    _update_buttons()

    def _on_save():
        text = combo.currentText().strip()
        npub = self._extract_npub(text)
        if not npub.startswith("npub1"):
            return
        name, ok = QInputDialog.getText(d, _("Save Contact"), _("Contact name:"))
        if not ok or not name.strip():
            return
        contacts[npub] = name.strip()
        self._save_contacts(contacts)
        # Refresh combo
        combo.clear()
        npub_list.clear()
        for n, nm in contacts.items():
            combo.addItem(f"{nm} ({n})")
            npub_list.append(n)
        combo.setCurrentIndex(npub_list.index(npub))
        _update_buttons()

    def _on_delete():
        idx = combo.currentIndex()
        if idx < 0 or idx >= len(npub_list):
            return
        npub = npub_list[idx]
        name = contacts[npub]
        reply = QMessageBox.question(
            d, _("Delete Contact"),
            _("Remove {} from contacts?").format(name),
        )
        if reply != QMessageBox.StandardButton.Yes:
            return
        del contacts[npub]
        self._save_contacts(contacts)
        combo.removeItem(idx)
        npub_list.pop(idx)
        combo.setCurrentText("")
        _update_buttons()

    save_btn.clicked.connect(_on_save)
    delete_btn.clicked.connect(_on_delete)

    # --- Relays toggle ---
    relays_label = QLabel(self.config.NOSTR_RELAYS.replace(",", "\n"))
    relays_label.setVisible(False)
    toggle_btn = QPushButton(_("Show relays"))
    toggle_btn.setFlat(True)
    toggle_btn.setStyleSheet("text-decoration: underline; color: palette(link);")

    def _toggle_relays():
        visible = not relays_label.isVisible()
        relays_label.setVisible(visible)
        toggle_btn.setText(_("Hide relays") if visible else _("Show relays"))

    toggle_btn.clicked.connect(_toggle_relays)
    layout.addWidget(toggle_btn)
    layout.addWidget(relays_label)

    # --- OK / Cancel ---
    layout.addLayout(Buttons(CancelButton(d), OkButton(d)))

    if not d.exec():
        return False, None

    text = combo.currentText().strip()
    npub = self._extract_npub(text)
    if not npub.startswith("npub1"):
        window.show_error(_("Invalid npub — must start with npub1"))
        return False, None

    # Save as last-used
    wallet.db.put(WK_RECIPIENT_NPUB, npub)
    wallet.save_db()
    return True, npub
```

- [ ] **Step 3: Update `_do_send` to use `_show_send_dialog`**

Replace the entire `_do_send` method body (find by method name, not line numbers — they shift after Tasks 1-2):

```python
def _do_send(self, d: 'TxDialog'):
    wallet = d.wallet
    window = self.windows.get(wallet)
    if not window:
        window = d.main_window if hasattr(d, 'main_window') else d.parent()
        self.windows[wallet] = window
        self._ensure_keypair(wallet)

    confirmed, recipient_npub = self._show_send_dialog(window, wallet)
    if not confirmed:
        return

    try:
        recipient_hex = self._npub_to_hex(recipient_npub)
    except Exception as e:
        window.show_error(str(e))
        return

    tx = d.tx
    if tx is None:
        window.show_error(_("No transaction to send."))
        return

    import base64
    psbt_b64 = base64.b64encode(tx.serialize_as_bytes()).decode()
    payload = {"tx": psbt_b64}
    if d.desc:
        payload["label"] = d.desc

    privkey_hex = self._get_privkey_hex(wallet)
    our_privkey = PrivateKey(bytes.fromhex(privkey_hex))
    encrypted = our_privkey.encrypt_message(json.dumps(payload), recipient_hex)

    coro = self._publish(wallet, encrypted, recipient_hex)
    text = _("Sending PSBT to signer via Nostr...")
    try:
        window.run_coroutine_dialog(coro, text)
        window.show_message(_("PSBT sent to signer."))
        d.close()
        window.send_tab.do_clear()
    except Exception as e:
        window.show_error(_("Failed to send: {}").format(str(e)))
```

- [ ] **Step 4: Delete old methods**

Remove these methods from the `Plugin` class (find by method name, not line numbers):
- `_confirm_send` method (the one returning `(bool, str)` tuple)
- `settings_dialog` hook (the `@hook` decorated method)
- `_show_settings` method
- The `# Settings` section comment above `settings_dialog`

- [ ] **Step 5: Run existing tests to verify nothing broke**

Run: `python -m pytest tests/test_nostr_signer.py -v`
Expected: All tests PASS (existing crypto tests + new helper tests).

- [ ] **Step 6: Commit**

```bash
git add nostr_signer/qt.py
git commit -m "feat(electrum): unified send dialog with address book combo box

Replace _confirm_send and _show_settings with _show_send_dialog.
Delete settings_dialog hook (was inaccessible in Electrum UI).
Dialog is 650px wide so npub fits without clipping."
```

---

### Task 4: Update documentation

**Files:**
- Modify: `CLAUDE.md` — the Source Layout section mentions `nostr_signer/` as "Electrum plugin for sending PSBTs over Nostr (uses `electrum_aionostr` + `electrum_ecc`)". Add mention of address book feature.
- Modify: `README.md` — check if it mentions the plugin and update if needed.

- [ ] **Step 1: Update CLAUDE.md Source Layout**

In the Source Layout section, update the `nostr_signer/` entry to mention the address book:

```
- `nostr_signer/` — Electrum plugin for sending PSBTs over Nostr (uses `electrum_aionostr` + `electrum_ecc`), with global address book for saved signer contacts
```

- [ ] **Step 2: Check README.md for plugin references and update if needed**

Search `README.md` for "nostr_signer", "Electrum plugin", or "Nostr". Update any descriptions to mention the address book. If the README doesn't mention the plugin in detail, no change needed.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: update Electrum plugin description for address book feature"
```

---

### Task 5: Generate zip for distribution

**Files:**
- Generate: `nostr_signer.zip` at project root

The plugin has no version string (checked `nostr_signer/__init__.py` — it only contains a docstring). Electrum plugins distributed as zip files don't require a version. Skip version bumping.

- [ ] **Step 1: Generate zip file**

```bash
cd /Users/sasha/Projects/remote_signer && zip -r nostr_signer.zip nostr_signer/
```

- [ ] **Step 2: Verify zip contents**

```bash
unzip -l nostr_signer.zip
```

Expected: should contain `nostr_signer/__init__.py`, `nostr_signer/qt.py`, `nostr_signer/nostr_signer.py`. No other files.

- [ ] **Step 3: Note** — `nostr_signer.zip` is gitignored (or should be). Do not commit the zip file.
