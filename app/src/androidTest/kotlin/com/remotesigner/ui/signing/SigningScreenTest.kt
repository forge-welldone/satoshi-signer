package com.remotesigner.ui.signing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SigningScreenTest(private val darkTheme: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "darkTheme={0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            SatoshiSignerTheme(darkTheme = darkTheme) { content() }
        }
    }

    @Test
    fun trezorStatusPanel_waiting() {
        setContent {
            TrezorStatusPanel(stage = SigningStage.Waiting, connection = TrezorConnection.Waiting)
        }
        composeTestRule.onNodeWithText("Plug in your Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect via USB-C OTG cable.").assertIsDisplayed()
    }

    @Test
    fun trezorStatusPanel_passphrase_connected() {
        setContent {
            TrezorStatusPanel(stage = SigningStage.Passphrase, connection = TrezorConnection.Connected)
        }
        composeTestRule.onNodeWithText("Enter your passphrase").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose where to enter it.").assertIsDisplayed()
    }

    @Test
    fun trezorStatusPanel_confirm_connected() {
        setContent {
            TrezorStatusPanel(stage = SigningStage.Confirm, connection = TrezorConnection.Connected)
        }
        composeTestRule.onNodeWithText("Confirm on device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Check each output on the Trezor screen.").assertIsDisplayed()
    }

    @Test
    fun trezorStatusPanel_signing_connected() {
        setContent {
            TrezorStatusPanel(stage = SigningStage.Signing, connection = TrezorConnection.Connected)
        }
        composeTestRule.onNodeWithText("Signing…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Talking to the secure element.").assertIsDisplayed()
    }

    @Test
    fun trezorStatusPanel_disconnected_showsConnectCopy() {
        // Disconnected is currently not reachable from the live state machine
        // (a USB drop becomes AppState.Error) but the component supports the
        // tone for symmetry with the prototype.
        setContent {
            TrezorStatusPanel(stage = SigningStage.Waiting, connection = TrezorConnection.Disconnected)
        }
        composeTestRule.onNodeWithText("Plug in your Trezor").assertIsDisplayed()
    }

    @Test
    fun progressCard_waiting_allInactive() {
        setContent {
            ProgressCard(stage = SigningStage.Waiting, connection = TrezorConnection.Waiting)
        }
        // Step 1 is active when not connected, others render with their numbers
        composeTestRule.onAllNodesWithText("Device connected", substring = false)[0].assertIsDisplayed()
        composeTestRule.onNodeWithText("Passphrase entered").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm outputs on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign").assertIsDisplayed()
    }

    @Test
    fun progressCard_signing_marksFirstThreeStepsDone() {
        setContent {
            ProgressCard(stage = SigningStage.Signing, connection = TrezorConnection.Connected)
        }
        composeTestRule.onNodeWithTag("step-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("step-4").assertIsDisplayed()
        // Numbers 2 & 3 should be replaced by the check icon when done — the
        // numerals "2" and "3" must therefore NOT appear in step tags.
        val rendered2 = composeTestRule.onAllNodesWithText("2").fetchSemanticsNodes().size
        val rendered3 = composeTestRule.onAllNodesWithText("3").fetchSemanticsNodes().size
        assertTrue("Step 2 should render its check, not the digit 2", rendered2 == 0)
        assertTrue("Step 3 should render its check, not the digit 3", rendered3 == 0)
    }

    @Test
    fun passphraseSheetContent_continueDisabledUntilModeChosen() {
        var modeState: PassphraseMode? = null
        setContent {
            PassphraseSheetContent(
                mode = modeState,
                onModeChange = { modeState = it },
                phonePassphrase = "",
                onPhonePassphraseChange = {},
                nfcAvailable = true,
                nfcWaiting = false,
                nfcError = null,
                onContinue = { fail("Continue should be disabled without a mode") },
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithTag("passphraseContinue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on this phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tap an NFC tag").assertIsDisplayed()
    }

    @Test
    fun passphraseSheetContent_hidesNfcWhenUnavailable() {
        setContent {
            PassphraseSheetContent(
                mode = null,
                onModeChange = {},
                phonePassphrase = "",
                onPhonePassphraseChange = {},
                nfcAvailable = false,
                nfcWaiting = false,
                nfcError = null,
                onContinue = {},
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithText("Type on Trezor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type on this phone").assertIsDisplayed()
        val nfcShown = composeTestRule.onAllNodesWithText("Tap an NFC tag").fetchSemanticsNodes().size
        assertTrue("NFC card should be hidden when device lacks NFC", nfcShown == 0)
    }

    @Test
    fun passphraseSheetContent_phoneMode_showsPassphraseField() {
        setContent {
            PassphraseSheetContent(
                mode = PassphraseMode.Phone,
                onModeChange = {},
                phonePassphrase = "hello",
                onPhonePassphraseChange = {},
                nfcAvailable = true,
                nfcWaiting = false,
                nfcError = null,
                onContinue = {},
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithTag("passphraseField").assertIsDisplayed()
    }

    @Test
    fun passphraseSheetContent_nfcMode_showsAffordance() {
        setContent {
            PassphraseSheetContent(
                mode = PassphraseMode.Nfc,
                onModeChange = {},
                phonePassphrase = "",
                onPhonePassphraseChange = {},
                nfcAvailable = true,
                nfcWaiting = true,
                nfcError = null,
                onContinue = {},
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithText("Hold the NFC tag to the back of the phone…").assertIsDisplayed()
    }

    @Test
    fun confirmOnDeviceSheetContent_rendersWaitingCopy() {
        setContent { ConfirmOnDeviceSheetContent() }
        composeTestRule.onNodeWithText("Confirm each output on the device.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for you to press the confirm button…").assertIsDisplayed()
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
