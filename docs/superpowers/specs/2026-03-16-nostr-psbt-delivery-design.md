# Nostr PSBT Delivery Design

## Problem

Satoshi Signer currently receives PSBTs via file import or USB. The user wants to send PSBTs from Electrum on their desktop to the Android app for Trezor signing without carrying a laptop. Nostr relays provide a decentralized, async transport channel that Electrum already supports for cosigner communication.

## Goals

- Deliver PSBTs from Electrum to the Android app over Nostr relays
- One-way push: Electrum sends, app receives and signs
- Support both single-sig and multisig wallets
- Minimal protocol — Nostr is just transport, not a signing orchestration layer
- No changes to existing PSBT parsing, Trezor signing, or broadcasting code

## Non-Goals

- Sending signed PSBTs back over Nostr (user exports/broadcasts from app as today)
- Background sync or persistent storage (stateless app philosophy preserved)
- Compatibility with Electrum's existing `psbt_nostr` plugin (it uses xpub-derived keys and is multisig-only)
- NIP-44 or NIP-59 encryption (NIP-04 matches Electrum ecosystem; can migrate later)

## Prior Art

- **Electrum `psbt_nostr` plugin** (4.6.0+): Sends PSBTs between multisig cosigners over Nostr. Uses kind 4 events, NIP-04 encryption, deterministic keypairs from xpub. Multisig-only, xpub-derived keys incompatible with our stateless app.
- **Bitcoin Safe**: Desktop multisig wallet using Nostr for PSBT exchange between cosigners.
- **Coinstr**: Full multisig orchestration over Nostr.
- No existing project combines mobile Trezor signing with Nostr PSBT delivery.

## Architecture

```
Electrum (desktop)                    Android app
+-----------------+                  +------------------------------+
| nostr_signer    |                  | NostrReceiver (Kotlin)       |
| plugin (Python) |   Nostr relay    |   |                          |
| Encrypts PSBT   |---- kind 4 ---->| NostrInbox (in-memory list)  |
| to signer npub  |   (NIP-04)      |   | user taps "Sign"         |
+-----------------+                  | loadPsbt -> existing flow    |
                                     +------------------------------+
```

Two new components, one on each side. The Android app gains a Nostr receiver and inbox. A custom Electrum plugin sends PSBTs. The existing signing pipeline (PSBT parsing, Trezor signing, broadcasting) is unchanged.

## Wire Protocol

Standard Nostr kind 4 event with NIP-04 encryption.

**Event structure (published by Electrum):**
```json
{
  "kind": 4,
  "pubkey": "<plugin_hex_pubkey>",
  "created_at": <unix_timestamp>,
  "tags": [
    ["p", "<signer_hex_pubkey>"],
    ["expiration", "<unix_timestamp + 86400>"]
  ],
  "content": "<nip04_encrypted_payload>",
  "id": "<event_id>",
  "sig": "<schnorr_sig>"
}
```

**Decrypted payload:**
```json
{"tx": "<psbt_base64>", "label": "Payment to Alice"}
```

- `tx`: base64-encoded serialized PSBT (required). Base64 is the standard PSBT exchange format per BIP-174 and what Electrum uses internally for PSBT serialization. The Android side base64-decodes to `ByteArray` before passing to `loadPsbt()`.
- `label`: human-readable description (optional)

**Subscription filter (Android app):**
```json
["REQ", "psbt-inbox", {"kinds": [4], "#p": ["<our_hex_pubkey>"], "since": <24h_ago>}]
```

Events expire after 24 hours. On app restart, the receiver reconnects and re-fetches unexpired events.

## Android Side

### Keypair Management (NostrKeyManager)

- First launch: generate a random secp256k1 keypair
- Store nsec in SharedPreferences (transport identity only, protects nothing of value)
- Display npub on Home screen as copyable text + QR code
- Keypair can be regenerated via settings

### Nostr Receiver (NostrReceiver)

- OkHttp WebSocket client connecting to configurable relay list
- Default relays: same as Electrum's defaults (nos.lol, relay.damus.io, relay.primal.net, etc.)
- On connect: send REQ subscription for kind 4 events tagged with our pubkey, since 24h ago
- On EVENT: decrypt NIP-04 content, parse JSON payload, add to inbox. Malformed events (decryption failure, invalid JSON, missing `tx` field, invalid base64, invalid PSBT) are silently dropped with debug-level logging.
- Reconnect on disconnect with exponential backoff
- Lifecycle: `MainActivity` calls connect/disconnect in `onStart()`/`onStop()`. No background service — PSBTs wait on the relay.

### NIP-04 Decryption (Kotlin)

- ECDH: multiply sender's pubkey by our privkey, take x-coordinate as shared secret
- AES-256-CBC decrypt using `javax.crypto.Cipher` (built into Android)
- secp256k1 ECDH via `fr.acinq.secp256k1:secp256k1-kmp-jni-android` — a lightweight JNI wrapper around Bitcoin's libsecp256k1, purpose-built for Bitcoin's curve on Android. Android's built-in `KeyAgreement` does NOT support secp256k1 (only NIST curves).
- Schnorr signing for Nostr events also uses secp256k1-kmp

### Inbox (NostrInbox)

In-memory list of received PSBTs held in the ViewModel.

**Inbox entry data class:**
```
InboxItem(
    id: String,           // Nostr event ID
    psbtBytes: ByteArray,
    label: String,        // from payload, or "Unsigned transaction"
    amount: String,       // from parse_psbt summary
    senderNpub: String,   // truncated sender pubkey
    receivedAt: Long,     // unix timestamp
    status: InboxStatus   // pending | signing | signed | failed
)
```

**Inbox item display:**
```
+----------------------------------+
| Payment to Alice           2m ago|
| 0.0045 BTC                      |
| from npub1a3x...k9f2    pending |
|                     [Sign] [Del] |
+----------------------------------+
```

- On receipt: call Python's `parse_psbt` to extract amount for display
- Sign: enters existing TransactionReview -> Signing flow
- Delete: removes from inbox
- Failed signing: status becomes `failed`, PSBT stays for retry
- Signed: status becomes `signed` (or auto-removed)
- No persistent storage: killed process loses inbox, but events are re-fetchable from relay within 24h

### ViewModel Changes

The inbox is a **separate `StateFlow<List<InboxItem>>`** alongside the existing `_state: MutableStateFlow<AppState>`. The Home screen observes both flows:

- `_state` drives navigation (Home, TransactionReview, Signing, Result) as today
- `_inboxItems` drives the inbox list rendered on the Home screen
- Tapping "Sign" on an inbox item calls `loadPsbt(inboxItem.psbtBytes)`, which transitions `_state` to `TransactionReview` as normal
- On return to Home (via `goHome()`), the inbox list is still there — it's independent of navigation state
- `NostrReceiver` lifecycle: `MainActivity` calls `viewModel.startNostrReceiver()` in `onStart()` and `viewModel.stopNostrReceiver()` in `onStop()`. The ViewModel delegates to `NostrReceiver.connect()`/`disconnect()`.

### Deduplication

- Inbox deduplicates by Nostr event ID (the `id` field in the event)
- In-memory `Set<String>` of seen event IDs, checked before adding to inbox
- On app restart, events are re-fetched from relay. Already-signed PSBTs will reappear as `pending` — this is acceptable since the user can just delete them, and the Trezor provides the real safety check

## Electrum Side

### Plugin Structure (`nostr_signer/`)

```
nostr_signer/
    __init__.py       # plugin registration
    nostr_signer.py   # NIP-04 encrypt, relay publish
    qt.py             # Qt UI: button in tx dialog, settings
```

### Configuration (wallet config)

- `signer_npub`: the Android app's npub (pasted by user)
- Relays: reuse Electrum's existing Nostr relay list from network preferences

### Workflow

1. User creates a transaction in Electrum as normal
2. Transaction dialog shows a "Send to Signer" button (alongside existing Sign/Export)
3. Click: serialize PSBT to base64, wrap in `{"tx": "<base64>", "label": "<description>"}`, encrypt with NIP-04 to configured npub, publish kind 4 event with 24h expiration to relays
4. Toast: "PSBT sent to signer"

### NIP-04 Encryption (Python)

- ECDH using Electrum's built-in `ecc` module
- AES-256-CBC using Electrum's crypto utilities
- Electrum's `electrum-aionostr` dependency may provide `encrypt_message()` directly
- Plugin generates its own random keypair on first use via `ecc.ECPrivkey.generate_random_key()`, stored per-wallet in wallet config as hex

### Scope

- No wallet-type checks: works for single-sig and multisig
- No receiving signed PSBTs back (one-way push)
- No automatic signing: user always initiates "Send to Signer" manually

## File Changes

### New Files (Android)

| File | Purpose |
|------|---------|
| `NostrReceiver.kt` | WebSocket connection, subscription, NIP-04 decryption |
| `NostrInbox.kt` | Inbox data class, inbox state management |
| `NostrKeyManager.kt` | Keypair generation, storage, npub formatting |
| `InboxScreen.kt` | Compose UI for inbox list |

### Modified Files (Android)

| File | Change |
|------|--------|
| `SignerViewModel.kt` | Add inbox list to StateFlow, wire NostrReceiver lifecycle |
| `HomeScreen.kt` | Show npub (text + QR), inbox count badge, relay status |

### New Files (Electrum)

| File | Purpose |
|------|---------|
| `nostr_signer/__init__.py` | Plugin registration |
| `nostr_signer/nostr_signer.py` | NIP-04 encrypt, relay publish |
| `nostr_signer/qt.py` | Qt UI hooks |

### Unchanged

- `PythonBridge.kt`, `psbt_parser.py`, `signer.py`, `usb_transport.py`, `TransactionReviewScreen.kt`, `SigningScreen.kt` — all unchanged.

## Dependencies

### Android

- **OkHttp** (`com.squareup.okhttp3:okhttp`) — WebSocket client, add as explicit dependency
- **secp256k1-kmp** (`fr.acinq.secp256k1:secp256k1-kmp-jni-android`) — secp256k1 ECDH and Schnorr signing for Nostr. Lightweight JNI wrapper around Bitcoin's libsecp256k1.
- **QR code generation** (`com.google.zxing:core`) — for displaying npub as QR on Home screen

### Electrum Plugin

- No new dependencies — uses Electrum's built-in `ecc`, crypto, and `electrum-aionostr`

## Security Considerations

- NIP-04 is deprecated in favor of NIP-44 due to known weaknesses (metadata leakage, no authentication). Acceptable here because: (1) PSBTs are not secret (they contain no private keys), (2) we're using Nostr as transport not as a trust layer, (3) Trezor confirms all transaction details on its screen before signing.
- The nsec stored on the phone is a transport identity, not a signing key. Compromise reveals nothing about Bitcoin funds.
- Relay operators can see that encrypted messages are being sent between two pubkeys but cannot read content.
- 24h event expiration limits the window of stored ciphertext on relays.
- **Spam/unsolicited PSBTs:** Anyone who learns the app's npub can send it PSBTs. This is acceptable because: (1) the Trezor's on-device confirmation is the trust boundary — the user verifies every transaction on the hardware screen before signing, (2) the npub is not publicly discoverable (shared manually), (3) spam PSBTs can be deleted from inbox. A "trusted senders" allowlist is a future consideration if this becomes a problem.

## Testing

- **NIP-04 crypto:** Unit tests for encrypt/decrypt round-trip on both Kotlin and Python sides using known test vectors
- **NostrReceiver:** Mock WebSocket to test subscription, event parsing, deduplication, and error handling
- **Electrum plugin:** Unit test for payload construction and NIP-04 encryption
- **Integration:** Encrypt a PSBT payload with a known key on the Python side, decrypt on the Kotlin side (and vice versa) to verify cross-language compatibility

## Future Considerations (not in scope)

- Migration to NIP-44 if Electrum ecosystem moves away from NIP-04
- Two-way communication (sending signed PSBTs back)
- Persistent inbox storage
- Background Nostr listening via Android foreground service
- NIP-59 gift wrap for metadata privacy
- Trusted sender allowlist
