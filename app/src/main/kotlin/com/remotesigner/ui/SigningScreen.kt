package com.remotesigner.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.ui.components.AppButton
import com.remotesigner.ui.components.AppButtonVariant
import com.remotesigner.ui.components.BottomSheetOverlay
import com.remotesigner.ui.components.Pill
import com.remotesigner.ui.components.PillTone
import com.remotesigner.ui.components.ScreenHeader
import com.remotesigner.ui.signing.ConfirmOnDeviceSheetContent
import com.remotesigner.ui.signing.PassphraseMode
import com.remotesigner.ui.signing.PassphraseSheetContent
import com.remotesigner.ui.signing.ProgressCard
import com.remotesigner.ui.signing.SigningStage
import com.remotesigner.ui.signing.StayOfflineTip
import com.remotesigner.ui.signing.TrezorConnection
import com.remotesigner.ui.signing.TrezorStatusPanel
import com.remotesigner.ui.signing.deriveStage
import com.remotesigner.ui.signing.deriveTrezorConnection
import com.remotesigner.ui.signing.pillCopy
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultTypography
import com.remotesigner.viewmodel.AccountPathRequest
import com.remotesigner.viewmodel.PassphraseRequest
import kotlinx.coroutines.delay

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

    var stage by rememberSaveable { mutableStateOf<SigningStage?>(null) }
    val nextStage = deriveStage(
        message = message,
        hasPassphraseRequest = passphraseRequest != null,
        previousStage = stage,
    )
    stage = nextStage
    val connection = deriveTrezorConnection(nextStage)

    SigningScaffold(
        stage = nextStage,
        connection = connection,
        log = log,
        onCancel = onCancel,
    )

    if (passphraseRequest != null) {
        PassphraseBottomSheet(
            request = passphraseRequest,
            nfcAvailable = nfcAvailable,
            nfcTagResult = nfcTagResult,
            onStartNfcWaiting = onStartNfcWaiting,
            onStopNfcWaiting = onStopNfcWaiting,
            onClearNfcResult = onClearNfcResult,
        )
    } else if (nextStage == SigningStage.Confirm) {
        BottomSheetOverlay(
            visible = true,
            onDismiss = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            ConfirmOnDeviceSheetContent()
        }
    }

    if (accountPathRequest != null) {
        AccountPathDialog(
            request = accountPathRequest,
            onDismiss = { accountPathRequest.callback.cancel() },
        )
    }
}

@Composable
private fun SigningScaffold(
    stage: SigningStage,
    connection: TrezorConnection,
    log: String,
    onCancel: () -> Unit,
) {
    val colors = LocalVaultColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        ScreenHeader(
            title = "Signing",
            onBack = onCancel,
            right = {
                val tone = when (connection) {
                    TrezorConnection.Connected -> PillTone.Good
                    TrezorConnection.Waiting -> PillTone.Warn
                    TrezorConnection.Disconnected -> PillTone.Bad
                }
                Pill(text = pillCopy(connection).text, tone = tone, modifier = Modifier.testTag("trezorPill"))
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TrezorStatusPanel(stage = stage, connection = connection)
            ProgressCard(stage = stage, connection = connection)
            StayOfflineTip()
            DebugLogSection(log = log)
            AppButton(
                text = "Cancel signing",
                onClick = onCancel,
                variant = AppButtonVariant.Ghost,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DebugLogSection(log: String) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    if (log.isBlank()) return
    var visible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        AppButton(
            text = if (visible) "Hide log" else "Show log",
            onClick = { visible = !visible },
            variant = AppButtonVariant.Ghost,
            small = true,
            fillMaxWidth = false,
        )
        if (visible) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Debug log",
                    style = typography.eyebrow.copy(color = colors.textMute),
                )
                TextButton(onClick = { copyToClipboard(context, "debug log", log, "Log copied") }) {
                    Text("Copy", fontSize = 12.sp, color = colors.textDim)
                }
            }
            Text(
                text = log,
                style = typography.monoSmall.copy(color = colors.textDim),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PassphraseBottomSheet(
    request: PassphraseRequest,
    nfcAvailable: Boolean,
    nfcTagResult: NfcReadResult?,
    onStartNfcWaiting: () -> Unit,
    onStopNfcWaiting: () -> Unit,
    onClearNfcResult: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var mode by rememberSaveable {
        mutableStateOf<PassphraseMode?>(
            if (request.availableOnDevice) null else PassphraseMode.Phone
        )
    }
    var phrase by rememberSaveable { mutableStateOf("") }
    var nfcWaiting by remember { mutableStateOf(false) }
    var nfcError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(nfcWaiting) {
        if (nfcWaiting) {
            delay(60_000L)
            if (nfcWaiting) {
                onStopNfcWaiting()
                nfcWaiting = false
                nfcError = null
                Toast.makeText(context, "Timed out waiting for NFC tag", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(nfcTagResult) {
        when (val r = nfcTagResult) {
            is NfcReadResult.Success -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStopNfcWaiting()
                nfcWaiting = false
                nfcError = null
                request.callback.submitPassphrase(r.passphrase)
                onClearNfcResult()
            }
            is NfcReadResult.Error -> {
                nfcError = r.message
                onClearNfcResult()
            }
            null -> {}
        }
    }

    BottomSheetOverlay(
        visible = true,
        onDismiss = {
            if (nfcWaiting) onStopNfcWaiting()
            request.callback.cancel()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        PassphraseSheetContent(
            mode = mode,
            onModeChange = { newMode ->
                if (mode == PassphraseMode.Nfc && newMode != PassphraseMode.Nfc && nfcWaiting) {
                    onStopNfcWaiting()
                    nfcWaiting = false
                }
                mode = newMode
            },
            phonePassphrase = phrase,
            onPhonePassphraseChange = { phrase = it },
            nfcAvailable = nfcAvailable,
            nfcWaiting = nfcWaiting,
            nfcError = nfcError,
            onContinue = {
                when (mode) {
                    PassphraseMode.Trezor -> request.callback.submitPassphrase("")
                    PassphraseMode.Phone -> request.callback.submitPassphrase(phrase)
                    PassphraseMode.Nfc -> {
                        nfcError = null
                        nfcWaiting = true
                        onStartNfcWaiting()
                    }
                    null -> {}
                }
            },
            onCancel = {
                if (nfcWaiting) onStopNfcWaiting()
                request.callback.cancel()
            },
        )
    }
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
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
