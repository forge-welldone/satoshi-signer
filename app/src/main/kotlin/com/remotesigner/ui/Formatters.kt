package com.remotesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

const val SECONDS_PER_DAY: Long = 86_400
const val PENDING_EXPIRY_SECONDS: Long = SECONDS_PER_DAY
const val SIGNED_EXPIRY_SECONDS: Long = SECONDS_PER_DAY * 7
const val SUBSCRIPTION_LOOKBACK_SECONDS: Long = SECONDS_PER_DAY
const val HIGH_FEE_THRESHOLD_SATS: Long = 1_000_000
const val MAX_LABEL_LENGTH: Int = 50

fun formatBtcAmount(satoshis: Long): String = "%.8f BTC".format(satoshis / 100_000_000.0)

fun copyToClipboard(context: Context, label: String, text: String, toast: String = "Copied") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
