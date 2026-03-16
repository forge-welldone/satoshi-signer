package com.remotesigner.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerViewModel
import java.io.File

@Composable
fun AppRoot(
    viewModel: SignerViewModel = viewModel(),
    intentPsbtBytes: ByteArray? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passphraseRequest by viewModel.passphraseRequest.collectAsStateWithLifecycle()
    val accountPathRequest by viewModel.accountPathRequest.collectAsStateWithLifecycle()
    val inboxItems by viewModel.inboxItems.collectAsStateWithLifecycle()
    val relayCount by viewModel.relayConnectedCount.collectAsStateWithLifecycle()
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingSavePsbt by remember { mutableStateOf<ByteArray?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingSavePsbt ?: return@rememberLauncherForActivityResult)
                } ?: throw IllegalStateException("Could not open output stream")
                Toast.makeText(context, "PSBT saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingSavePsbt = null
    }

    LaunchedEffect(intentPsbtBytes) {
        intentPsbtBytes?.let { viewModel.loadPsbt(it) }
    }

    when (val s = state) {
        is AppState.Home -> HomeScreen(
            npub = viewModel.keyManager.getNpub(),
            relayCount = relayCount,
            relayStatuses = relayStatuses,
            inboxItems = inboxItems,
            onPsbtSelected = { uri -> viewModel.loadPsbt(uri) },
            onSignInboxItem = { item -> viewModel.signInboxItem(item) },
            onDeleteInboxItem = { item -> viewModel.deleteInboxItem(item.id) },
        )
        is AppState.TransactionReview -> TransactionReviewScreen(
            state = s,
            onSign = { viewModel.signWithTrezor() },
            onCancel = { viewModel.goHome() },
        )
        is AppState.Signing -> SigningScreen(
            message = s.message,
            log = s.log,
            passphraseRequest = passphraseRequest,
            accountPathRequest = accountPathRequest,
            onCancel = { viewModel.cancelSigning() },
        )
        is AppState.Result -> ResultScreen(
            state = s,
            onBroadcast = { viewModel.broadcast() },
            onExportPsbt = { psbt ->
                val file = File(context.cacheDir, "signed.psbt")
                file.writeBytes(psbt)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export PSBT"))
            },
            onSavePsbt = { psbt ->
                pendingSavePsbt = psbt
                saveLauncher.launch("partially-signed.psbt")
            },
            onHome = { viewModel.goHome() },
        )
        is AppState.Error -> ErrorScreen(
            message = s.message,
            onHome = { viewModel.goHome() },
        )
    }
}
