package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.RelayStatus

@Composable
fun HomeScreen(
    npub: String,
    relayCount: Int,
    relayStatuses: Map<String, RelayStatus>,
    inboxItems: List<InboxItemEntity>,
    onPsbtSelected: (Uri) -> Unit,
    onSignInboxItem: (InboxItemEntity) -> Unit,
    onDeleteInboxItem: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit = {},
    onContacts: () -> Unit = {},
    onEncryptPassphrase: () -> Unit = {},
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onPsbtSelected(it) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Satoshi Signer",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Sign Bitcoin transactions with your Trezor",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // QR code of npub
            QrCodeImage(data = npub, size = 200.dp)

            Spacer(modifier = Modifier.height(8.dp))

            // npub with copy button
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    npub,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("npub", npub))
                    Toast.makeText(context, "Copied npub", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { launcher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open PSBT File")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onContacts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Contacts")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onEncryptPassphrase,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Encrypt Passphrase for NFC")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Relay status (tappable to expand)
            var relaysExpanded by remember { mutableStateOf(false) }
            TextButton(onClick = { relaysExpanded = !relaysExpanded }) {
                Text(
                    "\u25CF ",
                    color = if (relayCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (relayCount > 0) "$relayCount relay${if (relayCount > 1) "s" else ""} connected"
                    else "No relays connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (relaysExpanded) " \u25B2" else " \u25BC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (relaysExpanded) {
                RelayList(relayStatuses)
            }

            // Inbox
            InboxSection(
                items = inboxItems,
                onSign = onSignInboxItem,
                onDelete = onDeleteInboxItem,
                onItemTap = onItemTap,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RelayList(statuses: Map<String, RelayStatus>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            statuses.forEach { (url, status) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        url.removePrefix("wss://"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        when (status) {
                            RelayStatus.CONNECTED -> "\u25CF connected"
                            RelayStatus.CONNECTING -> "\u25CB connecting..."
                            RelayStatus.DISCONNECTED -> "\u25CB disconnected"
                            RelayStatus.ERROR -> "\u25CF error"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (status) {
                            RelayStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            RelayStatus.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant
                            RelayStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                            RelayStatus.ERROR -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeImage(data: String, size: Dp) {
    val bitmap = remember(data) {
        val pixels = 512
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, pixels, pixels)
        val bmp = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.RGB_565)
        for (x in 0 until pixels) {
            for (y in 0 until pixels) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Nostr public key QR code",
        modifier = Modifier.size(size),
    )
}
