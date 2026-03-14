package com.remotesigner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.remotesigner.viewmodel.AppState

@Composable
fun ResultScreen(
    state: AppState.Result,
    onBroadcast: () -> Unit,
    onExportPsbt: (ByteArray) -> Unit,
    onHome: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var broadcastClicked by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.isComplete) {
                Text("Transaction Signed", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))

                if (state.txid != null) {
                    Text("Broadcast successful!")
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text("txid: ${state.txid}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    state.broadcastStatus?.let {
                        Text(it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            broadcastClicked = true
                            onBroadcast()
                        },
                        enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Broadcast Transaction")
                    }
                }

                state.rawHex?.let { hex ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Raw transaction (for manual broadcast):", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(hex)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy Raw Hex to Clipboard")
                    }
                }
            } else {
                Text("Signature Added", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("The transaction needs more signatures before it can be broadcast.")
                Spacer(modifier = Modifier.height(16.dp))

                state.updatedPsbt?.let { psbt ->
                    Button(
                        onClick = { onExportPsbt(psbt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export Updated PSBT")
                    }
                }
            }

            state.errorMessage?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Home")
            }
        }
    }
}
