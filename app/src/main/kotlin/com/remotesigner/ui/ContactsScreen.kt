package com.remotesigner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotesigner.data.ContactWithFingerprints
import com.remotesigner.data.FingerprintValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contacts: List<ContactWithFingerprints>,
    onBack: () -> Unit,
    onAddContact: (label: String, fingerprint: String) -> Unit,
    onUpdateContact: (contactId: Long, label: String, npub: String?) -> Unit,
    onAddFingerprint: (contactId: Long, fingerprint: String) -> Unit,
    onDeleteContact: (contactId: Long) -> Unit,
    onDeleteFingerprint: (fingerprintId: Long) -> Unit,
) {
    BackHandler(onBack = onBack)

    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactWithFingerprints?>(null) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No contacts yet.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(contacts, key = { it.contact.id }) { cwf ->
                    ContactCard(
                        contact = cwf,
                        onEdit = { editingContact = cwf },
                        onDelete = { confirmDeleteId = cwf.contact.id },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onSave = { label, fingerprint ->
                onAddContact(label, fingerprint)
                showAddDialog = false
            },
        )
    }

    editingContact?.let { cwf ->
        EditContactDialog(
            contact = cwf,
            onDismiss = { editingContact = null },
            onUpdateLabel = { label, npub ->
                onUpdateContact(cwf.contact.id, label, npub)
                editingContact = null
            },
            onAddFingerprint = { fp ->
                onAddFingerprint(cwf.contact.id, fp)
                editingContact = null
            },
            onDeleteFingerprint = { fpId ->
                onDeleteFingerprint(fpId)
                editingContact = null
            },
        )
    }

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete Contact") },
            text = { Text("This will remove the contact and all associated fingerprints.") },
            confirmButton = {
                TextButton(onClick = { onDeleteContact(id); confirmDeleteId = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContactCard(
    contact: ContactWithFingerprints,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(contact.contact.label, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                contact.fingerprints.joinToString(", ") { it.fingerprint },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "npub: ${contact.contact.npub ?: "\u2014"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onSave: (label: String, fingerprint: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    val fpValid = FingerprintValidator.normalize(fingerprint) != null
    val labelValid = label.trim().let { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= MAX_LABEL_LENGTH) label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { if (it.length <= 8) fingerprint = it },
                    label = { Text("Fingerprint (8 hex chars)") },
                    singleLine = true,
                    isError = fingerprint.isNotEmpty() && !fpValid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), fingerprint) },
                enabled = labelValid && fpValid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditContactDialog(
    contact: ContactWithFingerprints,
    onDismiss: () -> Unit,
    onUpdateLabel: (label: String, npub: String?) -> Unit,
    onAddFingerprint: (fingerprint: String) -> Unit,
    onDeleteFingerprint: (fingerprintId: Long) -> Unit,
) {
    var label by remember { mutableStateOf(contact.contact.label) }
    var npub by remember { mutableStateOf(contact.contact.npub ?: "") }
    var newFingerprint by remember { mutableStateOf("") }
    val labelValid = label.trim().let { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= MAX_LABEL_LENGTH) label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = npub,
                    onValueChange = { npub = it },
                    label = { Text("npub (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Fingerprints:", style = MaterialTheme.typography.labelMedium)
                contact.fingerprints.forEach { fp ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fp.fingerprint, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onDeleteFingerprint(fp.id) }) {
                            Text("\u2717", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFingerprint,
                        onValueChange = { if (it.length <= 8) newFingerprint = it },
                        label = { Text("Add fingerprint") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            onAddFingerprint(newFingerprint)
                            newFingerprint = ""
                        },
                        enabled = FingerprintValidator.normalize(newFingerprint) != null,
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUpdateLabel(label.trim(), npub.ifBlank { null }) },
                enabled = labelValid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
