# Negative-Path Python Signing Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 11 negative-path tests to `tests/test_signer.py` covering error handling in `sign_psbt()`, `psbt_to_trezor_inputs()`, and `psbt_to_trezor_outputs()`.

**Architecture:** Two test classes: `TestSignPsbtNegativePaths` (9 integration tests through `sign_psbt()`) and `TestConversionNegativePaths` (2 direct unit tests on conversion helpers). Tests follow existing patterns in the file.

**Tech Stack:** Python, pytest, unittest.mock (MagicMock/patch), embit, trezorlib

**Spec:** `docs/superpowers/specs/2026-03-20-negative-path-signing-tests-design.md`

---

### Task 1: Add PSBT parsing failure tests (tests 1-3)

These test that `sign_psbt()` returns `{"status": "error"}` when given invalid PSBT bytes. No mocking needed — `PSBT.parse()` fails before any Trezor interaction.

**Files:**
- Modify: `tests/test_signer.py` (append after `TestIsPsbtFullySigned` class, line ~1380)

- [ ] **Step 1: Write the three tests**

Append to end of `tests/test_signer.py`:

```python
# ---------------------------------------------------------------------------
# Test sign_psbt negative paths
# ---------------------------------------------------------------------------

class TestSignPsbtNegativePaths:
    """sign_psbt should return error status for various failure modes."""

    def test_truncated_psbt_returns_error(self):
        """Truncated PSBT (valid magic + garbage) returns error status."""
        truncated = b"psbt\xff" + b"\x00\x01\x02"
        mock_bridge = MagicMock()
        result = sign_psbt(truncated, mock_bridge, network="test")
        assert result["status"] == "error"
        assert "message" in result

    def test_empty_bytes_returns_error(self):
        """Empty bytes returns error status."""
        mock_bridge = MagicMock()
        result = sign_psbt(b"", mock_bridge, network="test")
        assert result["status"] == "error"
        assert "message" in result

    def test_not_psbt_bytes_returns_error(self):
        """Arbitrary non-PSBT bytes returns error status."""
        mock_bridge = MagicMock()
        result = sign_psbt(b"not a psbt at all", mock_bridge, network="test")
        assert result["status"] == "error"
        assert "message" in result
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && python -m pytest tests/test_signer.py::TestSignPsbtNegativePaths::test_truncated_psbt_returns_error tests/test_signer.py::TestSignPsbtNegativePaths::test_empty_bytes_returns_error tests/test_signer.py::TestSignPsbtNegativePaths::test_not_psbt_bytes_returns_error -v`

Expected: 3 PASSED (these test existing error handling, no production changes needed)

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add PSBT parsing failure tests for sign_psbt (#18)"
```

---

### Task 2: Add connection and fingerprint failure tests (tests 4-5)

These test that `sign_psbt()` returns `{"status": "error"}` when USB connection or fingerprint reading fails. Need the standard `AndroidTransport`/`TrezorClient`/`trezor_btc` mock patches.

**Files:**
- Modify: `tests/test_signer.py` (add methods to `TestSignPsbtNegativePaths`)

- [ ] **Step 1: Write the two tests**

Add to `TestSignPsbtNegativePaths` class:

```python
    def test_connection_failure_returns_error(self):
        """USB transport failure returns error status."""
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport",
                   side_effect=OSError("USB device not found")), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient"):
            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "error"
        assert "USB device not found" in result["message"]

    def test_fingerprint_read_failure_returns_error(self):
        """Failure reading master fingerprint returns error status."""
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient"), \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_btc.get_public_node.side_effect = RuntimeError(
                "Device not initialized"
            )
            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "error"
        assert "Device not initialized" in result["message"]
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py::TestSignPsbtNegativePaths::test_connection_failure_returns_error tests/test_signer.py::TestSignPsbtNegativePaths::test_fingerprint_read_failure_returns_error -v`

Expected: 2 PASSED

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add connection/fingerprint failure tests for sign_psbt (#18)"
```

---

### Task 3: Add relative-paths-no-callback test (test 6)

Tests that `sign_psbt()` returns error when PSBT has relative derivation paths but no callback is provided. Requires patching `PSBT.parse` to return a mock PSBT with relative paths, and using a `side_effect` on `get_public_node` so `_find_key_origin` doesn't match.

**Files:**
- Modify: `tests/test_signer.py` (add method to `TestSignPsbtNegativePaths`)

- [ ] **Step 1: Write the test**

Add to `TestSignPsbtNegativePaths` class:

```python
    def test_relative_paths_no_callback_returns_error(self):
        """PSBT with relative paths and no callback returns error status."""
        # Build a mock PSBT with relative derivation paths
        mock_deriv = MagicMock()
        mock_deriv.fingerprint = b"\xAB\xCD\xEF\x01"
        mock_deriv.derivation = [0, 5]  # Relative: unhardened first component

        mock_pub = MagicMock()
        mock_pub.sec.return_value = b"\x02" + b"\xaa" * 32

        mock_inp = MagicMock()
        mock_inp.bip32_derivations = {mock_pub: mock_deriv}
        mock_inp.taproot_bip32_derivations = {}
        mock_inp.utxo.script_pubkey.data = b"\x00\x14" + b"\xab" * 20

        mock_psbt = MagicMock()
        mock_psbt.inputs = [mock_inp]
        mock_psbt.outputs = []
        mock_psbt.tx_version = 2
        mock_psbt.locktime = 0

        # get_public_node: first call returns fingerprint, subsequent calls
        # return non-matching pubkeys so _find_key_origin exhausts all probes
        call_count = [0]
        def gpn_side_effect(client, n=None, coin_name=None):
            call_count[0] += 1
            result = MagicMock()
            if call_count[0] == 1:
                # _get_master_fingerprint call
                result.root_fingerprint = 0xABCDEF01
                result.node = MagicMock(public_key=b"\x02" + b"\xbb" * 32)
            else:
                # _find_key_origin probing — never match
                result.node = MagicMock(public_key=b"\x02" + b"\xcc" * 32)
            return result

        with patch("remotesigner.signer.PSBT.parse", return_value=mock_psbt), \
             patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient"), \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_btc.get_public_node.side_effect = gpn_side_effect
            # No status_callback — triggers the ValueError
            result = sign_psbt(b"dummy", MagicMock(), status_callback=None, network="test")

        assert result["status"] == "error"
        assert "relative derivation paths" in result["message"].lower()
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py::TestSignPsbtNegativePaths::test_relative_paths_no_callback_returns_error -v`

Expected: 1 PASSED

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add relative-paths-no-callback test for sign_psbt (#18)"
```

---

### Task 4: Add TrezorFailure non-cancellation test (test 7)

Tests that a `TrezorFailure` with a code other than `ActionCancelled`/`PinCancelled` returns `"error"` status, not `"cancelled"`.

**Files:**
- Modify: `tests/test_signer.py` (add method to `TestSignPsbtNegativePaths`)

- [ ] **Step 1: Write the test**

Add to `TestSignPsbtNegativePaths` class:

```python
    def test_other_trezor_failure_code_returns_error(self):
        """Non-cancellation TrezorFailure returns error, not cancelled."""
        from trezorlib.exceptions import TrezorFailure
        from trezorlib.messages import Failure, FailureType

        failure = Failure(code=FailureType.UnexpectedMessage)

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

        assert result["status"] == "error"
        assert result["status"] != "cancelled"
        assert "message" in result
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py::TestSignPsbtNegativePaths::test_other_trezor_failure_code_returns_error -v`

Expected: 1 PASSED

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add non-cancellation TrezorFailure test for sign_psbt (#18)"
```

---

### Task 5: Add account-path callback error test (test 8)

Tests that when `status_callback.requestAccountPath()` raises, `sign_psbt()` returns error with message about "Account path". Uses same mock PSBT pattern as test 6.

**Files:**
- Modify: `tests/test_signer.py` (add method to `TestSignPsbtNegativePaths`)

- [ ] **Step 1: Write the test**

Add to `TestSignPsbtNegativePaths` class:

```python
    def test_account_path_callback_error_returns_error(self):
        """Callback raising during account path request returns error status."""
        mock_deriv = MagicMock()
        mock_deriv.fingerprint = b"\xAB\xCD\xEF\x01"
        mock_deriv.derivation = [0, 5]  # Relative path

        mock_pub = MagicMock()
        mock_pub.sec.return_value = b"\x02" + b"\xaa" * 32

        mock_inp = MagicMock()
        mock_inp.bip32_derivations = {mock_pub: mock_deriv}
        mock_inp.taproot_bip32_derivations = {}
        mock_inp.utxo.script_pubkey.data = b"\x00\x14" + b"\xab" * 20

        mock_psbt = MagicMock()
        mock_psbt.inputs = [mock_inp]
        mock_psbt.outputs = []
        mock_psbt.tx_version = 2
        mock_psbt.locktime = 0

        call_count = [0]
        def gpn_side_effect(client, n=None, coin_name=None):
            call_count[0] += 1
            result = MagicMock()
            if call_count[0] == 1:
                result.root_fingerprint = 0xABCDEF01
                result.node = MagicMock(public_key=b"\x02" + b"\xbb" * 32)
            else:
                result.node = MagicMock(public_key=b"\x02" + b"\xcc" * 32)
            return result

        mock_callback = MagicMock()
        mock_callback.requestAccountPath.side_effect = RuntimeError("User dismissed dialog")

        with patch("remotesigner.signer.PSBT.parse", return_value=mock_psbt), \
             patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient"), \
             patch("remotesigner.signer.trezor_btc") as mock_btc:
            mock_btc.get_public_node.side_effect = gpn_side_effect

            result = sign_psbt(b"dummy", MagicMock(),
                               status_callback=mock_callback, network="test")

        assert result["status"] == "error"
        assert "account path" in result["message"].lower()
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py::TestSignPsbtNegativePaths::test_account_path_callback_error_returns_error -v`

Expected: 1 PASSED

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add account-path callback error test for sign_psbt (#18)"
```

---

### Task 6: Add output conversion error integration test (test 9)

Tests that when `psbt_to_trezor_outputs` raises `ValueError` inside `_perform_signing`, `sign_psbt()` catches it and returns error status. Patches `psbt_to_trezor_outputs` at module level.

**Files:**
- Modify: `tests/test_signer.py` (add method to `TestSignPsbtNegativePaths`)

- [ ] **Step 1: Write the test**

Add to `TestSignPsbtNegativePaths` class:

```python
    def test_output_conversion_error_returns_error(self):
        """ValueError from psbt_to_trezor_outputs flows through as error status."""
        psbt_bytes = base64.b64decode(TEST_PSBT_B64)
        mock_bridge = MagicMock()

        with patch("remotesigner.signer.AndroidTransport"), \
             patch("remotesigner.signer.AndroidTrezorUi"), \
             patch("remotesigner.signer.TrezorClient"), \
             patch("remotesigner.signer.trezor_btc") as mock_btc, \
             patch("remotesigner.signer.psbt_to_trezor_outputs",
                   side_effect=ValueError("Cannot derive address from scriptPubKey: deadbeef")):
            mock_btc.get_public_node.return_value = MagicMock(
                root_fingerprint=0x3442193E,
                node=MagicMock(public_key=b"\x02" + b"\xff" * 32),
            )

            result = sign_psbt(psbt_bytes, mock_bridge, network="test")

        assert result["status"] == "error"
        assert "Cannot derive address" in result["message"]
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py::TestSignPsbtNegativePaths::test_output_conversion_error_returns_error -v`

Expected: 1 PASSED

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add output conversion error integration test for sign_psbt (#18)"
```

---

### Task 7: Add conversion helper unit tests (tests 10-11)

Direct unit tests on `psbt_to_trezor_inputs` (missing UTXO) and `psbt_to_trezor_outputs` (bad scriptPubKey). Requires new fake embit classes with richer attributes than the existing `_FakeInputScope`.

**Files:**
- Modify: `tests/test_signer.py` (append new class after `TestSignPsbtNegativePaths`)

- [ ] **Step 1: Write the fake helpers and tests**

Append after `TestSignPsbtNegativePaths`:

```python
# ---------------------------------------------------------------------------
# Test conversion helper negative paths
# ---------------------------------------------------------------------------

class _FakeConversionInputScope:
    """Fake input scope for psbt_to_trezor_inputs tests."""
    def __init__(self, utxo=None, script_pubkey_data=b"\x00\x14" + b"\xab" * 20,
                 txid=b"\x00" * 32, vout=0, sequence=0xFFFFFFFF,
                 bip32_derivations=None, redeem_script=None,
                 witness_script=None, partial_sigs=None):
        self.utxo = utxo
        self.txid = txid
        self.vout = vout
        self.sequence = sequence
        self.bip32_derivations = bip32_derivations or {}
        self.taproot_bip32_derivations = {}
        self.redeem_script = _FakeScript(redeem_script) if redeem_script else None
        self.witness_script = _FakeScript(witness_script) if witness_script else None
        self.partial_sigs = partial_sigs or {}


class _FakeBadOutputScope:
    """Fake output scope whose script_pubkey.address() raises."""
    def __init__(self):
        self.value = 50000
        sp = MagicMock()
        sp.data = b"\x99" * 25  # Nonsense script
        sp.address.side_effect = Exception("unknown script type")
        self.script_pubkey = sp
        self.bip32_derivations = {}
        self.taproot_bip32_derivations = {}
        self.redeem_script = None
        self.witness_script = None


class TestConversionNegativePaths:
    """Direct unit tests for conversion helper error handling."""

    def test_input_missing_utxo_raises(self):
        """Input with utxo=None raises ValueError."""
        inp = _FakeConversionInputScope(utxo=None)
        mock_psbt = MagicMock()
        mock_psbt.inputs = [inp]

        with pytest.raises(ValueError, match="no UTXO"):
            psbt_to_trezor_inputs(mock_psbt, MASTER_FP)

    def test_output_bad_script_pubkey_raises(self):
        """Output with unrecognizable scriptPubKey raises ValueError."""
        out = _FakeBadOutputScope()
        mock_psbt = MagicMock()
        mock_psbt.outputs = [out]

        with pytest.raises(ValueError, match="Cannot derive address"):
            psbt_to_trezor_outputs(mock_psbt, MASTER_FP, network="test")
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py::TestConversionNegativePaths -v`

Expected: 2 PASSED

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py
git commit -m "test: add conversion helper negative-path unit tests (#18)"
```

---

### Task 8: Run full test suite and update docs

Verify all 11 new tests pass alongside existing tests. Update the refactoring TODO to mark #18 as fixed.

**Files:**
- Modify: `docs/refactoring-todos.md` (mark #18 as fixed)

- [ ] **Step 1: Run full test suite**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_signer.py -v`

Expected: All tests PASS (existing + 11 new)

- [ ] **Step 2: Update refactoring TODO**

In `docs/refactoring-todos.md`, replace the `### 18.` section (lines 276-283) with:

```markdown
### ~~18. 🧪 Add negative-path Python signing tests~~ ✅ FIXED
| | |
|---|---|
| **File** | Expand `tests/test_signer.py` |
| **Consensus** | System Architect |

~~What happens with: truncated PSBT? No matching fingerprint? Relative paths and no callback? These error paths need coverage.~~

**Fixed:** 11 negative-path tests added in `TestSignPsbtNegativePaths` (9 integration tests through `sign_psbt`) and `TestConversionNegativePaths` (2 direct unit tests). Covers: truncated/empty/invalid PSBT bytes, USB connection failure, fingerprint read failure, relative paths without callback, non-cancellation TrezorFailure codes, account path callback error, output conversion error, missing UTXO validation, and bad scriptPubKey address derivation.
```

Also update the Testing Coverage Map "Gaps to close" table — mark the "Signing error paths" row as done:

```markdown
| ~~Signing error paths~~ | ~~Python unit tests~~ | ~~**Medium**~~ | ~~Done (#18)~~ |
```

- [ ] **Step 3: Commit**

```bash
git add tests/test_signer.py docs/refactoring-todos.md
git commit -m "docs: mark #18 negative-path signing tests as fixed"
```
