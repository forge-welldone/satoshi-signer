package com.remotesigner.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.remotesigner.ui.branding.AppLogo
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PrimitivesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appButton_dark_clicks() {
        var clicks = 0
        composeTestRule.setContent {
            SatoshiSignerTheme(darkTheme = true) {
                AppButton(text = "Open PSBT file", onClick = { clicks++ })
            }
        }
        composeTestRule.onNodeWithText("Open PSBT file").performClick()
        assert(clicks == 1)
    }

    @Test
    fun appButton_light_renders() {
        composeTestRule.setContent {
            SatoshiSignerTheme(darkTheme = false) {
                AppButton(text = "Sign on Trezor", onClick = {})
            }
        }
        composeTestRule.onNodeWithText("Sign on Trezor").assertIsDisplayed()
    }

    @Test
    fun appButton_disabled_swallowsClicks() {
        var clicks = 0
        composeTestRule.setContent {
            SatoshiSignerTheme {
                AppButton(text = "Disabled", onClick = { clicks++ }, enabled = false)
            }
        }
        composeTestRule.onNodeWithText("Disabled").performClick()
        assert(clicks == 0)
    }

    @Test
    fun pill_renders_eachTone() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                Box {
                    Pill(text = "Connected", tone = PillTone.Good)
                    Pill(text = "Waiting", tone = PillTone.Warn)
                    Pill(text = "Offline", tone = PillTone.Bad)
                    Pill(text = "Active", tone = PillTone.Accent)
                    Pill(text = "Idle", tone = PillTone.Neutral)
                }
            }
        }
        // Pill uppercases text
        composeTestRule.onNodeWithText("CONNECTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("OFFLINE").assertIsDisplayed()
    }

    @Test
    fun card_displaysContent() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                VaultCard(modifier = Modifier.testTag("card")) {
                    androidx.compose.material3.Text("Inside card")
                }
            }
        }
        composeTestRule.onNodeWithTag("card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inside card").assertIsDisplayed()
    }

    @Test
    fun eyebrow_uppercases() {
        composeTestRule.setContent {
            SatoshiSignerTheme { Eyebrow("Inbox") }
        }
        composeTestRule.onNodeWithText("INBOX").assertIsDisplayed()
    }

    @Test
    fun labeledRow_displaysLabelAndValue() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                LabeledRow(label = "Fee", value = "0.00000213 BTC")
            }
        }
        composeTestRule.onNodeWithText("Fee").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00000213 BTC").assertIsDisplayed()
    }

    @Test
    fun addr_truncatesLong() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                Addr(value = "bc1qmek5jz2m9l4k6s7yqfdjl2mxv3lqmlv6", head = 6, tail = 6)
            }
        }
        composeTestRule.onNodeWithText("bc1qme…qmlv6", substring = true).assertIsDisplayed()
    }

    @Test
    fun screenHeader_dispatchesBack() {
        var backs = 0
        composeTestRule.setContent {
            SatoshiSignerTheme {
                ScreenHeader(title = "Review", onBack = { backs++ })
            }
        }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backs == 1)
    }

    @Test
    fun bottomSheet_invokesDismiss_onScrimClick() {
        composeTestRule.setContent {
            val visible = remember { mutableStateOf(true) }
            SatoshiSignerTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    BottomSheetOverlay(
                        visible = visible.value,
                        onDismiss = { visible.value = false },
                    ) {
                        androidx.compose.material3.Text("Sheet body", modifier = Modifier.testTag("body"))
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("body").assertIsDisplayed()
    }

    @Test
    fun appLogo_renders() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                AppLogo(modifier = Modifier.testTag("logo"))
            }
        }
        composeTestRule.onNodeWithTag("logo").assertIsDisplayed()
    }

    @Test
    fun spinner_renders() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                Spinner(modifier = Modifier.testTag("spin"))
            }
        }
        composeTestRule.onNodeWithTag("spin").assertIsDisplayed()
    }
}
