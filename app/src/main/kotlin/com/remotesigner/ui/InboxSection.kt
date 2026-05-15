package com.remotesigner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotesigner.data.InboxItemEntity
import com.remotesigner.data.InboxStatus
import com.remotesigner.ui.components.Addr
import com.remotesigner.ui.components.Pill
import com.remotesigner.ui.components.PillTone
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun InboxSection(
    items: List<InboxItemEntity>,
    onSign: (InboxItemEntity) -> Unit,
    onDelete: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit = {},
) {
    if (items.isEmpty()) {
        InboxEmptyCard()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            InboxItemCard(item = item, onSign = onSign, onDelete = onDelete, onItemTap = onItemTap)
        }
    }
}

@Composable
private fun InboxEmptyCard() {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val shapes = LocalVaultShapes.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .dashedBorder(color = colors.lineStrong, shape = shapes.card)
            .padding(horizontal = 16.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No incoming transactions yet.",
            style = typography.bodyDim.copy(color = colors.textMute),
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.dashedBorder(
    color: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.dp,
    dashLength: androidx.compose.ui.unit.Dp = 6.dp,
    gapLength: androidx.compose.ui.unit.Dp = 4.dp,
): Modifier = drawBehind {
    val strokePx = strokeWidth.toPx()
    val dashPx = dashLength.toPx()
    val gapPx = gapLength.toPx()
    val cornerPx = (shape as? RoundedCornerShape)
        ?.let { runCatching { it.topStart.toPx(Size(size.width, size.height), this) }.getOrDefault(0f) }
        ?: 0f
    drawRoundRect(
        color = color,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f),
        ),
    )
}

@Composable
fun InboxItemCard(
    item: InboxItemEntity,
    onSign: (InboxItemEntity) -> Unit,
    onDelete: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit = {},
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val shapes = LocalVaultShapes.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .border(1.dp, colors.line, shapes.card)
            .background(colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "From ",
                            style = typography.bodyDim.copy(color = colors.textDim),
                        )
                        Addr(value = item.senderNpub, head = 8, tail = 4, color = colors.textDim)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val (amount, suffix) = splitAmountAndUnit(item.amount)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = amount,
                            style = typography.display.copy(
                                color = colors.text,
                                fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                        )
                        if (suffix.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = suffix,
                                style = typography.bodyDim.copy(color = colors.textMute),
                            )
                        }
                    }
                }
                StatusPill(item.status)
            }

            if (item.status == InboxStatus.BROADCAST && item.txid != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val context = LocalContext.current
                val txUrl = mempoolTxUrl(item.txid, item.network)
                Text(
                    text = "txid: ${item.txid.take(8)}…${item.txid.takeLast(8)}",
                    style = typography.monoSmall.copy(color = colors.accent),
                    modifier = Modifier
                        .clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(txUrl)))
                        },
                )
            }
        }

        InboxFooter(item = item, onSign = onSign, onDelete = onDelete, onItemTap = onItemTap)
    }
}

@Composable
private fun InboxFooter(
    item: InboxItemEntity,
    onSign: (InboxItemEntity) -> Unit,
    onDelete: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val isActionable = item.status in listOf(InboxStatus.PENDING, InboxStatus.FAILED, InboxStatus.SIGNING)
    val isReopenable = item.status == InboxStatus.SIGNED || item.status == InboxStatus.BROADCAST

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.line, RoundedCornerShape(0.dp))
            .height(44.dp),
    ) {
        when {
            isActionable -> {
                FooterButton(
                    text = "Review →",
                    color = colors.accent,
                    onClick = { onSign(item) },
                    modifier = Modifier.weight(1f),
                )
                FooterDivider()
                FooterButton(
                    text = "Dismiss",
                    color = colors.textDim,
                    onClick = { onDelete(item) },
                    modifier = Modifier.weight(1f),
                )
            }
            isReopenable -> {
                FooterButton(
                    text = "Open →",
                    color = colors.accent,
                    onClick = { onItemTap(item) },
                    modifier = Modifier.weight(1f),
                )
                FooterDivider()
                FooterButton(
                    text = "Dismiss",
                    color = colors.textDim,
                    onClick = { onDelete(item) },
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                FooterButton(
                    text = "Dismiss",
                    color = colors.textDim,
                    onClick = { onDelete(item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FooterButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = LocalVaultTypography.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.bodyDim.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun FooterDivider() {
    val colors = LocalVaultColors.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(colors.line),
    )
}

@Composable
private fun StatusPill(status: InboxStatus) {
    when (status) {
        InboxStatus.PENDING, InboxStatus.FAILED -> Pill(text = "Unsigned", tone = PillTone.Warn)
        InboxStatus.SIGNING -> Pill(text = "Signing", tone = PillTone.Accent)
        InboxStatus.SIGNED -> Pill(text = "Signed", tone = PillTone.Good)
        InboxStatus.BROADCAST -> Pill(text = "Broadcast", tone = PillTone.Good)
        InboxStatus.DELETED -> Pill(text = "Deleted", tone = PillTone.Neutral)
    }
}

private fun splitAmountAndUnit(text: String): Pair<String, String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "" to ""
    val idx = trimmed.indexOf(' ')
    return if (idx <= 0) trimmed to "" else trimmed.substring(0, idx) to trimmed.substring(idx + 1)
}
