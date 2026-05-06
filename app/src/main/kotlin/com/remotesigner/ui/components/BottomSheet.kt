package com.remotesigner.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.motion.tweenFade
import com.remotesigner.ui.motion.tweenSlideUp
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes

const val BOTTOM_SHEET_SCRIM_TAG = "bottomSheetScrim"

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
                    .padding(20.dp),
            ) {
                content()
            }
        }
    }
}
