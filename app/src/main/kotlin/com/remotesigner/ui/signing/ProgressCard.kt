package com.remotesigner.ui.signing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.components.Eyebrow
import com.remotesigner.ui.components.Spinner
import com.remotesigner.ui.icons.AppIcons
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

private data class StepState(val number: Int, val label: String, val done: Boolean, val active: Boolean)

private fun steps(stage: SigningStage, connection: TrezorConnection): List<StepState> {
    val connected = connection == TrezorConnection.Connected
    return listOf(
        StepState(
            number = 1,
            label = "Device connected",
            done = connected,
            active = !connected,
        ),
        StepState(
            number = 2,
            label = "Passphrase entered",
            done = stage == SigningStage.Confirm || stage == SigningStage.Signing,
            active = stage == SigningStage.Passphrase,
        ),
        StepState(
            number = 3,
            label = "Confirm outputs on Trezor",
            done = stage == SigningStage.Signing,
            active = stage == SigningStage.Confirm,
        ),
        StepState(
            number = 4,
            label = "Sign",
            done = false,
            active = stage == SigningStage.Signing,
        ),
    )
}

@Composable
fun ProgressCard(
    stage: SigningStage,
    connection: TrezorConnection,
    modifier: Modifier = Modifier,
) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    val rows = steps(stage, connection)
    Column(modifier = modifier.fillMaxWidth()) {
        Eyebrow(text = "Progress")
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.card)
                .border(1.dp, colors.line, shapes.card)
                .background(colors.surface),
        ) {
            rows.forEachIndexed { index, row ->
                StepRow(row)
                if (index < rows.lastIndex) {
                    HorizontalDivider(thickness = 1.dp, color = colors.line)
                }
            }
        }
    }
}

@Composable
private fun StepRow(state: StepState) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("step-${state.number}")
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .alpha(if (state.done || state.active) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepBadge(state)
        Text(
            text = state.label,
            style = typography.bodyDim.copy(color = colors.text),
            modifier = Modifier.weight(1f),
        )
        if (state.active) {
            Spinner(size = 14.dp)
        }
    }
}

@Composable
private fun StepBadge(state: StepState) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val borderColor = when {
        state.done -> colors.good
        state.active -> colors.accent
        else -> colors.lineStrong
    }
    val background = if (state.done) colors.good else Color.Transparent
    val numberColor = when {
        state.done -> colors.accentInk
        state.active -> colors.accent
        else -> colors.textMute
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (state.done) {
            val painter = rememberVectorPainter(image = AppIcons.Check)
            Image(
                painter = painter,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.accentInk),
                modifier = Modifier.size(12.dp),
            )
        } else {
            Text(
                text = state.number.toString(),
                style = typography.monoSmall.copy(color = numberColor),
            )
        }
    }
}
