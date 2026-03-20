# Negative-Path Python Signing Tests

> Refactoring TODO #18 — Add negative-path Python signing tests

## Problem

The Python signing module (`signer.py`) has ~23 untested error paths. Cassette-based E2E tests only replay happy paths. Error conditions like truncated PSBTs, missing fingerprints, and relative paths without callbacks are never exercised.

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
| 6 | `test_relative_paths_no_callback_returns_error` | Valid PSBT with relative derivation paths, `status_callback=None` | `status == "error"`, message mentions "relative derivation paths" |
| 7 | `test_other_trezor_failure_code_returns_error` | `trezor_btc.sign_tx` raises `TrezorFailure(UnexpectedMessage)` | `status == "error"` (not `"cancelled"`) |
| 8 | `test_account_path_callback_error_returns_error` | `status_callback.requestAccountPath()` raises `RuntimeError` | `status == "error"`, message mentions "Account path" |

### Class 2: `TestConversionNegativePaths`

Direct unit tests on `psbt_to_trezor_inputs` and `psbt_to_trezor_outputs`. Uses fake embit objects following the existing `_FakeInputScope`/`_FakePsbt` pattern.

| # | Test | Trigger | Assertion |
|---|------|---------|-----------|
| 9 | `test_input_missing_utxo_raises` | Fake input scope with `utxo = None` | Raises `ValueError` mentioning "no UTXO" |
| 10 | `test_output_bad_script_pubkey_raises` | Fake output scope with nonsense `script_pubkey` | Raises `ValueError` mentioning "Cannot derive address" |
| 11 | `test_input_no_matching_fingerprint_ignored` | Valid input with non-matching fingerprint | Input index in `to_ignore` list |
| 12 | `test_all_inputs_ignored_produces_empty_match` | Multi-input PSBT, no inputs match signer | All indices in `to_ignore` |

## Implementation Notes

- Tests 1-3 need no mocking — `PSBT.parse()` fails before any Trezor interaction.
- Tests 4-5 need the standard `AndroidTransport`/`TrezorClient`/`trezor_btc` patches but fail early in `_connect_and_get_fingerprint`.
- Test 6 needs a PSBT with relative derivation paths. Construct by modifying the existing `TEST_PSBT_B64` fixture — replace the BIP32 derivation fingerprint with a non-master fingerprint and use short (relative) paths.
- Test 8 needs a mock `status_callback` with `requestAccountPath()` that raises. Reuses the same PSBT fixture as test 6.
- Tests 9-12 use lightweight fake objects — no trezorlib mocking needed.

## Files Changed

- `tests/test_signer.py` — add `TestSignPsbtNegativePaths` and `TestConversionNegativePaths` classes

## Files NOT Changed

- `signer.py` — no production code changes (these are test-only additions)
