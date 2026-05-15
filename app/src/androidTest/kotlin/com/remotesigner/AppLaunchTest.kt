package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.HomeScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test

class AppLaunchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_displaysTitle() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(
                    npub = "npub1test",
                    relayCount = 0,
                    relayStatuses = emptyMap(),
                    inboxItems = emptyList(),
                    onPsbtSelected = {},
                    onSignInboxItem = {},
                    onDeleteInboxItem = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Satoshi Signer").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysOpenButton() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(
                    npub = "npub1test",
                    relayCount = 0,
                    relayStatuses = emptyMap(),
                    inboxItems = emptyList(),
                    onPsbtSelected = {},
                    onSignInboxItem = {},
                    onDeleteInboxItem = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Open PSBT file").assertIsDisplayed()
    }
}
