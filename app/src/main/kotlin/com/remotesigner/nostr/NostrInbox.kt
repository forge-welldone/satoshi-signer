package com.remotesigner.nostr

enum class InboxStatus { PENDING, SIGNING, SIGNED, FAILED }

data class InboxItem(
    val id: String,
    val psbtBytes: ByteArray,
    val label: String,
    val amount: String = "",
    val senderNpub: String,
    val receivedAt: Long,
    val status: InboxStatus = InboxStatus.PENDING,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxItem) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

fun formatRelativeTime(unixSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - unixSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

fun formatBtcAmount(satoshis: Long): String = "%.8f BTC".format(satoshis / 100_000_000.0)

fun truncateNpub(npub: String): String {
    return if (npub.length > 16) "${npub.take(12)}...${npub.takeLast(4)}" else npub
}
