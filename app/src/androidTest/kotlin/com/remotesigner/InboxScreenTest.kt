package com.remotesigner

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        // The Unsigned pill is uppercased by the Pill primitive. Both fixtures
        // (PENDING + FAILED) render one, so assert the count rather than uniqueness.
        composeTestRule.onAllNodesWithText("UNSIGNED").assertCountEquals(2)
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
    fun inboxItemCard_blankAmount_fallsBackToLabel() {
        // Regression (Copilot review): amount stays "" when PSBT parsing
        // fails — the card must fall back to the label instead of rendering
        // an empty headline.
        val unparsed = TestFixtures.sampleInboxItems[0].copy(amount = "")
        composeTestRule.setContent {
            SatoshiSignerTheme {
                InboxSection(items = listOf(unparsed), onSign = {}, onDelete = {})
            }
        }
        composeTestRule.onNodeWithText("Payment to Alice").assertIsDisplayed()
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
