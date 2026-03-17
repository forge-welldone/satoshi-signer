# Cosigner Contacts — Labels, Fingerprints & Room Database

## Problem

Multisig PSBTs show cosigner fingerprints as raw 4-byte hex strings (e.g., `3442193e`) on the TransactionReview screen. There's no way to know which real-world person or device each fingerprint belongs to. Users must remember or look up fingerprints externally.

Additionally, the app has no persistent structured storage. The planned inbox persistence spec uses a JSON file, but a relational database is a better foundation for both contacts and future inbox persistence.

## Design

### 1. Room Database

Introduce Room as the app's persistent storage layer. A single `AppDatabase` hosts all tables. This spec adds contact tables only. The inbox persistence spec (follow-up) will add the inbox table to the same database.

**Dependencies** (added to `gradle/libs.versions.toml` and `app/build.gradle.kts`):
- `androidx.room:room-runtime:2.6.1`
- `androidx.room:room-ktx:2.6.1` (coroutine/Flow support)
- `androidx.room:room-compiler:2.6.1` (KSP annotation processor)
- KSP Gradle plugin (version matching the project's Kotlin version)

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

### 3. Database Definition

```kotlin
@Database(
    entities = [Contact::class, ContactFingerprint::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "satoshi-signer.db",
                ).build().also { INSTANCE = it }
            }
    }
}
```

The follow-up inbox persistence spec will add an `inbox_items` table to this database via a `Migration(1, 2)`.

The ViewModel accesses the database via the singleton:

```kotlin
// In SignerViewModel init
private val db = AppDatabase.getInstance(application)
private val contactDao = db.contactDao()
```

### 4. ContactDao

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

    @Transaction
    @Query("""
        SELECT c.* FROM contacts c
        INNER JOIN contact_fingerprints cf ON c.id = cf.contactId
        WHERE cf.fingerprint IN (:fingerprints)
    """)
    suspend fun findByFingerprints(fingerprints: List<String>): List<ContactWithFingerprints>

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
- `findByFingerprint()` resolves a single fingerprint → label (used by quick-add duplicate check)
- `findByFingerprints()` is the hot path — batch resolves all signer fingerprints in one query for TransactionReview

### 5. TransactionReview Integration (Quick-Add)

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

**Enrichment flow** — inside the ViewModel's `parsePsbt()` method, after constructing the raw signers list and before setting `_state.value` to `TransactionReview`, resolve labels in a single batch query:

```kotlin
// In parsePsbt(), after building rawSigners from Python result:
val fingerprints = rawSigners.map { it.fingerprint }
val contactMap = contactDao.findByFingerprints(fingerprints)
    .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
    .toMap()

val signers = rawSigners.map { signer ->
    val contact = contactMap[signer.fingerprint]
    signer.copy(
        contactLabel = contact?.contact?.label,
        contactId = contact?.contact?.id,
    )
}
// Then set _state.value = AppState.TransactionReview(..., signers = signers, ...)
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
- Tapping a labeled signer opens the same edit dialog (pre-populated with the contact's data). Navigating away from TransactionReview mid-review would lose the current PSBT, so all contact editing from this screen uses modal dialogs.
- The quick-add dialog calls ViewModel contact CRUD methods via callbacks passed to `TransactionReviewScreen` (e.g., `onSaveContact: (label: String, fingerprint: String, existingContactId: Long?) -> Unit`). After saving, the signer list is re-enriched so the label appears immediately.

### 6. Contacts Screen

New `AppState.Contacts` state, accessible via a contacts icon button on the Home screen:

```kotlin
data object Contacts : AppState()
```

The Contacts screen manages its own UI state (selected contact, dialog visibility) via local Compose state — no need for parameters in the AppState variant. The `when` expression in `AppNavigation.kt` needs a new branch for this state.

**Navigation:**
- Home screen gets a contacts/people icon button → `AppState.Contacts`
- On-screen "←" back arrow on Contacts → `AppState.Home` (consistent with app's existing navigation pattern of on-screen buttons; also wire `BackHandler` composable for Android system back button)

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

### 7. Result Screen — Future PSBT Forwarding (Not Implemented)

When signing produces a partial result (multisig, not enough signatures), contacts with npubs could be offered as forwarding targets ("Send to Alice"). This requires Nostr sending logic (kind 4 event, NIP-04 encryption — reverse of the receive flow).

**Not implemented in this spec.** The contact model supports it (npub field exists), but the sending logic is a follow-up. The Result screen is unchanged.

### 8. Input Validation

**Fingerprints:**
- Must be exactly 8 hexadecimal characters (case-insensitive)
- Stored lowercase
- Checked for uniqueness against existing fingerprints (Room's unique index enforces this at the DB level; UI should show "This fingerprint is already assigned to [Contact Name]" on duplicate)
- Validation and normalization via a shared `FingerprintValidator` utility (used by both the quick-add dialog and the Contacts screen)

**Contact labels:**
- Must be 1–50 characters after trimming whitespace
- Leading/trailing whitespace is stripped on save

Future enhancement: accept key-origin format (`[fingerprint/path]zpub...`) and parse the master fingerprint from the brackets. Raw zpub parsing is not reliable for extracting master fingerprints (only contains parent fingerprint). Not in scope for this spec.

### 9. Python Layer

No Python changes are needed. The Python PSBT parser already returns cosigner fingerprints as hex strings. All contact resolution happens in the Kotlin layer after `parsePsbt()` returns.

## Files to Change

| File | Change |
|------|--------|
| New: `data/AppDatabase.kt` | Room database definition, singleton factory |
| New: `data/Contact.kt` | `Contact`, `ContactFingerprint`, `ContactWithFingerprints` entities |
| New: `data/ContactDao.kt` | DAO interface with queries |
| New: `data/FingerprintValidator.kt` | Shared fingerprint validation/normalization utility |
| New: `ui/ContactsScreen.kt` | Contacts list, edit dialog, add contact flow |
| `ui/TransactionReviewScreen.kt` | Label display on signer rows, quick-add dialog |
| `ui/HomeScreen.kt` | Contacts icon button |
| `ui/AppNavigation.kt` | New `when` branch for `AppState.Contacts` |
| `viewmodel/SignerViewModel.kt` | New `AppState.Contacts`, fingerprint→label resolution during PSBT parsing, contact CRUD methods, DB singleton access |
| `gradle/libs.versions.toml` | Room + KSP dependency versions |
| `app/build.gradle.kts` | Room dependencies + KSP plugin |

## Testing

**JVM unit tests** (no emulator):
- `FingerprintValidator`: 8-char hex acceptance, rejection of invalid input (too short, non-hex, empty), lowercase normalization

**Android instrumented tests** (`androidTest/`):
- `ContactDao` tests using Room's in-memory database: insert/query/delete contacts, fingerprint uniqueness constraint, cascade delete, `findByFingerprint` resolution, batch `findByFingerprints` query
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
