package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.ui.EncryptPassphraseScreen
import com.remotesigner.viewmodel.AppState
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
                    contacts = emptyList(),
                    onSign = {},
                    onCancel = {},
                    onSaveContact = { _, _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()
    }

    @Test
    fun transactionReviewScreen_displaysOpReturn() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                TransactionReviewScreen(
                    state = TestFixtures.reviewStateWithOpReturn,
                    contacts = emptyList(),
                    onSign = {},
                    onCancel = {},
                    onSaveContact = { _, _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText("OP_RETURN:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your ad here - https://aads.com/").assertIsDisplayed()
    }

    @Test
    fun transactionReviewScreen_displaysDescription() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                TransactionReviewScreen(
                    state = TestFixtures.reviewStateWithDescription,
                    contacts = emptyList(),
                    onSign = {},
                    onCancel = {},
                    onSaveContact = { _, _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText("Payment for server hosting — March 2026")
            .assertIsDisplayed()
    }

    @Test
    fun transactionReviewScreen_hidesDescriptionWhenNull() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                TransactionReviewScreen(
                    state = TestFixtures.reviewState,
                    contacts = emptyList(),
                    onSign = {},
                    onCancel = {},
                    onSaveContact = { _, _, _ -> },
                )
            }
        }
        // reviewState has description = null (default)
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Payment for server hosting — March 2026")
            .assertDoesNotExist()
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
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Signing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trezor Safe 3 · USB-C").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stay offline").assertIsDisplayed()
    }

    @Test
    fun resultScreen_completedTransaction() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = TestFixtures.resultComplete,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onSavePsbt = {},
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
                    onSavePsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Signature Added").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share Updated PSBT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save to Phone").assertIsDisplayed()
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
    }

    @Test
    fun signingScreen_logHiddenByDefault() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Show log").assertIsDisplayed()
        composeTestRule.onNodeWithText("Debug log").assertDoesNotExist()
        composeTestRule.onNodeWithText(state.log).assertDoesNotExist()
    }

    @Test
    fun signingScreen_showLogRevealsDebugLog() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Show log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hide log").assertIsDisplayed()
        composeTestRule.onNodeWithText("DEBUG LOG").assertIsDisplayed()
        composeTestRule.onNodeWithText(state.log).assertIsDisplayed()
    }

    @Test
    fun signingScreen_hideLogCollapsesDebugLog() {
        val state = TestFixtures.signingStateWithLog
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = state.message,
                    log = state.log,
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Show log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hide log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Show log").assertIsDisplayed()
        composeTestRule.onNodeWithText("DEBUG LOG").assertDoesNotExist()
        composeTestRule.onNodeWithText(state.log).assertDoesNotExist()
    }

    @Test
    fun signingScreen_displaysCancelButton() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = null,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Cancel signing").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseSheet_onDeviceAvailable() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("PASSPHRASE REQUIRED").assertIsDisplayed()
        composeTestRule.onNodeWithText("Where do you want to enter it?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on this phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
        // The sheet's "Cancel" plus the screen's "Cancel signing"
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel signing").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseSheet_phoneOnly_preselectsPhoneAndShowsField() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestPhoneOnly,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("PASSPHRASE REQUIRED").assertIsDisplayed()
        composeTestRule.onNodeWithTag("passphraseField").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseSheet_phoneMode_revealsPassphraseField() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    accountPathRequest = null,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Type on this phone").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("passphraseField").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseSheet_showsNfcOption_whenAvailable() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    accountPathRequest = null,
                    nfcAvailable = true,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Type on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on this phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tap an NFC tag").assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseSheet_hidesNfcOption_whenUnavailable() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    accountPathRequest = null,
                    nfcAvailable = false,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Type on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on this phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tap an NFC tag").assertDoesNotExist()
    }

    @Test
    fun signingScreen_passphraseSheet_nfcMode_showsAffordance() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    accountPathRequest = null,
                    nfcAvailable = true,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Tap an NFC tag").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "Tap Continue, then hold the NFC tag to the back of the phone.",
        ).assertIsDisplayed()
    }

    @Test
    fun signingScreen_passphraseSheet_nfcErrorShown() {
        var nfcTagResult by mutableStateOf<NfcReadResult?>(null)

        composeTestRule.setContent {
            SatoshiSignerTheme {
                SigningScreen(
                    message = "Signing...",
                    log = "",
                    passphraseRequest = TestFixtures.passphraseRequestOnDevice,
                    accountPathRequest = null,
                    nfcAvailable = true,
                    nfcTagResult = nfcTagResult,
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Tap an NFC tag").performClick()
        composeTestRule.runOnIdle {
            nfcTagResult = NfcReadResult.Error("No text found on tag")
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No text found on tag").assertIsDisplayed()
    }

    @Test
    fun encryptPassphraseScreen_rendersInitialState() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                EncryptPassphraseScreen(
                    onEncrypt = { it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Encrypt Passphrase").assertIsDisplayed()
        composeTestRule.onNodeWithText("Encrypt").assertIsDisplayed()
    }

    @Test
    fun encryptPassphraseScreen_showsResultAfterEncrypt() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                EncryptPassphraseScreen(
                    onEncrypt = { "fakeCipherText?iv=fakeIv" },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Passphrase").performClick()
        composeTestRule.onNodeWithText("Passphrase")
            .performTextInput("test passphrase")
        composeTestRule.onNodeWithText("Encrypt").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("fakeCipherText?iv=fakeIv").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy to Clipboard").assertIsDisplayed()
    }

    @Test
    fun resultScreen_testnet_showsThreeBroadcastButtons() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = TestFixtures.resultCompleteTestnet,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onSavePsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Broadcast to Testnet4").assertIsDisplayed()
        composeTestRule.onNodeWithText("Broadcast to Testnet3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Broadcast to Signet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Broadcast Transaction").assertDoesNotExist()
    }

    @Test
    fun resultScreen_mainnet_showsSingleBroadcastButton() {
        val mainnetResult = AppState.Result(
            isComplete = true,
            rawHex = "0200000001deadbeef",
            network = "main",
        )
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ResultScreen(
                    state = mainnetResult,
                    onBroadcast = {},
                    onExportPsbt = {},
                    onSavePsbt = {},
                    onHome = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Broadcast Transaction").assertIsDisplayed()
        composeTestRule.onNodeWithText("Broadcast to Testnet4").assertDoesNotExist()
    }
}
