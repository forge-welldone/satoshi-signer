"""
Standalone NIP-04 crypto and Nostr event construction.

Uses embit for secp256k1 (ECDH + Schnorr) and pyaes for AES-256-CBC.
No Electrum imports — testable with plain pytest.

The Electrum Qt plugin (qt.py) uses electrum_aionostr for relay I/O
and NIP-04 encryption, but this module serves as:
  1. A testable reference implementation of the crypto
  2. The standalone CLI/scripting entry point
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import time

from embit import ec
import pyaes


# ---------------------------------------------------------------------------
# NIP-04 crypto
# ---------------------------------------------------------------------------

def nip04_encrypt(our_privkey_bytes: bytes, their_xonly_bytes: bytes,
                  plaintext: str) -> str:
    """Encrypt *plaintext* per NIP-04.

    Returns ``"base64(ciphertext)?iv=base64(iv)"``.
    """
    shared_secret = _ecdh(our_privkey_bytes, their_xonly_bytes)
    iv = os.urandom(16)
    padded = _pkcs7_pad(plaintext.encode("utf-8"))
    aes = pyaes.AESModeOfOperationCBC(shared_secret, iv=iv)
    ciphertext = b"".join(
        aes.encrypt(padded[i:i + 16]) for i in range(0, len(padded), 16)
    )
    return (base64.b64encode(ciphertext).decode()
            + "?iv=" + base64.b64encode(iv).decode())


def nip04_decrypt(our_privkey_bytes: bytes, their_xonly_bytes: bytes,
                  content: str) -> str:
    """Decrypt a NIP-04 message. Inverse of :func:`nip04_encrypt`."""
    ct_b64, iv_b64 = content.split("?iv=")
    ciphertext = base64.b64decode(ct_b64)
    iv = base64.b64decode(iv_b64)
    shared_secret = _ecdh(our_privkey_bytes, their_xonly_bytes)
    aes = pyaes.AESModeOfOperationCBC(shared_secret, iv=iv)
    padded = b"".join(
        aes.decrypt(ciphertext[i:i + 16])
        for i in range(0, len(ciphertext), 16)
    )
    return _pkcs7_unpad(padded).decode("utf-8")


def _ecdh(privkey_bytes: bytes, xonly_bytes: bytes) -> bytes:
    """NIP-04 ECDH shared secret: raw x-coordinate of privkey * pubkey.

    Uses embit's secp256k1 bindings for point multiplication, then extracts
    the raw x-coordinate. libsecp256k1's default ``ecdh()`` returns
    SHA-256(compressed_shared_point), which is not what NIP-04 needs.
    Both sides (Python + Android) must agree on the same shared secret
    derivation for NIP-04 compatibility.
    """
    import ctypes
    from embit.util import ctypes_secp256k1 as secp

    pubkey = ec.PublicKey.from_xonly(xonly_bytes)
    # ec_pubkey_tweak_mul modifies the point in place — use a mutable buffer
    point_buf = ctypes.create_string_buffer(pubkey._point, 64)
    secp.ec_pubkey_tweak_mul(point_buf, privkey_bytes)
    compressed = secp.ec_pubkey_serialize(point_buf.raw)
    return compressed[1:33]  # x-coordinate from compressed pubkey


def _pkcs7_pad(data: bytes, block_size: int = 16) -> bytes:
    pad_len = block_size - (len(data) % block_size)
    return data + bytes([pad_len] * pad_len)


def _pkcs7_unpad(data: bytes) -> bytes:
    if len(data) == 0:
        raise ValueError("Cannot unpad empty data")
    pad_len = data[-1]
    if pad_len < 1 or pad_len > 16:
        raise ValueError(f"Invalid PKCS7 pad length: {pad_len}")
    if pad_len > len(data):
        raise ValueError(f"Pad length {pad_len} exceeds data length {len(data)}")
    if not all(b == pad_len for b in data[-pad_len:]):
        raise ValueError("Invalid PKCS7 padding bytes")
    return data[:-pad_len]


# ---------------------------------------------------------------------------
# Nostr event construction
# ---------------------------------------------------------------------------

def generate_keypair() -> tuple[str, str]:
    """Generate a random secp256k1 keypair.

    Returns ``(privkey_hex, xonly_pubkey_hex)``.
    """
    pk = ec.PrivateKey(os.urandom(32))
    return pk.secret.hex(), pk.xonly().hex()


def get_xonly_pubkey(privkey_hex: str) -> str:
    """Derive x-only public key from a private key hex string."""
    pk = ec.PrivateKey(bytes.fromhex(privkey_hex))
    return pk.xonly().hex()


def create_psbt_payload(psbt_base64: str, label: str | None = None) -> str:
    """Build the JSON payload: ``{"tx": "<base64>", "label": "..."}``."""
    payload: dict = {"tx": psbt_base64}
    if label:
        payload["label"] = label
    return json.dumps(payload, separators=(",", ":"))


def compute_event_id(pubkey_hex: str, created_at: int, kind: int,
                     tags: list, content: str) -> str:
    """NIP-01 event ID: ``sha256([0, pubkey, created_at, kind, tags, content])``."""
    serialized = json.dumps(
        [0, pubkey_hex, created_at, kind, tags, content],
        separators=(",", ":"), ensure_ascii=False,
    )
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Contacts helpers (config-based address book)
# ---------------------------------------------------------------------------

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


def schnorr_sign(privkey_bytes: bytes, msg_hash: bytes) -> bytes:
    """BIP-340 Schnorr sign a 32-byte message hash."""
    pk = ec.PrivateKey(privkey_bytes)
    sig = pk.schnorr_sign(msg_hash)
    return sig.serialize()


def create_nostr_event(privkey_hex: str, recipient_pubkey_hex: str,
                       psbt_base64: str, label: str | None = None) -> dict:
    """Create a signed kind 4 event with NIP-04 encrypted PSBT payload.

    Returns a dict ready for ``["EVENT", event]`` relay publishing.
    """
    privkey_bytes = bytes.fromhex(privkey_hex)
    recipient_bytes = bytes.fromhex(recipient_pubkey_hex)
    our_pubkey_hex = get_xonly_pubkey(privkey_hex)

    payload = create_psbt_payload(psbt_base64, label)
    encrypted = nip04_encrypt(privkey_bytes, recipient_bytes, payload)

    created_at = int(time.time())
    tags = [
        ["p", recipient_pubkey_hex],
        ["expiration", str(created_at + 86400)],
    ]

    event_id = compute_event_id(our_pubkey_hex, created_at, 4, tags, encrypted)
    sig = schnorr_sign(privkey_bytes, bytes.fromhex(event_id))

    return {
        "id": event_id,
        "pubkey": our_pubkey_hex,
        "created_at": created_at,
        "kind": 4,
        "tags": tags,
        "content": encrypted,
        "sig": sig.hex(),
    }
