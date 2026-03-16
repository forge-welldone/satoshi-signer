"""End-to-end signing tests using recorded USB cassettes.

These tests replay pre-recorded Trezor USB exchanges via PlaybackBridge,
testing the full sign_psbt() flow without hardware.

To record a new cassette:
    python tests/sign_cli.py sign <psbt_file> --record tests/cassettes/<name>.json
"""

import base64
import json
import os
import pytest

from remotesigner.signer import sign_psbt

CASSETTES_DIR = os.path.join(os.path.dirname(__file__), "cassettes")


def cassette_path(name: str) -> str:
    return os.path.join(CASSETTES_DIR, f"{name}.json")


def has_cassette(name: str) -> bool:
    return os.path.exists(cassette_path(name))


def load_cassette(name: str) -> dict:
    with open(cassette_path(name)) as f:
        return json.load(f)


@pytest.mark.skipif(
    not has_cassette("single-sig-p2wpkh"),
    reason="Cassette not recorded. Run: python tests/sign_cli.py sign <psbt> --record tests/cassettes/single-sig-p2wpkh.json",
)
class TestSingleSigP2wpkh:
    """E2E signing test for a single-sig P2WPKH transaction."""

    @pytest.fixture
    def cassette(self):
        return load_cassette("single-sig-p2wpkh")

    @pytest.fixture
    def bridge(self, cassette):
        from desktop_bridge import PlaybackBridge

        return PlaybackBridge(cassette)

    def test_sign_returns_signed_psbt(self, bridge, cassette):
        """sign_psbt() produces a signed PSBT from the recorded exchange."""
        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        assert result["status"] in ("complete", "partial")
        assert "psbt" in result
        signed_bytes = base64.b64decode(result["psbt"])
        assert signed_bytes.startswith(b"psbt\xff")
        bridge.assert_consumed()

    def test_sign_inserts_signatures(self, bridge, cassette):
        """Signed PSBT has partial_sigs populated."""
        from embit.psbt import PSBT

        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        assert result["status"] in ("complete", "partial")
        signed_psbt = PSBT.parse(base64.b64decode(result["psbt"]))
        for inp in signed_psbt.inputs:
            assert len(inp.partial_sigs) > 0 or inp.unknown.get(b"\x13")
        bridge.assert_consumed()


@pytest.mark.skipif(
    not has_cassette("multisig-testnet3"),
    reason="Cassette not recorded. Run: python tests/sign_cli.py --network test sign tests/psbts/multisig_testnet3.psbt --record tests/cassettes/multisig-testnet3.json",
)
class TestMultisigTestnet3:
    """E2E signing test for a multisig testnet transaction."""

    @pytest.fixture
    def cassette(self):
        return load_cassette("multisig-testnet3")

    @pytest.fixture
    def bridge(self, cassette):
        from desktop_bridge import PlaybackBridge

        return PlaybackBridge(cassette)

    def test_sign_returns_signed_psbt(self, bridge, cassette):
        """sign_psbt() produces a signed PSBT from the recorded exchange."""
        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        assert result["status"] in ("complete", "partial")
        assert "psbt" in result
        signed_bytes = base64.b64decode(result["psbt"])
        assert signed_bytes.startswith(b"psbt\xff")
        bridge.assert_consumed()

    def test_sign_inserts_signatures(self, bridge, cassette):
        """Signed PSBT has partial_sigs populated."""
        from embit.psbt import PSBT

        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        assert result["status"] in ("complete", "partial")
        signed_psbt = PSBT.parse(base64.b64decode(result["psbt"]))
        for inp in signed_psbt.inputs:
            assert len(inp.partial_sigs) > 0 or inp.unknown.get(b"\x13")
        bridge.assert_consumed()

    def test_partial_when_insufficient_signatures(self, bridge, cassette):
        """2-of-3 multisig with one signer returns partial (not broadcastable)."""
        psbt_b64 = cassette["metadata"].get("input_psbt_b64")
        if not psbt_b64:
            pytest.skip("Cassette missing input_psbt_b64 in metadata")

        psbt_bytes = base64.b64decode(psbt_b64)
        network = cassette["metadata"].get("network", "main")
        result = sign_psbt(psbt_bytes, bridge, network=network)

        assert result["status"] == "partial"
        assert "raw_tx" not in result
        bridge.assert_consumed()
