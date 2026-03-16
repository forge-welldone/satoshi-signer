"""
Core NIP-04 encryption, Nostr event creation, and relay publishing.

Uses embit for secp256k1 (ECDH + Schnorr) and pyaes for AES-256-CBC.
These are standalone — no Electrum imports — so the module is testable
with plain pytest.
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

    Uses ecdsa (pure Python, from trezor deps) for manual point multiplication
    so we get the raw x-coordinate — not the SHA-256 hash that libsecp256k1's
    default ecdh function returns. Both sides (Python + Android) must agree
    on the same shared secret derivation for NIP-04 compatibility.
    """
    from ecdsa import SECP256k1, SigningKey
    from ecdsa.ellipticcurve import Point

    # Reconstruct public key with even y (NIP-04 convention: 0x02 prefix)
    x = int.from_bytes(xonly_bytes, "big")
    p = SECP256k1.curve.p()
    y_sq = (pow(x, 3, p) + 7) % p
    y = pow(y_sq, (p + 1) // 4, p)
    if y % 2 != 0:
        y = p - y

    pubkey_point = Point(SECP256k1.curve, x, y)
    sk = SigningKey.from_string(privkey_bytes, curve=SECP256k1)
    shared_point = pubkey_point * sk.privkey.secret_multiplier
    return shared_point.x().to_bytes(32, "big")


def _pkcs7_pad(data: bytes, block_size: int = 16) -> bytes:
    pad_len = block_size - (len(data) % block_size)
    return data + bytes([pad_len] * pad_len)


def _pkcs7_unpad(data: bytes) -> bytes:
    pad_len = data[-1]
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


# ---------------------------------------------------------------------------
# Relay publishing (async, used from Electrum's event loop)
# ---------------------------------------------------------------------------

DEFAULT_RELAYS = [
    "wss://nos.lol",
    "wss://relay.damus.io",
    "wss://relay.primal.net",
]


async def publish_event(event: dict, relays: list[str] | None = None):
    """Publish a Nostr event to *relays* via WebSocket.

    Uses ``websockets`` if available (Electrum bundles it), otherwise
    falls back to synchronous ``requests``-style approach.
    """
    relays = relays or DEFAULT_RELAYS
    msg = json.dumps(["EVENT", event])
    errors = []

    try:
        import websockets

        for url in relays:
            try:
                async with websockets.connect(url) as ws:
                    await ws.send(msg)
                    # Wait briefly for OK response
                    try:
                        resp = await asyncio.wait_for(ws.recv(), timeout=5)
                    except Exception:
                        resp = None
                    return {"status": "ok", "relay": url, "response": resp}
            except Exception as e:
                errors.append(f"{url}: {e}")
    except ImportError:
        errors.append("websockets library not available")

    return {"status": "error", "message": "; ".join(errors)}


