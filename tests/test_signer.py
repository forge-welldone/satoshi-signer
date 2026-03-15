"""Tests for the signer module.

Focuses on script-type detection and PSBT-to-trezorlib conversion.
End-to-end sign_psbt tests require a real Trezor and are not included.
"""

import base64
import os
from unittest.mock import MagicMock, patch

import pytest
from trezorlib.messages import InputScriptType, OutputScriptType

from remotesigner.signer import (
    detect_script_type,
    is_p2pkh,
    is_p2sh,
    is_witness,
    _input_script_type_to_output,
    _parse_multisig_script,
    psbt_to_trezor_inputs,
    psbt_to_trezor_outputs,
    psbt_to_prev_txes,
    _get_master_fingerprint,
)


# ---------------------------------------------------------------------------
# Test script helpers: is_p2pkh
# ---------------------------------------------------------------------------

class TestIsP2pkh:
    def test_valid_p2pkh(self):
        # OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG
        script = b"\x76\xa9\x14" + b"\xab" * 20 + b"\x88\xac"
        assert is_p2pkh(script) is True

    def test_wrong_length(self):
        script = b"\x76\xa9\x14" + b"\xab" * 19 + b"\x88\xac"
        assert is_p2pkh(script) is False

    def test_wrong_prefix(self):
        script = b"\x00\xa9\x14" + b"\xab" * 20 + b"\x88\xac"
        assert is_p2pkh(script) is False

    def test_wrong_suffix(self):
        script = b"\x76\xa9\x14" + b"\xab" * 20 + b"\x88\x00"
        assert is_p2pkh(script) is False

    def test_empty(self):
        assert is_p2pkh(b"") is False

    def test_p2sh_not_p2pkh(self):
        script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        assert is_p2pkh(script) is False


# ---------------------------------------------------------------------------
# Test script helpers: is_p2sh
# ---------------------------------------------------------------------------

class TestIsP2sh:
    def test_valid_p2sh(self):
        # OP_HASH160 <20-byte hash> OP_EQUAL
        script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        assert is_p2sh(script) is True

    def test_wrong_length(self):
        script = b"\xa9\x14" + b"\xab" * 19 + b"\x87"
        assert is_p2sh(script) is False

    def test_wrong_prefix(self):
        script = b"\x00\x14" + b"\xab" * 20 + b"\x87"
        assert is_p2sh(script) is False

    def test_wrong_suffix(self):
        script = b"\xa9\x14" + b"\xab" * 20 + b"\x00"
        assert is_p2sh(script) is False

    def test_empty(self):
        assert is_p2sh(b"") is False


# ---------------------------------------------------------------------------
# Test script helpers: is_witness
# ---------------------------------------------------------------------------

class TestIsWitness:
    def test_p2wpkh_v0(self):
        # OP_0 PUSH(20 bytes)
        script = b"\x00\x14" + b"\xab" * 20
        is_wit, ver, prog = is_witness(script)
        assert is_wit is True
        assert ver == 0
        assert prog == b"\xab" * 20

    def test_p2wsh_v0(self):
        # OP_0 PUSH(32 bytes)
        script = b"\x00\x20" + b"\xcd" * 32
        is_wit, ver, prog = is_witness(script)
        assert is_wit is True
        assert ver == 0
        assert prog == b"\xcd" * 32

    def test_p2tr_v1(self):
        # OP_1 (0x51) PUSH(32 bytes)
        script = b"\x51\x20" + b"\xef" * 32
        is_wit, ver, prog = is_witness(script)
        assert is_wit is True
        assert ver == 1
        assert prog == b"\xef" * 32

    def test_witness_v16(self):
        # OP_16 (0x60) PUSH(32 bytes)
        script = b"\x60\x20" + b"\x01" * 32
        is_wit, ver, prog = is_witness(script)
        assert is_wit is True
        assert ver == 16
        assert prog == b"\x01" * 32

    def test_non_witness_p2pkh(self):
        script = b"\x76\xa9\x14" + b"\xab" * 20 + b"\x88\xac"
        is_wit, ver, prog = is_witness(script)
        assert is_wit is False

    def test_non_witness_p2sh(self):
        script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        is_wit, ver, prog = is_witness(script)
        assert is_wit is False

    def test_too_short(self):
        is_wit, _, _ = is_witness(b"\x00\x01\xab")
        assert is_wit is False

    def test_empty(self):
        is_wit, _, _ = is_witness(b"")
        assert is_wit is False

    def test_length_mismatch(self):
        # Claims 20-byte push but only has 19 bytes
        script = b"\x00\x14" + b"\xab" * 19
        is_wit, _, _ = is_witness(script)
        assert is_wit is False

    def test_invalid_version_byte(self):
        # 0x02 is not OP_0 or OP_1..OP_16
        script = b"\x02\x14" + b"\xab" * 20
        is_wit, _, _ = is_witness(script)
        assert is_wit is False

    def test_program_too_long(self):
        # Program of 41 bytes (max is 40)
        script = b"\x00\x29" + b"\xab" * 41
        is_wit, _, _ = is_witness(script)
        assert is_wit is False


# ---------------------------------------------------------------------------
# Test detect_script_type
# ---------------------------------------------------------------------------

class TestDetectScriptType:
    def test_p2wpkh(self):
        script = b"\x00\x14" + b"\xab" * 20
        assert detect_script_type(script) == InputScriptType.SPENDWITNESS

    def test_p2wsh(self):
        script = b"\x00\x20" + b"\xab" * 32
        assert detect_script_type(script) == InputScriptType.SPENDWITNESS

    def test_p2tr(self):
        script = b"\x51\x20" + b"\xab" * 32
        assert detect_script_type(script) == InputScriptType.SPENDTAPROOT

    def test_p2pkh(self):
        script = b"\x76\xa9\x14" + b"\xab" * 20 + b"\x88\xac"
        assert detect_script_type(script) == InputScriptType.SPENDADDRESS

    def test_p2sh_p2wpkh(self):
        """P2SH wrapper around P2WPKH."""
        p2sh_script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        # Redeem script is P2WPKH: OP_0 <20-byte hash>
        redeem = b"\x00\x14" + b"\xcd" * 20
        assert detect_script_type(p2sh_script, redeem) == InputScriptType.SPENDP2SHWITNESS

    def test_p2sh_p2wsh(self):
        """P2SH wrapper around P2WSH."""
        p2sh_script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        # Redeem script is P2WSH: OP_0 <32-byte hash>
        redeem = b"\x00\x20" + b"\xcd" * 32
        assert detect_script_type(p2sh_script, redeem) == InputScriptType.SPENDP2SHWITNESS

    def test_p2sh_bare_multisig(self):
        """P2SH with a non-witness redeem script => legacy multisig."""
        p2sh_script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        # Redeem script that is NOT witness (just some script bytes)
        redeem = b"\x51\x21" + b"\x02" * 33 + b"\x51\xae"  # 1-of-1 multisig
        assert detect_script_type(p2sh_script, redeem) == InputScriptType.SPENDMULTISIG

    def test_p2sh_without_redeem_script(self):
        """P2SH without redeem_script information — defaults to SPENDMULTISIG."""
        p2sh_script = b"\xa9\x14" + b"\xab" * 20 + b"\x87"
        # No redeem script provided — bare P2SH
        assert detect_script_type(p2sh_script, None) == InputScriptType.SPENDADDRESS

    def test_unknown_script_defaults(self):
        """Unknown script falls back to SPENDADDRESS."""
        assert detect_script_type(b"\xaa\xbb\xcc") == InputScriptType.SPENDADDRESS


# ---------------------------------------------------------------------------
# Test _input_script_type_to_output
# ---------------------------------------------------------------------------

class TestInputScriptTypeToOutput:
    def test_spendaddress(self):
        assert _input_script_type_to_output(InputScriptType.SPENDADDRESS) == OutputScriptType.PAYTOADDRESS

    def test_spendwitness(self):
        assert _input_script_type_to_output(InputScriptType.SPENDWITNESS) == OutputScriptType.PAYTOWITNESS

    def test_spendp2shwitness(self):
        assert _input_script_type_to_output(InputScriptType.SPENDP2SHWITNESS) == OutputScriptType.PAYTOP2SHWITNESS

    def test_spendtaproot(self):
        assert _input_script_type_to_output(InputScriptType.SPENDTAPROOT) == OutputScriptType.PAYTOTAPROOT

    def test_spendmultisig(self):
        assert _input_script_type_to_output(InputScriptType.SPENDMULTISIG) == OutputScriptType.PAYTOMULTISIG


# ---------------------------------------------------------------------------
# Test PSBT-to-trezor conversion with a real PSBT
# ---------------------------------------------------------------------------

# Same test PSBT from test_psbt_parser.py: 1 P2WPKH input, 2 outputs, fp=3442193e
TEST_PSBT_B64 = "cHNidP8BAHQCAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/////AqC7DQAAAAAAGXapFHaKQLvXQMvoHZiOcd4qTVxxOWsdiKzAJwkAAAAAABYAFJIsKEHyfEd4+Xumxx4KeWhabyxAAAAAAAABAR8AahgAAAAAABYAFOrbrH82w345NhFot6ruPLJKJTEtIgYCObSzonzR3YmTA41etkSSILNQwyrmL+wIM7k9uKSQMcUYNEIZPiwAAIAAAACAAAAAgAAAAAAAAAAAAAAiAgOXV8LhezbmViqgy7LnguDgeUu7H2VfwFP0qTaq+kSbThg0Qhk+LAAAgAAAAIAAAACAAQAAAAAAAAAA"

MASTER_FP = bytes.fromhex("3442193e")


class TestPsbtToTrezorInputs:
    def test_converts_single_input(self):
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        inputs, to_ignore = psbt_to_trezor_inputs(psbt, MASTER_FP)

        assert len(inputs) == 1
        assert to_ignore == []
        # It should be a witness input (P2WPKH)
        assert inputs[0].script_type == InputScriptType.SPENDWITNESS
        assert inputs[0].amount == 1600000
        assert inputs[0].prev_hash == b"\x00" * 32
        assert inputs[0].prev_index == 0
        # address_n should match the derivation path from the PSBT
        assert len(inputs[0].address_n) > 0

    def test_unmatched_fp_goes_to_ignore(self):
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        wrong_fp = b"\xde\xad\xbe\xef"
        inputs, to_ignore = psbt_to_trezor_inputs(psbt, wrong_fp)

        assert len(inputs) == 1
        assert to_ignore == [0]


class TestPsbtToTrezorOutputs:
    def test_converts_two_outputs(self):
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        outputs = psbt_to_trezor_outputs(psbt, MASTER_FP, "main")

        assert len(outputs) == 2

        # Output 0: payment (external) — should have address set
        assert outputs[0].address is not None
        assert outputs[0].amount == 900000

        # Output 1: change — should have address_n set
        assert len(outputs[1].address_n) > 0
        assert outputs[1].amount == 600000

    def test_all_external_if_wrong_fp(self):
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        wrong_fp = b"\xde\xad\xbe\xef"
        outputs = psbt_to_trezor_outputs(psbt, wrong_fp, "main")

        # All outputs should be external (address-based)
        for out in outputs:
            assert out.address is not None


class TestPsbtToPrevTxes:
    def test_no_prev_txes_for_witness_utxo_only(self):
        """The test PSBT has only witness_utxo, no non_witness_utxo."""
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        prev_txes = psbt_to_prev_txes(psbt)
        # witness_utxo only → no previous transactions needed
        assert len(prev_txes) == 0


# ---------------------------------------------------------------------------
# Test _parse_multisig_script
# ---------------------------------------------------------------------------

def _make_multisig_script(pubs, m):
    """Helper: build OP_m <pubs...> OP_n OP_CHECKMULTISIG."""
    n = len(pubs)
    script = bytes([0x50 + m])
    for pub in pubs:
        script += bytes([len(pub)]) + pub
    script += bytes([0x50 + n, 0xAE])
    return script


class TestParseMultisigScript:
    def test_valid_2_of_3(self):
        """Parse a standard 2-of-3 multisig script."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32

        script = _make_multisig_script([pub1, pub2, pub3], m=2)

        from embit.psbt import PSBT
        psbt = PSBT.__new__(PSBT)

        result = _parse_multisig_script(script, {}, psbt)
        assert result is not None
        assert result.m == 2
        assert len(result.pubkeys) == 3
        assert len(result.signatures) == 3

    def test_hd_nodes_use_empty_address_n(self):
        """Bug regression: HDNodePathType must use address_n=[] so the Trezor
        uses public_key directly without attempting child derivation."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32

        script = _make_multisig_script([pub1, pub2], m=1)

        from embit.psbt import PSBT
        psbt = PSBT.__new__(PSBT)

        result = _parse_multisig_script(script, {}, psbt)
        assert result is not None
        for hd_node_path in result.pubkeys:
            assert hd_node_path.address_n == []
            assert hd_node_path.node.public_key in (pub1, pub2)

    def test_populates_existing_partial_sigs(self):
        """Bug regression: existing partial_sigs must be placed in the
        signatures array at the correct positions."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32

        script = _make_multisig_script([pub1, pub2, pub3], m=2)

        # Simulate a partial_sigs dict keyed by embit PublicKey objects
        from embit.ec import PublicKey
        sig_for_pub2 = b"\x30\x44" + b"\xaa" * 68 + b"\x01"  # DER sig + sighash
        partial_sigs = {PublicKey.parse(pub2): sig_for_pub2}

        from embit.psbt import PSBT
        psbt = PSBT.__new__(PSBT)

        result = _parse_multisig_script(script, {}, psbt, partial_sigs=partial_sigs)
        assert result is not None
        # pub2 is at index 1 in the script
        assert result.signatures[0] == b""
        assert result.signatures[1] == sig_for_pub2
        assert result.signatures[2] == b""

    def test_not_multisig(self):
        """Non-multisig script returns None."""
        script = b"\x00\x14" + b"\xab" * 20  # P2WPKH
        from embit.psbt import PSBT
        psbt = PSBT.__new__(PSBT)
        result = _parse_multisig_script(script, {}, psbt)
        assert result is None

    def test_empty_script(self):
        from embit.psbt import PSBT
        psbt = PSBT.__new__(PSBT)
        assert _parse_multisig_script(b"", {}, psbt) is None

    def test_too_short(self):
        from embit.psbt import PSBT
        psbt = PSBT.__new__(PSBT)
        assert _parse_multisig_script(b"\x51\x51\xae", {}, psbt) is None


# ---------------------------------------------------------------------------
# Test _get_master_fingerprint
# ---------------------------------------------------------------------------

class TestGetMasterFingerprint:
    def test_returns_4_bytes(self):
        """Mocked client returns a fingerprint correctly."""
        mock_result = MagicMock()
        mock_result.root_fingerprint = 0x3442193E

        with patch("remotesigner.signer.trezor_btc.get_public_node", return_value=mock_result):
            mock_client = MagicMock()
            fp = _get_master_fingerprint(mock_client, "Bitcoin")
            assert fp == b"\x34\x42\x19\x3e"
            assert len(fp) == 4


# ---------------------------------------------------------------------------
# Regression: multisig PSBT-to-trezor conversion with real PSBT
# ---------------------------------------------------------------------------

PSBTS_DIR = os.path.join(os.path.dirname(__file__), "psbts")
MULTISIG_PSBT_PATH = os.path.join(PSBTS_DIR, "trezor.multisig.2.a-ads-7d42c2e3.psbt")


@pytest.mark.skipif(
    not os.path.exists(MULTISIG_PSBT_PATH),
    reason="Multisig PSBT fixture not present",
)
class TestMultisigPsbtConversion:
    """Regression tests for multisig PSBT conversion to trezorlib types."""

    @pytest.fixture
    def psbt(self):
        from embit.psbt import PSBT

        with open(MULTISIG_PSBT_PATH, "rb") as f:
            return PSBT.parse(f.read())

    def test_inputs_have_multisig(self, psbt):
        """Input should include MultisigRedeemScriptType."""
        # Use the first signer's fingerprint (4da3bedb)
        master_fp = bytes.fromhex("4da3bedb")
        inputs, to_ignore = psbt_to_trezor_inputs(psbt, master_fp)

        assert len(inputs) == 1
        assert inputs[0].multisig is not None
        assert inputs[0].multisig.m == 2
        assert len(inputs[0].multisig.pubkeys) == 3

    def test_multisig_hd_nodes_have_empty_address_n(self, psbt):
        """Bug regression: HDNode address_n must be empty to avoid
        derivation with placeholder chain_code."""
        master_fp = bytes.fromhex("4da3bedb")
        inputs, _ = psbt_to_trezor_inputs(psbt, master_fp)

        for hd_node_path in inputs[0].multisig.pubkeys:
            assert hd_node_path.address_n == []

    def test_multisig_preserves_existing_sigs(self, psbt):
        """Bug regression: existing partial_sigs must appear in
        MultisigRedeemScriptType.signatures."""
        master_fp = bytes.fromhex("4da3bedb")
        inputs, _ = psbt_to_trezor_inputs(psbt, master_fp)

        ms = inputs[0].multisig
        # The PSBT has 1 existing signature
        filled = [s for s in ms.signatures if s != b""]
        assert len(filled) == 1

    def test_unmatched_fp_goes_to_ignore(self, psbt):
        """Non-signer fingerprint puts input in to_ignore list."""
        wrong_fp = b"\xde\xad\xbe\xef"
        inputs, to_ignore = psbt_to_trezor_inputs(psbt, wrong_fp)
        assert to_ignore == [0]
