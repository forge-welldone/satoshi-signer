package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

fun formatBtcAmount(satoshis: Long): String = "%.8f BTC".format(satoshis / 100_000_000.0)

fun copyToClipboard(context: Context, label: String, text: String, toast: String = "Copied") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
