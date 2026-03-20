package com.remotesigner.viewmodel

import android.app.Application
import com.remotesigner.bridge.ParsedPsbtResult
import com.remotesigner.broadcast.BroadcastResult
import com.remotesigner.broadcast.TransactionBroadcaster
import com.remotesigner.bridge.PythonBridgeInterface
import com.remotesigner.bridge.SignerInfo
import com.remotesigner.bridge.SigningOrchestrator
import com.remotesigner.bridge.SigningResult
import com.remotesigner.bridge.TxInput
import com.remotesigner.bridge.TxOutput
import com.remotesigner.data.ContactRepository
import com.remotesigner.data.InboxRepository
import com.remotesigner.nostr.InboxItemEntity
import com.remotesigner.nostr.InboxStatus
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.usb.TrezorUsbManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var app: Application
    private lateinit var pythonBridge: PythonBridgeInterface
    private lateinit var contactRepo: ContactRepository
    private lateinit var inboxRepo: InboxRepository
    private lateinit var orchestrator: SigningOrchestrator
    private lateinit var trezorUsb: TrezorUsbManager
    private lateinit var keyManager: NostrKeyManager
    private lateinit var broadcaster: TransactionBroadcaster
    private lateinit var vm: SignerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        app = mockk(relaxed = true)
        pythonBridge = mockk()
        contactRepo = mockk(relaxed = true)
        inboxRepo = mockk(relaxed = true)
        orchestrator = mockk(relaxed = true)
        trezorUsb = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        broadcaster = mockk()

        every { contactRepo.allWithFingerprints } returns flowOf(emptyList())
        every { inboxRepo.items } returns flowOf(emptyList())
        coEvery { inboxRepo.cleanupAndSeedIds() } returns emptySet()
        every { orchestrator.passphraseRequest } returns MutableStateFlow(null)
        every { orchestrator.accountPathRequest } returns MutableStateFlow(null)

        vm = SignerViewModel(
            app, pythonBridge, contactRepo, inboxRepo,
            orchestrator, trezorUsb, keyManager, broadcaster,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Shared test data ---

    private val testPsbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74)

    private val testParseResult = ParsedPsbtResult(
        inputs = listOf(TxInput("tb1qtest", 100_000)),
        outputs = listOf(TxOutput("tb1qrecv", 90_000, isChange = false)),
        fee = 10_000,
        status = "unsigned",
        signers = listOf(SignerInfo("aabbccdd", signed = false)),
        network = "test",
        requiredSigs = 1,
        totalSigs = 1,
    )

    private suspend fun awaitState(predicate: (AppState) -> Boolean): AppState {
        return withTimeout(1000) {
            vm.state.first(predicate)
        }
    }

    private suspend fun loadTestPsbt() {
        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }
        vm.loadPsbt(testPsbtBytes)
        awaitState { it is AppState.TransactionReview }
    }

    private suspend fun loadAndSign(result: SigningResult) {
        loadTestPsbt()
        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns result
        vm.signWithTrezor()
    }

    private fun testInboxItem(
        id: String = "event1",
        label: String = "Test payment",
        status: InboxStatus = InboxStatus.PENDING,
        rawHex: String? = null,
        txid: String? = null,
        network: String = "main",
    ) = InboxItemEntity(
        id = id,
        psbtBytes = testPsbtBytes,
        label = label,
        amount = "0.00100000 BTC",
        senderNpub = "npub1test",
        receivedAt = System.currentTimeMillis() / 1000,
        status = status,
        rawHex = rawHex,
        txid = txid,
        network = network,
    )

    // ===== State Transitions =====

    @Test
    fun `initial state is Home`() {
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `loadPsbt transitions to TransactionReview`() = runBlocking {
        loadTestPsbt()

        val state = vm.state.value as AppState.TransactionReview
        assertEquals("test", state.network)
        assertEquals(10_000L, state.fee)
        assertEquals(90_000L, state.totalSent)
        assertEquals(1, state.signers.size)
        assertEquals("aabbccdd", state.signers[0].fingerprint)
        assertEquals(1, state.requiredSigs)
        assertEquals(1, state.totalSigs)
    }

    @Test
    fun `loadPsbt parse error transitions to Error`() = runBlocking {
        coEvery { pythonBridge.parsePsbt(any()) } throws IllegalArgumentException("Bad PSBT data")

        vm.loadPsbt(testPsbtBytes)
        val state = awaitState { it is AppState.Error } as AppState.Error

        assertTrue(state.message.contains("Bad PSBT data"))
    }

    @Test
    fun `loadPsbt high fee generates warning`() = runBlocking {
        val highFeeResult = testParseResult.copy(fee = 2_000_000)
        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns highFeeResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.loadPsbt(testPsbtBytes)
        val state = awaitState { it is AppState.TransactionReview } as AppState.TransactionReview

        assertTrue(state.warnings.any { it.contains("unusually high") })
    }

    @Test
    fun `loadPsbt normal fee no warning`() = runBlocking {
        loadTestPsbt()

        val state = vm.state.value as AppState.TransactionReview
        assertTrue(state.warnings.isEmpty())
    }

    @Test
    fun `loadPsbt enriches signers via contact repository`() = runBlocking {
        val enriched = listOf(
            SignerInfo("aabbccdd", signed = false, contactLabel = "Alice", contactId = 1L),
        )
        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } returns enriched

        vm.loadPsbt(testPsbtBytes)
        val state = awaitState { it is AppState.TransactionReview } as AppState.TransactionReview

        assertEquals("Alice", state.signers[0].contactLabel)
        assertEquals(1L, state.signers[0].contactId)
    }

    @Test
    fun `showContacts transitions to Contacts`() {
        vm.showContacts()
        assertEquals(AppState.Contacts, vm.state.value)
    }

    @Test
    fun `showEncryptPassphrase transitions to EncryptPassphrase`() {
        vm.showEncryptPassphrase()
        assertEquals(AppState.EncryptPassphrase, vm.state.value)
    }

    @Test
    fun `goHome from non-Home state returns to Home`() {
        vm.showContacts()
        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `goHome clears PSBT state`() = runBlocking {
        loadTestPsbt()
        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)

        // Verify signWithTrezor is a no-op (currentPsbtBytes cleared)
        vm.signWithTrezor()
        assertEquals(AppState.Home, vm.state.value)
    }

    // ===== Signing State Transitions =====

    @Test
    fun `signWithTrezor without PSBT is no-op`() {
        vm.signWithTrezor()
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `signWithTrezor sets Signing state`() = runBlocking {
        loadTestPsbt()

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } coAnswers {
            awaitCancellation()
        }

        vm.signWithTrezor()
        val state = awaitState { it is AppState.Signing } as AppState.Signing

        assertTrue(state.message.contains("Connecting"))
    }

    @Test
    fun `signing complete transitions to Result`() = runBlocking {
        loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
        val state = awaitState { it is AppState.Result } as AppState.Result

        assertTrue(state.isComplete)
        assertEquals("0200abcd", state.rawHex)
        assertEquals("test", state.network)
    }

    @Test
    fun `signing partial transitions to Result not complete`() = runBlocking {
        val updatedPsbt = byteArrayOf(1, 2, 3)
        loadAndSign(SigningResult.Partial(updatedPsbtBytes = updatedPsbt, network = "test"))
        val state = awaitState { it is AppState.Result } as AppState.Result

        assertFalse(state.isComplete)
        assertTrue(state.updatedPsbt.contentEquals(updatedPsbt))
    }

    @Test
    fun `signing cancelled returns to TransactionReview`() = runBlocking {
        loadTestPsbt()

        // Re-parse after cancel needs the mock
        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns SigningResult.Cancelled

        vm.signWithTrezor()
        // After cancel, ViewModel re-parses the PSBT
        awaitState { it is AppState.TransactionReview }

        assertTrue(vm.state.value is AppState.TransactionReview)
    }

    @Test
    fun `signing error transitions to Error`() = runBlocking {
        loadAndSign(SigningResult.Error(message = "Trezor disconnected", log = "some log"))
        val state = awaitState { it is AppState.Error } as AppState.Error

        assertTrue(state.message.contains("Trezor disconnected"))
        assertTrue(state.message.contains("Log"))
    }

    // ===== Broadcast =====

    @Test
    fun `broadcast success updates Result with txid`() = runBlocking {
        loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
        awaitState { it is AppState.Result }

        every { broadcaster.broadcast("0200abcd", "testnet4") } returns
            BroadcastResult(status = "ok", txid = "tx123abc")

        vm.broadcast("testnet4")
        val state = awaitState {
            it is AppState.Result && it.txid != null
        } as AppState.Result

        assertEquals("tx123abc", state.txid)
        assertEquals("Broadcast successful", state.broadcastStatus)
        assertEquals("testnet4", state.network)
    }

    @Test
    fun `broadcast error status shows error in broadcastStatus`() = runBlocking {
        loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
        awaitState { it is AppState.Result }

        every { broadcaster.broadcast("0200abcd", "testnet4") } returns
            BroadcastResult(status = "error", message = "Mempool full")

        vm.broadcast("testnet4")
        val state = awaitState {
            it is AppState.Result && it.broadcastStatus?.contains("failed") == true
        } as AppState.Result

        assertTrue(state.broadcastStatus!!.contains("Mempool full"))
    }

    @Test
    fun `broadcast exception shows error in broadcastStatus`() = runBlocking {
        loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
        awaitState { it is AppState.Result }

        every { broadcaster.broadcast("0200abcd", "testnet4") } throws
            RuntimeException("Network error")

        vm.broadcast("testnet4")
        val state = awaitState {
            it is AppState.Result && it.broadcastStatus?.contains("failed") == true
        } as AppState.Result

        assertTrue(state.broadcastStatus!!.contains("Network error"))
    }

    @Test
    fun `broadcast when not in Result state is no-op`() {
        assertEquals(AppState.Home, vm.state.value)
        vm.broadcast("testnet4")
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `broadcast when rawHex is null is no-op`() = runBlocking {
        loadAndSign(SigningResult.Partial(updatedPsbtBytes = byteArrayOf(1), network = "test"))
        awaitState { it is AppState.Result }

        val stateBefore = vm.state.value
        vm.broadcast("testnet4")
        assertEquals(stateBefore, vm.state.value)
    }

    // ===== Race Condition Regression =====

    @Test
    fun `broadcast completion preserves Home state when user navigated away`() = runBlocking {
        loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
        awaitState { it is AppState.Result }

        // Make broadcaster suspend so we can interleave goHome()
        val broadcastStarted = java.util.concurrent.CountDownLatch(1)
        val broadcastContinue = java.util.concurrent.CountDownLatch(1)
        every { broadcaster.broadcast("0200abcd", "testnet4") } answers {
            broadcastStarted.countDown()
            broadcastContinue.await()
            BroadcastResult(status = "ok", txid = "tx123abc")
        }

        vm.broadcast("testnet4")
        broadcastStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)

        // User navigates away while broadcast is in flight
        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)

        // Broadcast completes — should NOT overwrite Home
        broadcastContinue.countDown()

        // Wait for IO continuation to resume and process stale write
        Thread.sleep(100)
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `reEnrichSigners preserves Home state when user left TransactionReview`() = runBlocking {
        loadTestPsbt()

        // Make enrichSigners suspend so we can interleave goHome()
        val enrichStarted = java.util.concurrent.CountDownLatch(1)
        val enrichContinue = java.util.concurrent.CountDownLatch(1)
        coEvery { contactRepo.enrichSigners(any()) } coAnswers {
            enrichStarted.countDown()
            enrichContinue.await()
            firstArg()
        }

        vm.saveContact("Alice", "aabbccdd", null)
        enrichStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)

        // User navigates away while enrichment is in flight
        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)

        // Enrichment completes — should NOT overwrite Home
        enrichContinue.countDown()

        // Wait for IO continuation to resume and process stale write
        Thread.sleep(100)
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `progress callback preserves Result state when signing already completed`() = runBlocking {
        loadTestPsbt()

        // Capture the onProgress callback
        var capturedOnProgress: ((String) -> Unit)? = null
        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } coAnswers {
            capturedOnProgress = thirdArg()
            SigningResult.Complete(rawHex = "0200abcd", network = "test")
        }

        vm.signWithTrezor()
        awaitState { it is AppState.Result }

        // Simulate a late progress callback arriving after signing completed
        capturedOnProgress?.invoke("Late message")

        // State should still be Result, not Signing
        assertTrue(vm.state.value is AppState.Result)
    }

    // ===== Cancellation =====

    @Test
    fun `cancelSigning with loaded PSBT returns to TransactionReview`() = runBlocking {
        loadTestPsbt()

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } coAnswers {
            awaitCancellation()
        }
        vm.signWithTrezor()
        awaitState { it is AppState.Signing }

        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        vm.cancelSigning()

        assertTrue(awaitState { it is AppState.TransactionReview } is AppState.TransactionReview)
    }

    @Test
    fun `cancelSigning without PSBT returns to Home`() {
        vm.cancelSigning()
        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `cancelSigning calls orchestrator cancel`() = runBlocking {
        loadTestPsbt()

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } coAnswers {
            awaitCancellation()
        }
        vm.signWithTrezor()
        awaitState { it is AppState.Signing }

        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        vm.cancelSigning()

        verify { orchestrator.cancel() }
    }

    @Test
    fun `cancelSigning resets NFC state`() {
        vm.startNfcWaiting()
        assertTrue(vm.nfcWaitingForTag.value)

        vm.cancelSigning()

        assertFalse(vm.nfcWaitingForTag.value)
        assertNull(vm.nfcTagResult.value)
    }

    @Test
    fun `cancelSigning with inbox item resets status to PENDING`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } coAnswers {
            awaitCancellation()
        }
        vm.signWithTrezor()
        awaitState { it is AppState.Signing }

        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        vm.cancelSigning()

        coVerify(timeout = 1000) { inboxRepo.updateStatus("event1", InboxStatus.PENDING) }
    }

    // ===== Inbox Status Transitions =====

    @Test
    fun `signInboxItem sets status to SIGNING and loads PSBT`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coVerify { inboxRepo.updateStatus("event1", InboxStatus.SIGNING) }
    }

    @Test
    fun `signInboxItem sets description from label`() = runBlocking {
        val item = testInboxItem(label = "Payment for services")
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        val state = awaitState { it is AppState.TransactionReview } as AppState.TransactionReview

        assertEquals("Payment for services", state.description)
    }

    @Test
    fun `signInboxItem blank label sets null description`() = runBlocking {
        val item = testInboxItem(label = "")
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        val state = awaitState { it is AppState.TransactionReview } as AppState.TransactionReview

        assertNull(state.description)
    }

    @Test
    fun `deleteInboxItem sets status to DELETED`() {
        vm.deleteInboxItem("event1")
        coVerify(timeout = 1000) { inboxRepo.updateStatus("event1", InboxStatus.DELETED) }
    }

    @Test
    fun `openInboxResult shows Result for SIGNED item`() {
        val item = testInboxItem(status = InboxStatus.SIGNED, rawHex = "0200abcd")

        vm.openInboxResult(item)

        val state = vm.state.value as AppState.Result
        assertTrue(state.isComplete)
        assertEquals("0200abcd", state.rawHex)
        assertNull(state.txid)
        assertNull(state.broadcastStatus)
    }

    @Test
    fun `openInboxResult shows Result for BROADCAST item with txid`() {
        val item = testInboxItem(
            status = InboxStatus.BROADCAST,
            rawHex = "0200abcd",
            txid = "tx123",
            network = "testnet4",
        )

        vm.openInboxResult(item)

        val state = vm.state.value as AppState.Result
        assertEquals("tx123", state.txid)
        assertEquals("Broadcast successful", state.broadcastStatus)
        assertEquals("testnet4", state.network)
    }

    @Test
    fun `openInboxResult ignores PENDING item`() {
        val item = testInboxItem(status = InboxStatus.PENDING)

        vm.openInboxResult(item)

        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `openInboxResult ignores FAILED item`() {
        val item = testInboxItem(status = InboxStatus.FAILED)

        vm.openInboxResult(item)

        assertEquals(AppState.Home, vm.state.value)
    }

    @Test
    fun `signing complete updates inbox to SIGNED with rawHex`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Complete(rawHex = "0200abcd", network = "test")

        vm.signWithTrezor()
        awaitState { it is AppState.Result }

        coVerify(timeout = 1000) {
            inboxRepo.updateSigned("event1", InboxStatus.SIGNED, "0200abcd", "test")
        }
    }

    @Test
    fun `signing complete without rawHex updates inbox status only`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Complete(rawHex = null, network = "test")

        vm.signWithTrezor()
        awaitState { it is AppState.Result }

        coVerify(timeout = 1000) {
            inboxRepo.updateStatus("event1", InboxStatus.SIGNED)
        }
    }

    @Test
    fun `signing cancelled updates inbox to PENDING`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Cancelled

        vm.signWithTrezor()
        awaitState { it is AppState.TransactionReview }

        coVerify(timeout = 1000) { inboxRepo.updateStatus("event1", InboxStatus.PENDING) }
    }

    @Test
    fun `signing error updates inbox to FAILED`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Error(message = "Failed", log = "")

        vm.signWithTrezor()
        awaitState { it is AppState.Error }

        coVerify(timeout = 1000) { inboxRepo.updateStatus("event1", InboxStatus.FAILED) }
    }

    @Test
    fun `broadcast success updates inbox to BROADCAST`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Complete(rawHex = "0200abcd", network = "test")
        vm.signWithTrezor()
        awaitState { it is AppState.Result }

        every { broadcaster.broadcast("0200abcd", "testnet4") } returns
            BroadcastResult(status = "ok", txid = "tx123")

        vm.broadcast("testnet4")
        awaitState { it is AppState.Result && it.txid != null }

        coVerify(timeout = 1000) {
            inboxRepo.updateBroadcast("event1", InboxStatus.BROADCAST, "tx123", "testnet4")
        }
    }

    // ===== goHome inbox transitions =====

    @Test
    fun `goHome from Result without txid updates inbox to SIGNED`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Complete(rawHex = "0200abcd", network = "test")
        vm.signWithTrezor()
        awaitState { it is AppState.Result }

        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)

        coVerify(timeout = 1000) { inboxRepo.updateStatus("event1", InboxStatus.SIGNED) }
    }

    @Test
    fun `goHome from Error updates inbox to FAILED`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Error(message = "Fail", log = "")
        vm.signWithTrezor()
        awaitState { it is AppState.Error }

        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)

        coVerify(timeout = 1000) { inboxRepo.updateStatus("event1", InboxStatus.FAILED) }
    }

    @Test
    fun `goHome from broadcast Result does not overwrite BROADCAST status`() = runBlocking {
        val item = testInboxItem()
        coEvery { pythonBridge.parsePsbt(item.psbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } answers { firstArg() }

        vm.signInboxItem(item)
        awaitState { it is AppState.TransactionReview }

        coEvery { orchestrator.signWithTrezor(any(), any(), any()) } returns
            SigningResult.Complete(rawHex = "0200abcd", network = "test")
        vm.signWithTrezor()
        awaitState { it is AppState.Result }

        every { broadcaster.broadcast("0200abcd", "testnet4") } returns
            BroadcastResult(status = "ok", txid = "tx123")
        vm.broadcast("testnet4")
        awaitState { it is AppState.Result && it.txid != null }

        vm.goHome()
        assertEquals(AppState.Home, vm.state.value)

        // With txid set, goHome should NOT call updateStatus to SIGNED
        coVerify(exactly = 0) { inboxRepo.updateStatus("event1", InboxStatus.SIGNED) }
    }

    // ===== Contact CRUD =====

    @Test
    fun `saveContact delegates to repository`() {
        vm.saveContact("Alice", "aabbccdd", null)
        coVerify(timeout = 1000) { contactRepo.saveContact("Alice", "aabbccdd", null) }
    }

    @Test
    fun `saveContact with existing contact id delegates correctly`() {
        vm.saveContact("Alice", "aabbccdd", 5L)
        coVerify(timeout = 1000) { contactRepo.saveContact("Alice", "aabbccdd", 5L) }
    }

    @Test
    fun `updateContact delegates to repository`() {
        vm.updateContact(1L, "Bob", "npub1xyz")
        coVerify(timeout = 1000) { contactRepo.updateContact(1L, "Bob", "npub1xyz") }
    }

    @Test
    fun `addFingerprintToContact delegates to repository`() {
        vm.addFingerprintToContact(1L, "aabbccdd")
        coVerify(timeout = 1000) { contactRepo.addFingerprint(1L, "aabbccdd") }
    }

    @Test
    fun `deleteContact delegates to repository`() {
        vm.deleteContact(1L)
        coVerify(timeout = 1000) { contactRepo.deleteContact(1L) }
    }

    @Test
    fun `deleteFingerprint delegates to repository`() {
        vm.deleteFingerprint(42L)
        coVerify(timeout = 1000) { contactRepo.deleteFingerprint(42L) }
    }

    @Test
    fun `saveContact re-enriches signers when in TransactionReview`() = runBlocking {
        loadTestPsbt()

        val enriched = listOf(
            SignerInfo("aabbccdd", signed = false, contactLabel = "Alice", contactId = 1L),
        )
        coEvery { contactRepo.enrichSigners(any()) } returns enriched

        vm.saveContact("Alice", "aabbccdd", null)

        val state = awaitState {
            it is AppState.TransactionReview &&
                (it as AppState.TransactionReview).signers.any { s -> s.contactLabel == "Alice" }
        } as AppState.TransactionReview

        assertEquals("Alice", state.signers[0].contactLabel)
    }

    @Test
    fun `deleteContact re-enriches signers when in TransactionReview`() = runBlocking {
        // Load PSBT with enriched signers
        val enriched = listOf(
            SignerInfo("aabbccdd", signed = false, contactLabel = "Alice", contactId = 1L),
        )
        coEvery { pythonBridge.parsePsbt(testPsbtBytes) } returns testParseResult
        coEvery { contactRepo.enrichSigners(any()) } returns enriched
        vm.loadPsbt(testPsbtBytes)
        awaitState { it is AppState.TransactionReview }

        // After delete, enrichment returns without label
        val unenriched = listOf(SignerInfo("aabbccdd", signed = false))
        coEvery { contactRepo.enrichSigners(any()) } returns unenriched

        vm.deleteContact(1L)

        val state = awaitState {
            it is AppState.TransactionReview &&
                (it as AppState.TransactionReview).signers.all { s -> s.contactLabel == null }
        } as AppState.TransactionReview

        assertNull(state.signers[0].contactLabel)
    }

    // ===== Error Propagation =====

    @Test
    fun `parsePsbt RuntimeException shows Error state`() = runBlocking {
        coEvery { pythonBridge.parsePsbt(any()) } throws RuntimeException("Unexpected error")

        vm.loadPsbt(testPsbtBytes)
        val state = awaitState { it is AppState.Error } as AppState.Error

        assertTrue(state.message.contains("Unexpected error"))
        assertTrue(state.message.startsWith("Invalid PSBT:"))
    }

    // ===== NFC =====

    @Test
    fun `startNfcWaiting enables waiting and clears result`() {
        vm.startNfcWaiting()

        assertTrue(vm.nfcWaitingForTag.value)
        assertNull(vm.nfcTagResult.value)
    }

    @Test
    fun `stopNfcWaiting disables waiting and clears result`() {
        vm.startNfcWaiting()
        vm.stopNfcWaiting()

        assertFalse(vm.nfcWaitingForTag.value)
        assertNull(vm.nfcTagResult.value)
    }

    @Test
    fun `clearNfcResult clears result`() {
        vm.clearNfcResult()
        assertNull(vm.nfcTagResult.value)
    }

    // ===== Signing resets NFC state =====

    @Test
    fun `signing complete resets NFC state`() = runBlocking {
        vm.startNfcWaiting()
        loadAndSign(SigningResult.Complete(rawHex = "0200abcd", network = "test"))
        awaitState { it is AppState.Result }

        assertFalse(vm.nfcWaitingForTag.value)
        assertNull(vm.nfcTagResult.value)
    }

    // ===== AppState.Result ByteArray equality =====

    @Test
    fun `Result equals compares ByteArray by content not reference`() {
        val a = AppState.Result(
            isComplete = false,
            updatedPsbt = byteArrayOf(1, 2, 3),
        )
        val b = AppState.Result(
            isComplete = false,
            updatedPsbt = byteArrayOf(1, 2, 3),
        )
        assertEquals(a, b)
    }

    @Test
    fun `Result hashCode is consistent for same ByteArray content`() {
        val a = AppState.Result(
            isComplete = false,
            updatedPsbt = byteArrayOf(1, 2, 3),
        )
        val b = AppState.Result(
            isComplete = false,
            updatedPsbt = byteArrayOf(1, 2, 3),
        )
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Result with different ByteArray content is not equal`() {
        val a = AppState.Result(
            isComplete = false,
            updatedPsbt = byteArrayOf(1, 2, 3),
        )
        val b = AppState.Result(
            isComplete = false,
            updatedPsbt = byteArrayOf(4, 5, 6),
        )
        assertFalse(a == b)
    }

    @Test
    fun `Result with null vs non-null ByteArray is not equal`() {
        val a = AppState.Result(isComplete = false, updatedPsbt = null)
        val b = AppState.Result(isComplete = false, updatedPsbt = byteArrayOf(1, 2, 3))
        assertFalse(a == b)
    }

    @Test
    fun `Result with both null ByteArray is equal`() {
        val a = AppState.Result(isComplete = true, rawHex = "0200", network = "test")
        val b = AppState.Result(isComplete = true, rawHex = "0200", network = "test")
        assertEquals(a, b)
    }
}
