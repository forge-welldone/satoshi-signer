package com.remotesigner.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.remotesigner.viewmodel.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    state: AppState.Result,
    onBroadcast: (String) -> Unit,
    onExportPsbt: (ByteArray) -> Unit,
    onSavePsbt: (ByteArray) -> Unit,
    onHome: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var broadcastClicked by remember { mutableStateOf(false) }

    BackHandler(onBack = onHome)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isComplete) "Transaction Signed" else "Signature Added") },
                navigationIcon = {
                    IconButton(onClick = onHome) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.isComplete) {
                if (state.txid != null) {
                    Text("Broadcast successful!")
                    Spacer(modifier = Modifier.height(8.dp))
                    val context = LocalContext.current
                    val txUrl = mempoolTxUrl(state.txid, state.network)
                    SelectionContainer {
                        Text(
                            "txid: ${state.txid}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(txUrl)))
                            },
                        )
                    }
                } else {
                    state.broadcastStatus?.let {
                        Text(it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    val isTestnet = state.network == "test"
                    if (isTestnet) {
                        Button(
                            onClick = {
                                broadcastClicked = true
                                onBroadcast("testnet4")
                            },
                            enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Broadcast to Testnet4")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                broadcastClicked = true
                                onBroadcast("testnet3")
                            },
                            enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Broadcast to Testnet3")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                broadcastClicked = true
                                onBroadcast("signet")
                            },
                            enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Broadcast to Signet")
                        }
                    } else {
                        Button(
                            onClick = {
                                broadcastClicked = true
                                onBroadcast(state.network)
                            },
                            enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Broadcast Transaction")
                        }
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
                Text("The transaction needs more signatures before it can be broadcast.")
                Spacer(modifier = Modifier.height(16.dp))

                state.updatedPsbt?.let { psbt ->
                    Button(
                        onClick = { onExportPsbt(psbt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share Updated PSBT")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onSavePsbt(psbt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save to Phone")
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

        }
    }
}
