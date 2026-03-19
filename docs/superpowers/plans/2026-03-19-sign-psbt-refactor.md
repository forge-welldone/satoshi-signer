# Refactor sign_psbt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Break the 200-line `sign_psbt` function into 4 extracted functions, each independently testable, while preserving identical public behavior.

**Architecture:** Pure extract-method refactoring. Four private functions (`_connect_and_get_fingerprint`, `_resolve_paths`, `_perform_signing`, `_insert_signatures`) are extracted from `sign_psbt`. The orchestrator keeps the try/finally/except error handling. Status messaging uses a `_status` callable passed to each function.

**Tech Stack:** Python, embit, trezorlib

**Spec:** `docs/superpowers/specs/2026-03-19-sign-psbt-refactor-design.md`

---

## File Map

- **Modify:** `app/src/main/python/remotesigner/signer.py` — extract 4 functions, rewrite `sign_psbt` as orchestrator, fix docstring
- **Test:** `tests/test_signer.py` — existing tests verify no behavioral change

---

### Task 1: Verify existing tests pass (baseline)

**Files:**
- Test: `tests/test_signer.py`

- [ ] **Step 1: Run existing signer tests**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`
Expected: All tests PASS. Record the count (should be ~40+ tests).

---

### Task 2: Extract `_connect_and_get_fingerprint`

**Files:**
- Modify: `app/src/main/python/remotesigner/signer.py:749` (insert new function before `sign_psbt`, then update `sign_psbt`)

- [ ] **Step 1: Add `_connect_and_get_fingerprint` function**

Insert this function above `sign_psbt` (after the `# Main signing function` comment, before the `def sign_psbt` line):

```python
def _connect_and_get_fingerprint(
    bridge,
    status_callback,
    coin_name: str,
    _status: Callable[[str], None],
) -> Tuple["TrezorClient", bytes]:
    """Create a Trezor client connection and read the master fingerprint."""
    _status("Connecting to Trezor...")
    transport = AndroidTransport(bridge)
    ui = AndroidTrezorUi(status_callback)
    client = TrezorClient(transport, ui=ui)
    _status("Reading device fingerprint...")
    master_fp = _get_master_fingerprint(client, coin_name)
    return client, master_fp
```

- [ ] **Step 2: Update `sign_psbt` to call `_connect_and_get_fingerprint`**

In `sign_psbt`, replace the block from `# Connect to the Trezor` through `_status(f"Device fingerprint: {master_fp.hex()}")` with:

```python
        client, master_fp = _connect_and_get_fingerprint(
            bridge, status_callback, coin_name, _status
        )

        try:
            _status(f"Device fingerprint: {master_fp.hex()}")
```

Remove the old lines:
```python
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
```

- [ ] **Step 3: Run tests to verify no behavioral change**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`
Expected: All tests PASS, same count as baseline.

---

### Task 3: Extract `_resolve_paths`

**Files:**
- Modify: `app/src/main/python/remotesigner/signer.py`

- [ ] **Step 1: Add `_resolve_paths` function**

Insert after `_connect_and_get_fingerprint`, before `sign_psbt`:

```python
def _resolve_paths(
    client: "TrezorClient",
    coin_name: str,
    psbt: PSBT,
    master_fp: bytes,
    status_callback,
    _status: Callable[[str], None],
) -> Dict[bytes, List[int]]:
    """Resolve derivation path prefixes for watch-only wallet PSBTs.

    Tries auto-detection via ``_find_key_origin``.  Falls back to asking
    the user via ``status_callback.requestAccountPath()`` when relative
    paths are present but auto-detection fails.
    """
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

    return fp_to_prefix
```

- [ ] **Step 2: Update `sign_psbt` to call `_resolve_paths`**

In `sign_psbt`, replace the block from `# Resolve key origins` through the `_status(f"Resolved account path: m/{path_str}")` block with:

```python
            fp_to_prefix = _resolve_paths(
                client, coin_name, psbt, master_fp, status_callback, _status
            )
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`
Expected: All tests PASS.

---

### Task 4: Extract `_perform_signing`

**Files:**
- Modify: `app/src/main/python/remotesigner/signer.py`

- [ ] **Step 1: Add `_perform_signing` function**

Insert after `_resolve_paths`, before `sign_psbt`:

```python
def _perform_signing(
    client: "TrezorClient",
    coin_name: str,
    psbt: PSBT,
    master_fp: bytes,
    fp_to_prefix: Dict[bytes, List[int]],
    network: str,
    _status: Callable[[str], None],
) -> Tuple[list, Optional[bytes], List[TxInputType], List[int]]:
    """Convert PSBT to trezorlib types and sign on the Trezor.

    Returns ``(signatures, serialized_tx, trezor_inputs, to_ignore)``.
    """
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

    return signatures, serialized_tx, trezor_inputs, to_ignore
```

- [ ] **Step 2: Update `sign_psbt` to call `_perform_signing`**

In `sign_psbt`, replace the block from `# Convert PSBT to trezorlib types` through the `sign_tx` return with:

```python
            signatures, serialized_tx, trezor_inputs, to_ignore = _perform_signing(
                client, coin_name, psbt, master_fp, fp_to_prefix, network, _status
            )
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`
Expected: All tests PASS.

---

### Task 5: Extract `_insert_signatures`

**Files:**
- Modify: `app/src/main/python/remotesigner/signer.py`

- [ ] **Step 1: Add `_insert_signatures` function**

Insert after `_perform_signing`, before `sign_psbt`:

```python
def _insert_signatures(
    psbt: PSBT,
    signatures: list,
    serialized_tx: Optional[bytes],
    trezor_inputs: List[TxInputType],
    to_ignore: List[int],
    master_fp: bytes,
    fp_to_prefix: Dict[bytes, List[int]],
    _status: Callable[[str], None],
) -> dict:
    """Insert Trezor signatures into the PSBT and build the result dict.

    Returns a dict with ``status`` (``"complete"`` or ``"partial"``),
    ``psbt`` (base64), and optionally ``raw_tx`` (hex).
    """
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
        return {
            "status": "complete",
            "raw_tx": serialized_tx.hex(),
            "psbt": signed_psbt_b64,
        }
    else:
        return {
            "status": "partial",
            "psbt": signed_psbt_b64,
        }
```

- [ ] **Step 2: Update `sign_psbt` to call `_insert_signatures`**

In `sign_psbt`, replace the block from `# Insert signatures back into the PSBT` through the result dict construction (ending before `_status("Signing complete.")`) with:

```python
            result = _insert_signatures(
                psbt, signatures, serialized_tx, trezor_inputs, to_ignore,
                master_fp, fp_to_prefix, _status
            )
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`
Expected: All tests PASS.

---

### Task 6: Fix docstring and update imports/exports

**Files:**
- Modify: `app/src/main/python/remotesigner/signer.py:755-776` (docstring)
- Modify: `tests/test_signer.py:14-30` (imports)

- [ ] **Step 1: Fix `sign_psbt` docstring**

Replace the existing Returns section of the `sign_psbt` docstring:

```python
    Returns
    -------
    dict with keys:
        - ``status``: ``"signed"`` or ``"error"``
        - ``psbt``: base64-encoded signed PSBT (on success)
        - ``raw_tx``: hex-encoded serialized transaction (on success, if
          fully signed)
        - ``error``: error message string (on failure)
```

With:

```python
    Returns
    -------
    dict with keys:
        - ``status``: ``"complete"``, ``"partial"``, ``"cancelled"``, or
          ``"error"``
        - ``psbt``: base64-encoded signed PSBT (on ``"complete"`` or
          ``"partial"``)
        - ``raw_tx``: hex-encoded serialized transaction (on ``"complete"``
          only, when fully signed)
        - ``message``: error/cancellation message string (on ``"error"``
          or ``"cancelled"``)
```

- [ ] **Step 2: Run all tests**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`
Expected: All tests PASS, same count as baseline.

---

### Task 7: Final verification and mark TODO as fixed

**Files:**
- Modify: `docs/refactoring-todos.md` (mark #9 as fixed)

- [ ] **Step 1: Run full Python test suite**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/ -v`
Expected: All tests PASS.

- [ ] **Step 2: Verify the refactored `sign_psbt` is ~35 lines**

Read `sign_psbt` function body and confirm it's a clean orchestrator (no inline logic, just calls to the 4 extracted functions + error handling).

- [ ] **Step 3: Mark TODO #9 as fixed in `docs/refactoring-todos.md`**

Update the `### 9. Refactor sign_psbt in Python (200-line function)` section to match the pattern of other fixed items. Change the heading to `### ~~9. Refactor sign_psbt in Python (200-line function)~~ ✅ FIXED` and add a summary line like:

```
**Fixed:** Extracted `_connect_and_get_fingerprint()`, `_resolve_paths()`, `_perform_signing()`, `_insert_signatures()` from the 200-line `sign_psbt()`. Orchestrator is now ~35 lines. All existing tests pass unchanged. Fixed stale docstring (said "signed"/"error", actual statuses are "complete"/"partial"/"cancelled"/"error").
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/python/remotesigner/signer.py tests/test_signer.py docs/refactoring-todos.md
git commit -m "refactor: extract sign_psbt into 4 focused functions (TODO #9)"
```
