package com.remotesigner.ui

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import android.nfc.NfcAdapter
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.viewmodel.AccountPathRequest
import com.remotesigner.viewmodel.PassphraseRequest
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SigningScreen(
    message: String,
    log: String,
    passphraseRequest: PassphraseRequest?,
    accountPathRequest: AccountPathRequest?,
    nfcAvailable: Boolean = false,
    nfcTagResult: NfcReadResult? = null,
    onStartNfcWaiting: () -> Unit = {},
    onStopNfcWaiting: () -> Unit = {},
    onClearNfcResult: () -> Unit = {},
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    // Keep screen on during signing to prevent USB disconnection.
    // Unwrap context to find the Activity (Compose may wrap it).
    DisposableEffect(Unit) {
        var ctx = context
        while (ctx is ContextWrapper && ctx !is Activity) {
            ctx = ctx.baseContext
        }
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Signing") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
            if (log.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                var showLog by remember { mutableStateOf(false) }
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "Hide Log" else "Show Log", fontSize = 12.sp)
                }
                if (showLog) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Debug Log:", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            copyToClipboard(context, "debug log", log, "Log copied")
                        }) {
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                    Text(
                        log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Show passphrase dialog when Trezor requests it
    if (passphraseRequest != null) {
        PassphraseDialog(
            request = passphraseRequest,
            onDismiss = { passphraseRequest.callback.cancel() },
            nfcAvailable = nfcAvailable,
            nfcTagResult = nfcTagResult,
            onStartNfcWaiting = onStartNfcWaiting,
            onStopNfcWaiting = onStopNfcWaiting,
            onClearNfcResult = onClearNfcResult,
        )
    }

    // Show account path dialog when auto-detection fails
    if (accountPathRequest != null) {
        AccountPathDialog(
            request = accountPathRequest,
            onDismiss = { accountPathRequest.callback.cancel() },
        )
    }
}

@Composable
private fun PassphraseDialog(
    request: PassphraseRequest,
    onDismiss: () -> Unit,
    nfcAvailable: Boolean = false,
    nfcTagResult: NfcReadResult? = null,
    onStartNfcWaiting: () -> Unit = {},
    onStopNfcWaiting: () -> Unit = {},
    onClearNfcResult: () -> Unit = {},
) {
    var showTextField by remember { mutableStateOf(!request.availableOnDevice) }
    var showNfcWaiting by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var nfcErrorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // 60-second timeout for NFC waiting
    LaunchedEffect(showNfcWaiting) {
        if (showNfcWaiting) {
            delay(60_000L)
            if (showNfcWaiting) {
                onStopNfcWaiting()
                showNfcWaiting = false
                nfcErrorMessage = null
                Toast.makeText(context, "Timed out waiting for NFC tag", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // React to NFC tag read results
    LaunchedEffect(nfcTagResult) {
        when (nfcTagResult) {
            is NfcReadResult.Success -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStopNfcWaiting()
                showNfcWaiting = false
                nfcErrorMessage = null
                request.callback.submitPassphrase(nfcTagResult.passphrase)
                onClearNfcResult()
            }
            is NfcReadResult.Error -> {
                nfcErrorMessage = nfcTagResult.message
                onClearNfcResult()
            }
            null -> {}
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (showNfcWaiting) onStopNfcWaiting()
            onDismiss()
        },
        title = { Text("Passphrase Required") },
        text = {
            Column {
                when {
                    showNfcWaiting -> {
                        Text(
                            "Hold NFC tag to back of phone",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (nfcErrorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                nfcErrorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    showTextField -> {
                        Text(
                            "Less secure than on-device entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrect = false,
                            ),
                            visualTransformation = if (passwordVisible)
                                VisualTransformation.None
                            else
                                PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text("Choose where to enter your passphrase:")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { request.callback.submitPassphrase("") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enter on Trezor")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showTextField = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enter on phone")
                        }
                        if (nfcAvailable) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    showNfcWaiting = true
                                    nfcErrorMessage = null
                                    onStartNfcWaiting()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Read from NFC tag")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showTextField) {
                Button(onClick = {
                    request.callback.submitPassphrase(passphrase)
                }) {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            when {
                showNfcWaiting -> {
                    TextButton(onClick = {
                        onStopNfcWaiting()
                        showNfcWaiting = false
                        nfcErrorMessage = null
                    }) {
                        Text("Cancel")
                    }
                }
                showTextField && request.availableOnDevice -> {
                    TextButton(onClick = { showTextField = false; passphrase = "" }) {
                        Text("Back")
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        },
    )
}

@Composable
private fun AccountPathDialog(
    request: AccountPathRequest,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf("m/84'/0'/0'") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Path Required") },
        text = {
            Column {
                Text(
                    "The PSBT has relative derivation paths. " +
                        "Enter the account derivation path from your wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Account path") },
                    placeholder = { Text("m/84'/0'/0'") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { request.callback.submitAccountPath(path) },
                enabled = path.isNotBlank(),
            ) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
