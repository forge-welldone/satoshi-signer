"""
Electrum Qt plugin: adds "Send via Nostr" button to the transaction dialog.

Uses electrum_aionostr for NIP-04 encryption and relay publishing,
and electrum_ecc for key generation — matching the patterns in
Electrum's built-in psbt_nostr (Nostr Cosigner) plugin.
"""
import asyncio
import json
import ssl
import time
from typing import TYPE_CHECKING, Optional

from PyQt6.QtWidgets import (
    QPushButton, QLabel, QComboBox, QVBoxLayout, QHBoxLayout,
    QInputDialog, QMessageBox,
)

import electrum_ecc as ecc
import electrum_aionostr as aionostr
from electrum_aionostr.key import PrivateKey

from electrum.crypto import sha256
from electrum.i18n import _
from electrum.logging import Logger
from electrum.plugin import BasePlugin, hook
from electrum.util import (
    log_exceptions, ca_path, make_aiohttp_proxy_connector,
)
from electrum.gui.qt.util import WindowModalDialog, Buttons, OkButton, CancelButton

if TYPE_CHECKING:
    from electrum.gui.qt.transaction_dialog import TxDialog
    from electrum.gui.qt.main_window import ElectrumWindow
    from electrum.wallet import Abstract_Wallet


NOSTR_EVENT_KIND = 4
KEEP_DELAY = 24 * 60 * 60  # 24 hours

# Wallet storage keys
WK_PRIVKEY = "nostr_signer_privkey"
WK_RECIPIENT_NPUB = "nostr_signer_recipient_npub"

# Address book helpers (no external deps — safe to import in Electrum)
from .helpers import (
    CK_CONTACTS, get_contacts, save_contacts, extract_npub,
)


class Plugin(BasePlugin, Logger):

    def __init__(self, parent, config, name):
        BasePlugin.__init__(self, parent, config, name)
        Logger.__init__(self)
        self.windows = {}  # wallet -> window

    def is_available(self):
        return True

    @hook
    def load_wallet(self, wallet: 'Abstract_Wallet', window: 'ElectrumWindow'):
        self.windows[wallet] = window
        self._ensure_keypair(wallet)

    @hook
    def on_close_window(self, window: 'ElectrumWindow'):
        self.windows.pop(window.wallet, None)

    @hook
    def transaction_dialog(self, d: 'TxDialog'):
        b = QPushButton(_("Send via Nostr"))
        b.clicked.connect(lambda: self._on_send(d))
        d.buttons.insert(0, b)

    # ------------------------------------------------------------------
    # Keypair management (per-wallet, deterministic from first xpub)
    # ------------------------------------------------------------------

    def _ensure_keypair(self, wallet: 'Abstract_Wallet'):
        if wallet.db.get(WK_PRIVKEY):
            return
        privkey_bytes = ecc.ECPrivkey.generate_random_key().get_secret_bytes()
        wallet.db.put(WK_PRIVKEY, privkey_bytes.hex())
        wallet.save_db()

    def _get_privkey_hex(self, wallet: 'Abstract_Wallet') -> str:
        return wallet.db.get(WK_PRIVKEY)

    def _get_pubkey_hex(self, wallet: 'Abstract_Wallet') -> str:
        privkey_bytes = bytes.fromhex(self._get_privkey_hex(wallet))
        return ecc.ECPrivkey(privkey_bytes).get_public_key_bytes()[1:].hex()

    def _get_recipient_hex(self, wallet: 'Abstract_Wallet') -> Optional[str]:
        npub = wallet.db.get(WK_RECIPIENT_NPUB)
        if not npub:
            return None
        return self._npub_to_hex(npub)

    # ------------------------------------------------------------------
    # Contacts (global address book in config)
    # ------------------------------------------------------------------

    def _get_contacts(self) -> dict:
        """Load address book from global config. Returns {} on bad data."""
        return get_contacts(self.config)

    def _save_contacts(self, contacts: dict):
        """Persist address book to global config."""
        save_contacts(self.config, contacts)

    # ------------------------------------------------------------------
    # Send PSBT
    # ------------------------------------------------------------------

    def _on_send(self, d: 'TxDialog'):
        try:
            self._do_send(d)
        except Exception as e:
            import traceback
            traceback.print_exc()
            try:
                window = d.main_window if hasattr(d, 'main_window') else d.parent()
                window.show_error(_("Nostr Signer error: {}").format(str(e)))
            except Exception:
                QMessageBox.critical(d, _("Error"), str(e))

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

    @log_exceptions
    async def _publish(self, wallet: 'Abstract_Wallet', encrypted: str,
                       recipient_hex: str):
        privkey_hex = self._get_privkey_hex(wallet)
        relays = self.config.NOSTR_RELAYS.split(",")
        ssl_context = ssl.create_default_context(
            purpose=ssl.Purpose.SERVER_AUTH, cafile=ca_path
        )
        proxy = None
        network = wallet.network
        if network and network.proxy and network.proxy.enabled:
            proxy = make_aiohttp_proxy_connector(network.proxy, ssl_context)

        async with aionostr.Manager(
            relays=relays,
            private_key=privkey_hex,
            ssl_context=ssl_context,
            proxy=proxy,
        ) as manager:
            await aionostr._add_event(
                manager,
                kind=NOSTR_EVENT_KIND,
                content=encrypted,
                private_key=privkey_hex,
                tags=[
                    ["p", recipient_hex],
                    ["expiration", str(int(time.time()) + KEEP_DELAY)],
                ],
            )

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
            npub = extract_npub(text)
            can_save = npub.startswith("npub1") and npub not in contacts
            save_btn.setEnabled(can_save)
            # Delete enabled only when a saved contact is selected
            idx = combo.currentIndex()
            can_delete = (0 <= idx < len(npub_list)
                          and combo.currentText() == combo.itemText(idx))
            delete_btn.setEnabled(can_delete)

        combo.currentTextChanged.connect(lambda _: _update_buttons())
        combo.currentIndexChanged.connect(lambda _: _update_buttons())
        _update_buttons()

        def _on_save():
            text = combo.currentText().strip()
            npub = extract_npub(text)
            if not npub.startswith("npub1"):
                return
            name, ok = QInputDialog.getText(
                d, _("Save Contact"), _("Contact name:"))
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
        toggle_btn.setStyleSheet(
            "text-decoration: underline; color: palette(link);")

        def _toggle_relays():
            visible = not relays_label.isVisible()
            relays_label.setVisible(visible)
            toggle_btn.setText(
                _("Hide relays") if visible else _("Show relays"))

        toggle_btn.clicked.connect(_toggle_relays)
        layout.addWidget(toggle_btn)
        layout.addWidget(relays_label)

        # --- OK / Cancel ---
        layout.addLayout(Buttons(CancelButton(d), OkButton(d)))

        if not d.exec():
            return False, None

        text = combo.currentText().strip()
        npub = extract_npub(text)
        if not npub.startswith("npub1"):
            window.show_error(_("Invalid npub — must start with npub1"))
            return False, None

        # Save as last-used
        wallet.db.put(WK_RECIPIENT_NPUB, npub)
        wallet.save_db()
        return True, npub

    # ------------------------------------------------------------------
    # Bech32 npub decode (minimal, avoids external deps)
    # ------------------------------------------------------------------

    @staticmethod
    def _npub_to_hex(npub: str) -> str:
        if not npub.startswith("npub1"):
            raise ValueError("Invalid npub: must start with npub1")
        charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        data_part = npub[5:]  # after "npub1"
        values = [charset.index(c) for c in data_part.lower()]
        # Strip 6-byte checksum, convert 5-bit to 8-bit
        payload = values[:-6]
        acc, bits = 0, 0
        result = []
        for v in payload:
            acc = (acc << 5) | v
            bits += 5
            while bits >= 8:
                bits -= 8
                result.append((acc >> bits) & 0xFF)
        hex_str = bytes(result).hex()
        if len(hex_str) != 64:
            raise ValueError(f"Invalid npub: decoded to {len(hex_str)//2} bytes, expected 32")
        return hex_str
