package com.remotesigner.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.motion.tweenFade
import com.remotesigner.ui.motion.tweenSlideUp
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes

const val BOTTOM_SHEET_SCRIM_TAG = "bottomSheetScrim"

/**
 * Vault-styled modal sheet. Place it as the last child of a full-screen `Box`
 * overlay slot — the overlay measures at full size even while hidden, so it is
 * not meant to sit inside a `Column`/`Row`.
 *
 * Dismissal: scrim tap and system back both call [onDismiss]. The sheet body
 * consumes taps so touches inside it never reach the scrim.
 *
 * Known limitation: the overlay lives in the host window (no Popup), so
 * TalkBack can still traverse content behind the open sheet. Full modality
 * needs a Popup/Dialog rewrite, which requires on-device validation.
 */
@Composable
fun BottomSheetOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    val noIndication = remember { MutableInteractionSource() }
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tweenFade()),
            exit = fadeOut(animationSpec = tweenFade()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BOTTOM_SHEET_SCRIM_TAG)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = noIndication,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Dismiss",
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tweenSlideUp()),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tweenSlideUp()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = shapes.radiusCard, topEnd = shapes.radiusCard))
                    .background(colors.surfaceElev)
                    // Consume taps: clip/background don't block hit-testing, so
                    // without this, taps on the sheet body would fall through to
                    // the scrim's clickable and dismiss the sheet.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = "Sheet" }
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(20.dp),
            ) {
                content()
            }
        }
    }
}
