package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotesigner.viewmodel.PassphraseRequest

@Composable
fun SigningScreen(
    message: String,
    log: String,
    passphraseRequest: PassphraseRequest?,
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

    Surface(modifier = Modifier.fillMaxSize()) {
        // Show passphrase dialog when Trezor requests it
        if (passphraseRequest != null) {
            PassphraseDialog(
                request = passphraseRequest,
                onDismiss = { passphraseRequest.callback.cancel() },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
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
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Debug Log:", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("debug log", log))
                        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
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
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PassphraseDialog(
    request: PassphraseRequest,
    onDismiss: () -> Unit,
) {
    var showTextField by remember { mutableStateOf(!request.availableOnDevice) }
    var passphrase by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passphrase Required") },
        text = {
            Column {
                if (showTextField) {
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
                } else {
                    Text("Choose where to enter your passphrase:")
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
            } else {
                Column {
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
                }
            }
        },
        dismissButton = {
            if (showTextField && request.availableOnDevice) {
                TextButton(onClick = { showTextField = false; passphrase = "" }) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
