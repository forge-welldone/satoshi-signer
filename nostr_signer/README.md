# Nostr Signer — Electrum Plugin

Send PSBTs from Electrum to the [Satoshi Signer](../) Android app over Nostr relays.

## How It Works

1. You create a transaction in Electrum as usual
2. Click **Send to Signer** in the transaction dialog
3. The plugin encrypts the PSBT with [NIP-04](https://github.com/nostr-protocol/nips/blob/master/04.md) and publishes a kind 4 event to Nostr relays
4. The Satoshi Signer app receives the event, decrypts it, and displays the PSBT in its inbox
5. You sign on the Trezor and broadcast from the app

One-way push: Electrum sends, the app receives and signs. No changes to your existing signing workflow beyond clicking one button.

## Requirements

- **Electrum 4.6+** (with Python 3.9+)
- **Satoshi Signer** app installed on your Android phone
- Python packages: `embit`, `pyaes` (included in Satoshi Signer's dev dependencies)

## Installation

### Option 1: Symlink (development)

```bash
# From the repo root
ln -s "$(pwd)/nostr_signer" ~/.electrum/plugins/nostr_signer
```

### Option 2: Copy

```bash
cp -r nostr_signer/ ~/.electrum/plugins/nostr_signer/
```

Then restart Electrum and enable the plugin: **Tools → Plugins → Nostr Signer**.

## Setup

1. Open the Satoshi Signer app — your **npub** is displayed on the Home screen as a QR code
2. In Electrum: **Tools → Plugins → Nostr Signer → Settings**
3. Paste the npub from the app (or scan the QR code)
4. (Optional) Edit the relay list — defaults match the app's defaults:
   - `wss://nos.lol`
   - `wss://relay.damus.io`
   - `wss://relay.primal.net`

## Usage

1. Create a transaction in Electrum (File → Pay, or any other method)
2. In the transaction dialog, click **Send to Signer**
3. The plugin encrypts and publishes to your configured relays
4. Open the Satoshi Signer app — the PSBT appears in the inbox
5. Tap **Sign**, confirm on your Trezor, then broadcast

## Security

- **PSBTs are not secret** — they contain no private keys. NIP-04 encryption prevents relay operators from reading transaction details, but the real trust boundary is the Trezor's on-device confirmation.
- **The plugin keypair** is a random secp256k1 key stored per-wallet. It's a transport identity, not a signing key.
- **NIP-04** is deprecated in favor of NIP-44 in the broader Nostr ecosystem. It's acceptable here because we're using Nostr as transport, not as a trust layer.
- **24-hour expiration** — events expire after 24 hours, limiting the window of stored ciphertext on relays.

## Wire Protocol

Standard Nostr kind 4 event:

```json
{
  "kind": 4,
  "pubkey": "<plugin_hex_pubkey>",
  "tags": [["p", "<signer_hex_pubkey>"], ["expiration", "<24h>"]],
  "content": "<nip04_encrypted>",
  "sig": "<schnorr_sig>"
}
```

Decrypted payload:

```json
{"tx": "<psbt_base64>", "label": "Payment to Alice"}
```

## Testing

```bash
# From the repo root (requires venv with embit + pyaes)
python -m pytest tests/test_nostr_signer.py -v
```

Tests cover NIP-04 encrypt/decrypt round-trip, ECDH symmetry, event construction, Schnorr signature verification, and cross-language compatibility with the Android side.
