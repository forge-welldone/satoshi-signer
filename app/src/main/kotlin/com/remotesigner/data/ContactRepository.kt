package com.remotesigner.data

import com.remotesigner.bridge.SignerInfo
import kotlinx.coroutines.flow.Flow

class ContactRepository(private val contactDao: ContactDao) {

    val allWithFingerprints: Flow<List<ContactWithFingerprints>> =
        contactDao.getAllWithFingerprints()

    suspend fun enrichSigners(signers: List<SignerInfo>): List<SignerInfo> {
        val fingerprints = signers.map { it.fingerprint }
        val contactMap = contactDao.findByFingerprints(fingerprints)
            .flatMap { cwf -> cwf.fingerprints.map { fp -> fp.fingerprint to cwf } }
            .toMap()
        return signers.map { signer ->
            val contact = contactMap[signer.fingerprint]
            signer.copy(
                contactLabel = contact?.contact?.label,
                contactId = contact?.contact?.id,
            )
        }
    }

    suspend fun saveContact(label: String, fingerprint: String, existingContactId: Long?) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        if (existingContactId != null) {
            val updated = contactDao.updateFingerprintContact(existingContactId, normalized)
            if (updated == 0) {
                contactDao.insertFingerprint(
                    ContactFingerprint(contactId = existingContactId, fingerprint = normalized)
                )
            }
        } else {
            val trimmed = label.trim()
            if (trimmed.isEmpty() || trimmed.length > 50) return
            val id = contactDao.insertContact(Contact(label = trimmed))
            contactDao.insertFingerprint(
                ContactFingerprint(contactId = id, fingerprint = normalized)
            )
        }
    }

    suspend fun updateContact(contactId: Long, newLabel: String, npub: String?) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty() || trimmed.length > 50) return
        contactDao.updateContact(
            Contact(id = contactId, label = trimmed, npub = npub?.trim()?.ifEmpty { null })
        )
    }

    suspend fun addFingerprint(contactId: Long, fingerprint: String) {
        val normalized = FingerprintValidator.normalize(fingerprint) ?: return
        contactDao.insertFingerprint(
            ContactFingerprint(contactId = contactId, fingerprint = normalized)
        )
    }

    suspend fun deleteContact(contactId: Long) = contactDao.deleteContact(contactId)
    suspend fun deleteFingerprint(fingerprintId: Long) = contactDao.deleteFingerprint(fingerprintId)
}
