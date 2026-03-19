package com.remotesigner.data

import com.remotesigner.bridge.PythonBridgeInterface
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatBtcAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class InboxRepository(
    private val inboxDao: InboxDao,
    private val pythonBridge: PythonBridgeInterface,
) {
    val items: Flow<List<InboxItemEntity>> = inboxDao.getAll()

    suspend fun cleanupAndSeedIds(): Set<String> {
        val now = System.currentTimeMillis() / 1000
        inboxDao.deleteExpired(
            pendingCutoff = now - 86_400,
            signedCutoff = now - 86_400 * 7,
        )
        return inboxDao.getAllOnce().map { it.id }.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun handleInboxEvent(item: InboxItemEntity) {
        val inserted = inboxDao.insertIgnore(item)
        if (inserted == -1L) return

        try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(item.psbtBytes)
            }
            val outputs = result["outputs"] as? List<Map<String, Any?>> ?: emptyList()
            val totalSent = outputs
                .filter { it["is_change"] as? Boolean != true }
                .sumOf { (it["amount"] as? Number)?.toLong() ?: 0L }
            val network = result["network"]?.toString() ?: "main"
            inboxDao.updateParsedFields(
                id = item.id,
                amount = formatBtcAmount(totalSent),
                network = network,
            )
        } catch (_: Exception) {
            // Keep original row if parse fails
        }
    }

    suspend fun updateStatus(id: String, status: InboxStatus) =
        inboxDao.updateStatus(id, status)

    suspend fun updateSigned(id: String, status: InboxStatus, rawHex: String, network: String) =
        inboxDao.updateSigned(id, status, rawHex, network)

    suspend fun updateBroadcast(id: String, status: InboxStatus, txid: String, network: String) =
        inboxDao.updateBroadcast(id, status, txid, network)
}
