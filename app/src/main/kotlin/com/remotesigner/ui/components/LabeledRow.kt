package com.remotesigner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun LabeledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    mono: Boolean = true,
    valueColor: Color? = null,
    showDivider: Boolean = true,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = typography.bodyDim.copy(color = colors.textDim),
                )
                if (sub != null) {
                    Text(
                        text = sub,
                        style = typography.caption.copy(color = colors.textMute),
                    )
                }
            }
            Text(
                text = value,
                style = (if (mono) typography.mono else typography.body)
                    .copy(color = valueColor ?: colors.text),
                textAlign = TextAlign.End,
            )
        }
        if (showDivider) {
            HorizontalDivider(thickness = 1.dp, color = colors.line)
        }
    }
}
