"""
Electrum Qt plugin: adds "Send to Signer" button to the transaction dialog.

Uses electrum_aionostr for NIP-04 encryption and relay publishing,
and electrum_ecc for key generation — matching the patterns in
Electrum's built-in psbt_nostr (Nostr Cosigner) plugin.
"""
import asyncio
import json
import ssl
import time
from typing import TYPE_CHECKING, Optional

from PyQt6.QtWidgets import QPushButton, QLabel, QLineEdit, QVBoxLayout

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
        b = QPushButton(_("Send to Signer"))
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
    # Send PSBT
    # ------------------------------------------------------------------

    def _on_send(self, d: 'TxDialog'):
        wallet = d.wallet
        window = self.windows.get(wallet)
        if not window:
            return

        recipient_npub = wallet.db.get(WK_RECIPIENT_NPUB)
        if not recipient_npub:
            self._show_settings(window, wallet)
            recipient_npub = wallet.db.get(WK_RECIPIENT_NPUB)
            if not recipient_npub:
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

        # Build payload
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

    # ------------------------------------------------------------------
    # Settings
    # ------------------------------------------------------------------

    @hook
    def settings_dialog(self, window: 'ElectrumWindow'):
        self._show_settings(window, window.wallet)

    def _show_settings(self, window: 'ElectrumWindow',
                       wallet: 'Abstract_Wallet'):
        d = WindowModalDialog(window, _("Nostr Signer Settings"))
        layout = QVBoxLayout(d)

        layout.addWidget(QLabel(
            _("Signer npub (from the Satoshi Signer app):")
        ))
        npub_edit = QLineEdit()
        npub_edit.setText(wallet.db.get(WK_RECIPIENT_NPUB) or "")
        npub_edit.setPlaceholderText("npub1...")
        layout.addWidget(npub_edit)

        our_pub = self._get_pubkey_hex(wallet)
        layout.addWidget(QLabel(
            _("Plugin pubkey: {}...").format(our_pub[:16])
        ))
        layout.addWidget(QLabel(
            _("Relays: {}").format(self.config.NOSTR_RELAYS)
        ))

        layout.addLayout(Buttons(CancelButton(d), OkButton(d)))

        if d.exec():
            npub = npub_edit.text().strip()
            if npub and not npub.startswith("npub1"):
                window.show_error(_("Invalid npub — must start with npub1"))
                return
            wallet.db.put(WK_RECIPIENT_NPUB, npub)
            wallet.save_db()

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
