package com.remotesigner.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.data.FingerprintValidator
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxInput
import com.remotesigner.viewmodel.TxOutput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionReviewScreen(
    state: AppState.TransactionReview,
    contacts: List<ContactWithFingerprints>,
    onSign: () -> Unit,
    onCancel: () -> Unit,
    onSaveContact: (label: String, fingerprint: String, existingContactId: Long?) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf<SignerInfo?>(null) }

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction Details") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
            if (!state.description.isNullOrBlank()) {
                Text(
                    state.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

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
                    SignerRow(signer) { showAddDialog = signer }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onSign, modifier = Modifier.fillMaxWidth()) {
                Text("Sign with Trezor")
            }
        }
    }

    showAddDialog?.let { signer ->
        QuickAddContactDialog(
            signer = signer,
            existingContacts = contacts,
            onDismiss = { showAddDialog = null },
            onSave = onSaveContact,
        )
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
private fun SignerRow(signer: SignerInfo, onTap: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (signer.signed) "\u2713" else "\u2717",
            modifier = Modifier.width(24.dp),
        )
        if (signer.contactLabel != null) {
            Text(signer.contactLabel)
            Text(
                " (${signer.fingerprint})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(signer.fingerprint)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "+ Add label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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

@Composable
private fun QuickAddContactDialog(
    signer: SignerInfo,
    existingContacts: List<ContactWithFingerprints>,
    onDismiss: () -> Unit,
    onSave: (label: String, fingerprint: String, existingContactId: Long?) -> Unit,
) {
    var label by remember { mutableStateOf(signer.contactLabel ?: "") }
    var selectedContactId by remember { mutableStateOf<Long?>(signer.contactId) }
    var showExisting by remember { mutableStateOf(signer.contactId != null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (signer.contactLabel != null) "Edit Contact"
                else "Label Cosigner ${signer.fingerprint}"
            )
        },
        text = {
            Column {
                if (!showExisting) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { if (it.length <= 50) label = it },
                        label = { Text("Contact name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (existingContacts.isNotEmpty()) {
                        TextButton(onClick = { showExisting = true }) {
                            Text("Or add to existing contact")
                        }
                    }
                } else {
                    Text("Add to existing contact:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    existingContacts.forEach { cwf ->
                        TextButton(
                            onClick = { selectedContactId = cwf.contact.id },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                cwf.contact.label,
                                color = if (selectedContactId == cwf.contact.id)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(onClick = { showExisting = false; selectedContactId = null }) {
                        Text("Or create new contact")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (showExisting && selectedContactId != null) {
                        onSave("", signer.fingerprint, selectedContactId)
                    } else if (label.trim().isNotEmpty()) {
                        onSave(label.trim(), signer.fingerprint, null)
                    }
                    onDismiss()
                },
                enabled = (showExisting && selectedContactId != null) || label.trim().isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
