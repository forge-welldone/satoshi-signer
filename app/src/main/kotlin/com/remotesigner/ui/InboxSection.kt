package com.remotesigner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotesigner.nostr.InboxItem
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatRelativeTime

@Composable
fun InboxSection(
    items: List<InboxItem>,
    onSign: (InboxItem) -> Unit,
    onDelete: (InboxItem) -> Unit,
) {
    if (items.isEmpty()) return

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text("Inbox", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    items.forEach { item ->
        InboxItemCard(item = item, onSign = onSign, onDelete = onDelete)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun InboxItemCard(
    item: InboxItem,
    onSign: (InboxItem) -> Unit,
    onDelete: (InboxItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(item.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatRelativeTime(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.amount.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.amount, style = MaterialTheme.typography.bodyMedium)
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
                    item.status.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (item.status) {
                        InboxStatus.PENDING -> MaterialTheme.colorScheme.primary
                        InboxStatus.SIGNING -> MaterialTheme.colorScheme.tertiary
                        InboxStatus.SIGNED -> MaterialTheme.colorScheme.secondary
                        InboxStatus.BROADCAST -> MaterialTheme.colorScheme.secondary
                        InboxStatus.FAILED -> MaterialTheme.colorScheme.error
                    },
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
