package com.remotesigner.nostr

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

fun formatRelativeTime(unixSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - unixSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

fun truncateNpub(npub: String): String {
    return if (npub.length > 16) "${npub.take(12)}...${npub.takeLast(4)}" else npub
}
