# Cosigner Contacts — Labels, Fingerprints & Room Database

## Problem

Multisig PSBTs show cosigner fingerprints as raw 4-byte hex strings (e.g., `3442193e`) on the TransactionReview screen. There's no way to know which real-world person or device each fingerprint belongs to. Users must remember or look up fingerprints externally.

Additionally, the app has no persistent structured storage. The planned inbox persistence spec uses a JSON file, but a relational database is a better foundation for both contacts and future inbox persistence.

## Design

### 1. Room Database

Introduce Room as the app's persistent storage layer. A single `AppDatabase` hosts all tables. This spec adds contact tables and a stubbed inbox table (schema defined, not wired up — inbox migration is a follow-up).

**Dependencies** (added to `gradle/libs.versions.toml` and `app/build.gradle.kts`):
- `androidx.room:room-runtime`
- `androidx.room:room-ktx` (coroutine/Flow support)
- `androidx.room:room-compiler` (KSP annotation processor)

### 2. Data Model

Two entities with a one-to-many relationship:

```kotlin
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val npub: String? = null,
)

@Entity(
    tableName = "contact_fingerprints",
    foreignKeys = [ForeignKey(
        entity = Contact::class,
        parentColumns = ["id"],
        childColumns = ["contactId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("contactId"), Index(value = ["fingerprint"], unique = true)],
)
data class ContactFingerprint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val fingerprint: String,
)
```

Convenience class for queries needing the full picture:

```kotlin
data class ContactWithFingerprints(
    @Embedded val contact: Contact,
    @Relation(parentColumn = "id", entityColumn = "contactId")
    val fingerprints: List<ContactFingerprint>,
)
```

**Key constraints:**
- Fingerprint is unique across all contacts (one fingerprint cannot belong to two contacts)
- `CASCADE` delete — removing a contact removes its fingerprints
- `npub` is nullable — not required until PSBT forwarding is implemented in a future spec

### 3. Stubbed Inbox Table

Defined in the database schema but not wired to any DAO or ViewModel logic. Inbox continues to use in-memory `StateFlow` as today. A follow-up spec wires this table and migrates inbox persistence from the planned JSON approach to Room.

```kotlin
@Entity(tableName = "inbox_items")
data class InboxItemEntity(
    @PrimaryKey val id: String,
    val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: String = "PENDING",
    val rawHex: String? = null,
    val txid: String? = null,
    val network: String = "main",
)
```

### 4. Database Definition

```kotlin
@Database(
    entities = [Contact::class, ContactFingerprint::class, InboxItemEntity::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    // abstract fun inboxDao(): InboxDao  ← uncommented when inbox is wired up
}
```

Database is a singleton, created via `Room.databaseBuilder()` in the Application class or a companion factory.

### 5. ContactDao

```kotlin
@Dao
interface ContactDao {
    @Transaction
    @Query("SELECT * FROM contacts ORDER BY label ASC")
    fun getAllWithFingerprints(): Flow<List<ContactWithFingerprints>>

    @Query("""
        SELECT c.* FROM contacts c
        INNER JOIN contact_fingerprints cf ON c.id = cf.contactId
        WHERE cf.fingerprint = :fingerprint
    """)
    suspend fun findByFingerprint(fingerprint: String): Contact?

    @Insert
    suspend fun insertContact(contact: Contact): Long

    @Insert
    suspend fun insertFingerprint(fingerprint: ContactFingerprint)

    @Update
    suspend fun updateContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)

    @Query("DELETE FROM contact_fingerprints WHERE id = :id")
    suspend fun deleteFingerprint(id: Long)
}
```

- `getAllWithFingerprints()` returns `Flow` for reactive UI on the Contacts screen
- `findByFingerprint()` is the hot path — resolves fingerprint → label for the TransactionReview signer list

### 6. TransactionReview Integration (Quick-Add)

Extend `SignerInfo` with contact resolution fields:

```kotlin
data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
    val contactLabel: String? = null,
    val contactId: Long? = null,
)
```

**Enrichment flow** — after `parsePsbt()` returns signers, the ViewModel resolves labels:

```kotlin
val signers = rawSigners.map { signer ->
    val contact = contactDao.findByFingerprint(signer.fingerprint)
    signer.copy(
        contactLabel = contact?.label,
        contactId = contact?.id,
    )
}
```

**Signer row display:**

```
✓ Alice's Trezor (3442193e)
✗ 7a8b9c0d                    [+ Add label]
```

- Known fingerprints: label with fingerprint in parentheses
- Unknown fingerprints: raw fingerprint with tappable "+ Add label"
- Tapping "+ Add label" opens a modal dialog (does not navigate away):
  - Text field for contact name
  - Dropdown/list of existing contacts to assign the fingerprint to
  - "Create new contact" option
  - Save / Cancel
- Tapping a labeled signer navigates to edit that contact on the Contacts screen

### 7. Contacts Screen

New `AppState.Contacts` state, accessible via a contacts icon button on the Home screen.

**Navigation:**
- Home screen gets a contacts/people icon button → `AppState.Contacts`
- Back button on Contacts → `AppState.Home`

**Screen layout:**

```
← Contacts

┌─────────────────────────────────┐
│ Alice's Trezor                  │
│ 3442193e, 7a8b9c0d             │
│ npub: —                         │
└─────────────────────────────────┘
┌─────────────────────────────────┐
│ Bob's ColdCard                  │
│ a1b2c3d4                        │
│ npub: npub1def...               │
└─────────────────────────────────┘

                          [+ Add Contact]
```

**Each contact card:**
- Label (tappable → edit dialog)
- List of fingerprints (each with a delete button)
- npub if set (tappable to edit, "—" if empty)
- "Add fingerprint" option (manual 8-char hex entry, validated)
- Delete contact button (with confirmation dialog)

**Edit dialog** (reused for both quick-add and contacts screen editing):
- Text field for label
- Current fingerprints with individual delete buttons
- Text field to add new fingerprint (validated: 8 hex characters)
- Optional npub field
- Save / Cancel

### 8. Result Screen — Future PSBT Forwarding (Not Implemented)

When signing produces a partial result (multisig, not enough signatures), contacts with npubs could be offered as forwarding targets ("Send to Alice"). This requires Nostr sending logic (kind 4 event, NIP-04 encryption — reverse of the receive flow).

**Not implemented in this spec.** The contact model supports it (npub field exists), but the sending logic is a follow-up. The Result screen is unchanged.

### 9. Fingerprint Validation

Fingerprints are validated on input:
- Must be exactly 8 hexadecimal characters (case-insensitive)
- Stored lowercase
- Checked for uniqueness against existing fingerprints (Room's unique index enforces this at the DB level; UI should show a user-friendly error if a duplicate is attempted)

Future enhancement: accept key-origin format (`[fingerprint/path]zpub...`) and parse the master fingerprint from the brackets. Raw zpub parsing is not reliable for extracting master fingerprints (only contains parent fingerprint). Not in scope for this spec.

## Files to Change

| File | Change |
|------|--------|
| New: `data/AppDatabase.kt` | Room database definition, singleton factory |
| New: `data/Contact.kt` | `Contact`, `ContactFingerprint`, `ContactWithFingerprints` entities |
| New: `data/ContactDao.kt` | DAO interface with queries |
| New: `data/InboxItemEntity.kt` | Stubbed inbox entity (schema only, not wired) |
| New: `ui/ContactsScreen.kt` | Contacts list, edit dialog, add contact flow |
| `ui/TransactionReviewScreen.kt` | Label display on signer rows, quick-add dialog |
| `ui/HomeScreen.kt` | Contacts icon button |
| `viewmodel/SignerViewModel.kt` | New `AppState.Contacts`, fingerprint→label resolution during PSBT parsing, contact CRUD methods |
| `gradle/libs.versions.toml` | Room dependency versions |
| `app/build.gradle.kts` | Room dependencies + KSP annotation processor |

## Testing

**JVM unit tests** (no emulator):
- `ContactDao` tests using Room's in-memory database: insert/query/delete contacts, fingerprint uniqueness constraint, cascade delete, `findByFingerprint` resolution
- Fingerprint validation: 8-char hex acceptance, rejection of invalid input

**Android instrumented tests**:
- Contacts screen: displays contacts, add/edit/delete flows work correctly
- TransactionReview signer row: labeled vs unlabeled fingerprints render correctly, quick-add dialog creates contact
- Database migration tests (for future schema version changes)

**Existing test updates**:
- Tests constructing `SignerInfo` get new `contactLabel`/`contactId` fields (default `null`, so most pass unchanged)

## Out of Scope

- Inbox persistence migration to Room (follow-up spec)
- PSBT forwarding to cosigner npubs via Nostr (follow-up spec)
- zpub parsing for fingerprint extraction (future enhancement)
- Contact import/export
- Contact sync across devices
