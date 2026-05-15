package com.remotesigner.ui.signing

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotesigner.ui.components.Eyebrow
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun TrezorStatusPanel(
    stage: SigningStage,
    connection: TrezorConnection,
    modifier: Modifier = Modifier,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val shapes = LocalVaultShapes.current
    val copy = stageCopy(stage)
    val connected = connection == TrezorConnection.Connected

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .border(1.dp, colors.line, shapes.card)
            .background(colors.surface)
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(shapes.small)
                .border(1.dp, colors.lineStrong, shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            TrezorIcon(
                size = 32.dp,
                color = if (connected) colors.accent else colors.textDim,
                pulse = connected && stage != SigningStage.Waiting,
                pulseColor = colors.accent,
            )
            if (connected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 4.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(colors.good)
                        .border(2.dp, colors.surface, CircleShape),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Eyebrow(text = "Trezor Safe 3 · USB-C")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = copy.title,
                style = typography.title.copy(
                    color = colors.text,
                    fontSize = 19.sp,
                ),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = copy.sub,
                style = typography.bodyDim.copy(color = colors.textDim),
            )
        }
    }
}
