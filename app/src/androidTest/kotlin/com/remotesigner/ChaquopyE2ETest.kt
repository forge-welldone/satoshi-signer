package com.remotesigner

import android.app.Application
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerViewModel
import com.remotesigner.viewmodel.SignerViewModelFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end tests that exercise the full Kotlin → Chaquopy → Python → PlaybackBridge
 * → Compose UI chain on the Android emulator.
 *
 * Uses recorded USB cassettes from androidTest/assets/cassettes/ so no Trezor
 * hardware is needed. Proves that Chaquopy initialization, Python module imports,
 * PythonBridge JSON round-trip, and the Compose state machine all work together.
 *
 * Note: signWithBridge() passes null callback to Python so AndroidTrezorUi
 * falls back to on-device passphrase automatically (no blocking UI dialog).
 */
@LargeTest
class ChaquopyE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: SignerViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = SignerViewModelFactory(app).create(SignerViewModel::class.java)
    }

    @Test
    fun singleSigP2wpkh_parseThenSign() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val playbackBridge = PlaybackBridge.fromAsset(context, "single-sig-p2wpkh.json")
        val psbtBytes = Base64.decode(playbackBridge.inputPsbtB64, Base64.DEFAULT)

        composeTestRule.setContent {
            SatoshiSignerTheme { AppRoot(viewModel = viewModel) }
        }

        // Load PSBT → Chaquopy parse_psbt() → TransactionReview
        viewModel.loadPsbt(psbtBytes)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            viewModel.state.value is AppState.TransactionReview
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign with Trezor").assertIsDisplayed()

        // Sign with cassette replay → Chaquopy sign_psbt() → Result
        viewModel.signWithBridge(playbackBridge, psbtBytes, playbackBridge.network)
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            viewModel.state.value is AppState.Result || viewModel.state.value is AppState.Error
        }

        // Verify signing succeeded (complete or partial depending on cassette)
        val state = viewModel.state.value
        if (state is AppState.Error) {
            throw AssertionError("Signing failed with error: ${state.message}")
        }
        val resultState = state as AppState.Result
        if (resultState.isComplete) {
            composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
        } else {
            composeTestRule.onNodeWithText("Signature Added").assertIsDisplayed()
        }
        playbackBridge.assertConsumed()
    }

    @Test
    fun multisigTestnet3_parseThenSign() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val playbackBridge = PlaybackBridge.fromAsset(context, "multisig-testnet3.json")
        val psbtBytes = Base64.decode(playbackBridge.inputPsbtB64, Base64.DEFAULT)

        composeTestRule.setContent {
            SatoshiSignerTheme { AppRoot(viewModel = viewModel) }
        }

        // Load PSBT → Chaquopy parse_psbt() → TransactionReview
        viewModel.loadPsbt(psbtBytes)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            viewModel.state.value is AppState.TransactionReview
        }
        composeTestRule.onNodeWithText("Transaction Details").assertIsDisplayed()

        // Sign with cassette replay → Chaquopy sign_psbt() → Result
        viewModel.signWithBridge(playbackBridge, psbtBytes, playbackBridge.network)
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            viewModel.state.value is AppState.Result || viewModel.state.value is AppState.Error
        }

        // Verify signing succeeded
        val state = viewModel.state.value
        if (state is AppState.Error) {
            throw AssertionError("Signing failed with error: ${state.message}")
        }
        val resultState = state as AppState.Result
        if (resultState.isComplete) {
            composeTestRule.onNodeWithText("Transaction Signed").assertIsDisplayed()
        } else {
            composeTestRule.onNodeWithText("Signature Added").assertIsDisplayed()
        }
        playbackBridge.assertConsumed()
    }
}
