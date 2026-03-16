# Nostr Signer — Electrum Plugin

Send PSBTs from Electrum to the [Satoshi Signer](../) Android app over Nostr relays.

## How It Works

1. You create a transaction in Electrum as usual
2. Click **Send to Signer** in the transaction dialog
3. The plugin encrypts the PSBT with [NIP-04](https://github.com/nostr-protocol/nips/blob/master/04.md) and publishes a kind 4 event to your configured Nostr relays
4. The Satoshi Signer app receives the event, decrypts it, and displays the PSBT in its inbox
5. You sign on the Trezor and broadcast from the app

One-way push: Electrum sends, the app receives and signs.

## Requirements

- **Electrum 4.6+** (uses `electrum_aionostr` and `electrum_ecc`)
- **Satoshi Signer** app installed on your Android phone

No additional Python dependencies — the plugin uses Electrum's bundled `electrum_aionostr` for NIP-04 encryption and relay publishing.

## Installation

Package the plugin as a zip:

```bash
# From the Electrum source tree
./contrib/make_plugin /path/to/remote_signer/nostr_signer
```

Then import the zip in Electrum: **Tools → Plugins → Add Plugin** (or via the setup wizard).

For development, symlink into Electrum's plugin directory:

```bash
ln -s "$(pwd)/nostr_signer" /path/to/electrum/electrum/plugins/nostr_signer
```

## Setup

1. Open the Satoshi Signer app — your **npub** is displayed on the Home screen as a QR code
2. In Electrum: **Tools → Plugins → Nostr Signer → Settings**
3. Paste the npub from the app (scan the QR or copy the text)
4. Relays are shared with Electrum's Nostr settings (no separate configuration needed)

## Usage

1. Create a transaction in Electrum (File → Pay, or any other method)
2. In the transaction dialog, click **Send to Signer**
3. Open the Satoshi Signer app — the PSBT appears in the inbox
4. Tap **Sign**, confirm on your Trezor, then broadcast

## Plugin Structure

```
nostr_signer/
    manifest.json       # Plugin metadata (name, version, available_for)
    __init__.py         # Package marker
    nostr_signer.py     # Standalone NIP-04 crypto (testable without Electrum)
    qt.py               # Electrum Qt plugin: UI hooks, relay publishing
    README.md
```

- `qt.py` contains `class Plugin(BasePlugin)` — the Electrum entry point. Uses `electrum_aionostr.Manager` for relay connections and `PrivateKey.encrypt_message()` for NIP-04 encryption, matching the patterns in Electrum's built-in Nostr Cosigner plugin.
- `nostr_signer.py` is a standalone reference implementation using `embit` + `pyaes`. It's used for testing and can also be used as a CLI tool without Electrum.

## Security

- **PSBTs contain no private keys** — NIP-04 encryption prevents relay operators from reading transaction details, but the Trezor's on-device confirmation is the real trust boundary.
- **Plugin keypair** is a random secp256k1 key stored per-wallet. Transport identity only.
- **Relays** are Electrum's configured Nostr relays (shared with the Nostr Cosigner plugin).
- **24-hour expiration** — events expire after 24 hours.

## Testing

The standalone crypto module is testable without Electrum:

```bash
# From the repo root (requires venv with embit + pyaes)
python -m pytest tests/test_nostr_signer.py -v
```
