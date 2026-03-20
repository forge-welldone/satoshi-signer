"""Parse PSBT files and extract transaction details for display."""

from dataclasses import dataclass, field
from embit.psbt import PSBT
from embit.networks import NETWORKS
import sys
if sys.version_info >= (3, 11):
    from typing import NotRequired, TypedDict
else:
    from typing_extensions import NotRequired, TypedDict
from remotesigner.script_utils import parse_multisig_script


class InputInfo(TypedDict):
    index: int
    txid: str
    vout: int
    amount: int
    address: str


class OutputInfo(TypedDict):
    index: int
    address: str
    amount: int
    is_change: bool
    op_return: NotRequired[str]


class SignerStatus(TypedDict):
    fingerprint: str
    signed: bool


class ParseResult(TypedDict):
    inputs: list[InputInfo]
    outputs: list[OutputInfo]
    fee: int
    status: str
    signers: list[SignerStatus]
    network: str
    required_sigs: NotRequired[int]
    total_sigs: NotRequired[int]


PSBT_MAGIC = b"psbt\xff"
MAX_PSBT_SIZE = 1_048_576  # 1 MB


@dataclass
class ParsedTransaction:
    inputs: list = field(default_factory=list)
    outputs: list = field(default_factory=list)
    fee: int = 0
    status: str = "unsigned"
    signers: list = field(default_factory=list)
    raw_psbt: object = None


def parse_psbt(psbt_bytes: bytes, network: str = "main") -> ParseResult:
    """Parse PSBT bytes and return structured transaction data as a dict.

    Args:
        psbt_bytes: Raw PSBT binary data.
        network: "main" or "test".

    Returns:
        Dict with inputs, outputs, fee, status, signers.

    Raises:
        ValueError: If the bytes are not a valid PSBT.
    """
    psbt_bytes = bytes(psbt_bytes)
    if len(psbt_bytes) > MAX_PSBT_SIZE:
        raise ValueError(
            f"PSBT too large: {len(psbt_bytes)} bytes "
            f"(max {MAX_PSBT_SIZE})"
        )
    if not psbt_bytes.startswith(PSBT_MAGIC):
        raise ValueError("Invalid PSBT: missing magic bytes")

    try:
        psbt = PSBT.parse(psbt_bytes)
    except Exception as e:
        raise ValueError(f"Invalid PSBT: {e}") from e

    # Auto-detect network from derivation paths before encoding addresses;
    # fall back to explicit parameter if derivation paths are absent.
    detected_network = _detect_network(psbt) or network
    net = NETWORKS[detected_network]
    result = ParsedTransaction(raw_psbt=psbt)

    # Collect all master fingerprints from inputs (to identify change outputs)
    input_fingerprints = set()
    for inp_scope in psbt.inputs:
        for pub, deriv in inp_scope.bip32_derivations.items():
            input_fingerprints.add(deriv.fingerprint)
        for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
            input_fingerprints.add(deriv.fingerprint)

    # Parse inputs
    for i, inp_scope in enumerate(psbt.inputs):
        # inp_scope.utxo is a property that returns witness_utxo or from non_witness_utxo
        utxo = inp_scope.utxo
        try:
            address = utxo.script_pubkey.address(net) if utxo and utxo.script_pubkey else "unknown"
        except (ValueError, Exception):
            address = "unknown"
        inp_data = {
            "index": i,
            "txid": inp_scope.txid.hex() if inp_scope.txid else "",
            "vout": inp_scope.vout if inp_scope.vout is not None else 0,
            "amount": utxo.value if utxo else 0,
            "address": address,
        }
        result.inputs.append(inp_data)

    # Parse outputs and detect change
    for i, out_scope in enumerate(psbt.outputs):
        # OutputScope has .script_pubkey and .value directly
        script_pubkey = out_scope.script_pubkey
        try:
            address = script_pubkey.address(net) if script_pubkey else "unknown"
        except (ValueError, Exception):
            address = "unknown"

        is_change = _is_change_output(out_scope, input_fingerprints)

        out_data = {
            "index": i,
            "address": address,
            "amount": out_scope.value if out_scope.value is not None else 0,
            "is_change": is_change,
        }

        # Detect OP_RETURN outputs and extract text payload
        if script_pubkey and script_pubkey.data[:1] == b'\x6a':
            payload = script_pubkey.data[2:]  # skip OP_RETURN + push length
            try:
                out_data["op_return"] = payload.decode("utf-8")
            except UnicodeDecodeError:
                out_data["op_return"] = payload.hex()

        result.outputs.append(out_data)

    # Fee: psbt.fee() sums utxo values minus output values
    try:
        result.fee = psbt.fee()
    except Exception:
        result.fee = -1

    # Signing status
    result.status, result.signers, required_sigs, total_sigs = _analyze_signing_status(psbt)

    out = {
        "inputs": result.inputs,
        "outputs": result.outputs,
        "fee": result.fee,
        "status": result.status,
        "signers": result.signers,
        "network": detected_network,
    }
    if required_sigs > 0:
        out["required_sigs"] = required_sigs
        out["total_sigs"] = total_sigs
    return out


def _detect_network(psbt: PSBT):
    """Detect network from BIP32 derivation paths in the PSBT.

    Coin type 1 (hardened) in the second path element means testnet.
    Coin type 0 means mainnet. Returns None if undetermined.
    """
    HARDENED = 0x80000000
    for inp_scope in psbt.inputs:
        for pub, deriv in inp_scope.bip32_derivations.items():
            path = deriv.derivation
            if len(path) >= 2:
                coin_type = path[1] & ~HARDENED
                if coin_type == 1:
                    return "test"
                elif coin_type == 0:
                    return "main"
        for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
            path = deriv.derivation
            if len(path) >= 2:
                coin_type = path[1] & ~HARDENED
                if coin_type == 1:
                    return "test"
                elif coin_type == 0:
                    return "main"
    return None


def _is_change_output(out_scope, input_fingerprints: set) -> bool:
    """Detect if an output is a change output.

    An output is considered change if:
    - It has a bip32 derivation whose fingerprint matches one of the input fingerprints
    - AND the second-to-last path component is 1 (internal/change chain)
    """
    for pub, deriv in out_scope.bip32_derivations.items():
        if deriv.fingerprint in input_fingerprints:
            path = deriv.derivation
            if len(path) >= 2 and path[-2] == 1:
                return True

    for pub, (leaf_hashes, deriv) in out_scope.taproot_bip32_derivations.items():
        if deriv.fingerprint in input_fingerprints:
            path = deriv.derivation
            if len(path) >= 2 and path[-2] == 1:
                return True

    return False



def _analyze_signing_status(psbt: PSBT) -> tuple:
    """Determine signing status and list signers.

    Returns a tuple of (status_str, signers_list, required_sigs, total_sigs).
    Status is one of: "unsigned", "partially_signed", "fully_signed".
    Each signer is {"fingerprint": hex_str, "signed": bool}.
    required_sigs and total_sigs are ints (0 if not multisig).
    """
    all_fingerprints: dict = {}
    required_sigs = 0
    total_sigs = 0

    for inp_scope in psbt.inputs:
        signed_pubs = set(inp_scope.partial_sigs.keys()) if inp_scope.partial_sigs else set()
        # taproot_sigs is a dict keyed by (pub, leaf_hash) tuples
        has_tap_sig = bool(inp_scope.taproot_sigs) if inp_scope.taproot_sigs else False

        # Extract m-of-n from multisig scripts
        ms = inp_scope.witness_script or inp_scope.redeem_script
        if ms is not None:
            info = parse_multisig_script(ms.data)
            if info is not None:
                required_sigs = max(required_sigs, info.m)
                total_sigs = max(total_sigs, info.n)

        for pub, deriv in inp_scope.bip32_derivations.items():
            # fingerprint is bytes; convert to hex for display
            fp = deriv.fingerprint.hex()
            if fp not in all_fingerprints:
                all_fingerprints[fp] = {"fingerprint": fp, "signed": False}
            if pub in signed_pubs:
                all_fingerprints[fp]["signed"] = True

        for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
            fp = deriv.fingerprint.hex()
            if fp not in all_fingerprints:
                all_fingerprints[fp] = {"fingerprint": fp, "signed": False}
            if has_tap_sig:
                all_fingerprints[fp]["signed"] = True

    signers = list(all_fingerprints.values())

    if not signers:
        status = "unsigned"
    elif all(s["signed"] for s in signers):
        status = "fully_signed"
    elif any(s["signed"] for s in signers):
        status = "partially_signed"
    else:
        status = "unsigned"

    return status, signers, required_sigs, total_sigs
