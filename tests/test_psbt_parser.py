import base64
import os
import pytest
from remotesigner.psbt_parser import parse_psbt

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


MULTISIG_PSBT_PATH = os.path.join(PSBTS_DIR, "trezor.multisig.2.a-ads-7d42c2e3.psbt")


@pytest.mark.skipif(
    not os.path.exists(MULTISIG_PSBT_PATH),
    reason="Multisig PSBT fixture not present",
)
class TestParseMultisigPsbt:
    """Regression tests for multisig PSBT parsing."""

    @pytest.fixture
    def psbt_bytes(self):
        with open(MULTISIG_PSBT_PATH, "rb") as f:
            return f.read()

    def test_parses_partially_signed_multisig(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["status"] == "partially_signed"

    def test_extracts_required_sigs(self, psbt_bytes):
        """Bug regression: m-of-n requirements must be reported."""
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

    def test_outputs_have_address(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert len(result["outputs"]) >= 1
        for out in result["outputs"]:
            assert out["address"] != "unknown"
            assert out["amount"] > 0

    def test_detects_mainnet(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["network"] == "main"


OP_RETURN_PSBT_PATH = os.path.join(PSBTS_DIR, "aa_cold3_watch-f1516d7b.psbt")


@pytest.mark.skipif(
    not os.path.exists(OP_RETURN_PSBT_PATH),
    reason="OP_RETURN PSBT fixture not present",
)
class TestParseOpReturnPsbt:
    """Tests for OP_RETURN output detection and text extraction."""

    @pytest.fixture
    def psbt_bytes(self):
        with open(OP_RETURN_PSBT_PATH, "rb") as f:
            return f.read()

    def test_detects_op_return_output(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        op_return_out = result["outputs"][0]
        assert op_return_out["op_return"] == "Your ad here - https://aads.com/"

    def test_op_return_output_amount_is_zero(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        op_return_out = result["outputs"][0]
        assert op_return_out["amount"] == 0

    def test_non_op_return_output_has_no_op_return_field(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        change_out = result["outputs"][1]
        assert "op_return" not in change_out


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
