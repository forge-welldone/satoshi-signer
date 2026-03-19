# Design: Refactor sign_psbt into smaller functions

**Date:** 2026-03-19
**TODO:** #9 — Refactor sign_psbt in Python (200-line function)
**File:** `remotesigner/signer.py:749-955`

## Problem

`sign_psbt` is a ~200-line function with a nested try/finally/try/except block handling: PSBT parsing, transport creation, fingerprint reading, path resolution (with user callback fallback), PSBT-to-trezorlib conversion, signing, and signature insertion. The monolithic structure makes individual phases hard to test in isolation.

## Approach

Extract four named functions from `sign_psbt`. Each handles one phase and is independently testable. `sign_psbt` becomes a ~35-line orchestrator that calls them in sequence inside the existing error handling structure.

## Status messaging convention

The `_status` closure is defined in `sign_psbt` and wraps `status_callback.onStatus()` with a try/except. Extracted functions that need status updates receive `_status` as a `Callable[[str], None]` parameter — this avoids duplicating the try/except pattern. Functions that need the raw `status_callback` object (for `requestAccountPath()` or `AndroidTrezorUi` constructor) receive it separately.

## Extracted Functions

### `_connect_and_get_fingerprint(bridge, status_callback, coin_name, _status) -> Tuple[TrezorClient, bytes]`

- Creates `AndroidTransport(bridge)`
- Creates `AndroidTrezorUi(status_callback)` — needs raw `status_callback` for the UI handler constructor
- Creates `TrezorClient(transport, ui=ui)`
- Emits "Reading device fingerprint..." via `_status`
- Calls `_get_master_fingerprint(client, coin_name)`
- Returns `(client, master_fp)`
- Caller owns `client.close()` — stays in `sign_psbt`'s `finally` block

**Extracted from:** lines 793-802

### `_resolve_paths(client, coin_name, psbt, master_fp, status_callback, _status) -> Dict[bytes, List[int]]`

- Calls `_find_key_origin()` for auto-detection
- Falls back to `status_callback.requestAccountPath()` if auto-detection fails and relative paths exist — needs raw `status_callback` for the blocking callback
- Logs resolved paths via `_status`
- Returns `fp_to_prefix` dict (possibly empty)

**Extracted from:** lines 805-845

### `_perform_signing(client, coin_name, psbt, master_fp, fp_to_prefix, network, _status) -> Tuple[list, Optional[bytes], List[TxInputType], List[int]]`

- Converts PSBT to trezorlib types via existing helpers (`psbt_to_trezor_inputs`, `psbt_to_trezor_outputs`, `psbt_to_prev_txes`)
- Extracts `version`/`lock_time` from PSBT globals
- Calls `trezor_btc.sign_tx()`
- Returns `(signatures, serialized_tx, trezor_inputs, to_ignore)`

**Extracted from:** lines 848-873

### `_insert_signatures(psbt, signatures, serialized_tx, trezor_inputs, to_ignore, master_fp, fp_to_prefix, _status) -> dict`

- Iterates signatures, skipping `None` and ignored indices
- Inserts taproot sigs as `PSBT_IN_TAP_KEY_SIG` (unknown key `0x13`)
- Inserts ECDSA sigs with `SIGHASH_ALL` byte into `partial_sigs`
- Checks `_is_psbt_fully_signed()` to determine `complete` vs `partial`
- Returns result dict: `status` is `"complete"` or `"partial"`, always includes `psbt` (base64), `"complete"` also includes `raw_tx` (hex from `serialized_tx`)

**Extracted from:** lines 876-920

## sign_psbt after refactoring

```python
def sign_psbt(psbt_bytes, bridge, status_callback=None, network="main"):
    coin_name = "Bitcoin" if network == "main" else "Testnet"

    def _status(msg):
        if status_callback is not None:
            try:
                status_callback.onStatus(msg)
            except Exception:
                pass

    try:
        _status("Parsing PSBT...")
        psbt_bytes = bytes(psbt_bytes)
        psbt = PSBT.parse(psbt_bytes)

        client, master_fp = _connect_and_get_fingerprint(
            bridge, status_callback, coin_name, _status
        )

        try:
            _status(f"Device fingerprint: {master_fp.hex()}")

            fp_to_prefix = _resolve_paths(
                client, coin_name, psbt, master_fp, status_callback, _status
            )

            signatures, serialized_tx, trezor_inputs, to_ignore = _perform_signing(
                client, coin_name, psbt, master_fp, fp_to_prefix, network, _status
            )

            result = _insert_signatures(
                psbt, signatures, serialized_tx, trezor_inputs, to_ignore,
                master_fp, fp_to_prefix, _status
            )

            _status("Signing complete.")
            return result

        finally:
            try:
                client.close()
            except Exception:
                pass

    except TrezorFailure as e:
        # ... unchanged cancellation/error handling ...
    except Exception as e:
        # ... unchanged cancellation/error handling ...
```

## What does NOT change

- Public API of `sign_psbt` — same parameters, same return dict shape
- Error handling structure — try/finally/except stays in `sign_psbt`
- All existing tests pass without modification
- No new dependencies or imports

## Test impact

- Each extracted function becomes independently testable with mocks
- Existing `TestSignPsbtCancellation` tests remain unchanged
- New unit tests for extracted functions deferred to TODO #13/#18
- TDD for this refactoring: verify existing tests pass before and after

## Side fix

The existing `sign_psbt` docstring says return status is `"signed"` or `"error"`, but actual return statuses are `"complete"`, `"partial"`, `"cancelled"`, or `"error"`. Fix the docstring while refactoring.

## Implementation plan

1. Write the 4 extracted functions above `sign_psbt`
2. Replace the corresponding code blocks in `sign_psbt` with calls to the extracted functions
3. Fix `sign_psbt` docstring to document actual return statuses
4. Run `pytest tests/test_signer.py -v` to verify all existing tests pass
5. Mark TODO #9 as fixed in `docs/refactoring-todos.md`
