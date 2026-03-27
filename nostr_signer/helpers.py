"""Pure helper functions for the Nostr Signer plugin.

No external dependencies — safe to import from both the Electrum plugin
(qt.py) and desktop tests.
"""

# Global config key for address book
CK_CONTACTS = "nostr_signer_contacts"


def get_contacts(config, key: str = CK_CONTACTS) -> dict:
    """Load address book from config. Returns {} on bad data."""
    raw = config.get(key, {})
    if not isinstance(raw, dict):
        return {}
    if not all(isinstance(k, str) and isinstance(v, str)
               for k, v in raw.items()):
        return {}
    return raw


def save_contacts(config, contacts: dict, key: str = CK_CONTACTS):
    """Persist address book to config."""
    config.set_key(key, contacts)


def extract_npub(combo_text: str) -> str:
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
