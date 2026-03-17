# Cosigner Contacts Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add labeled contacts for multisig cosigners, backed by a Room database, with a contacts management screen and quick-add from the transaction review signer list.

**Architecture:** Room database (singleton) with `contacts` + `contact_fingerprints` tables. ViewModel resolves fingerprint → label via batch DAO query during PSBT parsing. Contacts screen is a new `AppState.Contacts` with full CRUD. Quick-add dialog on TransactionReview uses modal dialogs to avoid losing the current PSBT.

**Tech Stack:** Room 2.6.1, KSP (Kotlin 2.1.0), Jetpack Compose, existing SignerViewModel pattern

**Spec:** `docs/superpowers/specs/2026-03-17-cosigner-contacts-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| New: `app/src/main/kotlin/com/remotesigner/data/AppDatabase.kt` | Room database definition, singleton factory |
| New: `app/src/main/kotlin/com/remotesigner/data/Contact.kt` | `Contact`, `ContactFingerprint`, `ContactWithFingerprints` entities |
| New: `app/src/main/kotlin/com/remotesigner/data/ContactDao.kt` | DAO interface with queries |
| New: `app/src/main/kotlin/com/remotesigner/data/FingerprintValidator.kt` | Fingerprint validation/normalization utility |
| New: `app/src/main/kotlin/com/remotesigner/ui/ContactsScreen.kt` | Contacts list screen + edit/add dialogs |
| Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` | `AppState.Contacts`, contact CRUD methods, fingerprint enrichment in `parsePsbt()` |
| Modify: `app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt` | Signer labels, quick-add dialog |
| Modify: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt` | Contacts button |
| Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt` | New `when` branch for `AppState.Contacts`, callbacks wiring |
| Modify: `gradle/libs.versions.toml` | Room + KSP versions |
| Modify: `app/build.gradle.kts` | Room deps + KSP plugin |
| New: `app/src/test/kotlin/com/remotesigner/data/FingerprintValidatorTest.kt` | JVM unit tests for validation |
| New: `app/src/androidTest/kotlin/com/remotesigner/data/ContactDaoTest.kt` | Instrumented DAO tests |
| Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt` | Add contact-related fixtures |

---

### Task 1: Add Room + KSP Dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add versions and library entries to `gradle/libs.versions.toml`**

After line 12 (`zxing = "3.5.3"`), add:

```toml
room = "2.6.1"
ksp = "2.1.0-1.0.29"
```

In the `[libraries]` section, after line 31 (`zxing-core`), add:

```toml
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

In the `[plugins]` section, after line 36 (`kotlin-compose`), add:

```toml
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Add KSP plugin and Room dependencies to `app/build.gradle.kts`**

Add KSP plugin after line 4 (`alias(libs.plugins.kotlin.compose)`):

```kotlin
alias(libs.plugins.ksp)
```

Add Room dependencies in the `dependencies` block, after line 69 (`implementation(libs.zxing.core)`):

```kotlin
implementation(libs.room.runtime)
implementation(libs.room.ktx)
ksp(libs.room.compiler)
```

Add Room testing and coroutines test dependencies after line 78 (`androidTestImplementation(libs.okhttp.mockwebserver)`):

```kotlin
androidTestImplementation(libs.room.testing)
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

- [ ] **Step 3: Verify the build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Room 2.6.1 and KSP dependencies"
```

---

### Task 2: FingerprintValidator (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/data/FingerprintValidator.kt`
- Create: `app/src/test/kotlin/com/remotesigner/data/FingerprintValidatorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.remotesigner.data

import org.junit.Assert.*
import org.junit.Test

class FingerprintValidatorTest {

    @Test
    fun valid_lowercase_fingerprint() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("a1b2c3d4"))
    }

    @Test
    fun valid_uppercase_normalized_to_lowercase() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("A1B2C3D4"))
    }

    @Test
    fun valid_mixed_case_normalized() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("A1b2C3d4"))
    }

    @Test
    fun too_short_returns_null() {
        assertNull(FingerprintValidator.normalize("a1b2c3"))
    }

    @Test
    fun too_long_returns_null() {
        assertNull(FingerprintValidator.normalize("a1b2c3d4e5"))
    }

    @Test
    fun empty_returns_null() {
        assertNull(FingerprintValidator.normalize(""))
    }

    @Test
    fun non_hex_returns_null() {
        assertNull(FingerprintValidator.normalize("g1h2i3j4"))
    }

    @Test
    fun whitespace_trimmed_before_validation() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("  a1b2c3d4  "))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.data.FingerprintValidatorTest" 2>&1 | tail -5`
Expected: FAIL — class not found

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.remotesigner.data

object FingerprintValidator {
    private val HEX_8_REGEX = Regex("^[0-9a-f]{8}$")

    fun normalize(input: String): String? {
        val trimmed = input.trim().lowercase()
        return if (HEX_8_REGEX.matches(trimmed)) trimmed else null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.remotesigner.data.FingerprintValidatorTest" 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL` — all 8 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/data/FingerprintValidator.kt \
       app/src/test/kotlin/com/remotesigner/data/FingerprintValidatorTest.kt
git commit -m "feat: add FingerprintValidator with TDD tests"
```

---

### Task 3: Room Entities + Database + DAO

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/data/Contact.kt`
- Create: `app/src/main/kotlin/com/remotesigner/data/ContactDao.kt`
- Create: `app/src/main/kotlin/com/remotesigner/data/AppDatabase.kt`

- [ ] **Step 1: Create Room entities**

`app/src/main/kotlin/com/remotesigner/data/Contact.kt`:

```kotlin
package com.remotesigner.data

import androidx.room.*

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

data class ContactWithFingerprints(
    @Embedded val contact: Contact,
    @Relation(parentColumn = "id", entityColumn = "contactId")
    val fingerprints: List<ContactFingerprint>,
)
```

- [ ] **Step 2: Create ContactDao**

`app/src/main/kotlin/com/remotesigner/data/ContactDao.kt`:

```kotlin
package com.remotesigner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
    suspend fun insertFingerprint(fingerprint: ContactFingerprint): Long

    @Update
    suspend fun updateContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)

    @Query("DELETE FROM contact_fingerprints WHERE id = :id")
    suspend fun deleteFingerprint(id: Long)
}
```

- [ ] **Step 3: Create AppDatabase**

`app/src/main/kotlin/com/remotesigner/data/AppDatabase.kt`:

```kotlin
package com.remotesigner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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

- [ ] **Step 4: Verify the build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL` (KSP generates Room implementation classes)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/data/
git commit -m "feat: add Room database with Contact entities and DAO"
```

---

### Task 4: ContactDao Instrumented Tests

**Files:**
- Create: `app/src/androidTest/kotlin/com/remotesigner/data/ContactDaoTest.kt`

- [ ] **Step 1: Write DAO tests**

```kotlin
package com.remotesigner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContactDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.contactDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insert_and_query_contact_with_fingerprint() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "a1b2c3d4"))

        val all = dao.getAllWithFingerprints().first()
        assertEquals(1, all.size)
        assertEquals("Alice", all[0].contact.label)
        assertEquals(1, all[0].fingerprints.size)
        assertEquals("a1b2c3d4", all[0].fingerprints[0].fingerprint)
    }

    @Test
    fun findByFingerprint_returns_matching_contact() = runTest {
        val contactId = dao.insertContact(Contact(label = "Bob"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "e5f6a7b8"))

        val found = dao.findByFingerprint("e5f6a7b8")
        assertNotNull(found)
        assertEquals("Bob", found!!.label)
    }

    @Test
    fun findByFingerprint_returns_null_for_unknown() = runTest {
        assertNull(dao.findByFingerprint("00000000"))
    }

    @Test
    fun findByFingerprints_batch_query() = runTest {
        val id1 = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = id1, fingerprint = "a1b2c3d4"))
        val id2 = dao.insertContact(Contact(label = "Bob"))
        dao.insertFingerprint(ContactFingerprint(contactId = id2, fingerprint = "e5f6a7b8"))

        val results = dao.findByFingerprints(listOf("a1b2c3d4", "e5f6a7b8", "00000000"))
        assertEquals(2, results.size)
    }

    @Test
    fun cascade_delete_removes_fingerprints() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "a1b2c3d4"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "e5f6a7b8"))

        dao.deleteContact(contactId)
        val all = dao.getAllWithFingerprints().first()
        assertTrue(all.isEmpty())
    }

    @Test
    fun fingerprint_unique_constraint_enforced() = runTest {
        val id1 = dao.insertContact(Contact(label = "Alice"))
        dao.insertFingerprint(ContactFingerprint(contactId = id1, fingerprint = "a1b2c3d4"))

        val id2 = dao.insertContact(Contact(label = "Bob"))
        try {
            dao.insertFingerprint(ContactFingerprint(contactId = id2, fingerprint = "a1b2c3d4"))
            fail("Expected exception for duplicate fingerprint")
        } catch (e: Exception) {
            // Expected: UNIQUE constraint violation
        }
    }

    @Test
    fun updateContact_changes_label() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        dao.updateContact(Contact(id = contactId, label = "Alice's Trezor"))

        val all = dao.getAllWithFingerprints().first()
        assertEquals("Alice's Trezor", all[0].contact.label)
    }

    @Test
    fun deleteFingerprint_keeps_contact() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice"))
        val fp1 = dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "a1b2c3d4"))
        dao.insertFingerprint(ContactFingerprint(contactId = contactId, fingerprint = "e5f6a7b8"))

        dao.deleteFingerprint(fp1)
        val all = dao.getAllWithFingerprints().first()
        assertEquals(1, all.size)
        assertEquals(1, all[0].fingerprints.size)
        assertEquals("e5f6a7b8", all[0].fingerprints[0].fingerprint)
    }

    @Test
    fun getAllWithFingerprints_ordered_by_label() = runTest {
        dao.insertContact(Contact(label = "Charlie"))
        dao.insertContact(Contact(label = "Alice"))
        dao.insertContact(Contact(label = "Bob"))

        val all = dao.getAllWithFingerprints().first()
        assertEquals(listOf("Alice", "Bob", "Charlie"), all.map { it.contact.label })
    }

    @Test
    fun contact_with_npub() = runTest {
        val contactId = dao.insertContact(Contact(label = "Alice", npub = "npub1abc...xyz"))
        val all = dao.getAllWithFingerprints().first()
        assertEquals("npub1abc...xyz", all[0].contact.npub)
    }
}
```

- [ ] **Step 2: Run tests on emulator**

Run: `./gradlew connectedDebugAndroidTest --tests "com.remotesigner.data.ContactDaoTest" 2>&1 | tail -10`
Expected: All 10 tests pass

Note: requires a running emulator (`emulator -avd test_device -no-audio &`). Add `androidTestImplementation("androidx.test:core:1.6.1")` to `app/build.gradle.kts` if `ApplicationProvider` is not resolved — it should already be available transitively via `test-runner`.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/com/remotesigner/data/ContactDaoTest.kt
git commit -m "test: add ContactDao instrumented tests"
```

---

### Task 5: ViewModel — AppState.Contacts + Database Access + Contact CRUD

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Add `AppState.Contacts` to the sealed class**

In `SignerViewModel.kt`, after line 77 (`data class Error(val message: String) : AppState()`), add:

```kotlin
    data object Contacts : AppState()
```

- [ ] **Step 2: Add database access to the ViewModel**

Add import at the top of the file:

```kotlin
import com.remotesigner.data.AppDatabase
import com.remotesigner.data.Contact
import com.remotesigner.data.ContactFingerprint
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.data.FingerprintValidator
```

After line 94 (`private val pythonBridge = PythonBridge()`), add:

```kotlin
    private val contactDao = AppDatabase.getInstance(application).contactDao()
    val contacts = contactDao.getAllWithFingerprints()
```

- [ ] **Step 3: Add contact CRUD methods**

Add the following methods at the end of the class (before the closing `}`):

```kotlin
    fun showContacts() {
        _state.value = AppState.Contacts
    }

    fun saveContact(label: String, fingerprint: String, existingContactId: Long?) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        val trimmedLabel = label.trim()
        if (trimmedLabel.isEmpty() || trimmedLabel.length > 50) return

        viewModelScope.launch(Dispatchers.IO) {
            if (existingContactId != null) {
                contactDao.insertFingerprint(
                    ContactFingerprint(contactId = existingContactId, fingerprint = normalized)
                )
            } else {
                val contactId = contactDao.insertContact(Contact(label = trimmedLabel))
                contactDao.insertFingerprint(
                    ContactFingerprint(contactId = contactId, fingerprint = normalized)
                )
            }
            // Re-enrich signers if we're on TransactionReview
            reEnrichSigners()
        }
    }

    fun updateContact(contactId: Long, newLabel: String, npub: String?) {
        val trimmedLabel = newLabel.trim()
        if (trimmedLabel.isEmpty() || trimmedLabel.length > 50) return

        viewModelScope.launch(Dispatchers.IO) {
            contactDao.updateContact(Contact(id = contactId, label = trimmedLabel, npub = npub?.trim()?.ifEmpty { null }))
        }
    }

    fun addFingerprintToContact(contactId: Long, fingerprint: String) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.insertFingerprint(
                ContactFingerprint(contactId = contactId, fingerprint = normalized)
            )
            reEnrichSigners()
        }
    }

    fun deleteContact(contactId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.deleteContact(contactId)
            reEnrichSigners()
        }
    }

    fun deleteFingerprint(fingerprintId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.deleteFingerprint(fingerprintId)
            reEnrichSigners()
        }
    }

    private suspend fun reEnrichSigners() {
        val currentState = _state.value
        if (currentState is AppState.TransactionReview) {
            val fingerprints = currentState.signers.map { it.fingerprint }
            val contactMap = contactDao.findByFingerprints(fingerprints)
                .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
                .toMap()
            val enriched = currentState.signers.map { signer ->
                val contact = contactMap[signer.fingerprint]
                signer.copy(
                    contactLabel = contact?.contact?.label,
                    contactId = contact?.contact?.id,
                )
            }
            _state.value = currentState.copy(signers = enriched)
        }
    }
```

- [ ] **Step 4: Verify the build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: add AppState.Contacts, contact CRUD methods, and DB access to ViewModel"
```

---

### Task 6: Extend SignerInfo + Enrich in parsePsbt()

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:47-51` (SignerInfo)
- Modify: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt:271-276` (parsePsbt signers)

- [ ] **Step 1: Add `contactLabel` and `contactId` fields to SignerInfo**

In `SignerViewModel.kt`, change lines 47-51 from:

```kotlin
data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
)
```

to:

```kotlin
data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
    val contactLabel: String? = null,
    val contactId: Long? = null,
)
```

- [ ] **Step 2: Add fingerprint enrichment in parsePsbt()**

In `parsePsbt()`, replace lines 271-276 (the signers extraction) with:

```kotlin
            val rawSigners = (result["signers"] as? List<Map<String, Any?>>)?.map { s ->
                SignerInfo(
                    fingerprint = s["fingerprint"]?.toString() ?: "",
                    signed = s["signed"] as? Boolean ?: false,
                )
            } ?: emptyList()

            val signerFingerprints = rawSigners.map { it.fingerprint }
            val contactMap = contactDao.findByFingerprints(signerFingerprints)
                .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
                .toMap()
            val signers = rawSigners.map { signer ->
                val contact = contactMap[signer.fingerprint]
                signer.copy(
                    contactLabel = contact?.contact?.label,
                    contactId = contact?.contact?.id,
                )
            }
```

Update line 289 to use `signers` (it should already reference the local variable — verify it matches).

- [ ] **Step 3: Verify the build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run existing tests to check nothing broke**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL` — existing tests pass because new `SignerInfo` fields have defaults

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
git commit -m "feat: extend SignerInfo with contact fields and enrich during PSBT parsing"
```

---

### Task 7: Update TransactionReviewScreen — Signer Labels + Quick-Add Dialog

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt`

- [ ] **Step 1: Add new callback parameter and imports**

Change the function signature (lines 20-24) from:

```kotlin
@Composable
fun TransactionReviewScreen(
    state: AppState.TransactionReview,
    onSign: () -> Unit,
    onCancel: () -> Unit,
)
```

to:

```kotlin
@Composable
fun TransactionReviewScreen(
    state: AppState.TransactionReview,
    contacts: List<ContactWithFingerprints>,
    onSign: () -> Unit,
    onCancel: () -> Unit,
    onSaveContact: (label: String, fingerprint: String, existingContactId: Long?) -> Unit,
)
```

Add import at top:

```kotlin
import androidx.compose.runtime.*
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.data.FingerprintValidator
```

- [ ] **Step 2: Add dialog state and dialog composable**

Add dialog state inside `TransactionReviewScreen`, just before the `Surface` call (after the function opening brace):

```kotlin
    var showAddDialog by remember { mutableStateOf<SignerInfo?>(null) }
```

Add a dialog composable at the bottom of the file (after `SignerRow`):

```kotlin
@Composable
private fun QuickAddContactDialog(
    signer: SignerInfo,
    existingContacts: List<ContactWithFingerprints>,
    onDismiss: () -> Unit,
    onSave: (label: String, fingerprint: String, existingContactId: Long?) -> Unit,
) {
    var label by remember { mutableStateOf(signer.contactLabel ?: "") }
    var selectedContactId by remember { mutableStateOf<Long?>(signer.contactId) }
    var showExisting by remember { mutableStateOf(signer.contactId != null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (signer.contactLabel != null) "Edit Contact"
                else "Label Cosigner ${signer.fingerprint}"
            )
        },
        text = {
            Column {
                if (!showExisting) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { if (it.length <= 50) label = it },
                        label = { Text("Contact name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (existingContacts.isNotEmpty()) {
                        TextButton(onClick = { showExisting = true }) {
                            Text("Or add to existing contact")
                        }
                    }
                } else {
                    Text("Add to existing contact:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    existingContacts.forEach { cwf ->
                        TextButton(
                            onClick = { selectedContactId = cwf.contact.id },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                cwf.contact.label,
                                color = if (selectedContactId == cwf.contact.id)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(onClick = { showExisting = false; selectedContactId = null }) {
                        Text("Or create new contact")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (showExisting && selectedContactId != null) {
                        onSave("", signer.fingerprint, selectedContactId)
                    } else if (label.trim().isNotEmpty()) {
                        onSave(label.trim(), signer.fingerprint, null)
                    }
                    onDismiss()
                },
                enabled = (showExisting && selectedContactId != null) || label.trim().isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

- [ ] **Step 3: Wire the dialog into the screen**

Add just before the closing `}` of the `Surface` block (before line 122):

```kotlin
    showAddDialog?.let { signer ->
        QuickAddContactDialog(
            signer = signer,
            existingContacts = contacts,
            onDismiss = { showAddDialog = null },
            onSave = onSaveContact,
        )
    }
```

- [ ] **Step 4: Update SignerRow to show labels and be tappable**

Replace the `SignerRow` composable (lines 163-175) with:

```kotlin
@Composable
private fun SignerRow(signer: SignerInfo, onTap: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (signer.signed) "\u2713" else "\u2717",
            modifier = Modifier.width(24.dp),
        )
        if (signer.contactLabel != null) {
            Text(signer.contactLabel)
            Text(
                " (${signer.fingerprint})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(signer.fingerprint)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "+ Add label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (signer.isThisDevice) {
            Text(" \u2190 this device", style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

Add import for `Alignment`:

```kotlin
import androidx.compose.ui.Alignment
```

- [ ] **Step 5: Update the signers forEach to pass onTap**

Change lines 105-110 from:

```kotlin
            if (state.signers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.signers.forEach { signer ->
                    SignerRow(signer)
                }
            }
```

to:

```kotlin
            if (state.signers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.signers.forEach { signer ->
                    SignerRow(signer) { showAddDialog = signer }
                }
            }
```

- [ ] **Step 6: Commit (build will be fixed in Task 8)**

The build won't compile yet because `AppNavigation.kt` calls `TransactionReviewScreen` without the new parameters. Task 8 fixes this immediately.

```bash
git add app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt
git commit -m "feat: add signer labels and quick-add contact dialog to TransactionReview"
```

---

### Task 8: Wire Navigation — HomeScreen Button + AppNavigation

This task comes before ContactsScreen to keep the build green. It wires the new TransactionReviewScreen parameters and adds the Contacts button + routing.

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`

- [ ] **Step 1: Add contacts button to HomeScreen**

Add `onContacts` callback to `HomeScreen` parameters. Change lines 34-43 from:

```kotlin
fun HomeScreen(
    npub: String,
    relayCount: Int,
    relayStatuses: Map<String, RelayStatus>,
    inboxItems: List<InboxItem>,
    onPsbtSelected: (Uri) -> Unit,
    onSignInboxItem: (InboxItem) -> Unit,
    onDeleteInboxItem: (InboxItem) -> Unit,
    onItemTap: (InboxItem) -> Unit = {},
)
```

to:

```kotlin
fun HomeScreen(
    npub: String,
    relayCount: Int,
    relayStatuses: Map<String, RelayStatus>,
    inboxItems: List<InboxItem>,
    onPsbtSelected: (Uri) -> Unit,
    onSignInboxItem: (InboxItem) -> Unit,
    onDeleteInboxItem: (InboxItem) -> Unit,
    onItemTap: (InboxItem) -> Unit = {},
    onContacts: () -> Unit = {},
)
```

After the "Open PSBT File" button (after line 100), add:

```kotlin
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onContacts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Contacts")
            }
```

- [ ] **Step 2: Wire contacts and new params into AppNavigation**

In `AppNavigation.kt`, add import:

```kotlin
import com.remotesigner.data.ContactWithFingerprints
```

Add a `val contacts by viewModel.contacts.collectAsStateWithLifecycle(initialValue = emptyList())` line after line 35 (`val nfcTagResult`).

Update the `HomeScreen` call (around line 61) to pass the new callback:

```kotlin
        is AppState.Home -> HomeScreen(
            npub = viewModel.keyManager.getNpub(),
            relayCount = relayCount,
            relayStatuses = relayStatuses,
            inboxItems = inboxItems,
            onPsbtSelected = { uri -> viewModel.loadPsbt(uri) },
            onSignInboxItem = { item -> viewModel.signInboxItem(item) },
            onDeleteInboxItem = { item -> viewModel.deleteInboxItem(item.id) },
            onItemTap = { item -> viewModel.openInboxResult(item) },
            onContacts = { viewModel.showContacts() },
        )
```

Update the `TransactionReviewScreen` call (around line 71) to pass new parameters:

```kotlin
        is AppState.TransactionReview -> TransactionReviewScreen(
            state = s,
            contacts = contacts,
            onSign = { viewModel.signWithTrezor() },
            onCancel = { viewModel.goHome() },
            onSaveContact = { label, fingerprint, existingId ->
                viewModel.saveContact(label, fingerprint, existingId)
            },
        )
```

Add the new `AppState.Contacts` branch before the `AppState.Error` branch (use an empty placeholder — ContactsScreen is created in Task 9):

```kotlin
        is AppState.Contacts -> {
            // ContactsScreen wired in Task 9
            Box(modifier = Modifier.fillMaxSize()) {
                Text("Contacts — coming soon")
            }
        }
```

- [ ] **Step 3: Verify the full build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run all existing tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt \
       app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
git commit -m "feat: wire contacts button on Home and navigation routing"
```

---

### Task 9: Contacts Screen

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/ui/ContactsScreen.kt`

- [ ] **Step 1: Create the ContactsScreen composable**

```kotlin
package com.remotesigner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.data.FingerprintValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contacts: List<ContactWithFingerprints>,
    onBack: () -> Unit,
    onAddContact: (label: String, fingerprint: String) -> Unit,
    onUpdateContact: (contactId: Long, label: String, npub: String?) -> Unit,
    onAddFingerprint: (contactId: Long, fingerprint: String) -> Unit,
    onDeleteContact: (contactId: Long) -> Unit,
    onDeleteFingerprint: (fingerprintId: Long) -> Unit,
) {
    BackHandler(onBack = onBack)

    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactWithFingerprints?>(null) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No contacts yet.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(contacts, key = { it.contact.id }) { cwf ->
                    ContactCard(
                        contact = cwf,
                        onEdit = { editingContact = cwf },
                        onDelete = { confirmDeleteId = cwf.contact.id },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onSave = { label, fingerprint ->
                onAddContact(label, fingerprint)
                showAddDialog = false
            },
        )
    }

    editingContact?.let { cwf ->
        EditContactDialog(
            contact = cwf,
            onDismiss = { editingContact = null },
            onUpdateLabel = { label, npub ->
                onUpdateContact(cwf.contact.id, label, npub)
                editingContact = null
            },
            onAddFingerprint = { fp ->
                onAddFingerprint(cwf.contact.id, fp)
                editingContact = null
            },
            onDeleteFingerprint = { fpId ->
                onDeleteFingerprint(fpId)
                editingContact = null
            },
        )
    }

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete Contact") },
            text = { Text("This will remove the contact and all associated fingerprints.") },
            confirmButton = {
                TextButton(onClick = { onDeleteContact(id); confirmDeleteId = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContactCard(
    contact: ContactWithFingerprints,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(contact.contact.label, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                contact.fingerprints.joinToString(", ") { it.fingerprint },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "npub: ${contact.contact.npub ?: "\u2014"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onSave: (label: String, fingerprint: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    val fpValid = FingerprintValidator.normalize(fingerprint) != null
    val labelValid = label.trim().let { it.isNotEmpty() && it.length <= 50 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= 50) label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { if (it.length <= 8) fingerprint = it },
                    label = { Text("Fingerprint (8 hex chars)") },
                    singleLine = true,
                    isError = fingerprint.isNotEmpty() && !fpValid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), fingerprint) },
                enabled = labelValid && fpValid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditContactDialog(
    contact: ContactWithFingerprints,
    onDismiss: () -> Unit,
    onUpdateLabel: (label: String, npub: String?) -> Unit,
    onAddFingerprint: (fingerprint: String) -> Unit,
    onDeleteFingerprint: (fingerprintId: Long) -> Unit,
) {
    var label by remember { mutableStateOf(contact.contact.label) }
    var npub by remember { mutableStateOf(contact.contact.npub ?: "") }
    var newFingerprint by remember { mutableStateOf("") }
    val labelValid = label.trim().let { it.isNotEmpty() && it.length <= 50 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= 50) label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = npub,
                    onValueChange = { npub = it },
                    label = { Text("npub (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Fingerprints:", style = MaterialTheme.typography.labelMedium)
                contact.fingerprints.forEach { fp ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fp.fingerprint, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onDeleteFingerprint(fp.id) }) {
                            Text("\u2717", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFingerprint,
                        onValueChange = { if (it.length <= 8) newFingerprint = it },
                        label = { Text("Add fingerprint") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            onAddFingerprint(newFingerprint)
                            newFingerprint = ""
                        },
                        enabled = FingerprintValidator.normalize(newFingerprint) != null,
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUpdateLabel(label.trim(), npub.ifBlank { null }) },
                enabled = labelValid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

- [ ] **Step 2: Verify the file was created**

Run: `ls app/src/main/kotlin/com/remotesigner/ui/ContactsScreen.kt`
Expected: file exists

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/ContactsScreen.kt
git commit -m "feat: add ContactsScreen with add/edit/delete dialogs"
```

---

### Task 10: Wire ContactsScreen into AppNavigation + Update Existing Tests

Replace the placeholder from Task 8 with the real ContactsScreen, and update existing tests for the new parameters.

**Files:**
- Modify: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`
- Modify: `app/src/androidTest/kotlin/com/remotesigner/TestFixtures.kt`
- Modify: any test file that calls `TransactionReviewScreen` or `HomeScreen` directly

- [ ] **Step 1: Replace the Contacts placeholder in AppNavigation**

Replace the `AppState.Contacts` placeholder branch with the real `ContactsScreen` call:

```kotlin
        is AppState.Contacts -> ContactsScreen(
            contacts = contacts,
            onBack = { viewModel.goHome() },
            onAddContact = { label, fp -> viewModel.saveContact(label, fp, null) },
            onUpdateContact = { id, label, npub -> viewModel.updateContact(id, label, npub) },
            onAddFingerprint = { id, fp -> viewModel.addFingerprintToContact(id, fp) },
            onDeleteContact = { id -> viewModel.deleteContact(id) },
            onDeleteFingerprint = { id -> viewModel.deleteFingerprint(id) },
        )
```

- [ ] **Step 2: Check which tests reference TransactionReviewScreen**

Run: `grep -r "TransactionReviewScreen" app/src/androidTest/ --include="*.kt" -l`

These test files need the new `contacts` and `onSaveContact` parameters added.

- [ ] **Step 3: Update test calls to TransactionReviewScreen**

Each call to `TransactionReviewScreen(state = ..., onSign = ..., onCancel = ...)` needs the new parameters:

```kotlin
TransactionReviewScreen(
    state = ...,
    contacts = emptyList(),
    onSign = ...,
    onCancel = ...,
    onSaveContact = { _, _, _ -> },
)
```

- [ ] **Step 4: Check which tests reference HomeScreen**

Run: `grep -r "HomeScreen(" app/src/androidTest/ --include="*.kt" -l`

Add `onContacts = {}` if any tests call `HomeScreen` directly.

- [ ] **Step 5: Verify the full build compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Run all Android instrumented tests**

Run: `./gradlew connectedDebugAndroidTest 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt \
       app/src/androidTest/
git commit -m "feat: wire ContactsScreen into navigation and update existing tests"
```

---

### Task 11: Final Verification

- [ ] **Step 1: Clean build**

Run: `./gradlew clean assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run all JVM tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run all Android instrumented tests**

Run: `./gradlew connectedDebugAndroidTest 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 4: Verify on device (manual)**

Install on device/emulator: `./gradlew installDebug`
- Home screen shows "Contacts" button
- Tapping opens empty Contacts screen with back arrow
- Can add a contact with label + fingerprint
- Can edit/delete contacts
- Loading a multisig PSBT shows labels next to known fingerprints
- Unknown fingerprints show "+ Add label"
- Quick-add dialog creates contact and label appears immediately
