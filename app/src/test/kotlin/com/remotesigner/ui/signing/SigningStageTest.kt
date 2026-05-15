package com.remotesigner.ui.signing

import org.junit.Assert.assertEquals
import org.junit.Test

class SigningStageTest {

    @Test
    fun waitingMessage_returnsWaiting() {
        assertEquals(
            SigningStage.Waiting,
            deriveStage("Connect Trezor via USB-C cable", hasPassphraseRequest = false, previousStage = null),
        )
    }

    @Test
    fun passphraseRequestSet_returnsPassphrase() {
        assertEquals(
            SigningStage.Passphrase,
            deriveStage("Python: get_passphrase", hasPassphraseRequest = true, previousStage = SigningStage.Waiting),
        )
    }

    @Test
    fun afterPassphrase_beforeSigning_returnsConfirm() {
        assertEquals(
            SigningStage.Confirm,
            deriveStage("Python: TxAck", hasPassphraseRequest = false, previousStage = SigningStage.Passphrase),
        )
    }

    @Test
    fun confirmStaysWhenStillConfirming() {
        assertEquals(
            SigningStage.Confirm,
            deriveStage("Python: TxAck output 2", hasPassphraseRequest = false, previousStage = SigningStage.Confirm),
        )
    }

    @Test
    fun signingMessage_returnsSigning() {
        assertEquals(
            SigningStage.Signing,
            deriveStage("Signing...", hasPassphraseRequest = false, previousStage = SigningStage.Confirm),
        )
    }

    @Test
    fun firstTick_withSigningMessage_returnsSigning() {
        assertEquals(
            SigningStage.Signing,
            deriveStage("Signing...", hasPassphraseRequest = false, previousStage = null),
        )
    }

    @Test
    fun firstTick_unknownMessage_returnsSigning() {
        // Without prior context and no passphrase request, default to "signing"
        // so the screen never gets stuck on an animated confirm sheet without a
        // matching device prompt.
        assertEquals(
            SigningStage.Signing,
            deriveStage("Opening USB connection...", hasPassphraseRequest = false, previousStage = null),
        )
    }

    @Test
    fun passphraseRequestWinsOverWaitingMessage() {
        // Pathological: message is stale "Connect Trezor" but passphrase
        // request is set. Waiting check still fires first because the
        // orchestrator only emits that progress string before the device is
        // open, so a passphrase request alongside it would be a logic bug
        // we'd want to surface — keep Waiting to reflect what the user sees.
        assertEquals(
            SigningStage.Waiting,
            deriveStage("Connect Trezor via USB-C cable", hasPassphraseRequest = true, previousStage = null),
        )
    }

    @Test
    fun trezorConnection_mapping() {
        assertEquals(TrezorConnection.Waiting, deriveTrezorConnection(SigningStage.Waiting))
        assertEquals(TrezorConnection.Connected, deriveTrezorConnection(SigningStage.Passphrase))
        assertEquals(TrezorConnection.Connected, deriveTrezorConnection(SigningStage.Confirm))
        assertEquals(TrezorConnection.Connected, deriveTrezorConnection(SigningStage.Signing))
    }
}
