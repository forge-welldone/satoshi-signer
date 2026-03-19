package com.remotesigner

import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.PassphraseRequest
import com.remotesigner.bridge.SignerInfo
import com.remotesigner.bridge.TxInput
import com.remotesigner.bridge.TxOutput

object TestFixtures {
    val sampleInputs = listOf(
        TxInput(address = "tb1q...sender", amount = 6_236_567L),
    )

    val sampleOutputs = listOf(
        TxOutput(
            address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            amount = 5_000_000L, // 0.05 BTC
            isChange = false,
        ),
        TxOutput(
            address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            amount = 1_234_567L, // ~0.01234567 BTC change
            isChange = true,
        ),
    )

    val sampleOpReturnOutput = TxOutput(
        address = "unknown",
        amount = 0L,
        isChange = false,
        opReturn = "Your ad here - https://aads.com/",
    )

    val sampleSigners = listOf(
        SignerInfo(fingerprint = "a1b2c3d4", signed = true, isThisDevice = true),
        SignerInfo(fingerprint = "e5f6a7b8", signed = false, isThisDevice = false),
    )

    val reviewState = AppState.TransactionReview(
        inputs = sampleInputs,
        outputs = sampleOutputs,
        fee = 2_100L,
        totalSent = 5_000_000L,
        status = "needs_sig",
        signers = sampleSigners,
        warnings = emptyList(),
    )

    val reviewStateWithOpReturn = AppState.TransactionReview(
        inputs = sampleInputs,
        outputs = listOf(sampleOpReturnOutput) + sampleOutputs,
        fee = 2_100L,
        totalSent = 5_000_000L,
        status = "needs_sig",
        signers = sampleSigners,
        warnings = emptyList(),
    )

    val reviewStateWithDescription = AppState.TransactionReview(
        inputs = sampleInputs,
        outputs = sampleOutputs,
        fee = 2_100L,
        totalSent = 5_000_000L,
        status = "needs_sig",
        signers = sampleSigners,
        warnings = emptyList(),
        description = "Payment for server hosting — March 2026",
    )

    val signingState = AppState.Signing(message = "Confirm on your Trezor...")

    val signingStateWithLog = AppState.Signing(
        message = "Confirm on your Trezor...",
        log = "Opening USB connection...\nClaiming interface...\nPython: Parsing PSBT...\n",
    )

    val resultComplete = AppState.Result(
        isComplete = true,
        txid = "a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1",
        rawHex = "01000000000101deadbeef00000000001976a914abc123def456abc123def456abc123def456abc12388ac00000000",
    )

    val resultPartial = AppState.Result(
        isComplete = false,
        updatedPsbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
    )

    val resultCompleteTestnet = AppState.Result(
        isComplete = true,
        rawHex = "0200000001deadbeef",
        network = "test",
    )

    val errorState = AppState.Error(message = "USB device not found")

    val noOpCallback = SigningCallbackImpl(
        onStatusUpdate = { _ -> },
        onPassphraseRequest = { _ -> },
    )

    val passphraseRequestOnDevice = PassphraseRequest(
        availableOnDevice = true,
        callback = noOpCallback,
    )

    val passphraseRequestPhoneOnly = PassphraseRequest(
        availableOnDevice = false,
        callback = noOpCallback,
    )

    val sampleInboxItems = listOf(
        com.remotesigner.nostr.InboxItemEntity(
            id = "event1",
            psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
            label = "Payment to Alice",
            amount = "0.00500000 BTC",
            senderNpub = "npub1a3x7...k9f2",
            receivedAt = System.currentTimeMillis() / 1000 - 120, // 2 min ago
            status = com.remotesigner.nostr.InboxStatus.PENDING,
        ),
        com.remotesigner.nostr.InboxItemEntity(
            id = "event2",
            psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
            label = "Unsigned transaction",
            amount = "0.10000000 BTC",
            senderNpub = "npub1b4y8...m8g3",
            receivedAt = System.currentTimeMillis() / 1000 - 900, // 15 min ago
            status = com.remotesigner.nostr.InboxStatus.FAILED,
        ),
    )

    val signedInboxItem = com.remotesigner.nostr.InboxItemEntity(
        id = "event3",
        psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
        label = "Payment to Bob",
        amount = "0.01000000 BTC",
        senderNpub = "npub1c5z9...n7h4",
        receivedAt = System.currentTimeMillis() / 1000 - 300,
        status = com.remotesigner.nostr.InboxStatus.SIGNED,
        rawHex = "0200000001deadbeef",
        network = "test",
    )

    val broadcastInboxItem = com.remotesigner.nostr.InboxItemEntity(
        id = "event4",
        psbtBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()),
        label = "Payment to Carol",
        amount = "0.00250000 BTC",
        senderNpub = "npub1d6a0...p8j5",
        receivedAt = System.currentTimeMillis() / 1000 - 600,
        status = com.remotesigner.nostr.InboxStatus.BROADCAST,
        rawHex = "0200000001cafebabe",
        txid = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
        network = "main",
    )
}
