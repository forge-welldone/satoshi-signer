# Synthetic PSBT Test Fixtures

**Date:** 2026-03-20
**Addresses:** Refactoring TODO #14 — Fix permanently skipped Python tests (missing fixtures)

## Problem

`TestParseMultisigPsbt` and `TestParseOpReturnPsbt` in `tests/test_psbt_parser.py` reference PSBT files (`trezor.multisig.2.a-ads-7d42c2e3.psbt`, `aa_cold3_watch-f1516d7b.psbt`) that were deleted because they contained real transaction data. The tests are permanently skipped.

## Solution

Replace file-based fixtures with inline embit-constructed PSBTs. Rewrite both test classes to use `@pytest.fixture` methods that build synthetic PSBTs programmatically. Remove `skipif` guards and file path references.

## Fixture: Multisig (2-of-3 P2WSH, partially signed, mainnet)

Construction in a `@pytest.fixture`:

1. Generate 3 deterministic keys (fixed seeds for reproducibility)
2. Build a 2-of-3 `OP_CHECKMULTISIG` witness script from the 3 public keys
3. Create a P2WSH script from the witness script
4. Build a transaction: 1 input (100k sats from a fake txid), 2 outputs (payment + change)
5. Populate the PSBT input scope:
   - `witness_utxo`: TransactionOutput with the P2WSH script
   - `witness_script`: the multisig script
   - `bip32_derivations`: one per signer, each with a unique 4-byte fingerprint and mainnet path (`m/48'/0'/0'/2'/0/0`)
   - `partial_sigs`: one entry (signer 0's key) with a dummy DER signature
6. Serialize to bytes, return from fixture

### Tests (`TestParseMultisigPsbt`)

- `test_partially_signed_status` — `result["status"] == "partially_signed"`
- `test_required_and_total_sigs` — `required_sigs == 2`, `total_sigs == 3`
- `test_reports_all_signers` — 3 signers, 1 signed, 2 unsigned
- `test_outputs_have_addresses` — all outputs have `address != "unknown"` and `amount > 0`
- `test_detects_mainnet` — `result["network"] == "main"`

## Fixture: OP_RETURN (single-sig with OP_RETURN output, mainnet)

Construction in a `@pytest.fixture`:

1. Generate 1 deterministic key
2. Build a transaction: 1 input (50k sats), 2 outputs:
   - Output 0: OP_RETURN (`0x6a` + push_len + UTF-8 text), amount 0
   - Output 1: P2WPKH payment, amount 49k sats
3. Populate PSBT input scope with `witness_utxo` and `bip32_derivations` (mainnet path)
4. Serialize to bytes, return from fixture

### Tests (`TestParseOpReturnPsbt`)

- `test_detects_op_return_output` — `result["outputs"][0]["op_return"]` equals the text payload
- `test_op_return_amount_is_zero` — `result["outputs"][0]["amount"] == 0`
- `test_non_op_return_output_has_no_field` — `"op_return" not in result["outputs"][1]`

## Cleanup

- Remove `MULTISIG_PSBT_PATH` and `OP_RETURN_PSBT_PATH` constants
- Remove the two `@pytest.mark.skipif` decorators
- Keep `PSBTS_DIR` (still used by `TestParseTestnetPsbt`)

## Dependencies

- `embit` (already in requirements-dev.txt and production deps)
- No new dependencies needed

## Files Changed

- `tests/test_psbt_parser.py` — rewrite `TestParseMultisigPsbt` and `TestParseOpReturnPsbt`
