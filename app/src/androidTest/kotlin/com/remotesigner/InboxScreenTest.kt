package com.remotesigner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.remotesigner.nostr.InboxItem
import com.remotesigner.ui.InboxSection
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test

class InboxScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun inboxSection_displaysItems() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = TestFixtures.sampleInboxItems,
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Payment to Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00500000 BTC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unsigned transaction").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.10000000 BTC").assertIsDisplayed()
    }

    @Test
    fun inboxSection_emptyList_showsNothing() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = emptyList(),
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Inbox").assertDoesNotExist()
    }

    @Test
    fun inboxItemCard_pendingStatus_showsSignButton() {
        val pendingItem = TestFixtures.sampleInboxItems[0] // PENDING
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = listOf(pendingItem), onSign = {}, onDelete = {})
            }
        }

        composeTestRule.onNodeWithText("Sign").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signedStatus_hidesSignButton() {
        val signedItem = TestFixtures.sampleInboxItems[0].copy(
            status = com.remotesigner.nostr.InboxStatus.SIGNED
        )
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = listOf(signedItem), onSign = {}, onDelete = {})
            }
        }

        composeTestRule.onNodeWithText("Sign").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signButton_callsOnSign() {
        var signedItem: InboxItem? = null
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.sampleInboxItems[0]),
                    onSign = { signedItem = it },
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Sign").performClick()
        assert(signedItem != null) { "onSign should have been called" }
    }
}
