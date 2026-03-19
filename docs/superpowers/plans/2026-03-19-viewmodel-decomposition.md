# SignerViewModel Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose the 680-line SignerViewModel into ContactRepository, InboxRepository, and SigningOrchestrator with constructor injection to enable unit testing.

**Architecture:** Bottom-up extraction — repositories first, then PythonBridgeInterface, then SigningOrchestrator, then wire everything up with a ViewModelFactory. Each task produces a buildable, testable intermediate state. During tasks 1-4, the ViewModel creates extracted classes internally. Task 5 switches to constructor injection.

**Tech Stack:** Kotlin, Room, Jetpack ViewModel, Coroutines/Flow, Chaquopy (Python bridge)

**Spec:** `docs/superpowers/specs/2026-03-19-viewmodel-decomposition-design.md`

---

### Task 1: Extract ContactRepository

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/data/ContactRepository.kt`
- Create: `app/src/androidTest/kotlin/com/remotesigner/data/ContactRepositoryTest.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Write the test file for ContactRepository**

```kotlin
// app/src/androidTest/kotlin/com/remotesigner/data/ContactRepositoryTest.kt
package com.remotesigner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.remotesigner.viewmodel.SignerInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContactRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ContactRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ContactRepository(db.contactDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun enrichSigners_maps_fingerprints_to_contact_labels() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val signers = listOf(
            SignerInfo(fingerprint = "a1b2c3d4", signed = false),
            SignerInfo(fingerprint = "ffffffff", signed = true),
        )

        val enriched = repo.enrichSigners(signers)

        assertEquals("Alice", enriched[0].contactLabel)
        assertNotNull(enriched[0].contactId)
        assertNull(enriched[1].contactLabel)
        assertNull(enriched[1].contactId)
    }

    @Test
    fun enrichSigners_returns_original_when_no_contacts() = runTest {
        val signers = listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        val enriched = repo.enrichSigners(signers)
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_creates_new_contact_and_fingerprint() = runTest {
        repo.saveContact("Bob", "e5f6a7b8", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "e5f6a7b8", signed = false))
        )
        assertEquals("Bob", enriched[0].contactLabel)
    }

    @Test
    fun saveContact_adds_fingerprint_to_existing_contact() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        repo.saveContact("ignored", "e5f6a7b8", existingContactId = contactId)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "e5f6a7b8", signed = false))
        )
        assertEquals("Alice", enriched[0].contactLabel)
    }

    @Test
    fun saveContact_rejects_invalid_fingerprint() = runTest {
        repo.saveContact("Alice", "not-hex", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "not-hex", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_rejects_empty_label() = runTest {
        repo.saveContact("", "a1b2c3d4", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun saveContact_rejects_label_over_50_chars() = runTest {
        repo.saveContact("A".repeat(51), "a1b2c3d4", existingContactId = null)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun deleteContact_removes_contact_and_fingerprints() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        repo.deleteContact(contactId)

        val enriched = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )
        assertNull(enriched[0].contactLabel)
    }

    @Test
    fun deleteFingerprint_keeps_contact() = runTest {
        repo.saveContact("Alice", "a1b2c3d4", existingContactId = null)
        val contactId = repo.enrichSigners(
            listOf(SignerInfo(fingerprint = "a1b2c3d4", signed = false))
        )[0].contactId!!

        repo.addFingerprint(contactId, "e5f6a7b8")

        // Get fingerprint ID via enrichment
        val dao = db.contactDao()
        val cwfs = dao.findByFingerprints(listOf("a1b2c3d4"))
        val fpId = cwfs[0].fingerprints.first { it.fingerprint == "a1b2c3d4" }.id

        repo.deleteFingerprint(fpId)

        // a1b2c3d4 gone, e5f6a7b8 still maps to Alice
        val enriched = repo.enrichSigners(listOf(
            SignerInfo(fingerprint = "a1b2c3d4", signed = false),
            SignerInfo(fingerprint = "e5f6a7b8", signed = false),
        ))
        assertNull(enriched[0].contactLabel)
        assertEquals("Alice", enriched[1].contactLabel)
    }
}
```

- [ ] **Step 2: Create ContactRepository (make tests pass)**

```kotlin
// app/src/main/kotlin/com/remotesigner/data/ContactRepository.kt
package com.remotesigner.data

import com.remotesigner.viewmodel.SignerInfo
import kotlinx.coroutines.flow.Flow

class ContactRepository(private val contactDao: ContactDao) {

    val allWithFingerprints: Flow<List<ContactWithFingerprints>> =
        contactDao.getAllWithFingerprints()

    suspend fun enrichSigners(signers: List<SignerInfo>): List<SignerInfo> {
        val fingerprints = signers.map { it.fingerprint }
        val contactMap = contactDao.findByFingerprints(fingerprints)
            .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
            .toMap()
        return signers.map { signer ->
            val contact = contactMap[signer.fingerprint]
            signer.copy(
                contactLabel = contact?.contact?.label,
                contactId = contact?.contact?.id,
            )
        }
    }

    suspend fun saveContact(label: String, fingerprint: String, existingContactId: Long?) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        if (existingContactId != null) {
            contactDao.insertFingerprint(
                ContactFingerprint(contactId = existingContactId, fingerprint = normalized)
            )
        } else {
            val trimmed = label.trim()
            if (trimmed.isEmpty() || trimmed.length > 50) return
            val id = contactDao.insertContact(Contact(label = trimmed))
            contactDao.insertFingerprint(
                ContactFingerprint(contactId = id, fingerprint = normalized)
            )
        }
    }

    suspend fun updateContact(contactId: Long, newLabel: String, npub: String?) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty() || trimmed.length > 50) return
        contactDao.updateContact(
            Contact(id = contactId, label = trimmed, npub = npub?.trim()?.ifEmpty { null })
        )
    }

    suspend fun addFingerprint(contactId: Long, fingerprint: String) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        contactDao.insertFingerprint(
            ContactFingerprint(contactId = contactId, fingerprint = normalized)
        )
    }

    suspend fun deleteContact(contactId: Long) = contactDao.deleteContact(contactId)
    suspend fun deleteFingerprint(fingerprintId: Long) = contactDao.deleteFingerprint(fingerprintId)
}
```

- [ ] **Step 3: Run ContactRepository tests**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.data.ContactRepositoryTest`
Expected: All tests PASS

- [ ] **Step 4: Update ViewModel to delegate to ContactRepository**

In `SignerViewModel.kt`, make these changes:

1. Create `ContactRepository` internally (temporary — will be injected in Task 5):
```kotlin
// Replace:
private val contactDao = AppDatabase.getInstance(application).contactDao()
val contacts = contactDao.getAllWithFingerprints()

// With:
private val contactDao = AppDatabase.getInstance(application).contactDao()
private val contactRepository = ContactRepository(contactDao)
val contacts = contactRepository.allWithFingerprints
```

2. Replace `saveContact` body:
```kotlin
fun saveContact(label: String, fingerprint: String, existingContactId: Long?) {
    viewModelScope.launch(Dispatchers.IO) {
        contactRepository.saveContact(label, fingerprint, existingContactId)
        reEnrichSigners()
    }
}
```

3. Replace `updateContact` body:
```kotlin
fun updateContact(contactId: Long, newLabel: String, npub: String?) {
    viewModelScope.launch(Dispatchers.IO) {
        contactRepository.updateContact(contactId, newLabel, npub)
    }
}
```

4. Replace `addFingerprintToContact` body:
```kotlin
fun addFingerprintToContact(contactId: Long, fingerprint: String) {
    viewModelScope.launch(Dispatchers.IO) {
        contactRepository.addFingerprint(contactId, fingerprint)
        reEnrichSigners()
    }
}
```

5. Replace `deleteContact` body:
```kotlin
fun deleteContact(contactId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        contactRepository.deleteContact(contactId)
        reEnrichSigners()
    }
}
```

6. Replace `deleteFingerprint` body:
```kotlin
fun deleteFingerprint(fingerprintId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        contactRepository.deleteFingerprint(fingerprintId)
        reEnrichSigners()
    }
}
```

7. Simplify `reEnrichSigners()`:
```kotlin
private suspend fun reEnrichSigners() {
    val currentState = _state.value
    if (currentState is AppState.TransactionReview) {
        val enriched = contactRepository.enrichSigners(currentState.signers)
        _state.value = currentState.copy(signers = enriched)
    }
}
```

8. In `parsePsbt()`, replace the fingerprint enrichment block (lines 296-306):
```kotlin
// Replace the contactMap + signers mapping block with:
val signers = contactRepository.enrichSigners(rawSigners)
```

9. Remove the `import com.remotesigner.data.FingerprintValidator` from ViewModel (now used only in ContactRepository). Add `import com.remotesigner.data.ContactRepository`.

- [ ] **Step 5: Run all existing tests to verify no regressions**

Run: `./gradlew testDebugUnitTest` (JVM tests)
Expected: All PASS

Run: `./gradlew assembleDebug` (build check)
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/data/ContactRepository.kt \
       app/src/androidTest/kotlin/com/remotesigner/data/ContactRepositoryTest.kt \
       app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "refactor: extract ContactRepository from SignerViewModel

Consolidates contact CRUD and signer enrichment logic into
ContactRepository. ViewModel delegates to repository. No external
API changes — constructor injection comes in a later step."
```

---

### Task 2: Extract PythonBridgeInterface and top-level SigningCallback

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`

- [ ] **Step 1: Create PythonBridgeInterface.kt with SigningCallback**

```kotlin
// app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt
package com.remotesigner.bridge

import com.remotesigner.usb.SigningBridge

/** Callback interface for signing operations. Called from Python's thread via Chaquopy. */
interface SigningCallback {
    fun onStatus(status: String)
    /** Blocks the calling (Python) thread until the user responds. */
    fun requestPassphrase(availableOnDevice: Boolean): String
    /** Blocks until the user provides an account derivation path. */
    fun requestAccountPath(): String
}

interface PythonBridgeInterface {
    fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?>
    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: SigningBridge,
        callback: SigningCallback?,
        network: String = "main",
    ): Map<String, Any?>
    fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?>
}
```

- [ ] **Step 2: Update PythonBridge to implement PythonBridgeInterface**

In `PythonBridge.kt`:

1. Add `: PythonBridgeInterface` to class declaration:
```kotlin
class PythonBridge : PythonBridgeInterface {
```

2. Remove the nested `SigningCallback` interface (lines 48-54 of original):
```kotlin
// DELETE this block:
//     interface SigningCallback {
//         fun onStatus(status: String)
//         fun requestPassphrase(availableOnDevice: Boolean): String
//         fun requestAccountPath(): String
//     }
```

3. Add `override` keyword to all three interface methods (`parsePsbt`, `signPsbt`, `broadcast`) since they now implement `PythonBridgeInterface`:
```kotlin
override fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?> { ... }
override fun signPsbt(..., callback: SigningCallback?, ...): Map<String, Any?> { ... }
override fun broadcast(rawHex: String, network: String): Map<String, Any?> { ... }
```
The `signPsbt` callback parameter type is already `SigningCallback?` (short name) — it now resolves to the top-level interface instead of the deleted nested one.

4. Update `SigningCallbackImpl` supertype (same file, line 96):
```kotlin
// Change:
) : PythonBridge.SigningCallback {
// To:
) : SigningCallback {
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

No test changes needed — `SigningCallback` is a type-only change with the same method signatures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt \
       app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt
git commit -m "refactor: extract PythonBridgeInterface and top-level SigningCallback

Moves SigningCallback from nested interface in PythonBridge to
top-level interface. Creates PythonBridgeInterface that PythonBridge
implements. Enables InboxRepository and SigningOrchestrator to
depend on the interface instead of the concrete Chaquopy class."
```

---

### Task 3: Extract InboxRepository

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt`
- Create: `app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Write the test file for InboxRepository**

```kotlin
// app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt
package com.remotesigner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.remotesigner.bridge.PythonBridgeInterface
import com.remotesigner.bridge.SigningCallback
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatBtcAmount
import com.remotesigner.usb.SigningBridge
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InboxRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    /** Fake that returns a fixed parse result. */
    private class FakePythonBridge(
        private val parseResult: Map<String, Any?> = mapOf(
            "outputs" to listOf(mapOf("amount" to 50000L, "is_change" to false)),
            "network" to "test",
        ),
        private val shouldThrow: Boolean = false,
    ) : PythonBridgeInterface {
        override fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?> {
            if (shouldThrow) throw RuntimeException("parse failed")
            return parseResult
        }
        override fun signPsbt(
            psbtBytes: ByteArray, bridge: SigningBridge,
            callback: SigningCallback?, network: String,
        ) = emptyMap<String, Any?>()
        override fun broadcast(rawHex: String, network: String) = emptyMap<String, Any?>()
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = InboxRepository(db.inboxDao(), FakePythonBridge())
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun makeItem(id: String, receivedAt: Long = System.currentTimeMillis() / 1000) =
        InboxItemEntity(
            id = id,
            psbtBytes = byteArrayOf(1, 2, 3),
            label = "Test",
            senderNpub = "npub1abc...xyz",
            receivedAt = receivedAt,
        )

    @Test
    fun cleanupAndSeedIds_deletes_expired_and_returns_remaining() = runTest {
        val now = System.currentTimeMillis() / 1000
        val dao = db.inboxDao()

        // Recent pending — should survive
        dao.insertIgnore(makeItem("recent", receivedAt = now - 100))
        // Old pending — should be deleted
        dao.insertIgnore(makeItem("old-pending", receivedAt = now - 90_000))
        // Old signed — should be deleted (> 7 days)
        val oldSigned = makeItem("old-signed", receivedAt = now - 700_000)
        dao.insertIgnore(oldSigned)
        dao.updateStatus("old-signed", InboxStatus.SIGNED)

        val ids = repo.cleanupAndSeedIds()

        assertEquals(setOf("recent"), ids)
    }

    @Test
    fun handleInboxEvent_inserts_and_enriches_parsed_fields() = runTest {
        val item = makeItem("event-1")
        repo.handleInboxEvent(item)

        val dao = db.inboxDao()
        val all = dao.getAllOnce()
        assertEquals(1, all.size)
        assertEquals("event-1", all[0].id)
        assertEquals(formatBtcAmount(50000L), all[0].amount)
        assertEquals("test", all[0].network)
    }

    @Test
    fun handleInboxEvent_skips_duplicate() = runTest {
        val item = makeItem("event-1")
        repo.handleInboxEvent(item)
        repo.handleInboxEvent(item) // duplicate

        val all = db.inboxDao().getAllOnce()
        assertEquals(1, all.size)
    }

    @Test
    fun handleInboxEvent_keeps_original_row_when_parse_fails() = runTest {
        val failingRepo = InboxRepository(db.inboxDao(), FakePythonBridge(shouldThrow = true))
        val item = makeItem("event-1")
        failingRepo.handleInboxEvent(item)

        val all = db.inboxDao().getAllOnce()
        assertEquals(1, all.size)
        assertEquals("", all[0].amount) // Original default, not enriched
        assertEquals("main", all[0].network) // Original default
    }

    @Test
    fun updateStatus_delegates_to_dao() = runTest {
        val dao = db.inboxDao()
        dao.insertIgnore(makeItem("event-1"))

        repo.updateStatus("event-1", InboxStatus.SIGNED)

        val item = dao.getAllOnce().first()
        assertEquals(InboxStatus.SIGNED, item.status)
    }
}
```

- [ ] **Step 2: Create InboxRepository (make tests pass)**

```kotlin
// app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt
package com.remotesigner.data

import com.remotesigner.bridge.PythonBridgeInterface
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatBtcAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class InboxRepository(
    private val inboxDao: InboxDao,
    private val pythonBridge: PythonBridgeInterface,
) {
    val items: Flow<List<InboxItemEntity>> = inboxDao.getAll()

    suspend fun cleanupAndSeedIds(): Set<String> {
        val now = System.currentTimeMillis() / 1000
        inboxDao.deleteExpired(
            pendingCutoff = now - 86_400,
            signedCutoff = now - 86_400 * 7,
        )
        return inboxDao.getAllOnce().map { it.id }.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun handleInboxEvent(item: InboxItemEntity) {
        val inserted = inboxDao.insertIgnore(item)
        if (inserted == -1L) return

        try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(item.psbtBytes)
            }
            val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
            val totalSent = outputs
                .filter { it["is_change"] as? Boolean != true }
                .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
            val network = result["network"]?.toString() ?: "main"
            inboxDao.updateParsedFields(
                id = item.id,
                amount = formatBtcAmount(totalSent),
                network = network,
            )
        } catch (_: Exception) {
            // Keep original row if parse fails
        }
    }

    suspend fun updateStatus(id: String, status: InboxStatus) =
        inboxDao.updateStatus(id, status)

    suspend fun updateSigned(id: String, status: InboxStatus, rawHex: String, network: String) =
        inboxDao.updateSigned(id, status, rawHex, network)

    suspend fun updateBroadcast(id: String, status: InboxStatus, txid: String, network: String) =
        inboxDao.updateBroadcast(id, status, txid, network)
}
```

- [ ] **Step 3: Run InboxRepository tests**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.data.InboxRepositoryTest`
Expected: All tests PASS

- [ ] **Step 4: Update ViewModel to delegate to InboxRepository**

In `SignerViewModel.kt`:

1. Create `InboxRepository` internally and replace direct DAO usage:
```kotlin
// Replace:
private val inboxDao = AppDatabase.getInstance(application).inboxDao()
val inboxItems: StateFlow<List<InboxItemEntity>> = inboxDao.getAll()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

// With:
private val inboxDao = AppDatabase.getInstance(application).inboxDao()
private val inboxRepository = InboxRepository(inboxDao, pythonBridge)
val inboxItems: StateFlow<List<InboxItemEntity>> = inboxRepository.items
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

2. Replace `init` block:
```kotlin
init {
    inboxSeedJob = viewModelScope.launch {
        val ids = inboxRepository.cleanupAndSeedIds()
        if (ids.isNotEmpty()) nostrReceiver.seedSeenIds(ids)
    }
}
```

3. Replace `handleInboxEvent`:
```kotlin
// Replace the entire private fun handleInboxEvent with:
// (NostrReceiver onItem lambda changes too)

private val nostrReceiver = NostrReceiver(
    keyManager = keyManager,
    onItem = { item ->
        viewModelScope.launch(Dispatchers.IO) {
            inboxRepository.handleInboxEvent(item)
        }
    },
    scope = viewModelScope,
)
```

Remove the old `handleInboxEvent` method entirely (lines 191-215).

4. Replace `signInboxItem` inbox status update:
```kotlin
fun signInboxItem(item: InboxItemEntity) {
    currentSigningInboxId = item.id
    currentDescription = item.label.ifBlank { null }
    viewModelScope.launch { inboxRepository.updateStatus(item.id, InboxStatus.SIGNING) }
    loadPsbt(item.psbtBytes)
}
```

5. Replace `deleteInboxItem`:
```kotlin
fun deleteInboxItem(id: String) {
    viewModelScope.launch { inboxRepository.updateStatus(id, InboxStatus.DELETED) }
}
```

6. In `doSignWithBridge`, replace all `inboxDao.*` calls with `inboxRepository.*`:
   - `inboxDao.updateSigned(...)` → `inboxRepository.updateSigned(...)`
   - `inboxDao.updateStatus(...)` → `inboxRepository.updateStatus(...)`

7. In `broadcast()`, replace:
   - `inboxDao.updateBroadcast(...)` → `inboxRepository.updateBroadcast(...)`

8. In `goHome()`, replace:
   - `inboxDao.updateStatus(...)` → `inboxRepository.updateStatus(...)`

9. In `cancelSigning()`, replace:
   - `inboxDao.updateStatus(...)` → `inboxRepository.updateStatus(...)`

10. Add `import com.remotesigner.data.InboxRepository`.

- [ ] **Step 5: Run build and existing tests**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt \
       app/src/androidTest/kotlin/com/remotesigner/data/InboxRepositoryTest.kt \
       app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "refactor: extract InboxRepository from SignerViewModel

Moves inbox event handling, cleanup/seed, and status update logic
into InboxRepository. ViewModel delegates to repository. NostrReceiver
onItem lambda now routes through InboxRepository.handleInboxEvent."
```

---

### Task 4: Extract SigningOrchestrator

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/bridge/SigningOrchestrator.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Create SigningOrchestrator**

```kotlin
// app/src/main/kotlin/com/remotesigner/bridge/SigningOrchestrator.kt
package com.remotesigner.bridge

import com.remotesigner.usb.SigningBridge
import com.remotesigner.usb.TrezorUsbManager
import com.remotesigner.viewmodel.AccountPathRequest
import com.remotesigner.viewmodel.PassphraseRequest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed class SigningResult {
    data class Complete(val rawHex: String?, val network: String) : SigningResult()
    data class Partial(val updatedPsbtBytes: ByteArray?, val network: String) : SigningResult()
    data object Cancelled : SigningResult()
    data class Error(val message: String, val log: String) : SigningResult()
}

class SigningOrchestrator(
    private val pythonBridge: PythonBridgeInterface,
    private val trezorUsb: TrezorUsbManager,
) {
    private var currentUsbBridge: SigningBridge? = null
    private var currentSigningCallback: SigningCallbackImpl? = null

    private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
    val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest.asStateFlow()
    private val _accountPathRequest = MutableStateFlow<AccountPathRequest?>(null)
    val accountPathRequest: StateFlow<AccountPathRequest?> = _accountPathRequest.asStateFlow()

    suspend fun signWithTrezor(
        psbtBytes: ByteArray,
        network: String,
        onProgress: (String) -> Unit,
    ): SigningResult {
        val device = trezorUsb.findTrezorDevice()

        if (device == null) {
            onProgress("Connect Trezor via USB-C cable")
            // Poll for device
            while (true) {
                delay(1000)
                val found = trezorUsb.findTrezorDevice() ?: continue

                if (!trezorUsb.hasPermission(found)) {
                    val granted = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                        trezorUsb.requestPermission(found) { cont.resume(it) }
                    }
                    if (!granted) return SigningResult.Error("USB permission denied", "")
                }

                val bridge = trezorUsb.openDevice(found)
                    ?: return SigningResult.Error("Failed to open USB device", "")
                onProgress(bridge.dumpDeviceInfo())
                return doSign(bridge, psbtBytes, network, onProgress)
            }
        }

        if (!trezorUsb.hasPermission(device)) {
            val granted = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                trezorUsb.requestPermission(device) { cont.resume(it) }
            }
            if (!granted) return SigningResult.Error("USB permission denied", "")
        }

        val bridge = trezorUsb.openDevice(device)
            ?: return SigningResult.Error("Failed to open USB device", "")
        onProgress(bridge.dumpDeviceInfo())
        return doSign(bridge, psbtBytes, network, onProgress)
    }

    suspend fun signWithBridge(
        bridge: SigningBridge,
        psbtBytes: ByteArray,
        network: String,
        onProgress: (String) -> Unit,
        withPassphraseUI: Boolean = true,
    ): SigningResult {
        return doSign(bridge, psbtBytes, network, onProgress, withPassphraseUI, manageBridge = false)
    }

    private suspend fun doSign(
        bridge: SigningBridge,
        psbtBytes: ByteArray,
        network: String,
        onProgress: (String) -> Unit,
        withPassphraseUI: Boolean = true,
        manageBridge: Boolean = true,
    ): SigningResult {
        var log = ""
        fun log(msg: String) {
            log += msg + "\n"
            onProgress(msg)
        }

        try {
            if (manageBridge) {
                currentUsbBridge = bridge
                log("Opening USB connection...")
                log("Claiming interface & finding endpoints...")
                withContext(Dispatchers.IO) { bridge.open() }
                log("USB bridge opened OK")
            }

            log("Starting Python signing (network=$network)...")

            val signingCallback: SigningCallbackImpl? = if (withPassphraseUI) {
                SigningCallbackImpl(
                    onStatusUpdate = { status -> log("Python: $status") },
                    onPassphraseRequest = { availableOnDevice ->
                        _passphraseRequest.value = PassphraseRequest(
                            availableOnDevice, currentSigningCallback!!
                        )
                    },
                    onPassphraseSubmitted = { _passphraseRequest.value = null },
                    onAccountPathRequest = {
                        _accountPathRequest.value = AccountPathRequest(
                            currentSigningCallback!!
                        )
                    },
                    onAccountPathSubmitted = { _accountPathRequest.value = null },
                ).also { currentSigningCallback = it }
            } else {
                null
            }

            val result = withContext(Dispatchers.IO) {
                pythonBridge.signPsbt(
                    psbtBytes = psbtBytes,
                    bridge = bridge,
                    callback = signingCallback,
                    network = network,
                )
            }

            return when (result["status"]) {
                "complete" -> SigningResult.Complete(
                    rawHex = result["raw_tx"]?.toString(),
                    network = network,
                )
                "partial" -> {
                    val psbtB64 = result["psbt"]?.toString()
                    SigningResult.Partial(
                        updatedPsbtBytes = psbtB64?.let {
                            android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                        },
                        network = network,
                    )
                }
                "cancelled" -> SigningResult.Cancelled
                else -> SigningResult.Error(
                    message = result["message"]?.toString() ?: "Signing failed",
                    log = log,
                )
            }
        } catch (e: Exception) {
            return SigningResult.Error(
                message = "Signing error: ${e.message}",
                log = log,
            )
        } finally {
            _passphraseRequest.value = null
            _accountPathRequest.value = null
            currentSigningCallback = null
            bridge.close()
            currentUsbBridge = null
        }
    }

    fun cancel() {
        currentSigningCallback?.cancel()
        currentSigningCallback = null
        _passphraseRequest.value = null
        _accountPathRequest.value = null
        currentUsbBridge?.close()
        currentUsbBridge = null
    }
}

```

Note: `dumpDeviceInfo()` is a method on `UsbBridge` (not `SigningBridge`). It's called in `signWithTrezor` on the `UsbBridge` returned by `trezorUsb.openDevice()` *before* passing to `doSign()`. This avoids a type check inside `doSign` and keeps the `SigningBridge` interface clean. The `signWithBridge` path (used for tests with `PlaybackBridge`) skips device info entirely.

- [ ] **Step 2: Update ViewModel to delegate signing to SigningOrchestrator**

In `SignerViewModel.kt`:

1. Create orchestrator internally (temporary):
```kotlin
private val signingOrchestrator = SigningOrchestrator(pythonBridge, trezorUsb)
```

2. Expose passphrase/account-path requests as pass-throughs:
```kotlin
// Replace:
private val _passphraseRequest = MutableStateFlow<PassphraseRequest?>(null)
val passphraseRequest: StateFlow<PassphraseRequest?> = _passphraseRequest.asStateFlow()
private val _accountPathRequest = MutableStateFlow<AccountPathRequest?>(null)
val accountPathRequest: StateFlow<AccountPathRequest?> = _accountPathRequest.asStateFlow()

// With:
val passphraseRequest: StateFlow<PassphraseRequest?> = signingOrchestrator.passphraseRequest
val accountPathRequest: StateFlow<AccountPathRequest?> = signingOrchestrator.accountPathRequest
```

3. Replace `signWithTrezor()`:
```kotlin
fun signWithTrezor() {
    val psbt = currentPsbtBytes ?: return

    _state.value = AppState.Signing("Connecting to Trezor...", log = "")

    signingJob = viewModelScope.launch {
        val result = signingOrchestrator.signWithTrezor(
            psbtBytes = psbt,
            network = currentNetwork,
            onProgress = { msg ->
                val current = (_state.value as? AppState.Signing)?.log ?: ""
                _state.value = AppState.Signing(msg, log = current + msg + "\n")
            },
        )
        handleSigningResult(result)
    }
}
```

4. Replace `signWithBridge()`:
```kotlin
@VisibleForTesting
internal fun signWithBridge(bridge: SigningBridge, psbtBytes: ByteArray, network: String) {
    _state.value = AppState.Signing("Signing...", log = "")
    signingJob = viewModelScope.launch {
        val result = signingOrchestrator.signWithBridge(
            bridge = bridge,
            psbtBytes = psbtBytes,
            network = network,
            onProgress = { msg ->
                val current = (_state.value as? AppState.Signing)?.log ?: ""
                _state.value = AppState.Signing(msg, log = current + msg + "\n")
            },
            withPassphraseUI = false,
        )
        handleSigningResult(result)
    }
}
```

5. Add `handleSigningResult` private method:
```kotlin
private suspend fun handleSigningResult(result: SigningResult) {
    // Clean up NFC state (was in doSignWithBridge.finally, now orchestrator doesn't own it)
    _nfcWaitingForTag.value = false
    _nfcTagResult.value = null

    val inboxId = currentSigningInboxId
    when (result) {
        is SigningResult.Complete -> {
            if (inboxId != null) {
                val rawTx = result.rawHex
                if (rawTx != null) {
                    inboxRepository.updateSigned(inboxId, InboxStatus.SIGNED, rawTx, result.network)
                } else {
                    inboxRepository.updateStatus(inboxId, InboxStatus.SIGNED)
                }
            }
            _state.value = AppState.Result(
                isComplete = true,
                rawHex = result.rawHex,
                network = result.network,
            )
        }
        is SigningResult.Partial -> {
            _state.value = AppState.Result(
                isComplete = false,
                updatedPsbt = result.updatedPsbtBytes,
                network = result.network,
            )
        }
        is SigningResult.Cancelled -> {
            if (inboxId != null) {
                inboxRepository.updateStatus(inboxId, InboxStatus.PENDING)
            }
            if (currentPsbtBytes != null) {
                parsePsbt(currentPsbtBytes!!)
            } else {
                _state.value = AppState.Home
            }
        }
        is SigningResult.Error -> {
            if (inboxId != null) {
                inboxRepository.updateStatus(inboxId, InboxStatus.FAILED)
            }
            _state.value = AppState.Error(
                "${result.message}\n\n--- Log ---\n${result.log}"
            )
        }
    }
}
```

6. Simplify `cancelSigning()`:
```kotlin
fun cancelSigning() {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        viewModelScope.launch { inboxRepository.updateStatus(inboxId, InboxStatus.PENDING) }
    }
    signingOrchestrator.cancel()
    signingJob?.cancel()
    signingJob = null
    _nfcWaitingForTag.value = false
    _nfcTagResult.value = null
    if (currentPsbtBytes != null) {
        viewModelScope.launch { parsePsbt(currentPsbtBytes!!) }
    } else {
        _state.value = AppState.Home
    }
}
```

7. Remove: `currentUsbBridge`, `currentSigningCallback`, the old `doSignWithBridge` method, and the old `signWithTrezor` implementation. Add `import com.remotesigner.bridge.SigningOrchestrator` and `import com.remotesigner.bridge.SigningResult`.

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run existing JVM tests**

Run: `./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/SigningOrchestrator.kt \
       app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "refactor: extract SigningOrchestrator from SignerViewModel

Moves USB bridge lifecycle, signing flow, passphrase/account-path
callback wiring into SigningOrchestrator. Returns SigningResult
sealed class — ViewModel maps results to AppState transitions."
```

---

### Task 5: Add ViewModelFactory and Constructor Injection

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt`

- [ ] **Step 1: Create SignerViewModelFactory**

```kotlin
// app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt
package com.remotesigner.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.remotesigner.bridge.PythonBridge
import com.remotesigner.bridge.SigningOrchestrator
import com.remotesigner.data.AppDatabase
import com.remotesigner.data.ContactRepository
import com.remotesigner.data.InboxRepository
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.usb.TrezorUsbManager

class SignerViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(application)
        val pythonBridge = PythonBridge()
        val contactRepo = ContactRepository(db.contactDao())
        val inboxRepo = InboxRepository(db.inboxDao(), pythonBridge)
        val trezorUsb = TrezorUsbManager(application)
        val orchestrator = SigningOrchestrator(pythonBridge, trezorUsb)
        return SignerViewModel(
            application = application,
            pythonBridge = pythonBridge,
            contactRepository = contactRepo,
            inboxRepository = inboxRepo,
            signingOrchestrator = orchestrator,
            trezorUsb = trezorUsb,
            keyManager = NostrKeyManager(application),
        ) as T
    }
}
```

- [ ] **Step 2: Change ViewModel constructor to accept injected dependencies**

Replace the ViewModel class declaration and internal dependency creation:

```kotlin
class SignerViewModel(
    application: Application,
    private val pythonBridge: PythonBridgeInterface,
    private val contactRepository: ContactRepository,
    private val inboxRepository: InboxRepository,
    private val signingOrchestrator: SigningOrchestrator,
    val trezorUsb: TrezorUsbManager,
    val keyManager: NostrKeyManager,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppState>(AppState.Home)
    val state: StateFlow<AppState> = _state.asStateFlow()

    val contacts = contactRepository.allWithFingerprints
    // ... rest unchanged
```

Remove the old internal creation lines:
```kotlin
// DELETE these:
// private val pythonBridge = PythonBridge()
// private val contactDao = AppDatabase.getInstance(application).contactDao()
// private val contactRepository = ContactRepository(contactDao)
// private val inboxDao = AppDatabase.getInstance(application).inboxDao()
// private val inboxRepository = InboxRepository(inboxDao, pythonBridge)
// val trezorUsb = TrezorUsbManager(application)
// val keyManager = NostrKeyManager(application)
// private val signingOrchestrator = SigningOrchestrator(pythonBridge, trezorUsb)
```

Add import: `import com.remotesigner.bridge.PythonBridgeInterface`

- [ ] **Step 3: Update MainActivity to use factory**

In `MainActivity.kt`:
```kotlin
// Replace:
private val viewModel: SignerViewModel by viewModels()

// With:
private val viewModel: SignerViewModel by viewModels {
    SignerViewModelFactory(application)
}
```

Add import: `import com.remotesigner.viewmodel.SignerViewModelFactory`

- [ ] **Step 4: Update ChaquopyE2ETest to use factory**

In `ChaquopyE2ETest.kt`:
```kotlin
// Replace:
import com.remotesigner.viewmodel.SignerViewModel

// With:
import com.remotesigner.viewmodel.SignerViewModel
import com.remotesigner.viewmodel.SignerViewModelFactory

// Replace setUp():
@Before
fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    viewModel = SignerViewModelFactory(app).create(SignerViewModel::class.java)
}
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run all JVM tests**

Run: `./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt \
       app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt \
       app/src/main/kotlin/com/remotesigner/MainActivity.kt \
       app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt
git commit -m "refactor: add SignerViewModelFactory with constructor injection

ViewModel now accepts all dependencies via constructor, created by
SignerViewModelFactory. MainActivity and ChaquopyE2ETest updated.
This enables unit testing with fakes (refactoring-todo #12)."
```

---

### Task 6: Final Verification

**Files:** None (verification only)

- [ ] **Step 1: Run full JVM test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All PASS

- [ ] **Step 2: Run full Android build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Update refactoring-todos.md**

Mark item #6 as fixed in `docs/refactoring-todos.md`:
```markdown
### ~~6. Extract responsibilities from SignerViewModel (God Object)~~ ✅ FIXED
```

Add a note:
```markdown
**Fixed:** Extracted ContactRepository, InboxRepository, and SigningOrchestrator. ViewModel accepts dependencies via constructor injection through SignerViewModelFactory. PythonBridgeInterface enables testing with fakes.
```

- [ ] **Step 4: Commit**

```bash
git add docs/refactoring-todos.md
git commit -m "docs: mark refactoring-todo #6 as fixed"
```
