package com.remotesigner.data

import com.remotesigner.bridge.PythonBridgeInterface

import com.remotesigner.ui.formatBtcAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class InboxRepository(
    private val inboxDao: InboxDao,
    private val pythonBridge: PythonBridgeInterface,
) {
    val items: Flow<List<InboxItemEntity>> = inboxDao.getAll()

    suspend fun cleanupAndSeedIds(): Set<String> {
        inboxDao.resetSigning()
        val now = System.currentTimeMillis() / 1000
        inboxDao.deleteExpired(
            pendingCutoff = now - 86_400,
            signedCutoff = now - 86_400 * 7,
        )
        return inboxDao.getAllOnce().map { it.id }.toSet()
    }

    suspend fun handleInboxEvent(item: InboxItemEntity) {
        val inserted = inboxDao.insertIgnore(item)
        if (inserted == -1L) return

        try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(item.psbtBytes)
            }
            val totalSent = result.outputs
                .filter { !it.isChange }
                .sumOf { it.amount }
            inboxDao.updateParsedFields(
                id = item.id,
                amount = formatBtcAmount(totalSent),
                network = result.network,
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
