# Synthetic PSBT Test Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace permanently-skipped `TestParseMultisigPsbt` and `TestParseOpReturnPsbt` with inline embit-constructed PSBT fixtures so the tests actually run.

**Architecture:** Single-file change. Two test classes get new `@pytest.fixture` methods that build PSBTs programmatically with embit, replacing file-based fixtures. Dead constants and `skipif` guards are removed.

**Tech Stack:** Python, pytest, embit (PSBT/transaction/script/ec modules)

**Spec:** `docs/superpowers/specs/2026-03-20-synthetic-psbt-fixtures-design.md`

---

### Task 1: Rewrite `TestParseMultisigPsbt` with synthetic fixture

**Files:**
- Modify: `tests/test_psbt_parser.py:86-129`

- [ ] **Step 1: Replace the multisig class (lines 86-129) with the new version**

Delete lines 86-129 (the `MULTISIG_PSBT_PATH` constant, `@pytest.mark.skipif`, and entire `TestParseMultisigPsbt` class). Replace with:

```python
class TestParseMultisigPsbt:
    """Tests for 2-of-3 multisig PSBT parsing with synthetic fixture."""

    @pytest.fixture
    def psbt_bytes(self):
        """Build a 2-of-3 P2WSH multisig PSBT, partially signed (1 of 3)."""
        from embit.psbt import PSBT, DerivationPath
        from embit.transaction import Transaction, TransactionInput, TransactionOutput
        from embit.script import Script, p2wpkh, p2wsh
        from embit import ec
        from io import BytesIO
        import hashlib

        # 3 deterministic keys (fixed seeds for reproducibility)
        keys = [ec.PrivateKey(hashlib.sha256(f"test-key-{i}".encode()).digest())
                for i in range(3)]
        pubs = sorted([k.get_public_key() for k in keys],
                       key=lambda p: p.serialize())

        # 2-of-3 multisig witness script
        script_bytes = b'\x52'  # OP_2
        for pub in pubs:
            ser = pub.serialize()
            script_bytes += bytes([len(ser)]) + ser
        script_bytes += b'\x53'  # OP_3
        script_bytes += b'\xae'  # OP_CHECKMULTISIG
        witness_script = Script(script_bytes)
        wsh = p2wsh(witness_script)

        fake_txid = hashlib.sha256(b"multisig-fixture-prevtx").digest()
        tx = Transaction(
            version=2,
            vin=[TransactionInput(fake_txid, 0, sequence=0xfffffffd)],
            vout=[
                TransactionOutput(90000, p2wpkh(pubs[0])),
                TransactionOutput(9000, wsh),
            ],
            locktime=0,
        )

        psbt = PSBT(tx)
        psbt.inputs[0].witness_utxo = TransactionOutput(100000, wsh)
        psbt.inputs[0].witness_script = witness_script

        # BIP32 derivations: unique fingerprint per signer, mainnet path
        HARDENED = 0x80000000
        for i, pub in enumerate(pubs):
            fp = bytes([i + 1, 0, 0, 0])
            path = [48 | HARDENED, 0 | HARDENED, 0 | HARDENED, 2 | HARDENED, 0, 0]
            psbt.inputs[0].bip32_derivations[pub] = DerivationPath(fp, path)

        # One partial signature (first signer) — dummy DER-encoded sig
        psbt.inputs[0].partial_sigs[pubs[0]] = b'\x30\x44' + b'\x00' * 68

        buf = BytesIO()
        psbt.write_to(buf)
        return buf.getvalue()

    def test_partially_signed_status(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["status"] == "partially_signed"

    def test_required_and_total_sigs(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["required_sigs"] == 2
        assert result["total_sigs"] == 3

    def test_reports_all_signers(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert len(result["signers"]) == 3
        signed = [s for s in result["signers"] if s["signed"]]
        unsigned = [s for s in result["signers"] if not s["signed"]]
        assert len(signed) == 1
        assert len(unsigned) == 2

    def test_outputs_have_addresses(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert len(result["outputs"]) >= 1
        for out in result["outputs"]:
            assert out["address"] != "unknown"
            assert out["amount"] > 0

    def test_detects_mainnet(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["network"] == "main"
```

- [ ] **Step 2: Run the multisig tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && PYTHONPATH=app/src/main/python python -m pytest tests/test_psbt_parser.py::TestParseMultisigPsbt -v`

Expected: 5 tests PASS (no skips)

- [ ] **Step 3: Commit**

```bash
git add tests/test_psbt_parser.py
git commit -m "test: replace skipped multisig tests with synthetic PSBT fixture (#14)"
```

---

### Task 2: Rewrite `TestParseOpReturnPsbt` with synthetic fixture

**Files:**
- Modify: `tests/test_psbt_parser.py:131-159` (line numbers after Task 1 edits — find by class name)

- [ ] **Step 1: Replace the OP_RETURN class with the new version**

Delete the `OP_RETURN_PSBT_PATH` constant, `@pytest.mark.skipif`, and entire `TestParseOpReturnPsbt` class. Replace with:

```python
class TestParseOpReturnPsbt:
    """Tests for OP_RETURN output detection and text extraction."""

    @pytest.fixture
    def psbt_bytes(self):
        """Build a single-sig PSBT with an OP_RETURN output."""
        from embit.psbt import PSBT, DerivationPath
        from embit.transaction import Transaction, TransactionInput, TransactionOutput
        from embit.script import Script, p2wpkh
        from embit import ec
        from io import BytesIO
        import hashlib

        key = ec.PrivateKey(hashlib.sha256(b"op-return-fixture-key").digest())
        pub = key.get_public_key()

        # OP_RETURN script: 0x6a + push_len + UTF-8 text
        text = "Synthetic PSBT fixture"
        payload = text.encode("utf-8")
        op_return_script = Script(b'\x6a' + bytes([len(payload)]) + payload)

        fake_txid = hashlib.sha256(b"op-return-fixture-prevtx").digest()
        tx = Transaction(
            version=2,
            vin=[TransactionInput(fake_txid, 0, sequence=0xfffffffd)],
            vout=[
                TransactionOutput(0, op_return_script),
                TransactionOutput(49000, p2wpkh(pub)),
            ],
            locktime=0,
        )

        psbt = PSBT(tx)
        psbt.inputs[0].witness_utxo = TransactionOutput(50000, p2wpkh(pub))

        HARDENED = 0x80000000
        fp = bytes([0xaa, 0xbb, 0xcc, 0xdd])
        path = [84 | HARDENED, 0 | HARDENED, 0 | HARDENED, 0, 0]
        psbt.inputs[0].bip32_derivations[pub] = DerivationPath(fp, path)

        buf = BytesIO()
        psbt.write_to(buf)
        return buf.getvalue()

    def test_detects_op_return_output(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["outputs"][0]["op_return"] == "Synthetic PSBT fixture"

    def test_op_return_amount_is_zero(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert result["outputs"][0]["amount"] == 0

    def test_non_op_return_output_has_no_field(self, psbt_bytes):
        result = parse_psbt(psbt_bytes)
        assert "op_return" not in result["outputs"][1]
```

- [ ] **Step 2: Run the OP_RETURN tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && PYTHONPATH=app/src/main/python python -m pytest tests/test_psbt_parser.py::TestParseOpReturnPsbt -v`

Expected: 3 tests PASS (no skips)

- [ ] **Step 3: Commit**

```bash
git add tests/test_psbt_parser.py
git commit -m "test: replace skipped OP_RETURN tests with synthetic PSBT fixture (#14)"
```

---

### Task 3: Run full test suite and update docs

**Files:**
- Modify: `docs/refactoring-todos.md:228-236`

- [ ] **Step 1: Run the full parser test file**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && PYTHONPATH=app/src/main/python python -m pytest tests/test_psbt_parser.py -v`

Expected: All tests pass, 0 skipped (the testnet tests should also pass since `singlesig_testnet3.psbt` exists)

- [ ] **Step 2: Run the full Python test suite**

Run: `cd /Users/sasha/Projects/remote_signer && source .venv/bin/activate && PYTHONPATH=app/src/main/python python -m pytest tests/ -v`

Expected: No regressions — same pass count as before, minus the 2 previously-skipped classes now passing.

- [ ] **Step 3: Mark #14 as fixed in refactoring-todos.md**

Change:
```markdown
### 14. 🧪 Fix permanently skipped Python tests (missing fixtures)
```
to:
```markdown
### ~~14. 🧪 Fix permanently skipped Python tests (missing fixtures)~~ ✅ FIXED
```

And wrap the description/fix paragraphs in strikethrough, then add a **Fixed:** line:

```markdown
**Fixed:** Replaced file-based fixtures with inline embit-constructed PSBTs. `TestParseMultisigPsbt` builds a synthetic 2-of-3 P2WSH multisig PSBT (partially signed). `TestParseOpReturnPsbt` builds a synthetic PSBT with an OP_RETURN output. All 8 tests now run without skips.
```

- [ ] **Step 4: Update CLAUDE.md if needed**

No architecture changes — skip this step.

- [ ] **Step 5: Commit**

```bash
git add tests/test_psbt_parser.py docs/refactoring-todos.md
git commit -m "docs: mark #14 fixed, all parser tests now run without skips"
```
