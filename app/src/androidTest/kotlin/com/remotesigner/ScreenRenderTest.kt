package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.ErrorScreen
import com.remotesigner.ui.ResultScreen
import com.remotesigner.ui.SigningScreen
import com.remotesigner.ui.TransactionReviewScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test
class ScreenRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun transactionReviewScreen_displaysDetails() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                TransactionReviewScreen(
                    state = TestFixtures.reviewState,
                    onSign = {},
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun signingScreen_displaysProgress() {
        val state = TestFixtures.signingState
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(state.message).assertIsDisplayed()
    }

    @Test
    fun resultScreen_completedTransaction() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = TestFixtures.resultComplete,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
    }

    @Test
    fun resultScreen_partialSignature() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = TestFixtures.resultPartial,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Signature Added").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export Updated PSBT").assertIsDisplayed()
    }

    @Test
    fun errorScreen_displaysMessageAndButtons() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ErrorScreen(
                    message = TestFixtures.errorState.message,
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText(TestFixtures.errorState.message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy Error").assertIsDisplayed()
    }

    @Test
    fun errorScreen_withSigningLog() {
        val longMessage = "Signing error: timeout\n\n--- Log ---\nOpening USB...\nClaiming interface...\nPython: Parsing PSBT...\nPython: Signing...\nConnection lost"
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ErrorScreen(
                    message = longMessage,
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText(longMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Home").assertIsDisplayed()
    }
}
