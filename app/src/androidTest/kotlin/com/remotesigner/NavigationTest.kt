package com.remotesigner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.HomeScreen
import com.remotesigner.ui.ResultScreen
import com.remotesigner.ui.SigningScreen
import com.remotesigner.ui.TransactionReviewScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.viewmodel.AppState
import org.junit.Rule
import org.junit.Test
class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stateChange_showsCorrectScreen() {
        var state by mutableStateOf<AppState>(AppState.Home)

        composeTestRule.setContent {
            SatoshiSignerTheme {
                when (val current = state) {
                    is AppState.Home -> HomeScreen(onPsbtSelected = {})
                    is AppState.TransactionReview -> TransactionReviewScreen(
                        state = current,
                        onSign = {},
                        onCancel = {},
                    )
                    is AppState.Signing -> SigningScreen(
                        message = current.message,
                        log = current.log,
                        passphraseRequest = null,
                        onCancel = {},
                    )
                    is AppState.Result -> ResultScreen(
                        state = current,
                        onBroadcast = {},
                        onExportPsbt = {},
                        onSavePsbt = {},
                        onHome = {},
                    )
                    is AppState.Error -> Unit
                }
            }
        }

        // Home screen is shown initially
        composeTestRule.onNodeWithText("Satoshi Signer").assertIsDisplayed()

        // Transition to TransactionReview
        state = TestFixtures.reviewState
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()

        // Transition to Signing
        state = TestFixtures.signingState
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(TestFixtures.signingState.message).assertIsDisplayed()

        // Transition to Result (complete)
        state = TestFixtures.resultComplete
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
    }
}
