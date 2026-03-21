package com.remotesigner.nostr

import com.remotesigner.ui.SECONDS_PER_DAY

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

fun formatRelativeTime(unixSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - unixSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < SECONDS_PER_DAY -> "${diff / 3600}h ago"
        else -> "${diff / SECONDS_PER_DAY}d ago"
    }
}

fun truncateNpub(npub: String): String {
    return if (npub.length > 16) "${npub.take(12)}...${npub.takeLast(4)}" else npub
}
