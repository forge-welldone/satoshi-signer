"""Sign PSBTs using a Trezor device via trezorlib.

Converts embit PSBT objects to trezorlib message types, sends them to
the Trezor for signing, and inserts the resulting signatures back into
the PSBT.  The conversion logic is ported from HWI's
hwilib/devices/trezor.py, stripped to the subset needed for standard
Bitcoin single-sig and multisig workflows.
"""

import hashlib
from typing import Callable, Dict, List, Optional, Set, Tuple

from embit.psbt import PSBT
from embit.networks import NETWORKS
from embit.script import Script

from trezorlib import messages
from trezorlib.client import TrezorClient
from trezorlib import btc as trezor_btc
from trezorlib.exceptions import TrezorFailure
from trezorlib.messages import (
    HDNodePathType,
    HDNodeType,
    InputScriptType,
    MultisigRedeemScriptType,
    OutputScriptType,
    TransactionType,
    TxInputType,
    TxOutputBinType,
    TxOutputType,
)

from remotesigner.usb_transport import AndroidTransport
from remotesigner.trezor_ui import AndroidTrezorUi


# ---------------------------------------------------------------------------
# Script-type detection helpers
# ---------------------------------------------------------------------------

def is_p2pkh(script: bytes) -> bool:
    """Return True if *script* is a P2PKH scriptPubKey.

    Format: OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG
    """
    return (
        len(script) == 25
        and script[0] == 0x76
        and script[1] == 0xA9
        and script[2] == 0x14
        and script[23] == 0x88
        and script[24] == 0xAC
    )


def is_p2sh(script: bytes) -> bool:
    """Return True if *script* is a P2SH scriptPubKey.

    Format: OP_HASH160 <20-byte-hash> OP_EQUAL
    """
    return (
        len(script) == 23
        and script[0] == 0xA9
        and script[1] == 0x14
        and script[22] == 0x87
    )


def is_witness(script: bytes) -> Tuple[bool, int, bytes]:
    """Detect if *script* is a witness (SegWit) scriptPubKey.

    Returns (is_wit, version, program).

    A witness scriptPubKey has the form: <version-byte> <push-of-program>
    where version is 0x00..0x10 and program length is 2..40 bytes.
    """
    if len(script) < 4 or len(script) > 42:
        return False, 0, b""

    version_byte = script[0]
    # OP_0 is 0x00, OP_1..OP_16 are 0x51..0x60
    if version_byte == 0x00:
        version = 0
    elif 0x51 <= version_byte <= 0x60:
        version = version_byte - 0x50
    else:
        return False, 0, b""

    prog_len = script[1]
    if prog_len + 2 != len(script):
        return False, 0, b""
    if prog_len < 2 or prog_len > 40:
        return False, 0, b""

    program = script[2:]
    return True, version, program


def detect_script_type(
    script_pubkey: bytes,
    redeem_script: Optional[bytes] = None,
    witness_script: Optional[bytes] = None,
) -> InputScriptType:
    """Map a scriptPubKey (and optional redeem/witness scripts) to a
    trezorlib ``InputScriptType``.

    Supports: P2PKH, P2WPKH, P2SH-P2WPKH, P2WSH, P2SH-P2WSH, P2TR,
    and P2SH multisig (bare).
    """
    is_wit, wit_ver, wit_prog = is_witness(script_pubkey)

    if is_wit:
        if wit_ver == 1 and len(wit_prog) == 32:
            return InputScriptType.SPENDTAPROOT
        if wit_ver == 0:
            if len(wit_prog) == 20:
                # Native P2WPKH
                return InputScriptType.SPENDWITNESS
            if len(wit_prog) == 32:
                # Native P2WSH (multisig)
                return InputScriptType.SPENDWITNESS
        # Unknown witness version/length — fall through to SPENDADDRESS
        return InputScriptType.SPENDADDRESS

    if is_p2sh(script_pubkey) and redeem_script is not None:
        rs_wit, rs_ver, rs_prog = is_witness(redeem_script)
        if rs_wit and rs_ver == 0:
            if len(rs_prog) == 20:
                return InputScriptType.SPENDP2SHWITNESS
            if len(rs_prog) == 32:
                # P2SH-P2WSH (wrapped segwit multisig)
                return InputScriptType.SPENDP2SHWITNESS
        # Bare P2SH (legacy multisig)
        return InputScriptType.SPENDMULTISIG

    if is_p2pkh(script_pubkey):
        return InputScriptType.SPENDADDRESS

    # Default fallback
    return InputScriptType.SPENDADDRESS


def _input_script_type_to_output(ist: InputScriptType) -> OutputScriptType:
    """Convert an ``InputScriptType`` to the corresponding ``OutputScriptType``."""
    mapping = {
        InputScriptType.SPENDADDRESS: OutputScriptType.PAYTOADDRESS,
        InputScriptType.SPENDWITNESS: OutputScriptType.PAYTOWITNESS,
        InputScriptType.SPENDP2SHWITNESS: OutputScriptType.PAYTOP2SHWITNESS,
        InputScriptType.SPENDTAPROOT: OutputScriptType.PAYTOTAPROOT,
        InputScriptType.SPENDMULTISIG: OutputScriptType.PAYTOMULTISIG,
    }
    return mapping.get(ist, OutputScriptType.PAYTOADDRESS)


# ---------------------------------------------------------------------------
# Multisig helpers
# ---------------------------------------------------------------------------

def _parse_multisig_script(
    script: bytes,
    bip32_derivations: dict,
    psbt: PSBT,
    partial_sigs: Optional[dict] = None,
) -> Optional[MultisigRedeemScriptType]:
    """Parse a multisig script and return a ``MultisigRedeemScriptType``.

    Only supports standard OP_CHECKMULTISIG scripts of the form:
        OP_m <pub1> <pub2> ... <pubN> OP_n OP_CHECKMULTISIG

    Parameters
    ----------
    partial_sigs:
        Existing partial signatures from the PSBT input scope.
        Used to populate the signatures array for partially signed PSBTs.

    Returns ``None`` if the script is not a recognizable multisig.
    """
    if len(script) < 37:
        return None
    if script[-1] != 0xAE:  # OP_CHECKMULTISIG
        return None

    # OP_1..OP_16 => 0x51..0x60
    m_byte = script[0]
    if not (0x51 <= m_byte <= 0x60):
        return None
    m = m_byte - 0x50

    n_byte = script[-2]
    if not (0x51 <= n_byte <= 0x60):
        return None
    n = n_byte - 0x50

    # Extract public keys
    pubkeys_raw: List[bytes] = []
    pos = 1
    for _ in range(n):
        if pos >= len(script) - 2:
            return None
        key_len = script[pos]
        pos += 1
        if pos + key_len > len(script) - 2:
            return None
        pubkeys_raw.append(script[pos : pos + key_len])
        pos += key_len

    if len(pubkeys_raw) != n:
        return None

    # Build HDNodePathType entries for each pubkey.
    # We use address_n=[] (empty) so the Trezor uses public_key directly
    # for matching, without attempting derivation (which would require a
    # real chain_code we don't have).
    hd_nodes: List[HDNodePathType] = []
    for raw_pub in pubkeys_raw:
        node = HDNodeType(
            depth=0,
            fingerprint=0,
            child_num=0,
            chain_code=b"\x00" * 32,
            public_key=raw_pub,
        )
        hd_nodes.append(HDNodePathType(node=node, address_n=[]))

    # Populate signatures array from existing partial_sigs
    sigs: List[bytes] = [b""] * n
    if partial_sigs:
        for j, raw_pub in enumerate(pubkeys_raw):
            for pub, sig in partial_sigs.items():
                if pub.sec() == raw_pub:
                    sigs[j] = sig
                    break

    return MultisigRedeemScriptType(
        pubkeys=hd_nodes,
        signatures=sigs,
        m=m,
    )


# ---------------------------------------------------------------------------
# PSBT completeness check
# ---------------------------------------------------------------------------

def _is_psbt_fully_signed(psbt: PSBT) -> bool:
    """Return True only when every input has enough signatures.

    For multisig inputs the required threshold *m* is read from the
    witness-script (or redeem-script for bare P2SH).  For single-sig and
    taproot inputs a single signature is sufficient.
    """
    for inp_scope in psbt.inputs:
        # Taproot key-path: stored as PSBT_IN_TAP_KEY_SIG (key 0x13)
        if inp_scope.unknown.get(b"\x13"):
            continue

        # Look for a multisig script (witness_script has priority)
        ms_script: Optional[bytes] = None
        if inp_scope.witness_script:
            ms_script = inp_scope.witness_script.data
        elif inp_scope.redeem_script:
            rs_data = inp_scope.redeem_script.data
            # Only use the redeem_script if it is NOT a witness program
            # (P2SH-P2WSH has a short witness-program redeem_script)
            is_wit, _, _ = is_witness(rs_data)
            if not is_wit:
                ms_script = rs_data

        if (
            ms_script
            and len(ms_script) >= 37
            and ms_script[-1] == 0xAE  # OP_CHECKMULTISIG
        ):
            m_byte = ms_script[0]
            if 0x51 <= m_byte <= 0x60:
                m = m_byte - 0x50
                if len(inp_scope.partial_sigs) < m:
                    return False
                continue

        # Single-sig ECDSA: need at least one partial_sig
        if len(inp_scope.partial_sigs) < 1:
            return False

    return True


# ---------------------------------------------------------------------------
# Master fingerprint helper
# ---------------------------------------------------------------------------

def _get_master_fingerprint(client: TrezorClient, coin_name: str) -> bytes:
    """Query the Trezor for the master fingerprint (4 bytes).

    Asks for the root public key at path ``m/`` and extracts the
    ``root_fingerprint`` field.
    """
    result = trezor_btc.get_public_node(client, n=[], coin_name=coin_name)
    # root_fingerprint is an int in the protobuf message
    fp_int = result.root_fingerprint
    return fp_int.to_bytes(4, "big")


def _node_fingerprint(public_key: bytes) -> bytes:
    """Compute the BIP32 fingerprint of a public key.

    The fingerprint is the first 4 bytes of HASH160(pubkey).
    """
    sha = hashlib.sha256(public_key).digest()
    ripemd = hashlib.new("ripemd160", sha).digest()
    return ripemd[:4]


def _parse_account_path(path_str: str) -> List[int]:
    """Parse a BIP32 account path string into a list of ints.

    Accepts formats like ``"m/84'/0'/20'"``, ``"84'/0'/20'"``,
    or ``"84h/0h/20h"``.
    """
    path_str = path_str.strip()
    if path_str.startswith("m/"):
        path_str = path_str[2:]

    components: List[int] = []
    for part in path_str.split("/"):
        part = part.strip()
        if not part:
            continue
        hardened = part[-1] in ("'", "h", "H")
        if hardened:
            part = part[:-1]
        num = int(part)
        if hardened:
            num += 0x80000000
        components.append(num)
    return components


def _is_relative_path(derivation: List[int]) -> bool:
    """Check if a derivation path looks relative (not from master).

    Full BIP paths always start with a hardened purpose (e.g., 84').
    Relative paths from an account-level xpub start with the unhardened
    chain index (0 for receive, 1 for change).
    """
    if not derivation:
        return False
    return derivation[0] < 0x80000000


def _prioritize_purposes(script_pubkey: bytes) -> List[int]:
    """Return BIP purpose numbers ordered by likelihood for a script type."""
    is_wit, wit_ver, wit_prog = is_witness(script_pubkey)
    if is_wit:
        if wit_ver == 1 and len(wit_prog) == 32:
            return [86, 84, 49, 44]  # Taproot first
        if wit_ver == 0 and len(wit_prog) == 20:
            return [84, 49, 44, 86]  # Native segwit first
    if is_p2sh(script_pubkey):
        return [49, 84, 44, 86]  # Wrapped segwit first
    return [44, 84, 49, 86]  # Legacy first


def _find_key_origin(
    client: TrezorClient,
    coin_name: str,
    psbt: PSBT,
    master_fp: bytes,
) -> Dict[bytes, List[int]]:
    """Resolve relative derivation paths to full BIP paths.

    PSBTs from watch-only wallets have relative paths (e.g., ``[1, 47]``)
    instead of full paths (e.g., ``[84', 0', 0', 1, 47]``).  This happens
    regardless of whether the fingerprint is the master's or an intermediate
    xpub's.

    Detection: any input whose first path component is unhardened.
    Resolution: probe standard BIP account paths on the Trezor, verifying
    that the derived pubkey matches the PSBT's pubkey.

    Returns ``{fingerprint: prefix_path}`` for each fingerprint needing a
    prefix.
    """
    # Find an input with a relative path and extract its pubkey for verification
    sample_pub = None
    sample_deriv = None
    sample_script = None

    for inp in psbt.inputs:
        for pub, deriv in inp.bip32_derivations.items():
            if _is_relative_path(deriv.derivation):
                sample_pub = pub
                sample_deriv = deriv
                sample_script = inp.utxo.script_pubkey.data if inp.utxo else None
                break
        if not sample_pub:
            for pub, (_, deriv) in inp.taproot_bip32_derivations.items():
                if _is_relative_path(deriv.derivation):
                    sample_pub = pub
                    sample_deriv = deriv
                    sample_script = inp.utxo.script_pubkey.data if inp.utxo else None
                    break
        if sample_pub:
            break

    if sample_pub is None:
        return {}

    target_pubkey = sample_pub.sec()
    relative_path = list(sample_deriv.derivation)
    fp = sample_deriv.fingerprint
    coin_type = 0 if coin_name == "Bitcoin" else 1

    # Prioritize BIP purpose based on script type
    purposes = (
        _prioritize_purposes(sample_script)
        if sample_script
        else [84, 49, 44, 86]
    )

    for purpose in purposes:
        for account in range(100):
            prefix = [
                0x80000000 + purpose,
                0x80000000 + coin_type,
                0x80000000 + account,
            ]
            full_path = prefix + relative_path
            try:
                result = trezor_btc.get_public_node(
                    client, n=full_path, coin_name=coin_name
                )
                if result.node.public_key == target_pubkey:
                    return {fp: prefix}
            except Exception:
                continue

    return {}


# ---------------------------------------------------------------------------
# PSBT → trezorlib conversion
# ---------------------------------------------------------------------------

def psbt_to_trezor_inputs(
    psbt: PSBT,
    master_fp: bytes,
    fp_to_prefix: Optional[Dict[bytes, List[int]]] = None,
) -> Tuple[List[TxInputType], List[int]]:
    """Convert PSBT inputs to ``TxInputType`` list.

    Returns ``(inputs, to_ignore)`` where *to_ignore* lists input indices
    that do not belong to this signer (will be signed with EXTERNAL type
    or dummy derivation).

    *fp_to_prefix* maps intermediate xpub fingerprints to their derivation
    prefix from master, for PSBTs created by watch-only wallets with
    relative paths.
    """
    inputs: List[TxInputType] = []
    to_ignore: List[int] = []

    for i, inp_scope in enumerate(psbt.inputs):
        # Determine the UTXO for this input
        utxo = inp_scope.utxo
        if utxo is None:
            raise ValueError(f"Input {i} has no UTXO information")

        # Get the scriptPubKey
        script_pubkey = utxo.script_pubkey.data

        # Determine redeem_script / witness_script
        redeem_script_data = (
            inp_scope.redeem_script.data if inp_scope.redeem_script else None
        )
        witness_script_data = (
            inp_scope.witness_script.data if inp_scope.witness_script else None
        )

        # Detect script type
        script_type = detect_script_type(
            script_pubkey, redeem_script_data, witness_script_data
        )

        # Check if this is a taproot input
        is_taproot = script_type == InputScriptType.SPENDTAPROOT

        # Find the signing key — match master_fp in bip32_derivations
        address_n: List[int] = []
        found_key = False

        if is_taproot:
            # For taproot, check taproot_bip32_derivations
            for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
                if fp_to_prefix and deriv.fingerprint in fp_to_prefix:
                    prefix = fp_to_prefix[deriv.fingerprint]
                    address_n = prefix + list(deriv.derivation)
                    found_key = True
                    break
                elif deriv.fingerprint == master_fp:
                    address_n = list(deriv.derivation)
                    found_key = True
                    break
        else:
            # Standard ECDSA — check bip32_derivations
            for pub, deriv in inp_scope.bip32_derivations.items():
                if fp_to_prefix and deriv.fingerprint in fp_to_prefix:
                    prefix = fp_to_prefix[deriv.fingerprint]
                    address_n = prefix + list(deriv.derivation)
                    found_key = True
                    break
                elif deriv.fingerprint == master_fp:
                    address_n = list(deriv.derivation)
                    found_key = True
                    break

        if not found_key:
            # Not our input — use a dummy path so trezorlib doesn't choke,
            # but remember to ignore the signature for this index.
            to_ignore.append(i)
            address_n = [0x80000054, 0x80000000, 0x80000000, 0, 0]  # m/84'/0'/0'/0/0

        # Build multisig info if applicable
        multisig = None
        if witness_script_data and script_type in (
            InputScriptType.SPENDWITNESS,
            InputScriptType.SPENDP2SHWITNESS,
        ):
            multisig = _parse_multisig_script(
                witness_script_data, inp_scope.bip32_derivations, psbt,
                partial_sigs=inp_scope.partial_sigs,
            )
        elif (
            redeem_script_data
            and script_type == InputScriptType.SPENDMULTISIG
        ):
            multisig = _parse_multisig_script(
                redeem_script_data, inp_scope.bip32_derivations, psbt,
                partial_sigs=inp_scope.partial_sigs,
            )

        # Build the TxInputType
        txid_bytes = inp_scope.txid
        prev_index = inp_scope.vout
        amount = utxo.value
        sequence = inp_scope.sequence if inp_scope.sequence is not None else 0xFFFFFFFF

        txin = TxInputType(
            address_n=address_n,
            prev_hash=txid_bytes,
            prev_index=prev_index,
            amount=amount,
            script_type=script_type,
            sequence=sequence,
        )
        if multisig is not None:
            txin.multisig = multisig

        inputs.append(txin)

    return inputs, to_ignore


def psbt_to_trezor_outputs(
    psbt: PSBT,
    master_fp: bytes,
    network: str = "main",
    fp_to_prefix: Optional[Dict[bytes, List[int]]] = None,
) -> List[TxOutputType]:
    """Convert PSBT outputs to ``TxOutputType`` list.

    Outputs whose ``bip32_derivations`` match *master_fp* (or a fingerprint
    in *fp_to_prefix*) are treated as change (``address_n`` set, ``address``
    cleared).  All other outputs get their ``address`` set from the
    scriptPubKey.
    """
    net = NETWORKS[network]
    outputs: List[TxOutputType] = []

    for out_scope in psbt.outputs:
        script_pubkey = out_scope.script_pubkey
        amount = out_scope.value
        is_taproot = script_pubkey.data[0] == 0x51 if script_pubkey and len(script_pubkey.data) > 0 else False

        # Check if this is our change output
        address_n: List[int] = []
        found_change = False
        out_script_type = OutputScriptType.PAYTOADDRESS

        if is_taproot:
            for pub, (leaf_hashes, deriv) in out_scope.taproot_bip32_derivations.items():
                if fp_to_prefix and deriv.fingerprint in fp_to_prefix:
                    prefix = fp_to_prefix[deriv.fingerprint]
                    address_n = prefix + list(deriv.derivation)
                    found_change = True
                    out_script_type = OutputScriptType.PAYTOTAPROOT
                    break
                elif deriv.fingerprint == master_fp:
                    address_n = list(deriv.derivation)
                    found_change = True
                    out_script_type = OutputScriptType.PAYTOTAPROOT
                    break
        else:
            for pub, deriv in out_scope.bip32_derivations.items():
                if fp_to_prefix and deriv.fingerprint in fp_to_prefix:
                    prefix = fp_to_prefix[deriv.fingerprint]
                    address_n = prefix + list(deriv.derivation)
                    found_change = True
                    sp_data = script_pubkey.data if script_pubkey else b""
                    redeem_data = (
                        out_scope.redeem_script.data
                        if out_scope.redeem_script
                        else None
                    )
                    ist = detect_script_type(sp_data, redeem_data, None)
                    out_script_type = _input_script_type_to_output(ist)
                    break
                elif deriv.fingerprint == master_fp:
                    address_n = list(deriv.derivation)
                    found_change = True
                    sp_data = script_pubkey.data if script_pubkey else b""
                    redeem_data = (
                        out_scope.redeem_script.data
                        if out_scope.redeem_script
                        else None
                    )
                    ist = detect_script_type(sp_data, redeem_data, None)
                    out_script_type = _input_script_type_to_output(ist)
                    break

        if found_change:
            # Multisig change output
            multisig = None
            if out_scope.witness_script:
                multisig = _parse_multisig_script(
                    out_scope.witness_script.data,
                    out_scope.bip32_derivations,
                    psbt,
                )
            elif out_scope.redeem_script:
                # Check if it's a bare multisig redeem script
                rs_data = out_scope.redeem_script.data
                rs_wit, _, _ = is_witness(rs_data)
                if not rs_wit:
                    multisig = _parse_multisig_script(
                        rs_data, out_scope.bip32_derivations, psbt
                    )

            txout = TxOutputType(
                address_n=address_n,
                amount=amount,
                script_type=out_script_type,
            )
            if multisig is not None:
                txout.multisig = multisig
            outputs.append(txout)
        else:
            # External output — resolve address from scriptPubKey
            sp_data = script_pubkey.data if script_pubkey else b""

            # Check for OP_RETURN
            if sp_data and sp_data[0] == 0x6A:
                # OP_RETURN data
                op_return_data = sp_data[1:]
                # Strip the length prefix if present
                if len(op_return_data) > 0 and op_return_data[0] == len(op_return_data) - 1:
                    op_return_data = op_return_data[1:]
                txout = TxOutputType(
                    amount=0,
                    script_type=OutputScriptType.PAYTOOPRETURN,
                    op_return_data=op_return_data,
                )
                outputs.append(txout)
                continue

            try:
                address = script_pubkey.address(net)
            except Exception:
                raise ValueError(
                    f"Cannot derive address from scriptPubKey: {sp_data.hex()}"
                )
            txout = TxOutputType(
                address=address,
                amount=amount,
                script_type=OutputScriptType.PAYTOADDRESS,
            )
            outputs.append(txout)

    return outputs


def psbt_to_prev_txes(psbt: PSBT) -> Dict[bytes, TransactionType]:
    """Build the ``prev_txes`` dict mapping txid → ``TransactionType``.

    Only includes inputs that carry a ``non_witness_utxo`` (full previous
    transaction).  Segwit-only inputs with just ``witness_utxo`` do not
    need an entry (Trezor firmware can verify them from the amount alone).

    Note: Trezor firmware >= 2.3.2 requires non_witness_utxo even for
    segwit inputs to prevent the fee-attack.  Electrum normally includes
    non_witness_utxo for all inputs, so this works in practice.
    """
    prev_txes: Dict[bytes, TransactionType] = {}

    for inp_scope in psbt.inputs:
        prev_tx = inp_scope.non_witness_utxo
        if prev_tx is None:
            continue

        txid = inp_scope.txid
        if txid in prev_txes:
            continue

        # Convert embit Transaction to trezorlib TransactionType
        t_inputs: List[TxInputType] = []
        for vin in prev_tx.vin:
            t_inputs.append(
                TxInputType(
                    prev_hash=vin.txid,
                    prev_index=vin.vout,
                    script_sig=vin.script_sig.data if isinstance(vin.script_sig, Script) else vin.script_sig,
                    sequence=vin.sequence if vin.sequence is not None else 0xFFFFFFFF,
                )
            )

        t_bin_outputs: List[TxOutputBinType] = []
        for vout in prev_tx.vout:
            t_bin_outputs.append(
                TxOutputBinType(
                    amount=vout.value,
                    script_pubkey=vout.script_pubkey.data,
                )
            )

        prev_txes[txid] = TransactionType(
            version=prev_tx.version,
            lock_time=prev_tx.locktime,
            inputs=t_inputs,
            bin_outputs=t_bin_outputs,
        )

    return prev_txes


# ---------------------------------------------------------------------------
# Main signing function
# ---------------------------------------------------------------------------

def sign_psbt(
    psbt_bytes: bytes,
    bridge,
    status_callback=None,
    network: str = "main",
) -> dict:
    """Sign a PSBT using a connected Trezor device.

    Parameters
    ----------
    psbt_bytes:
        Raw PSBT binary data.
    bridge:
        Kotlin USB bridge object (passed from Android via Chaquopy).
    status_callback:
        Optional Java callback with ``onStatus(String)`` for UI updates.
    network:
        ``"main"`` or ``"test"``.

    Returns
    -------
    dict with keys:
        - ``status``: ``"signed"`` or ``"error"``
        - ``psbt``: base64-encoded signed PSBT (on success)
        - ``raw_tx``: hex-encoded serialized transaction (on success, if
          fully signed)
        - ``error``: error message string (on failure)
    """
    coin_name = "Bitcoin" if network == "main" else "Testnet"

    def _status(msg: str) -> None:
        if status_callback is not None:
            try:
                status_callback.onStatus(msg)
            except Exception:
                pass

    try:
        # Parse the PSBT
        _status("Parsing PSBT...")
        psbt_bytes = bytes(psbt_bytes)
        psbt = PSBT.parse(psbt_bytes)

        # Connect to the Trezor
        _status("Connecting to Trezor...")
        transport = AndroidTransport(bridge)
        ui = AndroidTrezorUi(status_callback)
        client = TrezorClient(transport, ui=ui)

        try:
            # Get master fingerprint
            _status("Reading device fingerprint...")
            master_fp = _get_master_fingerprint(client, coin_name)
            _status(f"Device fingerprint: {master_fp.hex()}")

            # Resolve key origins for watch-only wallet PSBTs (relative paths)
            _status("Checking derivation paths...")
            fp_to_prefix = _find_key_origin(client, coin_name, psbt, master_fp)

            # Fallback: ask the user for the account path
            if not fp_to_prefix:
                # Find a fingerprint with relative paths (if any)
                rel_fp = None
                for inp_scope in psbt.inputs:
                    for pub, deriv in inp_scope.bip32_derivations.items():
                        if _is_relative_path(deriv.derivation):
                            rel_fp = deriv.fingerprint
                            break
                    if rel_fp:
                        break

                if rel_fp is not None:
                    _status("Could not auto-detect account path")
                    if status_callback is not None:
                        try:
                            path_str = str(
                                status_callback.requestAccountPath()
                            )
                            prefix = _parse_account_path(path_str)
                            fp_to_prefix = {rel_fp: prefix}
                        except Exception as e:
                            raise ValueError(
                                f"Account path required but not provided: {e}"
                            )
                    else:
                        raise ValueError(
                            "PSBT has relative derivation paths. "
                            "Account path (e.g., m/84'/0'/0') is required."
                        )

            if fp_to_prefix:
                for fp, prefix in fp_to_prefix.items():
                    path_str = "/".join(
                        f"{p - 0x80000000}'" if p >= 0x80000000 else str(p)
                        for p in prefix
                    )
                    _status(f"Resolved account path: m/{path_str}")

            # Convert PSBT to trezorlib types
            _status("Preparing transaction...")
            trezor_inputs, to_ignore = psbt_to_trezor_inputs(
                psbt, master_fp, fp_to_prefix
            )
            trezor_outputs = psbt_to_trezor_outputs(
                psbt, master_fp, network, fp_to_prefix
            )
            prev_txes = psbt_to_prev_txes(psbt)

            # Prepare extra sign_tx kwargs from the PSBT global transaction
            sign_kwargs = {}
            if psbt.tx_version is not None:
                sign_kwargs["version"] = psbt.tx_version
            if psbt.locktime is not None:
                sign_kwargs["lock_time"] = psbt.locktime

            # Sign!
            _status("Signing transaction — please confirm on your Trezor...")
            signatures, serialized_tx = trezor_btc.sign_tx(
                client,
                coin_name,
                trezor_inputs,
                trezor_outputs,
                prev_txes=prev_txes,
                **sign_kwargs,
            )

            # Insert signatures back into the PSBT
            _status("Inserting signatures into PSBT...")
            for idx, sig in enumerate(signatures):
                if sig is None or idx in to_ignore:
                    continue

                inp_scope = psbt.inputs[idx]
                is_taproot = trezor_inputs[idx].script_type == InputScriptType.SPENDTAPROOT

                if is_taproot:
                    # Taproot key-path signature: store as PSBT_IN_TAP_KEY_SIG
                    # embit doesn't natively support key 0x13, so we write to
                    # the unknowns dict which gets serialized on output.
                    inp_scope.unknown[b"\x13"] = sig
                else:
                    # ECDSA signature: add SIGHASH_ALL byte and store in
                    # partial_sigs keyed by the pubkey
                    sig_with_sighash = sig + b"\x01"

                    # Find the pubkey that matches master_fp or fp_to_prefix
                    for pub, deriv in inp_scope.bip32_derivations.items():
                        if deriv.fingerprint == master_fp or (
                            fp_to_prefix
                            and deriv.fingerprint in fp_to_prefix
                        ):
                            inp_scope.partial_sigs[pub] = sig_with_sighash
                            break

            # Serialize the updated PSBT
            signed_psbt_b64 = psbt.to_base64()

            # Check whether every input has enough signatures
            if _is_psbt_fully_signed(psbt) and serialized_tx:
                result = {
                    "status": "complete",
                    "raw_tx": serialized_tx.hex(),
                    "psbt": signed_psbt_b64,
                }
            else:
                result = {
                    "status": "partial",
                    "psbt": signed_psbt_b64,
                }

            _status("Signing complete.")
            return result

        finally:
            try:
                client.close()
            except Exception:
                pass

    except TrezorFailure as e:
        if e.code in (
            messages.FailureType.ActionCancelled,
            messages.FailureType.PinCancelled,
        ):
            _status("Signing cancelled.")
            return {
                "status": "cancelled",
                "message": str(e),
            }
        _status(f"Error: {e}")
        return {
            "status": "error",
            "message": str(e),
        }
    except Exception as e:
        if "cancelled" in str(e).lower():
            _status("Signing cancelled.")
            return {
                "status": "cancelled",
                "message": str(e),
            }
        _status(f"Error: {e}")
        return {
            "status": "error",
            "message": str(e),
        }
