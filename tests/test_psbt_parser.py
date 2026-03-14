import base64
import pytest
from remotesigner.psbt_parser import parse_psbt


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
