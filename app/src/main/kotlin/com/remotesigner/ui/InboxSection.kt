package com.remotesigner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatRelativeTime

@Composable
fun InboxSection(
    items: List<InboxItemEntity>,
    onSign: (InboxItemEntity) -> Unit,
    onDelete: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit = {},
) {
    if (items.isEmpty()) return

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text("Inbox", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    items.forEach { item ->
        InboxItemCard(item = item, onSign = onSign, onDelete = onDelete, onItemTap = onItemTap)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun InboxItemCard(
    item: InboxItemEntity,
    onSign: (InboxItemEntity) -> Unit,
    onDelete: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit = {},
) {
    val isTappable = item.status == InboxStatus.SIGNED || item.status == InboxStatus.BROADCAST

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isTappable) Modifier.clickable { onItemTap(item) } else Modifier),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: label + status chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(item.status)
            }

            if (item.amount.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.amount, style = MaterialTheme.typography.bodyMedium)
            }

            // Txid link for broadcast items
            if (item.status == InboxStatus.BROADCAST && item.txid != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val context = LocalContext.current
                val txUrl = mempoolTxUrl(item.txid, item.network)
                Text(
                    "txid: ${item.txid.take(8)}...${item.txid.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(txUrl)))
                    },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "from ${item.senderNpub}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatRelativeTime(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (item.status == InboxStatus.PENDING || item.status == InboxStatus.FAILED) {
                    OutlinedButton(onClick = { onSign(item) }) {
                        Text("Sign")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onDelete(item) }) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: InboxStatus) {
    val (label, color) = when (status) {
        InboxStatus.PENDING -> "pending" to MaterialTheme.colorScheme.primary
        InboxStatus.SIGNING -> "signing" to MaterialTheme.colorScheme.tertiary
        InboxStatus.SIGNED -> "signed" to MaterialTheme.colorScheme.secondary
        InboxStatus.BROADCAST -> "broadcast" to MaterialTheme.colorScheme.secondary
        InboxStatus.FAILED -> "failed" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
