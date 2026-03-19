package com.remotesigner.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.remotesigner.bridge.PythonBridge
import com.remotesigner.bridge.SigningOrchestrator
import com.remotesigner.data.AppDatabase
import com.remotesigner.data.ContactRepository
import com.remotesigner.data.InboxRepository
import com.remotesigner.nostr.NostrKeyManager
import com.remotesigner.usb.TrezorUsbManager

class SignerViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(application)
        val pythonBridge = PythonBridge()
        val contactRepo = ContactRepository(db.contactDao())
        val inboxRepo = InboxRepository(db.inboxDao(), pythonBridge)
        val trezorUsb = TrezorUsbManager(application)
        val orchestrator = SigningOrchestrator(pythonBridge, trezorUsb)
        return SignerViewModel(
            application = application,
            pythonBridge = pythonBridge,
            contactRepository = contactRepo,
            inboxRepository = inboxRepo,
            signingOrchestrator = orchestrator,
            trezorUsb = trezorUsb,
            keyManager = NostrKeyManager(application),
        ) as T
    }
}
