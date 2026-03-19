# Broadcaster Network Validation & Testnet Variant Support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix broadcaster's silent mainnet fallback and missing raw_hex validation, add broadcast support for testnet3/testnet4/signet, and give the Result screen three broadcast buttons for testnet variants.

**Architecture:** The network string `"test"` flows unchanged through parsing and signing (both only need coin_type 0 vs 1). The testnet variant distinction is introduced at the broadcast layer: broadcaster.py gains testnet4/signet endpoints and input validation, MempoolUrl.kt gains new path prefixes, and ResultScreen shows three buttons when `network == "test"`. The ViewModel passes the user's chosen variant through to broadcaster and persists it in the inbox DAO.

**Tech Stack:** Python (broadcaster.py, pytest), Kotlin (MempoolUrl.kt, InboxDao.kt, ResultScreen.kt, SignerViewModel.kt, AppNavigation.kt), Jetpack Compose, Room

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/python/remotesigner/broadcaster.py` | Modify | Add endpoints, network validation, raw_hex validation |
| `tests/test_broadcaster.py` | Modify | Add tests for new networks, validation errors |
| `app/src/main/kotlin/com/remotesigner/ui/MempoolUrl.kt` | Modify | Add networkPrefix() with testnet4/signet/throw |
| `app/src/test/kotlin/com/remotesigner/ui/MempoolUrlTest.kt` | Modify | Add tests for new networks, unknown network throw |
| `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt` | Modify | Add network param to updateBroadcast |
| `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt` | Modify | Change onBroadcast signature, three-button UI |
| `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` | Modify | broadcast(targetNetwork) signature |
| `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` | Modify | Pass network to broadcast() |
| `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` | Modify | Add three-button render tests |
| `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt` | Modify | onBroadcast lambda type inference |
| `app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt` | Modify | Update updateBroadcast call with network param |
| `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt` | Modify | Add resultCompleteTestnet fixture |

---

### Task 1: Broadcaster — network validation and new endpoints

**Files:**
- Modify: `tests/test_broadcaster.py`
- Modify: `app/src/main/python/remotesigner/broadcaster.py`

- [ ] **Step 1: Write failing tests for network validation**

Add to `tests/test_broadcaster.py` inside `class TestBroadcaster`:

```python
def test_rejects_unknown_network(self):
    with pytest.raises(ValueError, match="Unknown network"):
        broadcast_transaction("deadbeef", network="typo")

def test_rejects_unknown_network_testt(self):
    with pytest.raises(ValueError, match="Unknown network"):
        broadcast_transaction("deadbeef", network="testt")
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_broadcaster.py::TestBroadcaster::test_rejects_unknown_network tests/test_broadcaster.py::TestBroadcaster::test_rejects_unknown_network_testt -v`

Expected: FAIL — currently no ValueError is raised for unknown networks.

- [ ] **Step 3: Write failing tests for new network endpoints**

Add to `tests/test_broadcaster.py` inside `class TestBroadcaster`:

```python
@patch("remotesigner.broadcaster.requests.post")
def test_uses_testnet4_endpoint(self, mock_post):
    mock_post.return_value = MagicMock(status_code=200, text="txid_t4")
    result = broadcast_transaction("deadbeef", network="testnet4")
    assert result["status"] == "ok"
    assert "testnet4" in mock_post.call_args[0][0]

@patch("remotesigner.broadcaster.requests.post")
def test_uses_signet_endpoint(self, mock_post):
    mock_post.return_value = MagicMock(status_code=200, text="txid_sig")
    result = broadcast_transaction("deadbeef", network="signet")
    assert result["status"] == "ok"
    assert "signet" in mock_post.call_args[0][0]

@patch("remotesigner.broadcaster.requests.post")
def test_uses_testnet3_endpoint(self, mock_post):
    mock_post.return_value = MagicMock(status_code=200, text="txid_t3")
    result = broadcast_transaction("deadbeef", network="testnet3")
    assert result["status"] == "ok"
    assert "testnet" in mock_post.call_args[0][0]
    assert "testnet4" not in mock_post.call_args[0][0]

@patch("remotesigner.broadcaster.requests.post")
def test_test_backward_compat_uses_testnet3_endpoints(self, mock_post):
    mock_post.return_value = MagicMock(status_code=200, text="txid_bc")
    result = broadcast_transaction("deadbeef", network="test")
    assert result["status"] == "ok"
    assert "testnet" in mock_post.call_args[0][0]
```

- [ ] **Step 4: Run new endpoint tests to verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_broadcaster.py -v`

Expected: testnet4, signet, testnet3 tests FAIL (keys not in ENDPOINTS).

- [ ] **Step 5: Implement network validation and new endpoints**

In `app/src/main/python/remotesigner/broadcaster.py`, replace the ENDPOINTS dict:

```python
ENDPOINTS = {
    "main": [
        "https://mempool.space/api/tx",
        "https://blockstream.info/api/tx",
    ],
    "test": [  # backward compat alias for testnet3
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
    "testnet3": [
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
    "testnet4": [
        "https://mempool.space/testnet4/api/tx",
    ],
    "signet": [
        "https://mempool.space/signet/api/tx",
    ],
}
```

At the top of `broadcast_transaction`, add network validation and replace the old `endpoints =` line:

```python
if network not in ENDPOINTS:
    raise ValueError(
        f"Unknown network: {network!r}. "
        f"Valid: {', '.join(sorted(ENDPOINTS))}"
    )
endpoints = ENDPOINTS[network]
```

(This replaces the old `endpoints = ENDPOINTS.get(network, ENDPOINTS["main"])` line.)

- [ ] **Step 6: Run all broadcaster tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_broadcaster.py -v`

Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add tests/test_broadcaster.py app/src/main/python/remotesigner/broadcaster.py
git commit -m "fix: reject unknown broadcast networks, add testnet4/signet endpoints

Fixes refactoring-todos item #4. Raises ValueError for unrecognized
network values instead of silently falling back to mainnet. Adds
testnet3, testnet4, and signet broadcast endpoints."
```

---

### Task 2: Broadcaster — raw_hex input validation

**Files:**
- Modify: `tests/test_broadcaster.py`
- Modify: `app/src/main/python/remotesigner/broadcaster.py`

- [ ] **Step 1: Write failing tests for raw_hex validation**

Add to `tests/test_broadcaster.py` inside `class TestBroadcaster`:

```python
def test_rejects_empty_hex(self):
    with pytest.raises(ValueError, match="non-empty string"):
        broadcast_transaction("", network="main")

def test_rejects_none_hex(self):
    with pytest.raises(ValueError, match="non-empty string"):
        broadcast_transaction(None, network="main")

def test_rejects_odd_length_hex(self):
    with pytest.raises(ValueError, match="odd length"):
        broadcast_transaction("abc", network="main")

def test_rejects_non_hex_characters(self):
    with pytest.raises(ValueError, match="non-hex"):
        broadcast_transaction("xyz123", network="main")

def test_rejects_oversized_transaction(self):
    huge_hex = "ab" * 400_001  # 400,001 bytes
    with pytest.raises(ValueError, match="too large"):
        broadcast_transaction(huge_hex, network="main")
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_broadcaster.py::TestBroadcaster::test_rejects_empty_hex tests/test_broadcaster.py::TestBroadcaster::test_rejects_odd_length_hex tests/test_broadcaster.py::TestBroadcaster::test_rejects_non_hex_characters tests/test_broadcaster.py::TestBroadcaster::test_rejects_oversized_transaction -v`

Expected: FAIL — no validation exists.

- [ ] **Step 3: Implement raw_hex validation**

In `broadcast_transaction` in `app/src/main/python/remotesigner/broadcaster.py`, add after the network validation block and before `endpoints = ENDPOINTS[network]`:

```python
if not raw_hex or not isinstance(raw_hex, str):
    raise ValueError("raw_hex must be a non-empty string")
if len(raw_hex) % 2 != 0:
    raise ValueError("raw_hex has odd length — not valid hex")
try:
    bytes.fromhex(raw_hex)
except ValueError:
    raise ValueError("raw_hex contains non-hex characters")
MAX_TX_SIZE = 400_000  # 400KB, Bitcoin's max standard tx is ~400KB
if len(raw_hex) // 2 > MAX_TX_SIZE:
    raise ValueError(
        f"Transaction too large: {len(raw_hex) // 2} bytes (max {MAX_TX_SIZE})"
    )
```

- [ ] **Step 4: Run all broadcaster tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && python -m pytest tests/test_broadcaster.py -v`

Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add tests/test_broadcaster.py app/src/main/python/remotesigner/broadcaster.py
git commit -m "fix: validate raw_hex before broadcasting

Fixes refactoring-todos item #5. Validates hex encoding, even length,
and enforces 400KB max transaction size."
```

---

### Task 3: MempoolUrl — add testnet4/signet path prefixes

**Files:**
- Modify: `app/src/test/kotlin/com/remotesigner/ui/MempoolUrlTest.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/MempoolUrl.kt`

- [ ] **Step 1: Write failing tests for new networks and unknown throw**

Add to `MempoolUrlTest.kt`:

```kotlin
@Test
fun testnet4_tx_url() {
    assertEquals(
        "https://mempool.space/testnet4/tx/abc123def456",
        mempoolTxUrl("abc123def456", "testnet4"),
    )
}

@Test
fun signet_tx_url() {
    assertEquals(
        "https://mempool.space/signet/tx/abc123def456",
        mempoolTxUrl("abc123def456", "signet"),
    )
}

@Test
fun testnet3_tx_url() {
    assertEquals(
        "https://mempool.space/testnet/tx/abc123def456",
        mempoolTxUrl("abc123def456", "testnet3"),
    )
}

@Test
fun testnet3_address_url() {
    assertEquals(
        "https://mempool.space/testnet/address/tb1qtest",
        mempoolAddressUrl("tb1qtest", "testnet3"),
    )
}

@Test
fun testnet4_address_url() {
    assertEquals(
        "https://mempool.space/testnet4/address/tb1qtest",
        mempoolAddressUrl("tb1qtest", "testnet4"),
    )
}

@Test
fun signet_address_url() {
    assertEquals(
        "https://mempool.space/signet/address/tb1qtest",
        mempoolAddressUrl("tb1qtest", "signet"),
    )
}

@Test(expected = IllegalArgumentException::class)
fun unknown_network_throws() {
    mempoolTxUrl("abc123", "typo")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.ui.MempoolUrlTest" --info 2>&1 | tail -30`

Expected: New tests FAIL — current code returns empty prefix for unknown networks.

- [ ] **Step 3: Implement networkPrefix in MempoolUrl.kt**

Replace the entire content of `app/src/main/kotlin/com/remotesigner/ui/MempoolUrl.kt`:

```kotlin
package com.remotesigner.ui

private const val BASE = "https://mempool.space"

private fun networkPrefix(network: String): String = when (network) {
    "main" -> ""
    "test", "testnet3" -> "/testnet"
    "testnet4" -> "/testnet4"
    "signet" -> "/signet"
    else -> throw IllegalArgumentException("Unknown network: $network")
}

fun mempoolAddressUrl(address: String, network: String): String {
    return "$BASE${networkPrefix(network)}/address/$address"
}

fun mempoolTxUrl(txid: String, network: String): String {
    return "$BASE${networkPrefix(network)}/tx/$txid"
}
```

- [ ] **Step 4: Run all MempoolUrl tests to verify they pass**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --tests "com.remotesigner.ui.MempoolUrlTest" --info 2>&1 | tail -30`

Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/MempoolUrl.kt app/src/test/kotlin/com/remotesigner/ui/MempoolUrlTest.kt
git commit -m "feat: add testnet4/signet/testnet3 to MempoolUrl, throw on unknown

Replaces inline if with exhaustive networkPrefix() mapping. Unknown
networks throw IllegalArgumentException instead of silently producing
mainnet URLs."
```

---

### Task 4: Three-button broadcast UI, ViewModel wiring, and DAO update

This task modifies InboxDao, ResultScreen, ViewModel, and AppNavigation atomically — they form a single compilation unit where changing one breaks the others.

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt:38-39`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt:26,75-84`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:523-549`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt:98`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt:119-127`

- [ ] **Step 1: Add test fixture for testnet result**

Add to `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt` after `resultPartial`:

```kotlin
val resultCompleteTestnet = AppState.Result(
    isComplete = true,
    rawHex = "0200000001deadbeef",
    network = "test",
)
```

- [ ] **Step 2: Add render tests for three-button testnet UI**

Add to `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt`:

```kotlin
@Test
fun resultScreen_testnet_showsThreeBroadcastButtons() {
    composeTestRule.setContent {
        SatoshiSignerTheme {
            ResultScreen(
                state = TestFixtures.resultCompleteTestnet,
                onBroadcast = {},
                onExportPsbt = {},
                onSavePsbt = {},
                onHome = {},
            )
        }
    }
    composeTestRule.onNodeWithText("Broadcast to Testnet4").assertIsDisplayed()
    composeTestRule.onNodeWithText("Broadcast to Testnet3").assertIsDisplayed()
    composeTestRule.onNodeWithText("Broadcast to Signet").assertIsDisplayed()
    composeTestRule.onNodeWithText("Broadcast Transaction").assertDoesNotExist()
}

@Test
fun resultScreen_mainnet_showsSingleBroadcastButton() {
    val mainnetResult = AppState.Result(
        isComplete = true,
        rawHex = "0200000001deadbeef",
        network = "main",
    )
    composeTestRule.setContent {
        SatoshiSignerTheme {
            ResultScreen(
                state = mainnetResult,
                onBroadcast = {},
                onExportPsbt = {},
                onSavePsbt = {},
                onHome = {},
            )
        }
    }
    composeTestRule.onNodeWithText("Broadcast Transaction").assertIsDisplayed()
    composeTestRule.onNodeWithText("Broadcast to Testnet4").assertDoesNotExist()
}
```

- [ ] **Step 3: Update InboxDaoTest for new updateBroadcast signature**

In `app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt`, replace the `updateBroadcast_setsStatusAndTxid` test:

```kotlin
@Test
fun updateBroadcast_setsStatusAndTxid() = runTest {
    dao.upsert(makeItem(status = InboxStatus.SIGNED, rawHex = "deadbeef"))
    dao.updateBroadcast("event1", InboxStatus.BROADCAST, "abc123", "testnet4")
    val items = dao.getAll().first()
    assertEquals(InboxStatus.BROADCAST, items[0].status)
    assertEquals("abc123", items[0].txid)
    assertEquals("testnet4", items[0].network)
    assertEquals("deadbeef", items[0].rawHex) // rawHex preserved
}
```

- [ ] **Step 4: Implement all four production code changes**

**4a. InboxDao — add network param to updateBroadcast**

In `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt`, replace lines 38-39:

```kotlin
@Query("UPDATE inbox_items SET status = :status, txid = :txid, network = :network WHERE id = :id")
suspend fun updateBroadcast(id: String, status: InboxStatus, txid: String, network: String)
```

**4b. ResultScreen — change onBroadcast signature and add three-button UI**

In `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt`, change line 26:

```kotlin
onBroadcast: (String) -> Unit,
```

Replace the single broadcast Button block (lines 75-84) with:

```kotlin
val isTestnet = state.network == "test"
if (isTestnet) {
    Button(
        onClick = {
            broadcastClicked = true
            onBroadcast("testnet4")
        },
        enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Broadcast to Testnet4")
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            broadcastClicked = true
            onBroadcast("testnet3")
        },
        enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Broadcast to Testnet3")
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            broadcastClicked = true
            onBroadcast("signet")
        },
        enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Broadcast to Signet")
    }
} else {
    Button(
        onClick = {
            broadcastClicked = true
            onBroadcast(state.network)
        },
        enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Broadcast Transaction")
    }
}
```

**4c. ViewModel — broadcast(targetNetwork)**

In `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`, replace the `broadcast()` function (lines 523-550):

```kotlin
fun broadcast(targetNetwork: String) {
    val state = _state.value
    if (state !is AppState.Result || state.rawHex == null) return

    _state.value = state.copy(broadcastStatus = "Broadcasting...")

    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            pythonBridge.broadcast(state.rawHex, targetNetwork)
        }

        if (result["status"] == "ok") {
            val txid = result["txid"]?.toString()
            _state.value = state.copy(
                txid = txid,
                broadcastStatus = "Broadcast successful",
                network = targetNetwork,
            )
            val inboxId = currentSigningInboxId
            if (inboxId != null && txid != null) {
                inboxDao.updateBroadcast(inboxId, InboxStatus.BROADCAST, txid, targetNetwork)
            }
        } else {
            _state.value = state.copy(
                broadcastStatus = "Broadcast failed: ${result["message"]}",
            )
        }
    }
}
```

**4d. AppNavigation — pass network to broadcast()**

In `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`, change line 98:

```kotlin
onBroadcast = { network -> viewModel.broadcast(network) },
```

- [ ] **Step 5: Build and verify compilation**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew assembleDebug 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all JVM tests**

Run: `cd /Users/sasha/Projects/remote_signer && ./gradlew testDebugUnitTest --info 2>&1 | tail -20`

Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/data/InboxDao.kt \
       app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt \
       app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
       app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt \
       app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt \
       app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt \
       app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt
git commit -m "feat: three-button testnet broadcast with network variant persistence

When network is 'test', Result screen shows Testnet4 (primary),
Testnet3, and Signet broadcast buttons. ViewModel.broadcast() accepts
targetNetwork, passes it to pythonBridge.broadcast(), and persists
the variant via updateBroadcast() so explorer links resolve correctly."
```

---

### Task 5: Mark refactoring-todos items as fixed

**Files:**
- Modify: `docs/refactoring-todos.md`

- [ ] **Step 1: Mark items #4 and #5 as fixed**

In `docs/refactoring-todos.md`, update items #4 and #5 to match the format of items #1-3:

For item #4 (line 67), change the heading to:

```markdown
### ~~4. Broadcaster silently falls back to mainnet for unknown network values~~ ✅ FIXED
```

Strikethrough the description and fix paragraphs, then add:

```markdown
**Fixed:** `broadcast_transaction` now raises `ValueError` for any network not in `ENDPOINTS`. Added testnet3, testnet4, and signet endpoints. `"test"` kept as backward-compat alias for testnet3.
```

For item #5 (line 80), change the heading to:

```markdown
### ~~5. No input validation on broadcast raw_hex parameter~~ ✅ FIXED
```

Strikethrough the description and fix paragraphs, then add:

```markdown
**Fixed:** `broadcast_transaction` now validates: non-empty string, valid hex encoding, even length, and 400KB max transaction size. Raises `ValueError` on any mismatch.
```

- [ ] **Step 2: Commit**

```bash
git add docs/refactoring-todos.md
git commit -m "docs: mark refactoring-todos items #4 and #5 as fixed"
```
