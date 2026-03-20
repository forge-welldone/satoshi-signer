package com.remotesigner.bridge

data class TxInput(
    val address: String,
    val amount: Long,
)

data class TxOutput(
    val address: String,
    val amount: Long,
    val isChange: Boolean,
    val opReturn: String? = null,
)

data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
    val contactLabel: String? = null,
    val contactId: Long? = null,
)

data class ParsedPsbtResult(
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val fee: Long,
    val status: String,
    val signers: List<SignerInfo>,
    val network: String,
    val requiredSigs: Int = 0,
    val totalSigs: Int = 0,
)
