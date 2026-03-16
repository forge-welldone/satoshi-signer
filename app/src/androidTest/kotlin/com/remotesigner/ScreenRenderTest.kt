package com.remotesigner

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun signingScreen_displaysLogAndCopyButton() {
        val state = TestFixtures.signingStateWithLog
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
        composeTestRule.onNodeWithText("Debug Log:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText(state.log).assertIsDisplayed()
    }

    @Test
    fun signingScreen_displaysCancelButton() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseDialog_onDeviceAvailable() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Passphrase Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose where to enter your passphrase:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
        // Both the dialog and SigningScreen have a "Cancel" — assert both exist
        composeTestRule.onAllNodesWithText("Cancel").assertCountEquals(2)
    }

    @Test
    fun signingScreen_passphraseDialog_phoneOnly() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestPhoneOnly,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Passphrase Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Less secure than on-device entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseDialog_switchToTextField() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    onCancel = {},
                )
            }
        }
        // Start on choice screen
        composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
        // Tap "Enter on phone" to switch to text field
        composeTestRule.onNodeWithText("Enter on phone").performClick()
        composeTestRule.waitForIdle()
        // Text field and Submit should now appear
        composeTestRule.onNodeWithText("Less secure than on-device entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseDialog_backToChoices() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    onCancel = {},
                )
            }
        }
        // Switch to text field
        composeTestRule.onNodeWithText("Enter on phone").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Submit").assertIsDisplayed()
        // Tap "Back" to return to choices
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        // Choice screen should be back
        composeTestRule.onNodeWithText("Enter on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter on phone").assertIsDisplayed()
    }
}
