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
- Import `parse_multisig_script` from `script_utils`
- `_analyze_signing_status` call site changes from tuple unpacking to object access:
  ```python
  # Before:
  m, n = _parse_multisig_info(ms.data)
  if m is not None:
      required_sigs = max(required_sigs, m)
      total_sigs = max(total_sigs, n)

  # After:
  info = parse_multisig_script(ms.data)
  if info is not None:
      required_sigs = max(required_sigs, info.m)
      total_sigs = max(total_sigs, info.n)
  ```

### Changes to `signer.py`

- `_parse_multisig_script` calls `parse_multisig_script()` for the byte parsing, then builds `MultisigRedeemScriptType` from `MultisigInfo.pubkeys`. The function signature and return type stay the same — all 4 call sites unchanged.

### Tests

- **New `tests/test_script_utils.py`**: Direct tests for `parse_multisig_script` — valid 2-of-3, 1-of-1, edge cases (too short, no OP_CHECKMULTISIG, invalid m/n bytes, truncated pubkeys). May reuse the `_make_multisig_script` helper pattern from `test_signer.py`.
- **Existing `tests/test_signer.py`**: Tests for `_parse_multisig_script` continue to pass unchanged — they test the trezorlib wrapper layer

## Out of Scope

- Taproot vs ECDSA derivation deduplication (TODO #11 — separate item)
- `HARDENED` constant consolidation — it's a BIP32 derivation constant used in 6+ places across `signer.py` and `psbt_parser.py` for path logic unrelated to multisig scripts. Doesn't belong in a script parsing module.
- Other OP code constants not used by the multisig parser (e.g., OP_RETURN, OP_DUP)
- `_is_psbt_fully_signed`'s inline multisig check — a ~3 line inline check that only needs `m`, not pubkeys. Keeping it inline avoids coupling a quick predicate to the new module.
