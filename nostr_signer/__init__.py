"""
Nostr Signer — Electrum plugin for sending PSBTs to Satoshi Signer over Nostr.

Publishes NIP-04 encrypted kind 4 events to Nostr relays. The Satoshi Signer
Android app receives, decrypts, and signs them with a Trezor hardware wallet.

The crypto module (nostr_signer.py) is standalone and testable without Electrum.
The plugin registration below only loads when running inside Electrum.
"""
try:
    from electrum.i18n import _
    from electrum.plugin import BasePlugin, hook

    class NostrSignerPlugin(BasePlugin):

        fullname = _("Nostr Signer")
        description = _(
            "Send PSBTs to the Satoshi Signer Android app over Nostr relays. "
            "Encrypts transactions with NIP-04 and publishes to configurable relays."
        )
        available_for = ["qt"]

        def __init__(self, parent, config, name):
            super().__init__(parent, config, name)

        @hook
        def init_qt(self, gui_object):
            """Called when the Qt GUI initializes. Registers the Qt handler."""
            from .qt import NostrSignerQt
            for window in gui_object.windows:
                self._add_to_window(window)

        @hook
        def on_new_window(self, window):
            self._add_to_window(window)

        def _add_to_window(self, window):
            from .qt import NostrSignerQt
            handler = NostrSignerQt(window, self)
            window._nostr_signer = handler

except ImportError:
    # Not running inside Electrum — crypto module still importable for testing
    pass
