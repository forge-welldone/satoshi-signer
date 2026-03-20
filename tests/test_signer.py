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
    _is_psbt_fully_signed,
    _is_relative_path,
    _parse_account_path,
    _node_fingerprint,
    _find_key_origin,
    _find_matching_derivation,
    _parse_multisig_script,
    psbt_to_trezor_inputs,
    psbt_to_trezor_outputs,
    psbt_to_prev_txes,
    _get_master_fingerprint,
    sign_psbt,
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


# ---------------------------------------------------------------------------
# Test _node_fingerprint
# ---------------------------------------------------------------------------

class TestNodeFingerprint:
    def test_known_bip32_vector(self):
        """BIP32 test vector 1: master pubkey → fingerprint 3442193e."""
        pubkey = bytes.fromhex(
            "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2"
        )
        assert _node_fingerprint(pubkey) == bytes.fromhex("3442193e")

    def test_returns_4_bytes(self):
        fp = _node_fingerprint(b"\x02" + b"\x01" * 32)
        assert len(fp) == 4


# ---------------------------------------------------------------------------
# Test _find_key_origin (watch-only wallet path resolution)
# ---------------------------------------------------------------------------

class TestIsRelativePath:
    def test_relative_change_path(self):
        assert _is_relative_path([1, 47]) is True

    def test_relative_receive_path(self):
        assert _is_relative_path([0, 5]) is True

    def test_full_bip84_path(self):
        assert _is_relative_path([0x80000054, 0x80000000, 0x80000000, 1, 47]) is False

    def test_full_bip44_path(self):
        assert _is_relative_path([0x8000002C, 0x80000000, 0x80000000, 0, 0]) is False

    def test_empty_path(self):
        assert _is_relative_path([]) is False


class TestParseAccountPath:
    def test_standard_bip84(self):
        assert _parse_account_path("m/84'/0'/0'") == [0x80000054, 0x80000000, 0x80000000]

    def test_without_m_prefix(self):
        assert _parse_account_path("84'/0'/0'") == [0x80000054, 0x80000000, 0x80000000]

    def test_account_20(self):
        assert _parse_account_path("m/84'/0'/20'") == [0x80000054, 0x80000000, 0x80000014]

    def test_h_suffix(self):
        assert _parse_account_path("84h/0h/20h") == [0x80000054, 0x80000000, 0x80000014]

    def test_bip49(self):
        assert _parse_account_path("m/49'/0'/0'") == [0x80000031, 0x80000000, 0x80000000]

    def test_testnet(self):
        assert _parse_account_path("m/84'/1'/0'") == [0x80000054, 0x80000001, 0x80000000]

    def test_whitespace_tolerance(self):
        assert _parse_account_path("  m/84'/0'/0'  ") == [0x80000054, 0x80000000, 0x80000000]


# ---------------------------------------------------------------------------
# Test _find_matching_derivation
# ---------------------------------------------------------------------------

def _make_mock_scope(bip32_derivations=None, taproot_bip32_derivations=None):
    """Helper: create a mock PSBT scope with derivation dicts."""
    scope = MagicMock()
    scope.bip32_derivations = bip32_derivations or {}
    scope.taproot_bip32_derivations = taproot_bip32_derivations or {}
    return scope


def _make_deriv(fingerprint, derivation):
    """Helper: create a mock derivation object."""
    d = MagicMock()
    d.fingerprint = fingerprint
    d.derivation = derivation
    return d


class TestFindMatchingDerivation:
    """Tests for the extracted _find_matching_derivation helper."""

    def test_matches_master_fp_ecdsa(self):
        """ECDSA input with matching master_fp returns full derivation path."""
        master_fp = b"\x34\x42\x19\x3e"
        deriv = _make_deriv(master_fp, [0x80000054, 0x80000000, 0x80000000, 0, 5])
        pub = MagicMock()
        scope = _make_mock_scope(bip32_derivations={pub: deriv})

        result = _find_matching_derivation(scope, master_fp, None, is_taproot=False)
        assert result == [0x80000054, 0x80000000, 0x80000000, 0, 5]

    def test_matches_master_fp_taproot(self):
        """Taproot input with matching master_fp returns full derivation path."""
        master_fp = b"\x34\x42\x19\x3e"
        deriv = _make_deriv(master_fp, [0x80000056, 0x80000000, 0x80000000, 0, 3])
        pub = MagicMock()
        leaf_hashes = []
        scope = _make_mock_scope(taproot_bip32_derivations={pub: (leaf_hashes, deriv)})

        result = _find_matching_derivation(scope, master_fp, None, is_taproot=True)
        assert result == [0x80000056, 0x80000000, 0x80000000, 0, 3]

    def test_matches_fp_to_prefix_ecdsa(self):
        """ECDSA input with fp_to_prefix prepends prefix to relative path."""
        master_fp = b"\xAA\xBB\xCC\xDD"
        xpub_fp = b"\xDE\xC1\xA7\xC9"
        deriv = _make_deriv(xpub_fp, [1, 47])
        pub = MagicMock()
        scope = _make_mock_scope(bip32_derivations={pub: deriv})

        prefix = [0x80000054, 0x80000000, 0x80000000]
        fp_to_prefix = {xpub_fp: prefix}

        result = _find_matching_derivation(scope, master_fp, fp_to_prefix, is_taproot=False)
        assert result == [0x80000054, 0x80000000, 0x80000000, 1, 47]

    def test_matches_fp_to_prefix_taproot(self):
        """Taproot input with fp_to_prefix prepends prefix to relative path."""
        master_fp = b"\xAA\xBB\xCC\xDD"
        xpub_fp = b"\xDE\xC1\xA7\xC9"
        deriv = _make_deriv(xpub_fp, [0, 12])
        pub = MagicMock()
        scope = _make_mock_scope(taproot_bip32_derivations={pub: ([], deriv)})

        prefix = [0x80000056, 0x80000000, 0x80000000]
        fp_to_prefix = {xpub_fp: prefix}

        result = _find_matching_derivation(scope, master_fp, fp_to_prefix, is_taproot=True)
        assert result == [0x80000056, 0x80000000, 0x80000000, 0, 12]

    def test_fp_to_prefix_takes_priority_over_master_fp(self):
        """When fingerprint is in both fp_to_prefix and matches master_fp,
        fp_to_prefix wins (prefix is prepended)."""
        fp = b"\xDE\xC1\xA7\xC9"
        deriv = _make_deriv(fp, [1, 47])
        pub = MagicMock()
        scope = _make_mock_scope(bip32_derivations={pub: deriv})

        prefix = [0x80000054, 0x80000000, 0x80000000]
        fp_to_prefix = {fp: prefix}

        result = _find_matching_derivation(scope, fp, fp_to_prefix, is_taproot=False)
        assert result == prefix + [1, 47]

    def test_no_match_returns_none(self):
        """Returns None when no derivation matches master_fp or fp_to_prefix."""
        master_fp = b"\xAA\xBB\xCC\xDD"
        other_fp = b"\x11\x22\x33\x44"
        deriv = _make_deriv(other_fp, [0x80000054, 0x80000000, 0x80000000, 0, 0])
        pub = MagicMock()
        scope = _make_mock_scope(bip32_derivations={pub: deriv})

        result = _find_matching_derivation(scope, master_fp, None, is_taproot=False)
        assert result is None

    def test_empty_derivations_returns_none(self):
        """Returns None when scope has no derivations."""
        scope = _make_mock_scope()

        result = _find_matching_derivation(scope, b"\xAA\xBB\xCC\xDD", None, is_taproot=False)
        assert result is None

    def test_empty_fp_to_prefix_falls_through_to_master(self):
        """Empty fp_to_prefix dict doesn't prevent master_fp match."""
        master_fp = b"\x34\x42\x19\x3e"
        deriv = _make_deriv(master_fp, [0x80000054, 0x80000000, 0x80000000, 0, 5])
        pub = MagicMock()
        scope = _make_mock_scope(bip32_derivations={pub: deriv})

        result = _find_matching_derivation(scope, master_fp, {}, is_taproot=False)
        assert result == [0x80000054, 0x80000000, 0x80000000, 0, 5]

    def test_taproot_ignores_ecdsa_derivations(self):
        """When is_taproot=True, only taproot_bip32_derivations are checked."""
        master_fp = b"\x34\x42\x19\x3e"
        deriv = _make_deriv(master_fp, [0x80000054, 0x80000000, 0x80000000, 0, 5])
        pub = MagicMock()
        scope = _make_mock_scope(
            bip32_derivations={pub: deriv},
            taproot_bip32_derivations={},
        )

        result = _find_matching_derivation(scope, master_fp, None, is_taproot=True)
        assert result is None

    def test_ecdsa_ignores_taproot_derivations(self):
        """When is_taproot=False, only bip32_derivations are checked."""
        master_fp = b"\x34\x42\x19\x3e"
        deriv = _make_deriv(master_fp, [0x80000056, 0x80000000, 0x80000000, 0, 3])
        pub = MagicMock()
        scope = _make_mock_scope(
            bip32_derivations={},
            taproot_bip32_derivations={pub: ([], deriv)},
        )

        result = _find_matching_derivation(scope, master_fp, None, is_taproot=False)
        assert result is None


class TestFindKeyOrigin:
    def test_noop_when_paths_are_full(self):
        """No probing needed if PSBT has full BIP paths (hardened first component)."""
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        mock_client = MagicMock()
        result = _find_key_origin(mock_client, "Bitcoin", psbt, MASTER_FP)
        assert result == {}

    def test_resolves_relative_paths_by_pubkey(self):
        """Finds correct account prefix by matching derived pubkey."""
        from embit.psbt import PSBT
        from embit.ec import PublicKey

        target_pubkey = bytes.fromhex(
            "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2"
        )

        # Build a minimal mock PSBT with a relative path
        mock_deriv = MagicMock()
        mock_deriv.fingerprint = b"\xDE\xC1\xA7\xC9"
        mock_deriv.derivation = [1, 47]

        mock_pub = MagicMock()
        mock_pub.sec.return_value = target_pubkey

        mock_inp = MagicMock()
        mock_inp.bip32_derivations = {mock_pub: mock_deriv}
        mock_inp.taproot_bip32_derivations = {}
        mock_inp.utxo.script_pubkey.data = b"\x00\x14" + b"\xab" * 20  # P2WPKH

        mock_psbt = MagicMock()
        mock_psbt.inputs = [mock_inp]

        # Trezor returns matching pubkey for the correct full path
        mock_result = MagicMock()
        mock_result.node.public_key = target_pubkey

        master_fp = b"\xDE\xC1\xA7\xC9"  # Same fingerprint — the tricky case

        with patch(
            "remotesigner.signer.trezor_btc.get_public_node",
            return_value=mock_result,
        ):
            result = _find_key_origin(MagicMock(), "Bitcoin", mock_psbt, master_fp)

        assert master_fp in result
        # BIP84 mainnet account 0
        assert result[master_fp] == [0x80000054, 0x80000000, 0x80000000]

    def test_finds_non_zero_account(self):
        """Can discover account 20 (not just 0-2)."""
        from embit.ec import PublicKey

        target_pubkey = bytes.fromhex(
            "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2"
        )

        mock_deriv = MagicMock()
        mock_deriv.fingerprint = b"\xDE\xC1\xA7\xC9"
        mock_deriv.derivation = [1, 47]

        mock_pub = MagicMock()
        mock_pub.sec.return_value = target_pubkey

        mock_inp = MagicMock()
        mock_inp.bip32_derivations = {mock_pub: mock_deriv}
        mock_inp.taproot_bip32_derivations = {}
        mock_inp.utxo.script_pubkey.data = b"\x00\x14" + b"\xab" * 20

        mock_psbt = MagicMock()
        mock_psbt.inputs = [mock_inp]

        # Only return matching pubkey for account 20
        wrong_pubkey = b"\x02" + b"\xff" * 32

        def side_effect(client, n, coin_name):
            result = MagicMock()
            # Match only when account component is 20
            if len(n) >= 3 and n[2] == 0x80000000 + 20:
                result.node.public_key = target_pubkey
            else:
                result.node.public_key = wrong_pubkey
            return result

        with patch(
            "remotesigner.signer.trezor_btc.get_public_node",
            side_effect=side_effect,
        ):
            result = _find_key_origin(
                MagicMock(), "Bitcoin", mock_psbt, b"\xDE\xC1\xA7\xC9"
            )

        fp = b"\xDE\xC1\xA7\xC9"
        assert fp in result
        # m/84'/0'/20'
        assert result[fp] == [0x80000054, 0x80000000, 0x80000014]


# ---------------------------------------------------------------------------
# Test PSBT-to-trezor conversion with key origin prefix (watch-only wallets)
# ---------------------------------------------------------------------------

class TestPsbtToTrezorInputsWithPrefix:
    def test_prepends_prefix_when_fp_in_prefix_map(self):
        """Input derivation path gets prefix prepended when fingerprint
        is in fp_to_prefix (watch-only wallet with relative paths)."""
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        # Get the original path with matching master_fp
        inputs_orig, _ = psbt_to_trezor_inputs(psbt, MASTER_FP)
        original_path = list(inputs_orig[0].address_n)

        # Call with different master but PSBT's fp mapped to a prefix
        wrong_master = b"\xAA\xBB\xCC\xDD"
        prefix = [0x80000054, 0x80000000, 0x80000000]  # m/84'/0'/0'
        fp_to_prefix = {MASTER_FP: prefix}

        inputs, to_ignore = psbt_to_trezor_inputs(psbt, wrong_master, fp_to_prefix)

        assert to_ignore == []
        assert list(inputs[0].address_n) == prefix + original_path

    def test_no_prefix_needed_when_master_matches(self):
        """Existing behavior: direct master_fp match uses path as-is."""
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        inputs, to_ignore = psbt_to_trezor_inputs(psbt, MASTER_FP, {})

        assert to_ignore == []
        assert len(inputs[0].address_n) > 0


class TestPsbtToTrezorOutputsWithPrefix:
    def test_detects_change_with_prefix_map(self):
        """Change output detected even when master_fp doesn't match,
        via fp_to_prefix mapping."""
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        # Get original change path
        outputs_orig = psbt_to_trezor_outputs(psbt, MASTER_FP, "main")
        original_change_path = list(outputs_orig[1].address_n)

        # With wrong master but prefix map
        wrong_master = b"\xAA\xBB\xCC\xDD"
        prefix = [0x80000054, 0x80000000, 0x80000000]
        fp_to_prefix = {MASTER_FP: prefix}

        outputs = psbt_to_trezor_outputs(psbt, wrong_master, "main", fp_to_prefix)

        # Output 0: external (no bip32 deriv) → address set
        assert outputs[0].address is not None

        # Output 1: change → address_n with prefix, no address
        assert len(outputs[1].address_n) > 0
        assert list(outputs[1].address_n) == prefix + original_change_path

    def test_no_prefix_preserves_existing_behavior(self):
        """Without prefix map, wrong master_fp → all external."""
        from embit.psbt import PSBT

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        psbt = PSBT.parse(psbt_bytes)

        wrong_master = b"\xAA\xBB\xCC\xDD"
        outputs = psbt_to_trezor_outputs(psbt, wrong_master, "main", {})

        for out in outputs:
            assert out.address is not None


# ---------------------------------------------------------------------------
# Test with real watch-only PSBT (skipped if fixture not present)
# ---------------------------------------------------------------------------

WATCH_ONLY_PSBT_PATH = os.path.join(PSBTS_DIR, "aa_cold3_watch-35a87c14.psbt")


@pytest.mark.skipif(
    not os.path.exists(WATCH_ONLY_PSBT_PATH),
    reason="Watch-only PSBT fixture not present",
)
class TestWatchOnlyPsbtConversion:
    """Tests for PSBTs with relative derivation paths (watch-only wallets)."""

    @pytest.fixture
    def psbt(self):
        from embit.psbt import PSBT

        with open(WATCH_ONLY_PSBT_PATH, "rb") as f:
            return PSBT.parse(f.read())

    def test_paths_are_relative(self, psbt):
        """Verify the PSBT has short relative paths (2 components)."""
        for pub, deriv in psbt.inputs[0].bip32_derivations.items():
            assert len(deriv.derivation) == 2

    def test_inputs_with_bip84_prefix(self, psbt):
        """With BIP84 prefix, input gets full derivation path."""
        account_fp = bytes.fromhex("dec1a7c9")
        master_fp = b"\xAA\xBB\xCC\xDD"
        prefix = [0x80000054, 0x80000000, 0x80000000]  # m/84'/0'/0'
        fp_to_prefix = {account_fp: prefix}

        inputs, to_ignore = psbt_to_trezor_inputs(psbt, master_fp, fp_to_prefix)

        assert to_ignore == []
        # Full path: m/84'/0'/0'/1/47
        expected = prefix + [1, 47]
        assert list(inputs[0].address_n) == expected
        assert inputs[0].script_type == InputScriptType.SPENDWITNESS

    def test_change_output_detected_with_prefix(self, psbt):
        """Change output (index 16) marked as change with prefix."""
        account_fp = bytes.fromhex("dec1a7c9")
        master_fp = b"\xAA\xBB\xCC\xDD"
        prefix = [0x80000054, 0x80000000, 0x80000000]
        fp_to_prefix = {account_fp: prefix}

        outputs = psbt_to_trezor_outputs(psbt, master_fp, "main", fp_to_prefix)

        # Output 16 is change (2.62 BTC)
        change = outputs[16]
        expected = prefix + [1, 48]
        assert list(change.address_n) == expected
        assert change.script_type == OutputScriptType.PAYTOWITNESS

    def test_non_change_outputs_have_addresses(self, psbt):
        """All 16 non-change outputs should have addresses."""
        account_fp = bytes.fromhex("dec1a7c9")
        master_fp = b"\xAA\xBB\xCC\xDD"
        prefix = [0x80000054, 0x80000000, 0x80000000]
        fp_to_prefix = {account_fp: prefix}

        outputs = psbt_to_trezor_outputs(psbt, master_fp, "main", fp_to_prefix)

        for i in range(16):
            assert outputs[i].address is not None, f"Output {i} should have address"


# ---------------------------------------------------------------------------
# Test sign_psbt cancellation handling
# ---------------------------------------------------------------------------

class TestSignPsbtCancellation:
    """Trezor ActionCancelled should return 'cancelled' status, not 'error'."""

    def test_action_cancelled_returns_cancelled_status(self):
        """When user cancels on Trezor device, sign_psbt returns cancelled."""
        from trezorlib.exceptions import TrezorFailure
        from trezorlib.messages import Failure, FailureType

        failure = Failure(code=FailureType.ActionCancelled)

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient") as mock_client_cls, \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_client = mock_client_cls.return_value
            mock_btc.get_public_node.return_value = MagicMock(
                root_fingerprint=0x3442193E,
                node=MagicMock(public_key=b"\x02" + b"\xff" * 32),
            )
            mock_btc.sign_tx.side_effect = TrezorFailure(failure)

            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "cancelled"
        assert "message" in result

    def test_pin_cancelled_returns_cancelled_status(self):
        """When user cancels PIN entry, sign_psbt returns cancelled."""
        from trezorlib.exceptions import TrezorFailure
        from trezorlib.messages import Failure, FailureType

        failure = Failure(code=FailureType.PinCancelled)

        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient") as mock_client_cls, \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_client = mock_client_cls.return_value
            mock_btc.get_public_node.return_value = MagicMock(
                root_fingerprint=0x3442193E,
                node=MagicMock(public_key=b"\x02" + b"\xff" * 32),
            )
            mock_btc.sign_tx.side_effect = TrezorFailure(failure)

            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "cancelled"

    def test_passphrase_cancelled_returns_cancelled_status(self):
        """When user cancels passphrase entry (via callback), sign_psbt returns cancelled."""
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient") as mock_client_cls, \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_client = mock_client_cls.return_value
            mock_btc.get_public_node.return_value = MagicMock(
                root_fingerprint=0x3442193E,
                node=MagicMock(public_key=b"\x02" + b"\xff" * 32),
            )
            mock_btc.sign_tx.side_effect = RuntimeError(
                "Passphrase entry cancelled"
            )

            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "cancelled"

    def test_real_error_still_returns_error_status(self):
        """Non-cancellation exceptions still return 'error' status."""
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient") as mock_client_cls, \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_client = mock_client_cls.return_value
            mock_btc.get_public_node.return_value = MagicMock(
                root_fingerprint=0x3442193E,
                node=MagicMock(public_key=b"\x02" + b"\xff" * 32),
            )
            mock_btc.sign_tx.side_effect = RuntimeError("USB device disconnected")

            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "error"
        assert "USB device disconnected" in result["message"]


# ---------------------------------------------------------------------------
# Test _is_psbt_fully_signed
# ---------------------------------------------------------------------------

class _FakeScript:
    """Minimal stand-in for embit.script.Script (just holds .data)."""
    def __init__(self, data: bytes):
        self.data = data


class _FakeInputScope:
    """Minimal duck-type for embit InputScope used by _is_psbt_fully_signed."""
    def __init__(self, partial_sigs=None, tap_key_sig=None,
                 witness_script=None, redeem_script=None):
        self.unknown = {}
        self.partial_sigs = partial_sigs or {}
        self.witness_script = None
        self.redeem_script = None
        if tap_key_sig is not None:
            self.unknown[b"\x13"] = tap_key_sig
        if witness_script is not None:
            self.witness_script = _FakeScript(witness_script)
        if redeem_script is not None:
            self.redeem_script = _FakeScript(redeem_script)


class _FakePsbt:
    """Minimal duck-type for embit PSBT used by _is_psbt_fully_signed."""
    def __init__(self, inputs):
        self.inputs = list(inputs)


class TestIsPsbtFullySigned:
    """Direct unit tests for _is_psbt_fully_signed.

    Three code paths: taproot key-path, multisig threshold, single-sig ECDSA.
    """

    # -- Taproot key-path (PSBT_IN_TAP_KEY_SIG = 0x13) --------------------

    def test_taproot_keypath_signed(self):
        """Input with taproot key-path signature is considered signed."""
        inp = _FakeInputScope(tap_key_sig=b"\xab" * 64)
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    def test_taproot_keypath_no_sig(self):
        """Input without any signature is not signed."""
        inp = _FakeInputScope()
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is False

    def test_taproot_multiple_inputs_all_signed(self):
        """Multiple taproot inputs all signed."""
        inp1 = _FakeInputScope(tap_key_sig=b"\xab" * 64)
        inp2 = _FakeInputScope(tap_key_sig=b"\xcd" * 64)
        psbt = _FakePsbt([inp1, inp2])
        assert _is_psbt_fully_signed(psbt) is True

    def test_taproot_multiple_inputs_one_unsigned(self):
        """Multiple taproot inputs, one missing signature → not fully signed."""
        inp1 = _FakeInputScope(tap_key_sig=b"\xab" * 64)
        inp2 = _FakeInputScope()
        psbt = _FakePsbt([inp1, inp2])
        assert _is_psbt_fully_signed(psbt) is False

    # -- Single-sig ECDSA (partial_sigs) -----------------------------------

    def test_single_sig_ecdsa_signed(self):
        """Single-sig input with one partial_sig is signed."""
        pub = b"\x02" + b"\x01" * 32
        inp = _FakeInputScope(partial_sigs={pub: b"\x30" + b"\x00" * 70})
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    def test_single_sig_ecdsa_unsigned(self):
        """Single-sig input with no partial_sigs is not signed."""
        inp = _FakeInputScope()
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is False

    def test_single_sig_multiple_inputs(self):
        """Multiple single-sig inputs, all signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        inp1 = _FakeInputScope(partial_sigs={pub1: b"\x30" * 71})
        inp2 = _FakeInputScope(partial_sigs={pub2: b"\x30" * 71})
        psbt = _FakePsbt([inp1, inp2])
        assert _is_psbt_fully_signed(psbt) is True

    def test_single_sig_one_unsigned(self):
        """Multiple single-sig inputs, one missing signature → not fully signed."""
        pub1 = b"\x02" + b"\x01" * 32
        inp1 = _FakeInputScope(partial_sigs={pub1: b"\x30" * 71})
        inp2 = _FakeInputScope()
        psbt = _FakePsbt([inp1, inp2])
        assert _is_psbt_fully_signed(psbt) is False

    # -- Multisig threshold (witness_script) --------------------------------

    def test_multisig_2of3_fully_signed(self):
        """2-of-3 multisig with 2 signatures is fully signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_script = _make_multisig_script([pub1, pub2, pub3], m=2)
        inp = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71, pub2: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    def test_multisig_2of3_one_sig_partial(self):
        """2-of-3 multisig with only 1 signature is partial."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_script = _make_multisig_script([pub1, pub2, pub3], m=2)
        inp = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is False

    def test_multisig_2of3_zero_sigs(self):
        """2-of-3 multisig with 0 signatures is not signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_script = _make_multisig_script([pub1, pub2, pub3], m=2)
        inp = _FakeInputScope(witness_script=ms_script)
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is False

    def test_multisig_1of2_signed(self):
        """1-of-2 multisig with 1 signature is fully signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        ms_script = _make_multisig_script([pub1, pub2], m=1)
        inp = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    def test_multisig_3of3_needs_all(self):
        """3-of-3 multisig needs all 3 signatures."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_script = _make_multisig_script([pub1, pub2, pub3], m=3)
        # Only 2 sigs → partial
        inp = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71, pub2: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is False

    def test_multisig_3of3_fully_signed(self):
        """3-of-3 multisig with all 3 signatures is fully signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_script = _make_multisig_script([pub1, pub2, pub3], m=3)
        inp = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={
                pub1: b"\x30" * 71,
                pub2: b"\x30" * 71,
                pub3: b"\x30" * 71,
            },
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    def test_multisig_excess_sigs_ok(self):
        """2-of-3 multisig with 3 signatures (more than m) is still fully signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_script = _make_multisig_script([pub1, pub2, pub3], m=2)
        inp = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={
                pub1: b"\x30" * 71,
                pub2: b"\x30" * 71,
                pub3: b"\x30" * 71,
            },
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    # -- Multisig via redeem_script (bare P2SH, not P2SH-P2WSH) -----------

    def test_multisig_redeem_script_p2sh(self):
        """Multisig in redeem_script (bare P2SH) is recognized."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        ms_script = _make_multisig_script([pub1, pub2], m=2)
        inp = _FakeInputScope(
            redeem_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71, pub2: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is True

    def test_multisig_redeem_script_partial(self):
        """Multisig in redeem_script with insufficient sigs is partial."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        ms_script = _make_multisig_script([pub1, pub2], m=2)
        inp = _FakeInputScope(
            redeem_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        assert _is_psbt_fully_signed(psbt) is False

    def test_p2sh_p2wsh_witness_program_skipped(self):
        """P2SH-P2WSH: redeem_script is a witness program → not treated as multisig.

        The witness_script field should contain the actual multisig script.
        When redeem_script is just a witness program (OP_0 <32-byte hash>),
        it should be ignored for multisig detection.
        """
        # OP_0 PUSH(32) <hash> — a witness program v0
        witness_program = b"\x00\x20" + b"\xaa" * 32
        pub1 = b"\x02" + b"\x01" * 32
        inp = _FakeInputScope(
            redeem_script=witness_program,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        # Falls through to single-sig path → 1 partial_sig is enough
        assert _is_psbt_fully_signed(psbt) is True

    # -- witness_script takes priority over redeem_script ------------------

    def test_witness_script_priority_over_redeem(self):
        """witness_script multisig takes priority over redeem_script."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        ms_2of3 = _make_multisig_script([pub1, pub2, pub3], m=2)
        ms_1of2 = _make_multisig_script([pub1, pub2], m=1)
        # witness_script says 2-of-3, redeem_script says 1-of-2
        inp = _FakeInputScope(
            witness_script=ms_2of3,
            redeem_script=ms_1of2,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        # Only 1 sig but m=2 from witness_script → partial
        assert _is_psbt_fully_signed(psbt) is False

    # -- Mixed input types -------------------------------------------------

    def test_mixed_taproot_and_singlesig(self):
        """PSBT with both taproot and single-sig inputs, all signed."""
        pub = b"\x02" + b"\x01" * 32
        inp_tr = _FakeInputScope(tap_key_sig=b"\xab" * 64)
        inp_ss = _FakeInputScope(partial_sigs={pub: b"\x30" * 71})
        psbt = _FakePsbt([inp_tr, inp_ss])
        assert _is_psbt_fully_signed(psbt) is True

    def test_mixed_taproot_and_multisig(self):
        """PSBT with taproot and multisig inputs, all signed."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        ms_script = _make_multisig_script([pub1, pub2], m=2)
        inp_tr = _FakeInputScope(tap_key_sig=b"\xab" * 64)
        inp_ms = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71, pub2: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp_tr, inp_ms])
        assert _is_psbt_fully_signed(psbt) is True

    def test_mixed_multisig_partial_blocks_all(self):
        """One partial multisig input blocks the whole PSBT."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        ms_script = _make_multisig_script([pub1, pub2], m=2)
        inp_tr = _FakeInputScope(tap_key_sig=b"\xab" * 64)
        inp_ms = _FakeInputScope(
            witness_script=ms_script,
            partial_sigs={pub1: b"\x30" * 71},  # only 1 of 2
        )
        psbt = _FakePsbt([inp_tr, inp_ms])
        assert _is_psbt_fully_signed(psbt) is False

    # -- Edge cases --------------------------------------------------------

    def test_empty_psbt_no_inputs(self):
        """PSBT with no inputs is trivially fully signed."""
        psbt = _FakePsbt([])
        assert _is_psbt_fully_signed(psbt) is True

    def test_short_script_not_multisig(self):
        """A witness_script shorter than 37 bytes is not treated as multisig."""
        # Short script ending with OP_CHECKMULTISIG
        short_script = bytes([0x52, 0xAE])
        pub1 = b"\x02" + b"\x01" * 32
        inp = _FakeInputScope(
            witness_script=short_script,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        # Falls through to single-sig path
        assert _is_psbt_fully_signed(psbt) is True

    def test_script_without_checkmultisig_not_multisig(self):
        """A witness_script not ending with OP_CHECKMULTISIG → single-sig path."""
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        # Build a 37+ byte script that does NOT end with 0xAE
        non_ms_script = b"\x52" + bytes([0x21]) + pub1 + bytes([0x51, 0xAC])
        inp = _FakeInputScope(
            witness_script=non_ms_script,
            partial_sigs={pub1: b"\x30" * 71},
        )
        psbt = _FakePsbt([inp])
        # Falls through to single-sig path
        assert _is_psbt_fully_signed(psbt) is True
