package com.remotesigner.ui.signing

/**
 * Stages of the signing flow surfaced by the redesigned screen. Maps the
 * prototype's `passphrase | confirm | signing | done` plus a `waiting` state
 * for the period before the Trezor is plugged in. `done` is intentionally
 * absent — when signing completes the app transitions to `AppState.Result`
 * and the Signing screen unmounts.
 */
enum class SigningStage { Waiting, Passphrase, Confirm, Signing }

/**
 * Live Trezor connection state shown in the header pill.
 */
enum class TrezorConnection { Connected, Waiting, Disconnected }

/**
 * Derive the current stage from the signals the existing data layer already
 * surfaces (`AppState.Signing.message` + `passphraseRequest`). [previousStage]
 * is the stage we returned on the previous tick — used to disambiguate the
 * `confirm` and `signing` stages, both of which appear *after* a passphrase
 * request has been cleared.
 *
 * Rules (first match wins):
 * 1. While the orchestrator is still asking the user to plug the Trezor in,
 *    the `Connect Trezor via USB-C cable` progress message is set → `Waiting`.
 * 2. An open passphrase request → `Passphrase`.
 * 3. After a passphrase has been entered but the active signing has not yet
 *    been announced, the device is asking the user to confirm outputs →
 *    `Confirm`.
 * 4. Otherwise we are mid-signing → `Signing`.
 */
fun deriveStage(
    message: String,
    hasPassphraseRequest: Boolean,
    previousStage: SigningStage?,
): SigningStage {
    if (message.startsWith("Connect Trezor")) return SigningStage.Waiting
    if (hasPassphraseRequest) return SigningStage.Passphrase
    val signingStarted = message.contains("Signing", ignoreCase = true) ||
        message.contains("Sign tx", ignoreCase = true) ||
        message.contains("signature", ignoreCase = true)
    if (signingStarted) return SigningStage.Signing
    if (previousStage == SigningStage.Passphrase || previousStage == SigningStage.Confirm) {
        return SigningStage.Confirm
    }
    return SigningStage.Signing
}

fun deriveTrezorConnection(stage: SigningStage): TrezorConnection = when (stage) {
    SigningStage.Waiting -> TrezorConnection.Waiting
    SigningStage.Passphrase, SigningStage.Confirm, SigningStage.Signing -> TrezorConnection.Connected
}

internal data class StageCopy(val title: String, val sub: String)

internal fun stageCopy(stage: SigningStage): StageCopy = when (stage) {
    SigningStage.Waiting -> StageCopy("Plug in your Trezor", "Connect via USB-C OTG cable.")
    SigningStage.Passphrase -> StageCopy("Enter your passphrase", "Choose where to enter it.")
    SigningStage.Confirm -> StageCopy("Confirm on device", "Check each output on the Trezor screen.")
    SigningStage.Signing -> StageCopy("Signing…", "Talking to the secure element.")
}

internal data class PillCopy(val text: String)

internal fun pillCopy(connection: TrezorConnection): PillCopy = when (connection) {
    TrezorConnection.Connected -> PillCopy("Trezor ready")
    TrezorConnection.Waiting -> PillCopy("Plug in Trezor")
    TrezorConnection.Disconnected -> PillCopy("Not connected")
}
