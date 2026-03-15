package com.remotesigner.ui

import android.content.Intent
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
    val context = LocalContext.current

    LaunchedEffect(intentPsbtBytes) {
        intentPsbtBytes?.let { viewModel.loadPsbt(it) }
    }

    when (val s = state) {
        is AppState.Home -> HomeScreen(
            onPsbtSelected = { uri -> viewModel.loadPsbt(uri) },
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
            onHome = { viewModel.goHome() },
        )
        is AppState.Error -> {
            val clipContext = context
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Error", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(s.message, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.goHome() }) {
                            Text("Back to Home")
                        }
                        OutlinedButton(onClick = {
                            val clipboard = clipContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("error", s.message))
                            android.widget.Toast.makeText(clipContext, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy Error")
                        }
                    }
                }
            }
        }
    }
}
