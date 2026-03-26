# Electrum Plugin Icon

**Date:** 2026-03-26

## Goal

Add an icon to the `nostr_signer` Electrum plugin so it appears in Electrum's Settings > Plugins list.

## Design

The icon reuses the Satoshi Signer Android app icon: a chip/IC with traces on all four sides and a checkmark inside, on a diagonal orange-to-red gradient (#D97706 → #E11D48).

- **Format:** 80x80 PNG, RGBA with transparency (rounded corners)
- **Where it appears:** Electrum plugin list only (via `manifest.json` `"icon"` field)
- **Not applied to:** the "Send via Nostr" button in the transaction dialog (stays text-only)

## Implementation

1. `generate_icon.py` — Pillow script that programmatically draws the icon, producing `nostr_signer.png`. Kept in the plugin directory as reproducible source.
2. `nostr_signer.png` — the generated 80x80 icon file.
3. `manifest.json` — add `"icon": "nostr_signer.png"` field.

No changes to `qt.py` or `__init__.py` required — the `manifest.json` icon field is sufficient for plugin list display.

## Files Changed

- `nostr_signer/generate_icon.py` (new) — icon generator script
- `nostr_signer/nostr_signer.png` (new) — generated icon
- `nostr_signer/manifest.json` (modified) — add `"icon"` field
