# Inbox Room Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JSON file inbox persistence (`InboxStore`) with Room database, using reactive Flow and targeted DAO updates.

**Architecture:** Add `InboxItemEntity` to the existing `AppDatabase` (version 1→2 migration). DAO provides reactive `Flow<List<InboxItemEntity>>` collected by the ViewModel via `stateIn`. All write operations use targeted SQL updates (not full-row upsert) to avoid race conditions. Debounce/flush logic is removed entirely.

**Tech Stack:** Room 2.6.1, KSP, Kotlin Coroutines Flow, JUnit4 instrumented tests

**Spec:** `docs/superpowers/specs/2026-03-17-inbox-room-migration-design.md`

---

### Task 1: Entity, DAO, type converter, and migration

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt:7-25` (rename `InboxItem` → `InboxItemEntity`, add Room annotations)
- Create: `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/data/AppDatabase.kt` (version 2, add entity, DAO, converter, migration)

- [ ] **Step 1: Convert `InboxItem` to `InboxItemEntity` with Room annotations**

In `app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt`, rename the data class and add Room annotations. Keep the `equals`/`hashCode` override. Add `@Entity`, `@PrimaryKey`, `@ColumnInfo` for the BLOB field.

```kotlin
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inbox_items")
data class InboxItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: InboxStatus = InboxStatus.PENDING,
    val rawHex: String? = null,
    val txid: String? = null,
    val network: String = "main",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxItemEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
```

- [ ] **Step 2: Create `InboxDao.kt`**

Create `app/src/main/kotlin/com/remotesigner/data/InboxDao.kt`:

```kotlin
package com.remotesigner.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_items ORDER BY receivedAt DESC")
    fun getAll(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_items")
    suspend fun getAllOnce(): List<InboxItemEntity>

    @Query("SELECT COUNT(*) FROM inbox_items WHERE id = :id")
    suspend fun exists(id: String): Int

    @Upsert
    suspend fun upsert(item: InboxItemEntity)

    @Query("UPDATE inbox_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: InboxStatus)

    @Query("UPDATE inbox_items SET status = :status, rawHex = :rawHex, network = :network WHERE id = :id")
    suspend fun updateSigned(id: String, status: InboxStatus, rawHex: String, network: String)

    @Query("UPDATE inbox_items SET status = :status, txid = :txid WHERE id = :id")
    suspend fun updateBroadcast(id: String, status: InboxStatus, txid: String)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        DELETE FROM inbox_items WHERE
        (status IN ('PENDING', 'SIGNING', 'FAILED') AND receivedAt < :pendingCutoff)
        OR (status IN ('SIGNED', 'BROADCAST') AND receivedAt < :signedCutoff)
    """)
    suspend fun deleteExpired(pendingCutoff: Long, signedCutoff: Long)
}
```

- [ ] **Step 3: Update `AppDatabase.kt`**

Add `InboxItemEntity` to entities, bump version to 2, add type converter, migration, and DAO accessor:

```kotlin
package com.remotesigner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus

class InboxStatusConverter {
    @TypeConverter
    fun fromStatus(status: InboxStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): InboxStatus = try {
        InboxStatus.valueOf(value)
    } catch (_: IllegalArgumentException) {
        InboxStatus.PENDING
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inbox_items (
                id TEXT NOT NULL PRIMARY KEY,
                psbtBytes BLOB NOT NULL,
                label TEXT NOT NULL,
                amount TEXT NOT NULL,
                senderNpub TEXT NOT NULL,
                receivedAt INTEGER NOT NULL,
                status TEXT NOT NULL,
                rawHex TEXT,
                txid TEXT,
                network TEXT NOT NULL
            )
        """.trimIndent())
    }
}

@Database(
    entities = [Contact::class, ContactFingerprint::class, InboxItemEntity::class],
    version = 2,
)
@TypeConverters(InboxStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun inboxDao(): InboxDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "satoshi-signer.db",
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 4: Fix all compilation errors from the rename**

The rename `InboxItem` → `InboxItemEntity` will break imports in many files. Update all references:

- `app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt:30` — change `onItem: (InboxItem)` → `onItem: (InboxItemEntity)` and the `InboxItem(...)` constructor call at line 137
- `app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt:13` — change import and all parameter/variable types
- `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt:30` — change import and all parameter types
- `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` — update if it references `InboxItem` type
- `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:16` — change import (handled fully in Task 2)
- `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt` — change all `InboxItem(` → `InboxItemEntity(`
- `app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt:5` — change import and type annotations (`InboxItem?` → `InboxItemEntity?`)
- `app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt:83,110,183` — change `CopyOnWriteArrayList<InboxItem>` → `CopyOnWriteArrayList<InboxItemEntity>` (star import `com.remotesigner.nostr.*` handles the class name, but explicit type annotations need updating)

For each file, replace `InboxItem` with `InboxItemEntity` in:
- Imports: `com.remotesigner.nostr.InboxItem` → `com.remotesigner.nostr.InboxItemEntity`
- Type annotations: `List<InboxItem>` → `List<InboxItemEntity>`
- Constructor calls: `InboxItem(` → `InboxItemEntity(`
- Lambda parameter types: `(InboxItem) -> Unit` → `(InboxItemEntity) -> Unit`

- [ ] **Step 5: Verify compilation**

Note: The ViewModel still references `InboxStore` and old import names — compilation will fail until Task 2 completes. Skip the compile check here; it will be verified at the end of Task 2.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/nostr/NostrInbox.kt \
       app/src/main/kotlin/com/remotesigner/data/InboxDao.kt \
       app/src/main/kotlin/com/remotesigner/data/AppDatabase.kt \
       app/src/main/kotlin/com/remotesigner/nostr/NostrReceiver.kt \
       app/src/main/kotlin/com/remotesigner/ui/InboxSection.kt \
       app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt \
       app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt \
       app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt \
       app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt \
       app/src/androidTest/kotlin/com/remotesigner/NostrReceiverTest.kt
git commit -m "feat: add InboxItemEntity, InboxDao, and DB migration 1→2"
```

---

### Task 2: Refactor ViewModel to use Room DAO

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:141-229,448-501,515-544,546-566,643-661`

This task replaces all `InboxStore` / `MutableStateFlow` / debounce / flush logic with Room DAO calls.

- [ ] **Step 1: Replace inbox field declarations (lines 141-146)**

Remove:
```kotlin
private val _inboxItems = MutableStateFlow<List<InboxItem>>(emptyList())
val inboxItems: StateFlow<List<InboxItem>> = _inboxItems.asStateFlow()
private var currentSigningInboxId: String? = null
private val inboxStore = InboxStore(File(application.filesDir, "inbox.json"))
```

Replace with:
```kotlin
private val inboxDao = AppDatabase.getInstance(application).inboxDao()
val inboxItems: StateFlow<List<InboxItemEntity>> = inboxDao.getAll()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
private var currentSigningInboxId: String? = null
```

Note: `contactDao` is already declared on line 103. The `inboxDao` declaration goes in the inbox section (around line 143).

- [ ] **Step 2: Replace init block (lines 157-170)**

Remove the entire init block and replace with:
```kotlin
init {
    viewModelScope.launch {
        val now = System.currentTimeMillis() / 1000
        inboxDao.deleteExpired(
            pendingCutoff = now - 86_400,
            signedCutoff = now - 86_400 * 7,
        )
        val persisted = inboxDao.getAllOnce()
        if (persisted.isNotEmpty()) {
            nostrReceiver.seedSeenIds(persisted.map { it.id }.toSet())
        }
    }
}
```

- [ ] **Step 3: Remove `onCleared()` override (lines 172-175)**

Delete the entire `onCleared()` method — no more flush needed.

- [ ] **Step 4: Replace `handleInboxEvent` (lines 177-200)**

Replace with DAO-based version using `exists()` check:
```kotlin
private fun handleInboxEvent(item: InboxItemEntity) {
    viewModelScope.launch {
        if (inboxDao.exists(item.id) > 0) return@launch

        val enrichedItem = try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(item.psbtBytes)
            }
            @Suppress("UNCHECKED_CAST")
            val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
            val totalSent = outputs
                .filter { it["is_change"] as? Boolean != true }
                .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
            val network = result["network"]?.toString() ?: "main"
            item.copy(amount = formatBtcAmount(totalSent), network = network)
        } catch (_: Exception) {
            item
        }
        inboxDao.upsert(enrichedItem)
    }
}
```

- [ ] **Step 5: Replace `signInboxItem` (lines 202-206)**

```kotlin
fun signInboxItem(item: InboxItemEntity) {
    currentSigningInboxId = item.id
    viewModelScope.launch { inboxDao.updateStatus(item.id, InboxStatus.SIGNING) }
    loadPsbt(item.psbtBytes)
}
```

- [ ] **Step 6: Replace `deleteInboxItem` (lines 208-210)**

```kotlin
fun deleteInboxItem(id: String) {
    viewModelScope.launch { inboxDao.delete(id) }
}
```

- [ ] **Step 7: Remove `updateInboxItem` helper (lines 212-216)**

Delete entirely — replaced by targeted DAO calls.

- [ ] **Step 8: Update `openInboxResult` (lines 218-229)**

Change parameter type only — the method body reads item fields which work the same way:
```kotlin
fun openInboxResult(item: InboxItemEntity) {
```

- [ ] **Step 9: Update signing result handlers in `doSignWithBridge` (lines 448-501)**

Replace `updateInboxItem` calls with targeted DAO calls:

**"complete" branch (lines 450-458):**
```kotlin
"complete" -> {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        val rawTx = result["raw_tx"]?.toString()
        if (rawTx != null) {
            inboxDao.updateSigned(inboxId, InboxStatus.SIGNED, rawTx, network)
        } else {
            inboxDao.updateStatus(inboxId, InboxStatus.SIGNED)
        }
    }
    _state.value = AppState.Result(
        isComplete = true,
        rawHex = result["raw_tx"]?.toString(),
        network = network,
    )
}
```

**"cancelled" branch (lines 476-486):**
```kotlin
"cancelled" -> {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        inboxDao.updateStatus(inboxId, InboxStatus.PENDING)
    }
    if (currentPsbtBytes != null) {
        parsePsbt(currentPsbtBytes!!)
    } else {
        _state.value = AppState.Home
    }
}
```

**"else" branch (lines 487-495):**
```kotlin
else -> {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        inboxDao.updateStatus(inboxId, InboxStatus.FAILED)
    }
    _state.value = AppState.Error(
        result["message"]?.toString() ?: "Signing failed"
    )
}
```

**catch block (lines 497-503):**
```kotlin
} catch (e: Exception) {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        inboxDao.updateStatus(inboxId, InboxStatus.FAILED)
    }
    val signingLog = (_state.value as? AppState.Signing)?.log ?: ""
    _state.value = AppState.Error("Signing error: ${e.message}\n\n--- Log ---\n$signingLog")
}
```

- [ ] **Step 10: Update `broadcast()` (lines 515-544)**

Replace `updateInboxItem` call with targeted DAO call:
```kotlin
val inboxId = currentSigningInboxId
if (inboxId != null && txid != null) {
    inboxDao.updateBroadcast(inboxId, InboxStatus.BROADCAST, txid)
}
```

- [ ] **Step 11: Update `cancelSigning()` (lines 546-566)**

Replace `updateInboxItem` call:
```kotlin
val inboxId = currentSigningInboxId
if (inboxId != null) {
    viewModelScope.launch { inboxDao.updateStatus(inboxId, InboxStatus.PENDING) }
}
```

- [ ] **Step 12: Update `goHome()` (lines 643-660)**

Replace `updateInboxItem` calls with targeted DAO calls. The BROADCAST guard reads from the Flow snapshot (safe here since it's a safety net, not the primary update path):
```kotlin
fun goHome() {
    val inboxId = currentSigningInboxId
    if (inboxId != null) {
        val currentState = _state.value
        viewModelScope.launch {
            when (currentState) {
                is AppState.Result -> {
                    val item = inboxItems.value.find { it.id == inboxId }
                    if (item != null && item.status != InboxStatus.BROADCAST) {
                        inboxDao.updateStatus(inboxId, InboxStatus.SIGNED)
                    }
                }
                is AppState.Error -> inboxDao.updateStatus(inboxId, InboxStatus.FAILED)
                else -> {}
            }
        }
        currentSigningInboxId = null
    }
    currentPsbtBytes = null
    _state.value = AppState.Home
}
```

- [ ] **Step 13: Clean up imports**

Remove unused imports from `SignerViewModel.kt`:
- `com.remotesigner.nostr.InboxStore`
- `com.remotesigner.nostr.removeExpiredItems`
- `kotlinx.coroutines.FlowPreview`
- `kotlinx.coroutines.flow.debounce`
- `kotlinx.coroutines.flow.launchIn`
- `kotlinx.coroutines.flow.onEach`
- `kotlinx.coroutines.flow.update`
- `java.io.File`

Add new imports:
- `kotlinx.coroutines.flow.SharingStarted`
- `kotlinx.coroutines.flow.stateIn`

(Keep `com.remotesigner.nostr.InboxItemEntity`, `com.remotesigner.nostr.InboxStatus`, `com.remotesigner.nostr.formatBtcAmount`)

- [ ] **Step 14: Verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 15: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: replace InboxStore with Room DAO in ViewModel"
```

---

### Task 3: Delete old files and clean up build config

**Files:**
- Delete: `app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt`
- Delete: `app/src/test/kotlin/com/remotesigner/nostr/InboxStoreTest.kt`
- Delete: `app/src/test/kotlin/com/remotesigner/nostr/InboxExpiryTest.kt`
- Modify: `app/build.gradle.kts:78`

- [ ] **Step 1: Delete `InboxStore.kt`**

```bash
rm app/src/main/kotlin/com/remotesigner/nostr/InboxStore.kt
```

- [ ] **Step 2: Delete JVM tests that tested InboxStore and expiry**

```bash
rm app/src/test/kotlin/com/remotesigner/nostr/InboxStoreTest.kt
rm app/src/test/kotlin/com/remotesigner/nostr/InboxExpiryTest.kt
```

- [ ] **Step 3: Remove `org.json` test dependency from `build.gradle.kts`**

Remove line 78: `testImplementation("org.json:json:20231013")`

(No other JVM test uses `org.json` — verified by grep.)

- [ ] **Step 4: Verify compilation and JVM tests still pass**

Run: `./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -u  # stages deletions and build.gradle.kts change
git commit -m "chore: remove InboxStore, expiry JVM tests, and org.json dep"
```

---

### Task 4: Write InboxDao instrumented tests

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt`

- [ ] **Step 1: Write the test class**

Create `app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt`:

```kotlin
package com.remotesigner

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.remotesigner.data.AppDatabase
import com.remotesigner.data.InboxDao
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InboxDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: InboxDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.inboxDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeItem(
        id: String = "event1",
        status: InboxStatus = InboxStatus.PENDING,
        receivedAt: Long = 1710700000L,
        rawHex: String? = null,
        txid: String? = null,
        network: String = "main",
    ) = InboxItemEntity(
        id = id,
        psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
        label = "Payment",
        amount = "0.01000000 BTC",
        senderNpub = "npub1test",
        receivedAt = receivedAt,
        status = status,
        rawHex = rawHex,
        txid = txid,
        network = network,
    )

    @Test
    fun upsert_andGetAll_returnsItem() = runTest {
        val item = makeItem()
        dao.upsert(item)
        val items = dao.getAll().first()
        assertEquals(1, items.size)
        assertEquals("event1", items[0].id)
        assertEquals(InboxStatus.PENDING, items[0].status)
        assertArrayEquals(item.psbtBytes, items[0].psbtBytes)
    }

    @Test
    fun upsert_existingItem_updatesAllFields() = runTest {
        dao.upsert(makeItem())
        dao.upsert(makeItem(status = InboxStatus.SIGNED, rawHex = "deadbeef"))
        val items = dao.getAll().first()
        assertEquals(1, items.size)
        assertEquals(InboxStatus.SIGNED, items[0].status)
        assertEquals("deadbeef", items[0].rawHex)
    }

    @Test
    fun updateStatus_changesOnlyStatus() = runTest {
        dao.upsert(makeItem())
        dao.updateStatus("event1", InboxStatus.SIGNING)
        val items = dao.getAll().first()
        assertEquals(InboxStatus.SIGNING, items[0].status)
        assertEquals("Payment", items[0].label) // other fields unchanged
    }

    @Test
    fun updateSigned_setsStatusRawHexNetwork() = runTest {
        dao.upsert(makeItem())
        dao.updateSigned("event1", InboxStatus.SIGNED, "cafebabe", "test")
        val items = dao.getAll().first()
        assertEquals(InboxStatus.SIGNED, items[0].status)
        assertEquals("cafebabe", items[0].rawHex)
        assertEquals("test", items[0].network)
    }

    @Test
    fun updateBroadcast_setsStatusAndTxid() = runTest {
        dao.upsert(makeItem(status = InboxStatus.SIGNED, rawHex = "deadbeef"))
        dao.updateBroadcast("event1", InboxStatus.BROADCAST, "abc123")
        val items = dao.getAll().first()
        assertEquals(InboxStatus.BROADCAST, items[0].status)
        assertEquals("abc123", items[0].txid)
        assertEquals("deadbeef", items[0].rawHex) // rawHex preserved
    }

    @Test
    fun delete_removesItem() = runTest {
        dao.upsert(makeItem())
        dao.delete("event1")
        val items = dao.getAll().first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun exists_returnsCorrectCount() = runTest {
        assertEquals(0, dao.exists("event1"))
        dao.upsert(makeItem())
        assertEquals(1, dao.exists("event1"))
        assertEquals(0, dao.exists("nonexistent"))
    }

    @Test
    fun deleteExpired_removesOnlyExpired() = runTest {
        val now = 1710700000L
        // Pending, 2 hours old — kept (< 24h)
        dao.upsert(makeItem(id = "a1", receivedAt = now - 7200))
        // Pending, 25 hours old — removed (> 24h)
        dao.upsert(makeItem(id = "a2", receivedAt = now - 90000))
        // Signed, 3 days old — kept (< 7d)
        dao.upsert(makeItem(id = "a3", status = InboxStatus.SIGNED, receivedAt = now - 86400 * 3))
        // Broadcast, 8 days old — removed (> 7d)
        dao.upsert(makeItem(id = "a4", status = InboxStatus.BROADCAST, receivedAt = now - 86400 * 8))
        // Failed, 25 hours old — removed (> 24h)
        dao.upsert(makeItem(id = "a5", status = InboxStatus.FAILED, receivedAt = now - 90000))

        dao.deleteExpired(
            pendingCutoff = now - 86400,
            signedCutoff = now - 86400 * 7,
        )

        val items = dao.getAll().first()
        assertEquals(2, items.size)
        val ids = items.map { it.id }.toSet()
        assertTrue(ids.contains("a1"))
        assertTrue(ids.contains("a3"))
    }

    @Test
    fun getAll_orderedByReceivedAtDesc() = runTest {
        dao.upsert(makeItem(id = "old", receivedAt = 100L))
        dao.upsert(makeItem(id = "new", receivedAt = 300L))
        dao.upsert(makeItem(id = "mid", receivedAt = 200L))
        val items = dao.getAll().first()
        assertEquals(listOf("new", "mid", "old"), items.map { it.id })
    }

    @Test
    fun getAllOnce_returnsCurrentSnapshot() = runTest {
        dao.upsert(makeItem(id = "a"))
        dao.upsert(makeItem(id = "b"))
        val items = dao.getAllOnce()
        assertEquals(2, items.size)
    }
}
```

- [ ] **Step 2: Run instrumented tests on emulator**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.remotesigner.InboxDaoTest 2>&1 | tail -30`
Expected: All 10 tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/InboxDaoTest.kt
git commit -m "test: add InboxDao instrumented tests"
```

---

### Task 5: Update existing tests for `InboxItemEntity` rename

**Files:**
- Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt:95-139`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/InboxScreenTest.kt:5`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/NavigationTest.kt` (no changes needed — uses `inboxItems = emptyList()`, no type annotation)
- Modify: `app/src/androidTest/kotlin/com/remotesigner/ScreenRenderTest.kt` (no InboxItem references — no changes needed)

Note: If the rename was done globally in Task 1 Step 4, these files are already updated. This task is a verification step.

- [ ] **Step 1: Verify TestFixtures compile**

Check that all `InboxItem(` → `InboxItemEntity(` renames were applied in `TestFixtures.kt`. The fixtures at lines 95-139 should now use `InboxItemEntity(...)`.

- [ ] **Step 2: Verify InboxScreenTest compiles**

Check that import changed from `com.remotesigner.nostr.InboxItem` to `com.remotesigner.nostr.InboxItemEntity` and the type reference `var signedItem: InboxItem?` → `var signedItem: InboxItemEntity?` at line 81 and `var tappedItem: InboxItem?` → `var tappedItem: InboxItemEntity?` at line 147.

- [ ] **Step 3: Run all instrumented tests**

Run: `./gradlew connectedDebugAndroidTest 2>&1 | tail -30`
Expected: All tests PASS (InboxDaoTest + InboxScreenTest + NavigationTest + ScreenRenderTest + others)

- [ ] **Step 4: Commit (if any changes needed)**

```bash
git add -u
git commit -m "test: fix remaining InboxItem → InboxItemEntity references in tests"
```

---

### Task 6: Update docs and deploy

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update CLAUDE.md**

Update the "Inbox persisted to JSON file" design decision to reflect Room:

Change:
> **Inbox persisted to JSON file** — `_inboxItems: MutableStateFlow<List<InboxItem>>` in ViewModel, separate from the navigation `_state`. `InboxStore` saves to `inbox.json` in app-internal storage (debounced 500ms, flushed on `onCleared()`). On startup, persisted items are loaded, expired items removed (24h for pending/failed/signing, 7d for signed/broadcast), and their IDs seeded into `NostrReceiver.seenIds` so relays don't overwrite richer local state. Deduplication by Nostr event ID. Signed/broadcast items store `rawHex`, `txid`, and `network` so the Result screen can be reopened from an inbox card.

To:
> **Inbox persisted via Room** — `InboxItemEntity` is a Room `@Entity` in `AppDatabase` (version 2). `InboxDao` provides reactive `Flow<List<InboxItemEntity>>` collected via `stateIn` in the ViewModel. Write operations use targeted SQL updates (`updateStatus`, `updateSigned`, `updateBroadcast`) to avoid race conditions. On startup, `deleteExpired()` removes old items (24h for pending/failed/signing, 7d for signed/broadcast), then `getAllOnce()` seeds `NostrReceiver.seenIds` so relays don't overwrite richer local state. Deduplication by Nostr event ID via `exists()` query. Signed/broadcast items store `rawHex`, `txid`, and `network` so the Result screen can be reopened from an inbox card.

Also update "Minimal state app" if it still mentions `inbox.json`:
> Persisted state: Room database (`satoshi-signer.db`) for cosigner contacts and Nostr inbox items...

Update source layout to remove InboxStore reference:
> `app/src/main/kotlin/com/remotesigner/nostr/` — Nostr transport (keypair, NIP-04 crypto, WebSocket receiver, inbox model)

(Remove "InboxStore persistence" mention.)

- [ ] **Step 2: Commit docs**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for inbox Room migration"
```

- [ ] **Step 3: Deploy to phone**

Run: `./gradlew installDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, installed on connected device
