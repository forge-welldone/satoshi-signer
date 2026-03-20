package com.remotesigner

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.remotesigner.data.Contact
import com.remotesigner.data.ContactFingerprint
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.ui.ContactsScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContactsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleContacts = listOf(
        ContactWithFingerprints(
            contact = Contact(id = 1, label = "Alice", npub = "npub1abc123"),
            fingerprints = listOf(
                ContactFingerprint(id = 10, contactId = 1, fingerprint = "a1b2c3d4"),
            ),
        ),
        ContactWithFingerprints(
            contact = Contact(id = 2, label = "Bob", npub = null),
            fingerprints = listOf(
                ContactFingerprint(id = 20, contactId = 2, fingerprint = "e5f6a7b8"),
                ContactFingerprint(id = 21, contactId = 2, fingerprint = "11223344"),
            ),
        ),
    )

    // --- Empty state ---

    @Test
    fun emptyContacts_showsEmptyMessage() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("No contacts yet.\nTap + to add one.").assertIsDisplayed()
    }

    @Test
    fun emptyContacts_showsTitle() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
    }

    // --- Contact list rendering ---

    @Test
    fun contactsList_displaysContactLabels() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun contactsList_displaysFingerprints() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("a1b2c3d4").assertIsDisplayed()
        // Bob has two fingerprints shown as comma-separated
        composeTestRule.onNodeWithText("e5f6a7b8, 11223344").assertIsDisplayed()
    }

    @Test
    fun contactsList_displaysNpub() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("npub: npub1abc123").assertIsDisplayed()
        // Bob has no npub — shows em dash
        composeTestRule.onNodeWithText("npub: \u2014").assertIsDisplayed()
    }

    @Test
    fun contactsList_showsDeleteButtonOnEachCard() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("Delete").assertCountEquals(2)
    }

    // --- Add contact dialog ---

    @Test
    fun fabOpensAddDialog() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Add Contact").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fingerprint (8 hex chars)").assertIsDisplayed()
    }

    @Test
    fun addDialog_saveDisabledWhenEmpty() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun addDialog_saveDisabledWithInvalidFingerprint() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Name").performTextInput("Alice")
        composeTestRule.onNodeWithText("Fingerprint (8 hex chars)").performTextInput("xyz")
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun addDialog_saveEnabledWithValidInputs() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Name").performTextInput("Alice")
        composeTestRule.onNodeWithText("Fingerprint (8 hex chars)").performTextInput("a1b2c3d4")
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun addDialog_callsOnAddContactWithCorrectValues() {
        var savedLabel = ""
        var savedFingerprint = ""
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { label, fp ->
                        savedLabel = label
                        savedFingerprint = fp
                    },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Name").performTextInput("Alice")
        composeTestRule.onNodeWithText("Fingerprint (8 hex chars)").performTextInput("a1b2c3d4")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Alice", savedLabel)
        assertEquals("a1b2c3d4", savedFingerprint)
    }

    @Test
    fun addDialog_cancelDismissesWithoutSaving() {
        var addCalled = false
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onAddContact = { _, _ -> addCalled = true },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Add Contact").assertDoesNotExist()
        assertTrue("onAddContact should not be called", !addCalled)
    }

    // --- Edit contact dialog ---

    @Test
    fun tapContactOpensEditDialog() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Edit Contact").assertIsDisplayed()
    }

    @Test
    fun editDialog_showsExistingValues() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Edit Contact").assertIsDisplayed()
        // Label pre-filled
        composeTestRule.onNode(hasText("Alice") and hasText("Name")).assertIsDisplayed()
        // npub pre-filled
        composeTestRule.onNode(hasText("npub1abc123") and hasText("npub (optional)")).assertIsDisplayed()
        // Fingerprints listed
        composeTestRule.onNodeWithText("Fingerprints:").assertIsDisplayed()
        // The fingerprint text in the edit dialog row
        composeTestRule.onAllNodesWithText("a1b2c3d4").assertCountEquals(2) // one in card, one in dialog
    }

    @Test
    fun editDialog_callsOnUpdateContactOnSave() {
        var updatedId = 0L
        var updatedLabel = ""
        var updatedNpub: String? = "unchanged"
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { id, label, npub ->
                        updatedId = id
                        updatedLabel = label
                        updatedNpub = npub
                    },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        // Clear and change the label
        composeTestRule.onNode(hasText("Alice") and hasText("Name"))
            .performTextReplacement("Alice Updated")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1L, updatedId)
        assertEquals("Alice Updated", updatedLabel)
        assertEquals("npub1abc123", updatedNpub)
    }

    @Test
    fun editDialog_addFingerprintButton() {
        var addedContactId = 0L
        var addedFingerprint = ""
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { id, fp ->
                        addedContactId = id
                        addedFingerprint = fp
                    },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Add fingerprint").performTextInput("deadbeef")
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1L, addedContactId)
        assertEquals("deadbeef", addedFingerprint)
    }

    @Test
    fun editDialog_addButtonDisabledWithInvalidFingerprint() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        // "Add" button disabled when fingerprint field is empty
        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()
        // Enter invalid fingerprint (too short)
        composeTestRule.onNodeWithText("Add fingerprint").performTextInput("abc")
        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun editDialog_deleteFingerprintButton() {
        var deletedFingerprintId = 0L
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = { id -> deletedFingerprintId = id },
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        // The ✗ button next to the fingerprint
        composeTestRule.onNodeWithText("\u2717").performClick()
        composeTestRule.waitForIdle()
        assertEquals(10L, deletedFingerprintId)
    }

    // --- Delete contact confirmation ---

    @Test
    fun deleteButton_showsConfirmationDialog() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        // Click the first "Delete" button (on Alice's card)
        composeTestRule.onAllNodesWithText("Delete")[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete Contact").assertIsDisplayed()
        composeTestRule.onNodeWithText("This will remove the contact and all associated fingerprints.")
            .assertIsDisplayed()
    }

    @Test
    fun deleteConfirmation_callsOnDeleteContact() {
        var deletedId = 0L
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = { id -> deletedId = id },
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("Delete")[0].performClick()
        composeTestRule.waitForIdle()
        // Confirm dialog has "Delete" button too
        composeTestRule.onAllNodesWithText("Delete").fetchSemanticsNodes().let { nodes ->
            // The last "Delete" should be in the confirmation dialog
            composeTestRule.onAllNodesWithText("Delete")[nodes.size - 1].performClick()
        }
        composeTestRule.waitForIdle()
        assertEquals(1L, deletedId)
    }

    @Test
    fun deleteConfirmation_cancelDismissesDialog() {
        var deleteCalled = false
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = sampleContacts,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = { deleteCalled = true },
                    onDeleteFingerprint = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("Delete")[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete Contact").assertDoesNotExist()
        assertTrue("onDeleteContact should not be called", !deleteCalled)
    }

    // --- Dynamic contacts list ---

    @Test
    fun contactsList_updatesWhenContactsChange() {
        val contactsState = mutableStateOf(emptyList<ContactWithFingerprints>())
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ContactsScreen(
                    contacts = contactsState.value,
                    onBack = {},
                    onAddContact = { _, _ -> },
                    onUpdateContact = { _, _, _ -> },
                    onAddFingerprint = { _, _ -> },
                    onDeleteContact = {},
                    onDeleteFingerprint = {},
                )
            }
        }
        // Initially empty
        composeTestRule.onNodeWithText("No contacts yet.\nTap + to add one.").assertIsDisplayed()

        // Add contacts
        contactsState.value = sampleContacts
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
        composeTestRule.onNodeWithText("No contacts yet.\nTap + to add one.").assertDoesNotExist()
    }
}
