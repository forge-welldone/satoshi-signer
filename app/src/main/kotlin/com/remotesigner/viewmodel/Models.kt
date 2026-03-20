package com.remotesigner.viewmodel

import com.remotesigner.bridge.SignerInfo
import com.remotesigner.bridge.SigningCallbackImpl
import com.remotesigner.bridge.TxInput
import com.remotesigner.bridge.TxOutput

sealed class AppState {
    data object Home : AppState()
    data class TransactionReview(
        val inputs: List<TxInput>,
        val outputs: List<TxOutput>,
        val fee: Long,
        val totalSent: Long,
        val status: String,
        val signers: List<SignerInfo>,
        val warnings: List<String>,
        val requiredSigs: Int = 0,
        val totalSigs: Int = 0,
        val network: String = "main",
        val description: String? = null,
    ) : AppState()
    data class Signing(val message: String, val log: String = "") : AppState()
    data class Result(
        val isComplete: Boolean,
        val txid: String? = null,
        val rawHex: String? = null,
        val updatedPsbt: ByteArray? = null,
        val broadcastStatus: String? = null,
        val errorMessage: String? = null,
        val network: String = "main",
    ) : AppState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return isComplete == other.isComplete &&
                txid == other.txid &&
                rawHex == other.rawHex &&
                updatedPsbt.contentEquals(other.updatedPsbt) &&
                broadcastStatus == other.broadcastStatus &&
                errorMessage == other.errorMessage &&
                network == other.network
        }

        override fun hashCode(): Int {
            var result = isComplete.hashCode()
            result = 31 * result + (txid?.hashCode() ?: 0)
            result = 31 * result + (rawHex?.hashCode() ?: 0)
            result = 31 * result + (updatedPsbt?.contentHashCode() ?: 0)
            result = 31 * result + (broadcastStatus?.hashCode() ?: 0)
            result = 31 * result + (errorMessage?.hashCode() ?: 0)
            result = 31 * result + network.hashCode()
            return result
        }
    }
    data class Error(val message: String) : AppState()
    data object Contacts : AppState()
    data object EncryptPassphrase : AppState()
}

data class PassphraseRequest(
    val availableOnDevice: Boolean,
    val callback: SigningCallbackImpl,
)

data class AccountPathRequest(
    val callback: SigningCallbackImpl,
)
