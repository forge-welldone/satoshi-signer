"""
Electrum Qt UI integration for the Nostr Signer plugin.

Adds a "Send to Signer" button to the transaction dialog and a settings
tab for configuring the signer's npub and relay list.
"""
from electrum.i18n import _
from electrum.gui.qt.util import WindowModalDialog, Buttons, OkButton, CancelButton
from electrum.plugin import hook

from PyQt5.QtWidgets import (
    QLabel, QLineEdit, QPushButton, QVBoxLayout, QHBoxLayout, QMessageBox,
    QTextEdit,
)

from . import nostr_signer


WALLET_KEY_PRIVKEY = "nostr_signer_privkey"
WALLET_KEY_NPUB = "nostr_signer_recipient_npub"
WALLET_KEY_RELAYS = "nostr_signer_relays"


class NostrSignerQt:

    def __init__(self, window, plugin):
        self.window = window
        self.plugin = plugin
        self._ensure_keypair()

    # ------------------------------------------------------------------
    # Keypair management (per-wallet)
    # ------------------------------------------------------------------

    def _ensure_keypair(self):
        """Generate a plugin keypair on first use for this wallet."""
        wallet = self.window.wallet
        if not wallet.db.get(WALLET_KEY_PRIVKEY):
            privkey_hex, _ = nostr_signer.generate_keypair()
            wallet.db.put(WALLET_KEY_PRIVKEY, privkey_hex)
            wallet.save_db()

    def _get_privkey(self) -> str:
        return self.window.wallet.db.get(WALLET_KEY_PRIVKEY)

    def _get_recipient_npub(self) -> str | None:
        return self.window.wallet.db.get(WALLET_KEY_NPUB)

    def _get_relays(self) -> list[str]:
        stored = self.window.wallet.db.get(WALLET_KEY_RELAYS)
        if stored:
            return [r.strip() for r in stored.split(",") if r.strip()]
        return nostr_signer.DEFAULT_RELAYS

    # ------------------------------------------------------------------
    # Transaction dialog hook
    # ------------------------------------------------------------------

    @hook
    def transaction_dialog(self, dialog):
        """Add 'Send to Signer' button to the transaction dialog."""
        button = QPushButton(_("Send to Signer"))
        button.clicked.connect(lambda: self._send_to_signer(dialog))
        dialog.buttons.insert(0, button)

    def _send_to_signer(self, dialog):
        npub = self._get_recipient_npub()
        if not npub:
            QMessageBox.warning(
                dialog, _("Nostr Signer"),
                _("No signer npub configured. Go to Plugins → Nostr Signer → Settings."),
            )
            return

        try:
            # Decode npub to hex pubkey
            from nostr_signer.nostr_signer import _ecdh  # ensure crypto loads
            from embit.util.bech32 import decode as bech32_decode
        except ImportError:
            pass

        # Convert npub to hex — simple bech32 decode
        try:
            recipient_hex = self._npub_to_hex(npub)
        except Exception as e:
            QMessageBox.critical(dialog, _("Error"), str(e))
            return

        tx = dialog.tx
        if tx is None:
            QMessageBox.warning(dialog, _("Error"), _("No transaction to send."))
            return

        # Serialize PSBT to base64
        psbt_bytes = tx.serialize_as_bytes()
        import base64
        psbt_b64 = base64.b64encode(psbt_bytes).decode()

        label = dialog.desc or ""

        try:
            event = nostr_signer.create_nostr_event(
                privkey_hex=self._get_privkey(),
                recipient_pubkey_hex=recipient_hex,
                psbt_base64=psbt_b64,
                label=label,
            )

            relays = self._get_relays()
            # Run async publish in Electrum's event loop
            import asyncio
            loop = self.window.network.asyncio_loop
            future = asyncio.run_coroutine_threadsafe(
                nostr_signer.publish_event(event, relays), loop
            )
            result = future.result(timeout=15)

            if result.get("status") == "ok":
                QMessageBox.information(
                    dialog, _("Nostr Signer"),
                    _("PSBT sent to signer via {}").format(result.get("relay", "relay")),
                )
            else:
                QMessageBox.warning(
                    dialog, _("Nostr Signer"),
                    _("Failed to publish: {}").format(result.get("message", "unknown error")),
                )
        except Exception as e:
            QMessageBox.critical(dialog, _("Error"), str(e))

    def _npub_to_hex(self, npub: str) -> str:
        """Decode bech32 npub to 32-byte hex pubkey."""
        if not npub.startswith("npub1"):
            raise ValueError("Invalid npub: must start with npub1")
        # Use our own Bech32 decoder to avoid circular deps
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
        return bytes(result).hex()

    # ------------------------------------------------------------------
    # Settings dialog
    # ------------------------------------------------------------------

    @hook
    def settings_dialog(self, window):
        """Plugin settings: configure signer npub and relay list."""
        d = WindowModalDialog(window, _("Nostr Signer Settings"))
        layout = QVBoxLayout(d)

        # Signer npub
        layout.addWidget(QLabel(_("Signer npub (from Satoshi Signer app):")))
        npub_edit = QLineEdit()
        npub_edit.setText(self._get_recipient_npub() or "")
        npub_edit.setPlaceholderText("npub1...")
        layout.addWidget(npub_edit)

        # Relay list
        layout.addWidget(QLabel(_("Relays (one per line):")))
        relay_edit = QTextEdit()
        relay_edit.setPlainText("\n".join(self._get_relays()))
        relay_edit.setMaximumHeight(100)
        layout.addWidget(relay_edit)

        # Plugin pubkey (read-only info)
        our_pubkey = nostr_signer.get_xonly_pubkey(self._get_privkey())
        layout.addWidget(QLabel(_("Plugin pubkey (hex): ") + our_pubkey[:16] + "..."))

        layout.addLayout(Buttons(CancelButton(d), OkButton(d)))

        if d.exec_():
            wallet = self.window.wallet
            wallet.db.put(WALLET_KEY_NPUB, npub_edit.text().strip())
            relays_text = relay_edit.toPlainText().strip()
            relays = ",".join(r.strip() for r in relays_text.split("\n") if r.strip())
            wallet.db.put(WALLET_KEY_RELAYS, relays)
            wallet.save_db()
