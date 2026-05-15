package com.remotesigner.ui.signing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotesigner.ui.components.Eyebrow
import com.remotesigner.ui.components.Spinner
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun ConfirmOnDeviceSheetContent(modifier: Modifier = Modifier) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHandleConfirm()
        Eyebrow(text = "On your Trezor")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Confirm each output on the device.",
            style = typography.title.copy(
                color = colors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spinner(size = 18.dp)
            Text(
                text = "Waiting for you to press the confirm button…",
                style = typography.monoSmall.copy(color = colors.textDim),
            )
        }
    }
}

@Composable
private fun SheetHandleConfirm() {
    val colors = LocalVaultColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(colors.lineStrong),
        )
    }
}
