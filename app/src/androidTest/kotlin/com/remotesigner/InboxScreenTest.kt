package com.remotesigner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.remotesigner.nostr.InboxItem
import com.remotesigner.nostr.RelayStatus
import com.remotesigner.ui.HomeScreen
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

    @Test
    fun inboxItemCard_broadcastStatus_showsChipAndTxid() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.broadcastInboxItem),
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("broadcast").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign").assertDoesNotExist()
        composeTestRule.onNodeWithText("txid: a1b2c3d4...e9f0a1b2").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signedStatus_showsChip() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.signedInboxItem),
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("signed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign").assertDoesNotExist()
    }

    @Test
    fun inboxItemCard_pendingStatus_showsChip() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.sampleInboxItems[0]),
                    onSign = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signedStatus_cardTappable() {
        var tappedItem: InboxItem? = null
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.signedInboxItem),
                    onSign = {},
                    onDelete = {},
                    onItemTap = { tappedItem = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Payment to Bob").performClick()
        assert(tappedItem != null) { "onItemTap should have been called for signed item" }
    }

    @Test
    fun homeScreen_relayStatus_showsConnectedCount() {
        val relays = mapOf(
            "wss://nos.lol" to RelayStatus.CONNECTED,
            "wss://relay.damus.io" to RelayStatus.CONNECTED,
            "wss://relay.primal.net" to RelayStatus.ERROR,
        )
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(
                    npub = "npub1test",
                    relayCount = 2,
                    relayStatuses = relays,
                    inboxItems = emptyList(),
                    onPsbtSelected = {},
                    onSignInboxItem = {},
                    onDeleteInboxItem = {},
                )
            }
        }

        composeTestRule.onNodeWithText("2 relays connected").assertIsDisplayed()
    }

    @Test
    fun homeScreen_relayList_expandsOnTap() {
        val relays = mapOf(
            "wss://nos.lol" to RelayStatus.CONNECTED,
            "wss://relay.damus.io" to RelayStatus.ERROR,
        )
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(
                    npub = "npub1test",
                    relayCount = 1,
                    relayStatuses = relays,
                    inboxItems = emptyList(),
                    onPsbtSelected = {},
                    onSignInboxItem = {},
                    onDeleteInboxItem = {},
                )
            }
        }

        // Relay details not visible initially
        composeTestRule.onNodeWithText("nos.lol").assertDoesNotExist()

        // Tap the relay status to expand
        composeTestRule.onNodeWithText("1 relay connected").performClick()

        // Now relay details are visible
        composeTestRule.onNodeWithText("nos.lol").assertIsDisplayed()
        composeTestRule.onNodeWithText("relay.damus.io").assertIsDisplayed()
    }
}
