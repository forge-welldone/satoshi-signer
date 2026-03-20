import base64
import os
import pytest
from remotesigner.psbt_parser import parse_psbt, MAX_PSBT_SIZE

PSBTS_DIR = os.path.join(os.path.dirname(__file__), "psbts")


# Test PSBT: 1 input (1.6M sats), 2 outputs:
#   output 0: 900000 sats payment (P2PKH, no change derivation)
#   output 1: 600000 sats change (P2WPKH, bip32 derivation with internal chain index 1)
# Fee: 100000 sats
# Generated with embit using seed 000102...0f, master fingerprint 3442193e
TEST_PSBT_B64 = (
    "cHNidP8BAHQCAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAD/////AqC7DQAAAAAAGXapFHaKQLvXQMvoHZiOcd4qTVxxOWsdiKzAJwkA"
    "AAAAABYA FJIsKEHyfEd4+Xumxx4KeWhabyxAAAAAAAABAR8AahgAAAAAABYAF"
    "OrbrH82w345NhFot6ruPLJKJTEtIgYCObSzonzR3YmTA41etkSSILNQwyrmL+"
    "wIM7k9uKSQMcUYNEIZPiwAAIAAAACAAAAAgAAAAAAAAAAAAAAiAgOXV8Lhezbm"
    "ViqgyLnguDgeUu7H2VfwFP0qTaq+kSbThg0Qhk+LAAAgAAAAIAAAACAAQAAAA"
    "AAAAAA"
)

# Compact single-string version (no spaces/newlines issues)
TEST_PSBT_B64 = "cHNidP8BAHQCAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/////AqC7DQAAAAAAGXapFHaKQLvXQMvoHZiOcd4qTVxxOWsdiKzAJwkAAAAAABYAFJIsKEHyfEd4+Xumxx4KeWhabyxAAAAAAAABAR8AahgAAAAAABYAFOrbrH82w345NhFot6ruPLJKJTEtIgYCObSzonzR3YmTA41etkSSILNQwyrmL+wIM7k9uKSQMcUYNEIZPiwAAIAAAACAAAAAgAAAAAAAAAAAAAAiAgOXV8LhezbmViqgy7LnguDgeUu7H2VfwFP0qTaq+kSbThg0Qhk+LAAAgAAAAIAAAACAAQAAAAAAAAAA"


class TestParsePsbt:
    def test_parses_valid_psbt_bytes(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        assert isinstance(result, dict)
        assert len(result["inputs"]) >= 1
        assert len(result["outputs"]) >= 1

    def test_returns_fee(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        assert isinstance(result["fee"], int)

    def test_outputs_have_address_and_amount(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        for out in result["outputs"]:
            assert out["amount"] >= 0
            assert "address" in out

    def test_detects_change_output_by_derivation(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        for out in result["outputs"]:
            assert "is_change" in out
        # Output 1 should be detected as change (internal chain index 1 in derivation)
        assert result["outputs"][1]["is_change"] is True
        # Output 0 has no bip32 derivation so should NOT be change
        assert result["outputs"][0]["is_change"] is False

    def test_accepts_bytearray_input(self):
        """Regression: Chaquopy passes jarray('B'), not bytes. Ensure any
        bytes-like input (bytearray, memoryview) is accepted."""
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(bytearray(psbt_bytes))
        assert len(result["inputs"]) >= 1

    def test_rejects_invalid_bytes(self):
        with pytest.raises(ValueError, match="Invalid PSBT"):
            parse_psbt(b"not a psbt")

    def test_returns_signing_status(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        assert result["status"] in ("unsigned", "partially_signed", "fully_signed")

    def test_returns_signer_fingerprints(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        assert isinstance(result["signers"], list)

    def test_single_sig_has_no_multisig_fields(self):
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        result = parse_psbt(psbt_bytes)
        assert "required_sigs" not in result
        assert "total_sigs" not in result


class TestParseMultisigPsbt:
    """Tests for 2-of-3 multisig PSBT parsing with synthetic fixture."""

    @pytest.fixture
    def psbt_bytes(self):
        """Build a 2-of-3 P2WSH multisig PSBT, partially signed (1 of 3)."""
        from embit.psbt import PSBT, DerivationPath
        from embit.transaction import Transaction, TransactionInput, TransactionOutput
        from embit.script import Script, p2wpkh, p2wsh
        from embit import ec
        from io import BytesIO
        import hashlib

        # 3 deterministic keys (fixed seeds for reproducibility)
        keys = [ec.PrivateKey(hashlib.sha256(f"test-key-{i}".encode()).digest())
                for i in range(3)]
        pubs = sorted([k.get_public_key() for k in keys],
                       key=lambda p: p.serialize())

        # 2-of-3 multisig witness script
        script_bytes = b'\x52'  # OP_2
        for pub in pubs:
            ser = pub.serialize()
            script_bytes += bytes([len(ser)]) + ser
        script_bytes += b'\x53'  # OP_3
        script_bytes += b'\xae'  # OP_CHECKMULTISIG
        witness_script = Script(script_bytes)
        wsh = p2wsh(witness_script)

        fake_txid = hashlib.sha256(b"multisig-fixture-prevtx").digest()
        tx = Transaction(
            version=2,
            vin=[TransactionInput(fake_txid, 0, sequence=0xfffffffd)],
            vout=[
                TransactionOutput(90000, p2wpkh(pubs[0])),
                TransactionOutput(9000, wsh),
            ],
            locktime=0,
        )

        psbt = PSBT(tx)
        psbt.inputs[0].witness_utxo = TransactionOutput(100000, wsh)
        psbt.inputs[0].witness_script = witness_script

        # BIP32 derivations: unique fingerprint per signer, mainnet path
        HARDENED = 0x80000000
        for i, pub in enumerate(pubs):
            fp = bytes([i + 1, 0, 0, 0])
            path = [48 | HARDENED, 0 | HARDENED, 0 | HARDENED, 2 | HARDENED, 0, 0]
            psbt.inputs[0].bip32_derivations[pub] = DerivationPath(fp, path)

        # One partial signature (first signer) — dummy DER-encoded sig
        psbt.inputs[0].partial_sigs[pubs[0]] = b'\x30\x44' + b'\x00' * 68

        buf = BytesIO()
        psbt.write_to(buf)
        return buf.getvalue()

    def test_partially_signed_status(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["status"] == "partially_signed"

    def test_required_and_total_sigs(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["required_sigs"] == 2
        assert result["total_sigs"] == 3

    def test_reports_all_signers(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert len(result["signers"]) == 3
        signed = [s for s in result["signers"] if s["signed"]]
        unsigned = [s for s in result["signers"] if not s["signed"]]
        assert len(signed) == 1
        assert len(unsigned) == 2

    def test_outputs_have_addresses(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert len(result["outputs"]) >= 1
        for out in result["outputs"]:
            assert out["address"] != "unknown"
            assert out["amount"] > 0

    def test_detects_mainnet(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["network"] == "main"


class TestParseOpReturnPsbt:
    """Tests for OP_RETURN output detection and text extraction."""

    @pytest.fixture
    def psbt_bytes(self):
        """Build a single-sig PSBT with an OP_RETURN output."""
        from embit.psbt import PSBT, DerivationPath
        from embit.transaction import Transaction, TransactionInput, TransactionOutput
        from embit.script import Script, p2wpkh
        from embit import ec
        from io import BytesIO
        import hashlib

        key = ec.PrivateKey(hashlib.sha256(b"op-return-fixture-key").digest())
        pub = key.get_public_key()

        # OP_RETURN script: 0x6a + push_len + UTF-8 text
        text = "Synthetic PSBT fixture"
        payload = text.encode("utf-8")
        op_return_script = Script(b'\x6a' + bytes([len(payload)]) + payload)

        fake_txid = hashlib.sha256(b"op-return-fixture-prevtx").digest()
        tx = Transaction(
            version=2,
            vin=[TransactionInput(fake_txid, 0, sequence=0xfffffffd)],
            vout=[
                TransactionOutput(0, op_return_script),
                TransactionOutput(49000, p2wpkh(pub)),
            ],
            locktime=0,
        )

        psbt = PSBT(tx)
        psbt.inputs[0].witness_utxo = TransactionOutput(50000, p2wpkh(pub))

        HARDENED = 0x80000000
        fp = bytes([0xaa, 0xbb, 0xcc, 0xdd])
        path = [84 | HARDENED, 0 | HARDENED, 0 | HARDENED, 0, 0]
        psbt.inputs[0].bip32_derivations[pub] = DerivationPath(fp, path)

        buf = BytesIO()
        psbt.write_to(buf)
        return buf.getvalue()

    def test_detects_op_return_output(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["outputs"][0]["op_return"] == "Synthetic PSBT fixture"

    def test_op_return_amount_is_zero(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["outputs"][0]["amount"] == 0

    def test_non_op_return_output_has_no_field(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert "op_return" not in result["outputs"][1]


SINGLESIG_TESTNET_PATH = os.path.join(PSBTS_DIR, "singlesig_testnet3.psbt")


@pytest.mark.skipif(
    not os.path.exists(SINGLESIG_TESTNET_PATH),
    reason="Singlesig testnet PSBT fixture not present",
)
class TestParseTestnetPsbt:
    """Tests for testnet PSBT address encoding."""

    @pytest.fixture
    def psbt_bytes(self):
        with open(SINGLESIG_TESTNET_PATH, "rb") as f:
            return f.read()

    def test_detects_testnet(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["network"] == "test"

    def test_addresses_use_testnet_prefix(self, psbt_bytes):
        """Bug regression: testnet PSBTs must show tb1 addresses, not bc1."""
        result = parse_psbt(psbt_bytes)
        for inp in result["inputs"]:
            if inp["address"] != "unknown":
                assert inp["address"].startswith("tb1"), (
                    f"Input address {inp['address']} should start with tb1"
                )
        for out in result["outputs"]:
            if out["address"] != "unknown":
                assert out["address"].startswith("tb1"), (
                    f"Output address {out['address']} should start with tb1"
                )


class TestPsbtSizeLimit:
    """parse_psbt should reject oversized PSBTs (#23)."""

    def test_rejects_psbt_exceeding_size_limit(self):
        """PSBT bytes larger than MAX_PSBT_SIZE should raise ValueError."""
        oversized = b"psbt\xff" + b"\x00" * MAX_PSBT_SIZE
        with pytest.raises(ValueError, match="too large"):
            parse_psbt(oversized)

    def test_accepts_psbt_at_size_limit(self):
        """PSBT bytes exactly at MAX_PSBT_SIZE should not be rejected for size."""
        # This will fail parsing (not valid PSBT content) but NOT for size.
        at_limit = b"psbt\xff" + b"\x00" * (MAX_PSBT_SIZE - 5)
        assert len(at_limit) == MAX_PSBT_SIZE
        with pytest.raises(ValueError, match="Invalid PSBT"):
            parse_psbt(at_limit)

    def test_size_limit_is_1mb(self):
        """MAX_PSBT_SIZE should be 1MB (1_048_576 bytes)."""
        assert MAX_PSBT_SIZE == 1_048_576
