package com.remotesigner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit4.runners.AndroidJUnit4
import com.remotesigner.ui.HomeScreen
import com.remotesigner.ui.theme.SatoshiSignerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_displaysTitle() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(onPsbtSelected = {})
            }
        }
        composeTestRule.onNodeWithText("Satoshi Signer").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysOpenButton() {
        composeTestRule.setContent {
            SatoshiSignerTheme {
                HomeScreen(onPsbtSelected = {})
            }
        }
        composeTestRule.onNodeWithText("Open PSBT File").assertIsDisplayed()
    }
}
