# Negative-Path Python Signing Tests

> Refactoring TODO #18 — Add negative-path Python signing tests

## Problem

The Python signing module (`signer.py`) has numerous untested error paths. Cassette-based E2E tests only replay happy paths. Error conditions like truncated PSBTs, missing fingerprints, and relative paths without callbacks are never exercised.

## Approach

Mixed testing strategy:
- **Integration tests through `sign_psbt()`** for errors that flow through the top-level exception handler and must return the correct `{"status": "error", "message": ...}` dict.
- **Direct unit tests on helpers** (`psbt_to_trezor_inputs`, `psbt_to_trezor_outputs`) for input validation errors that are pure conversion logic.

This follows the existing test file's pattern: helpers tested directly in separate classes, `sign_psbt` tested in `TestSignPsbtCancellation`.

## Scope

Covers the 3 scenarios named in the TODO (truncated PSBT, no matching fingerprint, relative paths + no callback) plus all high-risk untested paths. Excludes low-risk graceful handlers (silent `client.close()` failures, `onStatus()` callback failures, key origin probing fallbacks).

## Test Design

### Class 1: `TestSignPsbtNegativePaths`

Integration tests through `sign_psbt()`. Uses the same mock pattern as `TestSignPsbtCancellation` (patch `AndroidTransport`, `AndroidTrezorUi`, `TrezorClient`, `trezor_btc`).

| # | Test | Trigger | Assertion |
|---|------|---------|-----------|
| 1 | `test_truncated_psbt_returns_error` | Truncated bytes (valid PSBT magic `b"psbt\xff"` + garbage) | `status == "error"`, message present |
| 2 | `test_empty_bytes_returns_error` | `b""` | `status == "error"` |
| 3 | `test_not_psbt_bytes_returns_error` | `b"not a psbt at all"` | `status == "error"` |
| 4 | `test_connection_failure_returns_error` | `AndroidTransport` constructor raises `OSError` | `status == "error"` |
| 5 | `test_fingerprint_read_failure_returns_error` | `trezor_btc.get_public_node` raises `RuntimeError` | `status == "error"` |
| 6 | `test_relative_paths_no_callback_returns_error` | Patch `PSBT.parse` to return mock PSBT with relative derivation paths, `status_callback=None` | `status == "error"`, message mentions "relative derivation paths" |
| 7 | `test_other_trezor_failure_code_returns_error` | `trezor_btc.sign_tx` raises `TrezorFailure(Failure(code=FailureType.UnexpectedMessage))` | `status == "error"` (not `"cancelled"`) |
| 8 | `test_account_path_callback_error_returns_error` | `status_callback.requestAccountPath()` raises `RuntimeError`. Same mock PSBT as test 6 | `status == "error"`, message mentions "Account path" |
| 9 | `test_output_conversion_error_returns_error` | Patch `psbt_to_trezor_outputs` to raise `ValueError` inside `_perform_signing` | `status == "error"`, message present |

### Class 2: `TestConversionNegativePaths`

Direct unit tests on `psbt_to_trezor_inputs` and `psbt_to_trezor_outputs`. Requires new fake embit objects (more elaborate than the existing `_FakeInputScope`/`_FakePsbt` which only serve `_is_psbt_fully_signed`). New fakes need: `utxo` (with `.script_pubkey.data`, `.value`), `bip32_derivations`, `taproot_bip32_derivations`, `txid`, `vout`, `sequence`, and `partial_sigs` for inputs; `script_pubkey` (with `.data`, `.address()`), `value`, `bip32_derivations`, `taproot_bip32_derivations` for outputs.

| # | Test | Trigger | Assertion |
|---|------|---------|-----------|
| 10 | `test_input_missing_utxo_raises` | Fake input scope with `utxo = None` | Raises `ValueError` mentioning "no UTXO" |
| 11 | `test_output_bad_script_pubkey_raises` | Fake output scope with nonsense `script_pubkey` that raises on `.address()` | Raises `ValueError` mentioning "Cannot derive address" |

## Implementation Notes

- **Tests 1-3:** No mocking needed — `PSBT.parse()` fails before any Trezor interaction.
- **Tests 4-5:** Need `AndroidTransport`/`TrezorClient`/`trezor_btc` patches but fail early in `_connect_and_get_fingerprint`.
- **Tests 6, 8:** Need a mock PSBT with relative derivation paths. Patch `PSBT.parse` to return a `MagicMock` PSBT with `bip32_derivations` containing relative paths (unhardened, e.g., `[0, 0]`). Also need `trezor_btc.get_public_node` to use a `side_effect` function: return the correct fingerprint on the first call (for `_get_master_fingerprint`), then return non-matching pubkeys for all subsequent calls (for `_find_key_origin` probing, which tries up to 400 paths).
- **Test 7:** `TrezorFailure` must be constructed as `TrezorFailure(Failure(code=FailureType.UnexpectedMessage))` following the existing pattern at `test_signer.py:962`.
- **Test 9:** Patch `psbt_to_trezor_outputs` at the module level to raise `ValueError`, verify it flows through `sign_psbt`'s exception handler correctly.
- **Tests 10-11:** Need new fake classes (e.g., `_FakeConversionInputScope`, `_FakeConversionOutputScope`) with richer attributes than the existing `_FakeInputScope`.

## Files Changed

- `tests/test_signer.py` — add `TestSignPsbtNegativePaths` and `TestConversionNegativePaths` classes, plus new fake helper classes

## Files NOT Changed

- `signer.py` — no production code changes (these are test-only additions)
