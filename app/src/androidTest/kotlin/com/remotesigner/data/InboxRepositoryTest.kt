package com.remotesigner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.remotesigner.bridge.BroadcastResult
import com.remotesigner.bridge.ParsedPsbtResult
import com.remotesigner.bridge.PythonBridgeInterface
import com.remotesigner.bridge.SigningCallback
import com.remotesigner.bridge.TxOutput
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.formatBtcAmount
import com.remotesigner.usb.SigningBridge
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InboxRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    private class FakePythonBridge(
        private val parseResult: ParsedPsbtResult = ParsedPsbtResult(
            inputs = emptyList(),
            outputs = listOf(TxOutput(address = "test", amount = 50000L, isChange = false)),
            fee = 0,
            status = "unsigned",
            signers = emptyList(),
            network = "test",
        ),
        private val shouldThrow: Boolean = false,
    ) : PythonBridgeInterface {
        override fun parsePsbt(psbtBytes: ByteArray): ParsedPsbtResult {
            if (shouldThrow) throw RuntimeException("parse failed")
            return parseResult
        }
        override fun signPsbt(
            psbtBytes: ByteArray, bridge: SigningBridge,
            callback: SigningCallback?, network: String,
        ) = emptyMap<String, Any?>()
        override fun broadcast(rawHex: String, network: String) = BroadcastResult(status = "ok")
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = InboxRepository(db.inboxDao(), FakePythonBridge())
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun makeItem(id: String, receivedAt: Long = System.currentTimeMillis() / 1000) =
        InboxItemEntity(
            id = id,
            psbtBytes = byteArrayOf(1, 2, 3),
            label = "Test",
            senderNpub = "npub1abc...xyz",
            receivedAt = receivedAt,
        )

    @Test
    fun cleanupAndSeedIds_deletes_expired_and_returns_remaining() = runTest {
        val now = System.currentTimeMillis() / 1000
        val dao = db.inboxDao()

        dao.insertIgnore(makeItem("recent", receivedAt = now - 100))
        dao.insertIgnore(makeItem("old-pending", receivedAt = now - 90_000))
        val oldSigned = makeItem("old-signed", receivedAt = now - 700_000)
        dao.insertIgnore(oldSigned)
        dao.updateStatus("old-signed", InboxStatus.SIGNED)

        val ids = repo.cleanupAndSeedIds()

        assertEquals(setOf("recent"), ids)
    }

    @Test
    fun handleInboxEvent_inserts_and_enriches_parsed_fields() = runTest {
        val item = makeItem("event-1")
        repo.handleInboxEvent(item)

        val dao = db.inboxDao()
        val all = dao.getAllOnce()
        assertEquals(1, all.size)
        assertEquals("event-1", all[0].id)
        assertEquals(formatBtcAmount(50000L), all[0].amount)
        assertEquals("test", all[0].network)
    }

    @Test
    fun handleInboxEvent_skips_duplicate() = runTest {
        val item = makeItem("event-1")
        repo.handleInboxEvent(item)
        repo.handleInboxEvent(item)

        val all = db.inboxDao().getAllOnce()
        assertEquals(1, all.size)
    }

    @Test
    fun handleInboxEvent_keeps_original_row_when_parse_fails() = runTest {
        val failingRepo = InboxRepository(db.inboxDao(), FakePythonBridge(shouldThrow = true))
        val item = makeItem("event-1")
        failingRepo.handleInboxEvent(item)

        val all = db.inboxDao().getAllOnce()
        assertEquals(1, all.size)
        assertEquals("", all[0].amount)
        assertEquals("main", all[0].network)
    }

    @Test
    fun updateStatus_delegates_to_dao() = runTest {
        val dao = db.inboxDao()
        dao.insertIgnore(makeItem("event-1"))

        repo.updateStatus("event-1", InboxStatus.SIGNED)

        val item = dao.getAllOnce().first()
        assertEquals(InboxStatus.SIGNED, item.status)
    }
}
