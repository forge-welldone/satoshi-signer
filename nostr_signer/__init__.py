"""
Nostr Signer — Electrum plugin for sending PSBTs to Satoshi Signer over Nostr.

The crypto module (nostr_signer.py) is standalone and testable without Electrum.
The Electrum plugin class lives in qt.py and uses electrum_aionostr for relay I/O.
"""
