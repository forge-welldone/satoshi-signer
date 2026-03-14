package com.remotesigner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.SatoshiSignerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val psbtBytes = readPsbtFromIntent(intent)

        setContent {
            SatoshiSignerTheme {
                AppRoot(intentPsbtBytes = psbtBytes)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
