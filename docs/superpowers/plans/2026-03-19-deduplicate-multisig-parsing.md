# Deduplicate Multisig Script Parsing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract shared OP_CHECKMULTISIG byte parsing from `psbt_parser.py` and `signer.py` into a single `script_utils.py` module.

**Architecture:** New pure utility module `remotesigner/script_utils.py` with `parse_multisig_script()` returning a `MultisigInfo` dataclass (m, n, pubkeys). Both consumers delegate byte parsing to the shared function — `psbt_parser.py` reads `.m` and `.n`, `signer.py` reads all three fields and builds trezorlib types on top.

**Tech Stack:** Python 3.13, embit (PSBT), trezorlib (MultisigRedeemScriptType), pytest

**Spec:** `docs/superpowers/specs/2026-03-19-deduplicate-multisig-parsing-design.md`

---

### Task 1: Create `script_utils.py` with `parse_multisig_script` (TDD)

**Files:**
- Create: `tests/test_script_utils.py`
- Create: `app/src/main/python/remotesigner/script_utils.py`

- [ ] **Step 1: Write test file with helper and first test**

```python
"""Tests for script_utils — shared multisig script parsing."""

from remotesigner.script_utils import parse_multisig_script, MultisigInfo


def _make_multisig_script(pubs, m):
    """Helper: build OP_m <pubs...> OP_n OP_CHECKMULTISIG."""
    n = len(pubs)
    script = bytes([0x50 + m])
    for pub in pubs:
        script += bytes([len(pub)]) + pub
    script += bytes([0x50 + n, 0xAE])
    return script


class TestParseMultisigScript:
    def test_valid_2_of_3(self):
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        script = _make_multisig_script([pub1, pub2, pub3], m=2)

        info = parse_multisig_script(script)
        assert info is not None
        assert info.m == 2
        assert info.n == 3
        assert info.pubkeys == [pub1, pub2, pub3]

    def test_valid_1_of_1(self):
        pub = b"\x02" + b"\xaa" * 32
        script = _make_multisig_script([pub], m=1)

        info = parse_multisig_script(script)
        assert info is not None
        assert info.m == 1
        assert info.n == 1
        assert info.pubkeys == [pub]

    def test_returns_none_empty_script(self):
        assert parse_multisig_script(b"") is None

    def test_returns_none_too_short(self):
        assert parse_multisig_script(b"\x51\x51\xae") is None

    def test_returns_none_no_checkmultisig(self):
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x51, len(pub)]) + pub + bytes([0x51, 0x00])
        assert parse_multisig_script(script) is None

    def test_returns_none_invalid_m_byte(self):
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x50, len(pub)]) + pub + bytes([0x51, 0xAE])  # 0x50 < OP_1
        assert parse_multisig_script(script) is None

    def test_returns_none_invalid_n_byte(self):
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x51, len(pub)]) + pub + bytes([0x61, 0xAE])  # 0x61 > OP_16
        assert parse_multisig_script(script) is None

    def test_returns_none_truncated_pubkeys(self):
        # Script claims 2-of-2 but only has 1 pubkey worth of data
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x52, len(pub)]) + pub + bytes([0x52, 0xAE])
        assert parse_multisig_script(script) is None

    def test_returns_multisig_info_type(self):
        pub = b"\x02" + b"\xbb" * 32
        script = _make_multisig_script([pub], m=1)
        info = parse_multisig_script(script)
        assert isinstance(info, MultisigInfo)

    def test_p2wpkh_not_multisig(self):
        script = b"\x00\x14" + b"\xab" * 20
        assert parse_multisig_script(script) is None

    def test_valid_15_of_15(self):
        """Upper boundary: OP_15 is 0x5f, the max commonly used."""
        pubs = [b"\x02" + bytes([i]) * 32 for i in range(15)]
        script = _make_multisig_script(pubs, m=15)
        info = parse_multisig_script(script)
        assert info is not None
        assert info.m == 15
        assert info.n == 15
        assert len(info.pubkeys) == 15
```

- [ ] **Step 2: Run tests — verify they fail (module doesn't exist yet)**

Run: `python -m pytest tests/test_script_utils.py -v`
Expected: `ModuleNotFoundError: No module named 'remotesigner.script_utils'`

- [ ] **Step 3: Create `script_utils.py` with implementation**

Create `app/src/main/python/remotesigner/script_utils.py`:

```python
"""Shared Bitcoin script parsing utilities.

Pure functions — no trezorlib or embit dependencies.
"""

from dataclasses import dataclass
from typing import List, Optional

OP_CHECKMULTISIG = 0xAE


@dataclass
class MultisigInfo:
    """Parsed m-of-n multisig script data."""
    m: int
    n: int
    pubkeys: List[bytes]


def parse_multisig_script(script: bytes) -> Optional[MultisigInfo]:
    """Parse an OP_CHECKMULTISIG script into m, n, and pubkeys.

    Supports standard scripts of the form:
        OP_m <pub1> <pub2> ... <pubN> OP_n OP_CHECKMULTISIG

    Returns None if the script is not a recognizable multisig.
    """
    if len(script) < 37:
        return None
    if script[-1] != OP_CHECKMULTISIG:
        return None

    # OP_1..OP_16 => 0x51..0x60
    m_byte = script[0]
    if not (0x51 <= m_byte <= 0x60):
        return None
    m = m_byte - 0x50

    n_byte = script[-2]
    if not (0x51 <= n_byte <= 0x60):
        return None
    n = n_byte - 0x50

    # Extract public keys
    pubkeys: List[bytes] = []
    pos = 1
    for _ in range(n):
        if pos >= len(script) - 2:
            return None
        key_len = script[pos]
        pos += 1
        if pos + key_len > len(script) - 2:
            return None
        pubkeys.append(script[pos : pos + key_len])
        pos += key_len

    if len(pubkeys) != n:
        return None

    return MultisigInfo(m=m, n=n, pubkeys=pubkeys)
```

- [ ] **Step 4: Run tests — verify all pass**

Run: `python -m pytest tests/test_script_utils.py -v`
Expected: 11 tests PASS

- [ ] **Step 5: Commit**

```bash
git add tests/test_script_utils.py app/src/main/python/remotesigner/script_utils.py
git commit -m "feat: add script_utils with parse_multisig_script (TODO #10)"
```

---

### Task 2: Wire `psbt_parser.py` to use `script_utils`

**Files:**
- Modify: `app/src/main/python/remotesigner/psbt_parser.py:211-257`

- [ ] **Step 1: Run existing parser tests to confirm green baseline**

Run: `python -m pytest tests/test_psbt_parser.py -v`
Expected: all pass

- [ ] **Step 2: Add import and replace call site in `_analyze_signing_status`**

In `psbt_parser.py`, add import at top (after the existing imports, around line 10):
```python
from remotesigner.script_utils import parse_multisig_script
```

Replace the call site at lines 251-257 (inside `_analyze_signing_status`):

Before:
```python
        # Extract m-of-n from multisig scripts
        ms = inp_scope.witness_script or inp_scope.redeem_script
        if ms is not None:
            m, n = _parse_multisig_info(ms.data)
            if m is not None:
                required_sigs = max(required_sigs, m)
                total_sigs = max(total_sigs, n)
```

After:
```python
        # Extract m-of-n from multisig scripts
        ms = inp_scope.witness_script or inp_scope.redeem_script
        if ms is not None:
            info = parse_multisig_script(ms.data)
            if info is not None:
                required_sigs = max(required_sigs, info.m)
                total_sigs = max(total_sigs, info.n)
```

- [ ] **Step 3: Delete `_parse_multisig_info` function (lines 211-231)**

Remove the entire function — it's now replaced by the shared `parse_multisig_script`.

- [ ] **Step 4: Run parser tests — verify all still pass**

Run: `python -m pytest tests/test_psbt_parser.py -v`
Expected: all pass (no public API changed)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/python/remotesigner/psbt_parser.py
git commit -m "refactor: psbt_parser uses shared parse_multisig_script (TODO #10)"
```

---

### Task 3: Wire `signer.py` to use `script_utils`

**Files:**
- Modify: `app/src/main/python/remotesigner/signer.py:159-238`

- [ ] **Step 1: Run existing signer tests to confirm green baseline**

Run: `python -m pytest tests/test_signer.py -v`
Expected: all pass

- [ ] **Step 2: Add import and refactor `_parse_multisig_script`**

In `signer.py`, add import at top (after the existing imports, around line 15):
```python
from remotesigner.script_utils import parse_multisig_script as _parse_multisig
```

Replace the body of `_parse_multisig_script` (lines 159-238). Keep the function signature and docstring; replace the byte-parsing body with a call to the shared function:

```python
def _parse_multisig_script(
    script: bytes,
    bip32_derivations: dict,
    psbt: PSBT,
    partial_sigs: Optional[dict] = None,
) -> Optional[MultisigRedeemScriptType]:
    """Parse a multisig script and return a ``MultisigRedeemScriptType``.

    Only supports standard OP_CHECKMULTISIG scripts of the form:
        OP_m <pub1> <pub2> ... <pubN> OP_n OP_CHECKMULTISIG

    Parameters
    ----------
    partial_sigs:
        Existing partial signatures from the PSBT input scope.
        Used to populate the signatures array for partially signed PSBTs.

    Returns ``None`` if the script is not a recognizable multisig.
    """
    info = _parse_multisig(script)
    if info is None:
        return None

    # Build HDNodePathType entries for each pubkey.
    # We use address_n=[] (empty) so the Trezor uses public_key directly
    # for matching, without attempting derivation (which would require a
    # real chain_code we don't have).
    hd_nodes: List[HDNodePathType] = []
    for raw_pub in info.pubkeys:
        node = HDNodeType(
            depth=0,
            fingerprint=0,
            child_num=0,
            chain_code=b"\x00" * 32,
            public_key=raw_pub,
        )
        hd_nodes.append(HDNodePathType(node=node, address_n=[]))

    # Populate signatures array from existing partial_sigs
    sigs: List[bytes] = [b""] * info.n
    if partial_sigs:
        for j, raw_pub in enumerate(info.pubkeys):
            for pub, sig in partial_sigs.items():
                if pub.sec() == raw_pub:
                    sigs[j] = sig
                    break

    return MultisigRedeemScriptType(
        pubkeys=hd_nodes,
        signatures=sigs,
        m=info.m,
    )
```

- [ ] **Step 3: Run signer tests — verify all pass**

Run: `python -m pytest tests/test_signer.py -v`
Expected: all pass (function signature unchanged, behavior identical)

- [ ] **Step 4: Run full test suite to catch any regressions**

Run: `python -m pytest tests/ -v`
Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/python/remotesigner/signer.py
git commit -m "refactor: signer uses shared parse_multisig_script (TODO #10)"
```

---

### Task 4: Update TODO list and docs

**Files:**
- Modify: `docs/refactoring-todos.md:167-177`

- [ ] **Step 1: Mark TODO #10 as fixed in refactoring-todos.md**

Change the heading at line 167 from:
```markdown
### 10. Deduplicate multisig script parsing
```
to:
```markdown
### ~~10. Deduplicate multisig script parsing~~ ✅ FIXED
```

Add a "Fixed" summary after the existing fix description (before the `---`), following the pattern of items 1-9:
```markdown
**Fixed:** Extracted `parse_multisig_script()` and `MultisigInfo` dataclass into `remotesigner/script_utils.py`. `psbt_parser.py` and `signer.py` both delegate byte parsing to the shared function. 11 direct unit tests added in `test_script_utils.py`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/refactoring-todos.md
git commit -m "docs: mark refactoring-todo #10 as fixed"
```
