package com.remotesigner

import android.content.Intent
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.remotesigner.nfc.NfcReadResult
import com.remotesigner.nfc.parseNdefTextPayload
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.SatoshiSignerTheme
import com.remotesigner.viewmodel.SignerViewModel
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val viewModel: SignerViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val psbtBytes = readPsbtFromIntent(intent)

        setContent {
            SatoshiSignerTheme {
                AppRoot(viewModel = viewModel, intentPsbtBytes = psbtBytes)
            }
        }

        // Observe nfcWaitingForTag and enable/disable reader mode accordingly
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.nfcWaitingForTag.collect { waiting ->
                    if (waiting) enableNfcReaderMode() else disableNfcReaderMode()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startNostrReceiver()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopNostrReceiver()
    }

    override fun onPause() {
        super.onPause()
        // Safety net: disable reader mode when Activity pauses
        disableNfcReaderMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(this, ::onTagDiscovered, flags, null)
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    private fun onTagDiscovered(tag: Tag) {
        // Runs on binder thread — only post to StateFlow, no UI operations
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            viewModel.onNfcTagResult(
                NfcReadResult.Error("Tag does not contain a passphrase. Use an NDEF-configured tag.")
            )
            return
        }

        try {
            ndef.connect()
            val message = ndef.ndefMessage
            if (message == null) {
                viewModel.onNfcTagResult(NfcReadResult.Error("No passphrase found on tag"))
                return
            }

            val textRecord = message.records.firstOrNull { record ->
                record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_TEXT)
            }

            if (textRecord == null) {
                viewModel.onNfcTagResult(NfcReadResult.Error("No text found on tag"))
                return
            }

            val text = parseNdefTextPayload(textRecord.payload)
            if (text == null) {
                viewModel.onNfcTagResult(NfcReadResult.Error("Failed to read tag — try again"))
                return
            }

            if (text.isEmpty()) {
                viewModel.onNfcTagResult(NfcReadResult.Error("Tag contains an empty passphrase"))
                return
            }

            viewModel.onNfcTagResult(NfcReadResult.Success(text))
        } catch (e: IOException) {
            viewModel.onNfcTagResult(NfcReadResult.Error("Failed to read tag — try again"))
        } finally {
            try { ndef.close() } catch (_: IOException) {}
        }
    }

    private fun readPsbtFromIntent(intent: Intent): ByteArray? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return try {
            contentResolver.openInputStream(uri)?.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}
