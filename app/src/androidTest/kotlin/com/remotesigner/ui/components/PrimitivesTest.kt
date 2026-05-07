package com.remotesigner.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.remotesigner.ui.branding.AppLogo
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Each primitive renders in both `darkTheme = true` and `darkTheme = false`
 * so light-only or dark-only regressions surface in CI. The class is
 * parameterised over `darkTheme` and every test goes through
 * [setThemedContent], so adding a new primitive test gives both modes for
 * free.
 */
@RunWith(Parameterized::class)
class PrimitivesTest(private val darkTheme: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "darkTheme={0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setThemedContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            SatoshiSignerTheme(darkTheme = darkTheme) { content() }
        }
    }

    @Test
    fun appButton_clicks() {
        var clicks = 0
        setThemedContent {
            AppButton(text = "Open PSBT file", onClick = { clicks++ })
        }
        composeTestRule.onNodeWithText("Open PSBT file").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun appButton_renders_allVariants() {
        setThemedContent {
            Box(Modifier.testTag("variants")) {
                AppButton(text = "Primary", onClick = {}, variant = AppButtonVariant.Primary)
                AppButton(text = "Secondary", onClick = {}, variant = AppButtonVariant.Secondary)
                AppButton(text = "Ghost", onClick = {}, variant = AppButtonVariant.Ghost)
                AppButton(text = "Danger", onClick = {}, variant = AppButtonVariant.Danger)
            }
        }
        composeTestRule.onNodeWithTag("variants").assertIsDisplayed()
    }

    @Test
    fun appButton_disabled_exposesNoClickAction() {
        // Compose's `clickable(enabled = false)` strips the OnClick semantics
        // action, so calling performClick() on the disabled node would fail
        // the test outright. Verify the disabled state instead.
        setThemedContent {
            AppButton(text = "Disabled", onClick = {}, enabled = false)
        }
        composeTestRule.onNodeWithText("Disabled")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertHasNoClickAction()
    }

    @Test
    fun pill_renders_eachTone() {
        setThemedContent {
            Box {
                Pill(text = "Connected", tone = PillTone.Good)
                Pill(text = "Waiting", tone = PillTone.Warn)
                Pill(text = "Offline", tone = PillTone.Bad)
                Pill(text = "Active", tone = PillTone.Accent)
                Pill(text = "Idle", tone = PillTone.Neutral)
            }
        }
        composeTestRule.onNodeWithText("CONNECTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("OFFLINE").assertIsDisplayed()
    }

    @Test
    fun card_displaysContent() {
        setThemedContent {
            VaultCard(modifier = Modifier.testTag("card")) {
                androidx.compose.material3.Text("Inside card")
            }
        }
        composeTestRule.onNodeWithTag("card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inside card").assertIsDisplayed()
    }

    @Test
    fun eyebrow_uppercases() {
        setThemedContent { Eyebrow("Inbox") }
        composeTestRule.onNodeWithText("INBOX").assertIsDisplayed()
    }

    @Test
    fun labeledRow_displaysLabelAndValue() {
        setThemedContent {
            LabeledRow(label = "Fee", value = "0.00000213 BTC")
        }
        composeTestRule.onNodeWithText("Fee").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00000213 BTC").assertIsDisplayed()
    }

    @Test
    fun addr_truncatesLong() {
        setThemedContent {
            Addr(value = "bc1qmek5jz2m9l4k6s7yqfdjl2mxv3lqmlv6", head = 6, tail = 6)
        }
        composeTestRule.onNodeWithText("bc1qme…qmlv6", substring = true).assertIsDisplayed()
    }

    @Test
    fun screenHeader_dispatchesBack() {
        var backs = 0
        setThemedContent {
            ScreenHeader(title = "Review", onBack = { backs++ })
        }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun bottomSheet_invokesDismiss_onScrimClick() {
        var dismissed = 0
        setThemedContent {
            val visible = remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize().testTag("root")) {
                BottomSheetOverlay(
                    visible = visible.value,
                    onDismiss = {
                        dismissed++
                        visible.value = false
                    },
                ) {
                    androidx.compose.material3.Text(
                        "Sheet body",
                        modifier = Modifier.testTag("body"),
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("body").assertIsDisplayed()
        composeTestRule.onNodeWithTag(BOTTOM_SHEET_SCRIM_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun appLogo_renders() {
        setThemedContent {
            AppLogo(modifier = Modifier.testTag("logo"))
        }
        composeTestRule.onNodeWithTag("logo").assertIsDisplayed()
    }

    @Test
    fun spinner_renders() {
        setThemedContent {
            Spinner(modifier = Modifier.testTag("spin"))
        }
        composeTestRule.onNodeWithTag("spin").assertIsDisplayed()
    }
}
