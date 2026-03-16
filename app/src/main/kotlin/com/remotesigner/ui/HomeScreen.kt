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
import com.remotesigner.nostr.InboxItem

@Composable
fun HomeScreen(
    npub: String,
    relayCount: Int,
    inboxItems: List<InboxItem>,
    onPsbtSelected: (Uri) -> Unit,
    onSignInboxItem: (InboxItem) -> Unit,
    onDeleteInboxItem: (InboxItem) -> Unit,
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

            Spacer(modifier = Modifier.height(16.dp))

            // Relay status
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            }

            // Inbox
            InboxSection(
                items = inboxItems,
                onSign = onSignInboxItem,
                onDelete = onDeleteInboxItem,
            )

            Spacer(modifier = Modifier.height(32.dp))
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
