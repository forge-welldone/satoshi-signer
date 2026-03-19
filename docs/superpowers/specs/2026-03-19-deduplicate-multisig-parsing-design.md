# Design: Deduplicate Multisig Script Parsing (TODO #10)

> Date: 2026-03-19

## Problem

`psbt_parser.py:_parse_multisig_info` and `signer.py:_parse_multisig_script` both parse OP_CHECKMULTISIG scripts with identical byte-level validation logic. The parser extracts just (m, n); the signer extracts m, n, pubkeys and builds trezorlib types. The shared core is duplicated.

## Design

### New file: `remotesigner/script_utils.py`

Pure utility module — no trezorlib or embit dependencies.

```python
from dataclasses import dataclass

OP_CHECKMULTISIG = 0xAE
HARDENED = 0x80000000

@dataclass
class MultisigInfo:
    m: int
    n: int
    pubkeys: list[bytes]  # raw compressed pubkeys in script order

def parse_multisig_script(script: bytes) -> MultisigInfo | None:
    """Parse an OP_CHECKMULTISIG script into m, n, and pubkeys.

    Supports standard scripts of the form:
        OP_m <pub1> <pub2> ... <pubN> OP_n OP_CHECKMULTISIG

    Returns None if the script is not a recognizable multisig.
    """
```

The function consolidates the validation logic currently duplicated:
- Length check (≥ 37 bytes)
- Last byte is OP_CHECKMULTISIG (0xAE)
- First byte is OP_1..OP_16 (0x51..0x60) → m
- Second-to-last byte is OP_1..OP_16 → n
- Extract n pubkeys by walking the script body

### Changes to `psbt_parser.py`

- Delete `_parse_multisig_info` function (lines 211-231)
- Import `parse_multisig_script` and `HARDENED` from `script_utils`
- `_analyze_signing_status`: replace `_parse_multisig_info(ms.data)` with `parse_multisig_script(ms.data)`, read `.m` and `.n`
- `_detect_network`: replace local `HARDENED = 0x80000000` with imported constant

### Changes to `signer.py`

- `_parse_multisig_script` calls `parse_multisig_script()` for the byte parsing, then builds `MultisigRedeemScriptType` from `MultisigInfo.pubkeys`. The function signature and return type stay the same.
- Replace hardcoded `0x80000000` with imported `HARDENED` constant where applicable

### Tests

- **New `tests/test_script_utils.py`**: Direct tests for `parse_multisig_script` — valid 2-of-3, 1-of-1, edge cases (too short, no OP_CHECKMULTISIG, invalid m/n bytes, truncated pubkeys)
- **Existing `tests/test_signer.py`**: Tests for `_parse_multisig_script` continue to pass unchanged — they test the trezorlib wrapper layer

## Out of Scope

- Taproot vs ECDSA derivation deduplication (TODO #11 — separate item)
- Other OP code constants not used by the multisig parser (e.g., OP_RETURN, OP_DUP)
- Moving `_is_psbt_fully_signed`'s inline multisig check (it reads `ms_script[-1] == 0xAE` and m/n bytes directly for a quick threshold check — could use `parse_multisig_script` but is a ~3 line inline check, not worth the function call overhead in a hot loop)
