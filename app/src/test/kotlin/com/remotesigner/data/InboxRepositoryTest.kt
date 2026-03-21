package com.remotesigner.data

import com.remotesigner.bridge.PythonBridgeInterface
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class InboxRepositoryTest {

    private val dao: InboxDao = mockk(relaxed = true)
    private val pythonBridge: PythonBridgeInterface = mockk()
    private val repo = InboxRepository(dao, pythonBridge)

    @Test
    fun `cleanupAndSeedIds resets signing items before deleting expired`() = runBlocking {
        coEvery { dao.getAllOnce() } returns emptyList()

        repo.cleanupAndSeedIds()

        coVerifyOrder {
            dao.resetSigning()
            dao.deleteExpired(any(), any())
        }
    }

    @Test
    fun `cleanupAndSeedIds returns all item ids after cleanup`() = runBlocking {
        val items = listOf(
            InboxItemEntity(
                id = "event1",
                psbtBytes = byteArrayOf(1),
                label = "test",
                amount = "",
                senderNpub = "npub1test",
                receivedAt = 1000L,
                status = InboxStatus.PENDING,
            ),
        )
        coEvery { dao.getAllOnce() } returns items

        val ids = repo.cleanupAndSeedIds()

        assert(ids == setOf("event1"))
    }
}
