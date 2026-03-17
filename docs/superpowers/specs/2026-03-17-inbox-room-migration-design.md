# Inbox Room Migration Design

**Date:** 2026-03-17
**Status:** Approved

## Overview

Migrate inbox persistence from JSON file (`InboxStore`) to Room database. The app already uses Room for cosigner contacts. This eliminates the manual debounce/flush pattern in favor of Room's reactive Flow and direct DAO writes.

## Entity: InboxItemEntity

New Room entity mapping 1:1 to the current `InboxItem` domain fields.

```kotlin
@Entity(tableName = "inbox_items")
data class InboxItemEntity(
    @PrimaryKey val id: String,          // Nostr event ID
    val psbtBytes: ByteArray,            // BLOB
    val label: String,
    val amount: String,
    val senderNpub: String,
    val receivedAt: Long,                // Unix seconds
    val status: InboxStatus,             // TypeConverter → String
    val rawHex: String?,
    val txid: String?,
    val network: String,
)
```

`InboxStatus` enum stored as its `.name` String via a `@TypeConverter` pair on `AppDatabase`.

No separate domain model — `InboxItemEntity` replaces `InboxItem` everywhere. The `equals`/`hashCode` override (identity by `id` only) carries over.

## DAO: InboxDao

```kotlin
@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_items ORDER BY receivedAt DESC")
    fun getAll(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_items")
    suspend fun getAllOnce(): List<InboxItemEntity>

    @Upsert
    suspend fun upsert(item: InboxItemEntity)

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

- `getAll()` returns a reactive Flow sorted by `receivedAt DESC` — Room re-emits on any table change.
- `getAllOnce()` is a one-shot suspend function used at init time to seed `NostrReceiver.seenIds`.
- `upsert()` uses Room's `@Upsert` annotation (insert or update by primary key).
- `deleteExpired()` takes pre-computed cutoff timestamps so the DAO stays pure.

## Database Changes

`AppDatabase` version 1 → 2:

- Add `InboxItemEntity` to `@Database(entities = [...])`.
- Add `inboxDao()` abstract method.
- Add `InboxStatusConverter` class with `@TypeConverter` methods (`fromStatus`/`toStatus`).
- Register converter via `@TypeConverters(InboxStatusConverter::class)` on the database class.
- Add `Migration(1, 2)` with CREATE TABLE SQL:

```sql
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
```

## ViewModel Changes

### Removed
- `InboxStore` instance and all references
- `_inboxItems: MutableStateFlow<List<InboxItem>>` (manual)
- Debounced save via `_inboxItems.debounce(500).onEach { inboxStore.save(it) }.launchIn(...)`
- `onCleared()` flush call to `inboxStore.save(...)`
- `removeExpiredItems()` pure function (replaced by SQL-based expiry)

### Added/Changed

**Inbox Flow** — collected from Room:
```kotlin
private val inboxDao = AppDatabase.getInstance(application).inboxDao()

val inboxItems: StateFlow<List<InboxItemEntity>> = inboxDao.getAll()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

**Init block:**
```kotlin
init {
    viewModelScope.launch {
        val now = System.currentTimeMillis() / 1000
        inboxDao.deleteExpired(
            pendingCutoff = now - 86_400,    // 24h
            signedCutoff = now - 86_400 * 7, // 7d
        )
        val persisted = inboxDao.getAllOnce()
        if (persisted.isNotEmpty()) {
            nostrReceiver.seedSeenIds(persisted.map { it.id }.toSet())
        }
    }
    // ... existing nostrReceiver setup
}
```

**Write operations** — each calls DAO directly:
- `handleInboxEvent`: `inboxDao.upsert(newEntity)` instead of `_inboxItems.update { ... }`
- `updateInboxItem(id, transform)`: read from `inboxItems.value.find { it.id == id }`, apply transform, `inboxDao.upsert(result)`
- `deleteInboxItem(id)`: `inboxDao.delete(id)`
- Signing status updates: same `updateInboxItem` pattern with DAO upsert
- Broadcast status update: same pattern

All DAO calls happen inside `viewModelScope.launch(Dispatchers.IO) { ... }` or `viewModelScope.launch { ... }` (Room already dispatches to a background thread for suspend functions, but explicit IO dispatcher for clarity is optional).

## Files Deleted

| File | Reason |
|------|--------|
| `InboxStore.kt` | Replaced by Room DAO |
| `InboxStoreTest.kt` (JVM) | Replaced by instrumented `InboxDaoTest` |
| `InboxExpiryTest.kt` (JVM) | Replaced by instrumented `InboxDaoTest` expiry tests |

## Files Modified

| File | Changes |
|------|---------|
| `AppDatabase.kt` | Version 2, add entity, DAO, migration, type converter |
| `NostrInbox.kt` | `InboxItem` → `InboxItemEntity` with Room annotations |
| `SignerViewModel.kt` | Replace InboxStore with DAO, remove debounce/flush |
| `HomeScreen.kt` | Update type from `InboxItem` to `InboxItemEntity` |
| `InboxSection.kt` | Update type from `InboxItem` to `InboxItemEntity` |
| `AppNavigation.kt` | Update type if needed |
| `TestFixtures.kt` | Update fixtures to use `InboxItemEntity` |
| `InboxScreenTest.kt` | Update to use `InboxItemEntity` |
| `build.gradle.kts` | Remove `testImplementation("org.json:json:20231013")` if no longer needed |

## New Files

| File | Purpose |
|------|---------|
| `InboxDaoTest.kt` (androidTest) | Instrumented tests: round-trip, upsert, delete, expiry, Flow emission |

## Test Plan

**InboxDaoTest** (instrumented, in-memory Room DB):
1. `upsert_andGetAll_returnsItem` — insert one item, verify Flow emits it
2. `upsert_existingItem_updates` — insert, then upsert with changed status, verify update
3. `delete_removesItem` — insert, delete, verify empty
4. `deleteExpired_removesOnlyExpired` — insert mix of statuses and ages, run deleteExpired, verify only expired removed
5. `getAll_orderedByReceivedAtDesc` — insert items with different timestamps, verify order
6. `getAllOnce_returnsCurrentSnapshot` — insert items, verify one-shot read

**Existing tests updated:**
- `InboxScreenTest.kt` — update fixture types
- `NavigationTest.kt` / `ScreenRenderTest.kt` — update if they reference InboxItem

## Migration Safety

- `Migration(1, 2)` only adds a new table — zero risk to existing contacts data
- First launch after upgrade: inbox starts empty (JSON file data not migrated, acceptable since items are transient)
- JSON `inbox.json` file left on disk (not deleted) — harmless orphan
