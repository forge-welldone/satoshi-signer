package com.remotesigner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxInput
import com.remotesigner.viewmodel.TxOutput

@Composable
fun TransactionReviewScreen(
    state: AppState.TransactionReview,
    onSign: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Transaction Details", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            state.warnings.forEach { warning ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(warning, modifier = Modifier.padding(12.dp))
                }
            }

            Text("From:", style = MaterialTheme.typography.titleMedium)
            state.inputs.forEach { inp ->
                InputRow(inp, state.network)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("To:", style = MaterialTheme.typography.titleMedium)
            state.outputs.filter { !it.isChange && it.opReturn == null }.forEach { out ->
                OutputRow(out, prefix = "\u2192", network = state.network)
            }

            val opReturnOutputs = state.outputs.filter { it.opReturn != null }
            if (opReturnOutputs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                opReturnOutputs.forEach { out ->
                    Text("OP_RETURN:", style = MaterialTheme.typography.titleMedium)
                    Text(
                        out.opReturn!!,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val changeOutputs = state.outputs.filter { it.isChange }
            if (changeOutputs.isNotEmpty()) {
                Text("Change (back to wallet):", style = MaterialTheme.typography.titleMedium)
                changeOutputs.forEach { out ->
                    OutputRow(out, prefix = "\u2190", network = state.network)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fee:")
                Text(formatBtc(state.fee))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total sent:", style = MaterialTheme.typography.titleMedium)
                Text(formatBtc(state.totalSent), style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Text("Status: ${state.status.replace('_', ' ')}", style = MaterialTheme.typography.titleMedium)
            if (state.requiredSigs > 0) {
                val signed = state.signers.count { it.signed }
                Text(
                    "Signatures: $signed of ${state.requiredSigs} required (${state.totalSigs} cosigners)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.signers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.signers.forEach { signer ->
                    SignerRow(signer)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onSign, modifier = Modifier.fillMaxWidth()) {
                Text("Sign with Trezor")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun InputRow(input: TxInput, network: String) {
    val context = LocalContext.current
    val url = mempoolAddressUrl(input.address, network)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "\u2190 ${shortenAddress(input.address)}",
            modifier = Modifier.weight(1f).clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(formatBtc(input.amount), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OutputRow(output: TxOutput, prefix: String, network: String) {
    val context = LocalContext.current
    val url = mempoolAddressUrl(output.address, network)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "$prefix ${shortenAddress(output.address)}",
            modifier = Modifier.weight(1f).clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(formatBtc(output.amount), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignerRow(signer: SignerInfo) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            if (signer.signed) "\u2713" else "\u2717",
            modifier = Modifier.width(24.dp),
        )
        Text(signer.fingerprint)
        if (signer.isThisDevice) {
            Text(" \u2190 this device", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBtc(satoshis: Long): String {
    return "%.8f BTC".format(satoshis / 100_000_000.0)
}

private fun shortenAddress(address: String): String {
    return if (address.length > 20) {
        "${address.take(10)}...${address.takeLast(8)}"
    } else address
}
