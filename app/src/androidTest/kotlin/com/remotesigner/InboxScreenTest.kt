package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.remotesigner.data.InboxItemEntity
import com.remotesigner.data.InboxStatus
import com.remotesigner.ui.InboxSection
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Tests focused on the redesigned `InboxSection` (SIG-3).
 * Home-level relay chip / hero copy / NFC-button tests live in [HomeScreenTest].
 */
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
        // Amount is rendered as display + suffix; assert on the leading numeric portion.
        composeTestRule.onNodeWithText("0.00500000").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.10000000").assertIsDisplayed()
        // The Unsigned pill is uppercased by the Pill primitive.
        composeTestRule.onNodeWithText("UNSIGNED").assertIsDisplayed()
    }

    @Test
    fun inboxSection_emptyList_showsEmptyCard() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = emptyList(), onSign = {}, onDelete = {})
            }
        }
        composeTestRule.onNodeWithText("No incoming transactions yet.").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_pendingStatus_showsReviewAndDismiss() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.sampleInboxItems[0]),
                    onSign = {},
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Review →").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signedStatus_showsOpenAndDismiss() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.signedInboxItem),
                    onSign = {},
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Open →").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
        composeTestRule.onNodeWithText("Review →").assertDoesNotExist()
    }

    @Test
    fun inboxItemCard_review_callsOnSign() {
        var signedItem: InboxItemEntity? = null
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.sampleInboxItems[0]),
                    onSign = { signedItem = it },
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Review →").performClick()
        assertNotNull("Review should fire onSign", signedItem)
    }

    @Test
    fun inboxItemCard_dismiss_callsOnDelete() {
        var deleted: InboxItemEntity? = null
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.sampleInboxItems[0]),
                    onSign = {},
                    onDelete = { deleted = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Dismiss").performClick()
        assertNotNull("Dismiss should fire onDelete", deleted)
    }

    @Test
    fun inboxItemCard_open_callsOnItemTap() {
        var tapped: InboxItemEntity? = null
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.signedInboxItem),
                    onSign = {},
                    onDelete = {},
                    onItemTap = { tapped = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Open →").performClick()
        assertNotNull("Open should fire onItemTap", tapped)
    }

    @Test
    fun inboxItemCard_broadcastStatus_showsTxidAndPill() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.broadcastInboxItem),
                    onSign = {},
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("BROADCAST").assertIsDisplayed()
        composeTestRule.onNodeWithText("txid: a1b2c3d4…e9f0a1b2", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_signedStatus_showsSignedPill() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(
                    items = listOf(TestFixtures.signedInboxItem),
                    onSign = {},
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("SIGNED").assertIsDisplayed()
    }

    @Test
    fun inboxItemCard_failedStatus_alsoShowsUnsignedPill() {
        val failed = TestFixtures.sampleInboxItems[0].copy(status = InboxStatus.FAILED)
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = listOf(failed), onSign = {}, onDelete = {})
            }
        }
        composeTestRule.onNodeWithText("UNSIGNED").assertIsDisplayed()
        composeTestRule.onNodeWithText("Review →").assertIsDisplayed()
    }
}
