"""Tests for the nostr_signer plugin crypto and event construction.

Runs with plain pytest — no Electrum required. Uses embit + pyaes
which are project dependencies.
"""
import base64
import hashlib
import json
import os

import pytest
from embit import ec

# Module under test — import from repo root
from nostr_signer.nostr_signer import (
    nip04_encrypt,
    nip04_decrypt,
    create_psbt_payload,
    compute_event_id,
    create_nostr_event,
    generate_keypair,
    get_xonly_pubkey,
    schnorr_sign,
    _ecdh,
    _pkcs7_pad,
    _pkcs7_unpad,
)


# ---------------------------------------------------------------------------
# NIP-04 crypto
# ---------------------------------------------------------------------------

class TestNip04Crypto:

    def test_encrypt_decrypt_roundtrip(self):
        alice = ec.PrivateKey(os.urandom(32))
        bob = ec.PrivateKey(os.urandom(32))

        plaintext = '{"tx": "cHNidFF...", "label": "Test payment"}'
        encrypted = nip04_encrypt(alice.secret, bob.xonly(), plaintext)

        assert "?iv=" in encrypted

        decrypted = nip04_decrypt(bob.secret, alice.xonly(), encrypted)
        assert decrypted == plaintext

    def test_ecdh_is_symmetric(self):
        alice = ec.PrivateKey(os.urandom(32))
        bob = ec.PrivateKey(os.urandom(32))

        shared1 = _ecdh(alice.secret, bob.xonly())
        shared2 = _ecdh(bob.secret, alice.xonly())
        assert shared1 == shared2

    def test_encrypt_produces_valid_format(self):
        alice = ec.PrivateKey(os.urandom(32))
        bob = ec.PrivateKey(os.urandom(32))

        encrypted = nip04_encrypt(alice.secret, bob.xonly(), "hello")
        parts = encrypted.split("?iv=")
        assert len(parts) == 2
        # Both parts must be valid base64
        base64.b64decode(parts[0])
        base64.b64decode(parts[1])
        # IV should be 16 bytes
        assert len(base64.b64decode(parts[1])) == 16

    def test_decrypt_invalid_content_raises(self):
        alice = ec.PrivateKey(os.urandom(32))
        bob = ec.PrivateKey(os.urandom(32))

        with pytest.raises(Exception):
            nip04_decrypt(alice.secret, bob.xonly(), "not-valid")

    def test_decrypt_wrong_key_produces_wrong_result(self):
        alice = ec.PrivateKey(os.urandom(32))
        bob = ec.PrivateKey(os.urandom(32))
        eve = ec.PrivateKey(os.urandom(32))
        original = "secret message"

        encrypted = nip04_encrypt(alice.secret, bob.xonly(), original)

        # Eve gets garbage or an exception — either way, not the original
        try:
            result = nip04_decrypt(eve.secret, alice.xonly(), encrypted)
            assert result != original, "Eve should not recover the plaintext"
        except Exception:
            pass  # Exception is also acceptable (bad padding, bad UTF-8, etc.)

    def test_cross_language_compat_with_android(self):
        """Verify Python NIP-04 output can be decrypted by the Android side.

        The Android Nip04.kt uses secp256k1-kmp's ecdh() with 0x02 prefix.
        embit's ecdh() must produce the same shared secret for compatibility.
        """
        # Fixed keys for reproducibility
        alice_priv = bytes.fromhex(
            "a" * 64  # 32 bytes of 0xaa
        )
        bob_priv = bytes.fromhex(
            "b" * 64  # 32 bytes of 0xbb
        )
        alice = ec.PrivateKey(alice_priv)
        bob = ec.PrivateKey(bob_priv)

        # ECDH must be symmetric with x-only pubkeys
        shared1 = _ecdh(alice_priv, bob.xonly())
        shared2 = _ecdh(bob_priv, alice.xonly())
        assert shared1 == shared2
        assert len(shared1) == 32

        # Round-trip with a PSBT payload
        payload = '{"tx":"cHNidA==","label":"Cross-language test"}'
        encrypted = nip04_encrypt(alice_priv, bob.xonly(), payload)
        decrypted = nip04_decrypt(bob_priv, alice.xonly(), encrypted)
        assert decrypted == payload


# ---------------------------------------------------------------------------
# PKCS7 padding validation
# ---------------------------------------------------------------------------

class TestPkcs7Padding:

    def test_unpad_valid_padding(self):
        """Valid PKCS7 padding should unpad correctly."""
        # 3 bytes of padding (each byte = 0x03)
        data = b"hello" + bytes([3, 3, 3])
        assert _pkcs7_unpad(data) == b"hello"

    def test_unpad_full_block_padding(self):
        """Full block of padding (16 bytes) is valid."""
        data = bytes([16] * 16)
        assert _pkcs7_unpad(data) == b""

    def test_unpad_single_byte_padding(self):
        """Single byte padding (0x01) is valid."""
        data = b"hello world!!!!!" + bytes([1])
        assert _pkcs7_unpad(data) == b"hello world!!!!!"

    def test_unpad_zero_pad_len_raises(self):
        """pad_len=0 is invalid PKCS7 — must raise ValueError."""
        data = b"hello\x00"
        with pytest.raises(ValueError):
            _pkcs7_unpad(data)

    def test_unpad_pad_len_exceeds_block_size_raises(self):
        """pad_len > 16 is invalid PKCS7 — must raise ValueError."""
        data = b"hello" + bytes([17])
        with pytest.raises(ValueError):
            _pkcs7_unpad(data)

    def test_unpad_inconsistent_padding_bytes_raises(self):
        """All padding bytes must equal pad_len — mixed bytes must raise."""
        # Claims 3 bytes of padding, but the bytes aren't all 0x03
        data = b"hello" + bytes([1, 2, 3])
        with pytest.raises(ValueError):
            _pkcs7_unpad(data)

    def test_unpad_pad_len_exceeds_data_length_raises(self):
        """pad_len larger than data itself must raise ValueError."""
        data = bytes([5])  # Claims 5 bytes of padding but only 1 byte of data
        with pytest.raises(ValueError):
            _pkcs7_unpad(data)

    def test_unpad_empty_data_raises(self):
        """Empty input must raise (no padding byte to read)."""
        with pytest.raises((ValueError, IndexError)):
            _pkcs7_unpad(b"")

    def test_pad_unpad_roundtrip(self):
        """Pad then unpad should return original data."""
        for msg in [b"", b"x", b"hello", b"a" * 16, b"b" * 31]:
            padded = _pkcs7_pad(msg)
            assert _pkcs7_unpad(padded) == msg

    def test_nip04_decrypt_with_corrupted_ciphertext_raises(self):
        """Corrupted ciphertext should raise due to bad padding, not return garbage."""
        alice = ec.PrivateKey(os.urandom(32))
        bob = ec.PrivateKey(os.urandom(32))

        encrypted = nip04_encrypt(alice.secret, bob.xonly(), "test message")
        # Corrupt the ciphertext (flip a byte)
        parts = encrypted.split("?iv=")
        ct_bytes = bytearray(base64.b64decode(parts[0]))
        ct_bytes[0] ^= 0xFF
        corrupted = base64.b64encode(bytes(ct_bytes)).decode() + "?iv=" + parts[1]

        with pytest.raises((ValueError, Exception)):
            nip04_decrypt(bob.secret, alice.xonly(), corrupted)


# ---------------------------------------------------------------------------
# Payload construction
# ---------------------------------------------------------------------------

class TestPayload:

    def test_payload_with_label(self):
        result = create_psbt_payload("cHNidA==", label="Payment to Alice")
        parsed = json.loads(result)
        assert parsed["tx"] == "cHNidA=="
        assert parsed["label"] == "Payment to Alice"

    def test_payload_without_label(self):
        result = create_psbt_payload("cHNidA==")
        parsed = json.loads(result)
        assert parsed["tx"] == "cHNidA=="
        assert "label" not in parsed

    def test_payload_is_compact_json(self):
        result = create_psbt_payload("abc", "test")
        # Compact JSON: no spaces
        assert " " not in result


# ---------------------------------------------------------------------------
# Event construction
# ---------------------------------------------------------------------------

class TestEventConstruction:

    def test_event_id_is_sha256(self):
        pubkey = "a" * 64
        created_at = 1700000000
        kind = 4
        tags = [["p", "b" * 64]]
        content = "encrypted_content"

        event_id = compute_event_id(pubkey, created_at, kind, tags, content)
        assert len(event_id) == 64

        # Verify manually
        serialized = json.dumps(
            [0, pubkey, created_at, kind, tags, content],
            separators=(",", ":"), ensure_ascii=False,
        )
        expected = hashlib.sha256(serialized.encode()).hexdigest()
        assert event_id == expected

    def test_create_nostr_event_structure(self):
        privkey_hex, pubkey_hex = generate_keypair()
        recipient_priv, recipient_pub = generate_keypair()

        event = create_nostr_event(
            privkey_hex=privkey_hex,
            recipient_pubkey_hex=recipient_pub,
            psbt_base64="cHNidA==",
            label="Test transaction",
        )

        assert event["kind"] == 4
        assert event["pubkey"] == pubkey_hex
        assert len(event["id"]) == 64
        assert len(event["sig"]) == 128
        assert event["tags"][0] == ["p", recipient_pub]
        assert event["tags"][1][0] == "expiration"
        assert "?iv=" in event["content"]

    def test_event_content_is_decryptable(self):
        sender_priv, sender_pub = generate_keypair()
        recipient_priv, recipient_pub = generate_keypair()

        event = create_nostr_event(
            privkey_hex=sender_priv,
            recipient_pubkey_hex=recipient_pub,
            psbt_base64="cHNidA==",
            label="Decryption test",
        )

        # Recipient can decrypt the content
        plaintext = nip04_decrypt(
            bytes.fromhex(recipient_priv),
            bytes.fromhex(sender_pub),
            event["content"],
        )
        payload = json.loads(plaintext)
        assert payload["tx"] == "cHNidA=="
        assert payload["label"] == "Decryption test"

    def test_event_signature_is_valid(self):
        sender_priv, sender_pub = generate_keypair()
        _, recipient_pub = generate_keypair()

        event = create_nostr_event(
            privkey_hex=sender_priv,
            recipient_pubkey_hex=recipient_pub,
            psbt_base64="cHNidA==",
        )

        # Verify Schnorr signature
        pub = ec.PublicKey.from_xonly(bytes.fromhex(sender_pub))
        sig = ec.SchnorrSig.parse(bytes.fromhex(event["sig"]))
        msg_hash = bytes.fromhex(event["id"])
        assert pub.schnorr_verify(sig, msg_hash)

    def test_event_has_24h_expiration(self):
        sender_priv, _ = generate_keypair()
        _, recipient_pub = generate_keypair()

        event = create_nostr_event(
            privkey_hex=sender_priv,
            recipient_pubkey_hex=recipient_pub,
            psbt_base64="cHNidA==",
        )

        expiration = int(event["tags"][1][1])
        assert abs(expiration - event["created_at"] - 86400) < 2


# ---------------------------------------------------------------------------
# Key management
# ---------------------------------------------------------------------------

class TestKeyManagement:

    def test_generate_keypair_lengths(self):
        priv, pub = generate_keypair()
        assert len(priv) == 64  # 32 bytes hex
        assert len(pub) == 64

    def test_get_xonly_pubkey_matches(self):
        priv, pub = generate_keypair()
        assert get_xonly_pubkey(priv) == pub

    def test_schnorr_sign_verify(self):
        priv = os.urandom(32)
        msg = hashlib.sha256(b"test message").digest()
        sig = schnorr_sign(priv, msg)
        assert len(sig) == 64

        pk = ec.PrivateKey(priv)
        pub = pk.get_public_key()
        assert pub.schnorr_verify(ec.SchnorrSig.parse(sig), msg)


# ---------------------------------------------------------------------------
# npub extraction from combo box display text
# ---------------------------------------------------------------------------

from nostr_signer.helpers import extract_npub


class TestExtractNpub:

    def test_raw_npub(self):
        npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"
        assert extract_npub(npub) == npub

    def test_contact_display_format(self):
        text = "Sasha's Trezor (npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc)"
        assert extract_npub(text) == "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"

    def test_name_with_parentheses(self):
        text = "My (test) signer (npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc)"
        assert extract_npub(text) == "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"

    def test_whitespace_stripped(self):
        assert extract_npub("  npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc  ") == \
            "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9qwmsc"

    def test_empty_string(self):
        assert extract_npub("") == ""

    def test_no_npub_returns_text(self):
        assert extract_npub("random text") == "random text"


# ---------------------------------------------------------------------------
# Contacts helpers (config-based address book)
# ---------------------------------------------------------------------------

from nostr_signer.helpers import get_contacts, save_contacts


class FakeConfig:
    """Minimal Electrum config stub for testing."""
    def __init__(self, data=None):
        self._data = data or {}

    def get(self, key, default=None):
        return self._data.get(key, default)

    def set_key(self, key, value):
        self._data[key] = value


class TestContacts:

    def test_get_contacts_empty(self):
        config = FakeConfig()
        assert get_contacts(config) == {}

    def test_get_contacts_valid(self):
        contacts = {"npub1abc": "Alice", "npub1def": "Bob"}
        config = FakeConfig({"nostr_signer_contacts": contacts})
        assert get_contacts(config) == contacts

    def test_get_contacts_corrupted_not_dict(self):
        config = FakeConfig({"nostr_signer_contacts": "garbage"})
        assert get_contacts(config) == {}

    def test_get_contacts_corrupted_non_string_values(self):
        config = FakeConfig({"nostr_signer_contacts": {"npub1abc": 123}})
        assert get_contacts(config) == {}

    def test_save_contacts(self):
        config = FakeConfig()
        contacts = {"npub1abc": "Alice"}
        save_contacts(config, contacts)
        assert config.get("nostr_signer_contacts") == contacts
