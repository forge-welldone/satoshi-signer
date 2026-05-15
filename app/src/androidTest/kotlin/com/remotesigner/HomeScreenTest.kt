package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.remotesigner.data.InboxItemEntity
import com.remotesigner.data.InboxStatus
import com.remotesigner.nostr.RelayStatus
import com.remotesigner.ui.HomeScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test

/**
 * Covers the SIG-3 acceptance matrix: relay × {connected, connecting,
 * disconnected} × inbox × {empty, item} × NFC × {available, unavailable}.
 *
 * Compose instrumented test — runs under `connectedDebugAndroidTest`,
 * outside the meta-repo's dockerized JVM test runner.
 */
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val npub = "npub1vkecsewm4fnfgpvncqv5x9rhrgrerjuh5qkyc309wdxph4xj44yqxjug6f"

    private fun renderHome(
        inboxItems: List<InboxItemEntity> = emptyList(),
        relayCount: Int = 0,
        relayStatuses: Map<String, RelayStatus> = emptyMap(),
        contactsCount: Int = 0,
        nfcAvailable: Boolean = false,
        onSign: (InboxItemEntity) -> Unit = {},
        onDelete: (InboxItemEntity) -> Unit = {},
        onItemTap: (InboxItemEntity) -> Unit = {},
        onContacts: () -> Unit = {},
        onEncryptPassphrase: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(
                    npub = npub,
                    relayCount = relayCount,
                    relayStatuses = relayStatuses,
                    inboxItems = inboxItems,
                    onPsbtSelected = {},
                    onSignInboxItem = onSign,
                    onDeleteInboxItem = onDelete,
                    onItemTap = onItemTap,
                    onContacts = onContacts,
                    onEncryptPassphrase = onEncryptPassphrase,
                    contactsCount = contactsCount,
                    nfcAvailable = nfcAvailable,
                )
            }
        }
    }

    @Test
    fun hero_emptyInbox_showsStandingByCopy() {
        renderHome(inboxItems = emptyList())
        composeTestRule.onNodeWithText("STANDING BY").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for a transaction to sign.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Open a PSBT file, or send one from Electrum to this phone over Nostr.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("No incoming transactions yet.").assertIsDisplayed()
    }

    @Test
    fun hero_oneItem_showsReadyToSignCopy_singular() {
        renderHome(inboxItems = listOf(TestFixtures.sampleInboxItems[0]))
        composeTestRule.onNodeWithText("READY TO SIGN").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 transaction waiting for your signature.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Review the outputs, plug in your Trezor, and sign offline.",
        ).assertIsDisplayed()
    }

    @Test
    fun hero_multipleItems_pluralisesCount() {
        renderHome(inboxItems = TestFixtures.sampleInboxItems) // 2 PENDING/FAILED
        composeTestRule.onNodeWithText("2 transactions waiting for your signature.")
            .assertIsDisplayed()
    }

    @Test
    fun hero_onlySignedItem_treatsAsReadyButCountFallsBackToOne() {
        renderHome(inboxItems = listOf(TestFixtures.signedInboxItem))
        composeTestRule.onNodeWithText("READY TO SIGN").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 transaction waiting for your signature.")
            .assertIsDisplayed()
    }

    @Test
    fun relayChip_connected_showsRelayCountAndLabel() {
        renderHome(
            relayCount = 3,
            relayStatuses = mapOf(
                "wss://nos.lol" to RelayStatus.CONNECTED,
                "wss://relay.damus.io" to RelayStatus.CONNECTED,
                "wss://relay.primal.net" to RelayStatus.CONNECTED,
            ),
        )
        composeTestRule.onNodeWithTag("relayChip").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 relays").assertIsDisplayed()
    }

    @Test
    fun relayChip_oneRelay_singular() {
        renderHome(
            relayCount = 1,
            relayStatuses = mapOf("wss://nos.lol" to RelayStatus.CONNECTED),
        )
        composeTestRule.onNodeWithText("1 relay").assertIsDisplayed()
    }

    @Test
    fun relayChip_connecting_showsConnectingLabel() {
        renderHome(
            relayCount = 0,
            relayStatuses = mapOf("wss://nos.lol" to RelayStatus.CONNECTING),
        )
        composeTestRule.onNodeWithText("Connecting").assertIsDisplayed()
    }

    @Test
    fun relayChip_disconnected_showsOffline() {
        renderHome(
            relayCount = 0,
            relayStatuses = mapOf(
                "wss://nos.lol" to RelayStatus.DISCONNECTED,
                "wss://relay.damus.io" to RelayStatus.ERROR,
            ),
        )
        composeTestRule.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun relayChip_emptyMap_showsOffline() {
        renderHome(relayCount = 0, relayStatuses = emptyMap())
        composeTestRule.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun ctaGrid_alwaysShowsOpenPsbtAndContacts() {
        renderHome(contactsCount = 5)
        composeTestRule.onNodeWithText("Open PSBT file").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contacts · 5").assertIsDisplayed()
    }

    @Test
    fun nfcButton_hiddenWhenAdapterAbsent() {
        renderHome(nfcAvailable = false)
        composeTestRule.onNodeWithText("NFC passphrase").assertDoesNotExist()
    }

    @Test
    fun nfcButton_visibleWhenAdapterPresent() {
        renderHome(nfcAvailable = true)
        composeTestRule.onNodeWithText("NFC passphrase").assertIsDisplayed()
    }

    @Test
    fun contactsButton_callsOnContacts() {
        var clicked = 0
        renderHome(contactsCount = 2, onContacts = { clicked++ })
        composeTestRule.onNodeWithText("Contacts · 2").performClick()
        assert(clicked == 1)
    }

    @Test
    fun nfcButton_callsOnEncryptPassphrase() {
        var clicked = 0
        renderHome(nfcAvailable = true, onEncryptPassphrase = { clicked++ })
        composeTestRule.onNodeWithText("NFC passphrase").performClick()
        assert(clicked == 1)
    }

    @Test
    fun signerKey_collapsed_hidesFullNpubAndShowsTruncated() {
        renderHome()
        composeTestRule.onNodeWithText("Your signer key").assertIsDisplayed()
        // Collapsed shows the truncated form via Addr (head=4, tail=5 -> "npub1…jug6f")
        composeTestRule.onNodeWithText("npub1…jug6f").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").assertDoesNotExist()
        composeTestRule.onNodeWithText("Share").assertDoesNotExist()
    }

    @Test
    fun signerKey_expanded_revealsFullNpubAndButtons() {
        renderHome()
        composeTestRule.onNodeWithText("Your signer key").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(npub).assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertIsDisplayed()
    }

    @Test
    fun inboxItem_review_callsOnSign() {
        var signed: InboxItemEntity? = null
        renderHome(
            inboxItems = listOf(TestFixtures.sampleInboxItems[0]),
            onSign = { signed = it },
        )
        composeTestRule.onNodeWithText("Review →").performClick()
        assert(signed != null) { "Review should call onSign" }
    }

    @Test
    fun inboxItem_dismiss_callsOnDelete() {
        var deleted: InboxItemEntity? = null
        renderHome(
            inboxItems = listOf(TestFixtures.sampleInboxItems[0]),
            onDelete = { deleted = it },
        )
        composeTestRule.onNodeWithText("Dismiss").performClick()
        assert(deleted != null) { "Dismiss should call onDelete" }
    }

    @Test
    fun inboxItem_signed_showsOpenAndCallsOnItemTap() {
        var tapped: InboxItemEntity? = null
        renderHome(
            inboxItems = listOf(TestFixtures.signedInboxItem),
            onItemTap = { tapped = it },
        )
        composeTestRule.onNodeWithText("Open →").performClick()
        assert(tapped != null) { "Open should call onItemTap" }
    }

    @Test
    fun inboxItem_broadcast_showsTxidLine() {
        renderHome(inboxItems = listOf(TestFixtures.broadcastInboxItem))
        composeTestRule.onNodeWithText(
            "txid: a1b2c3d4…e9f0a1b2",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun inboxItem_deletedItem_hiddenFromList() {
        val deleted = TestFixtures.sampleInboxItems[0].copy(status = InboxStatus.DELETED)
        renderHome(inboxItems = listOf(deleted))
        // Hero should treat as empty
        composeTestRule.onNodeWithText("STANDING BY").assertIsDisplayed()
        composeTestRule.onNodeWithText("No incoming transactions yet.").assertIsDisplayed()
    }
}
