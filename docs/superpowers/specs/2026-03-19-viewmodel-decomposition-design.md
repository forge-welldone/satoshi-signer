# SignerViewModel Decomposition

> Refactoring TODO #6: Extract responsibilities from SignerViewModel (God Object)
>
> Date: 2026-03-19

## Problem

`SignerViewModel` is 680 lines handling: PSBT parsing, signing orchestration, USB polling, Nostr lifecycle, inbox CRUD, contact CRUD, NFC state, passphrase/account-path callbacks, and broadcast. Mutable `var` fields must be coordinated manually. The ViewModel can't be unit-tested because it directly instantiates `PythonBridge`, `TrezorUsbManager`, and `AppDatabase`.

## Design Decisions

- **Three extracted classes:** `ContactRepository`, `InboxRepository`, `SigningOrchestrator`. NFC state (~30 lines) and PSBT parsing (~70 lines) stay in the ViewModel — they're small and tightly coupled to state transitions.
- **One interface:** `PythonBridgeInterface` — the only dependency that's truly untestable without the runtime (Chaquopy). Everything else uses concrete classes with constructor injection.
- **Manual `ViewModelProvider.Factory`:** No DI framework. A simple factory class creates the ViewModel with its dependencies.
- **Bottom-up extraction order:** `ContactRepository` → `PythonBridgeInterface` → `InboxRepository` → `SigningOrchestrator` → `ViewModelFactory`. (PythonBridgeInterface must precede InboxRepository because InboxRepository takes it as a constructor parameter.)

## Architecture

```
SignerViewModelFactory (creates all dependencies)
    └── SignerViewModel (composition root, holds AppState, drives navigation)
            ├── ContactRepository (wraps ContactDao, enriches signers)
            ├── InboxRepository (wraps InboxDao, handles inbox events)
            ├── SigningOrchestrator (USB, bridge lifecycle, Python signing)
            ├── PythonBridgeInterface (parsePsbt, signPsbt, broadcast)
            ├── TrezorUsbManager (USB device discovery, permissions)
            ├── NostrReceiver (unchanged, onItem → inboxRepository)
            └── NostrKeyManager (unchanged)
```

## Component Details

### ContactRepository

**File:** `data/ContactRepository.kt`
**Constructor:** `ContactRepository(contactDao: ContactDao)`

Responsibilities:
- Expose `allWithFingerprints: Flow<List<ContactWithFingerprints>>` (currently `contacts` on ViewModel)
- `suspend fun enrichSigners(signers: List<SignerInfo>): List<SignerInfo>` — consolidates the fingerprint→contact mapping duplicated in `parsePsbt()` and `reEnrichSigners()`. Must be `suspend` because `contactDao.findByFingerprints()` is a suspend function.
- `saveContact(label, fingerprint, existingContactId)` — validation + DAO writes
- `updateContact(contactId, newLabel, npub)` — validation + DAO update
- `addFingerprint(contactId, fingerprint)` — validation + DAO insert
- `deleteContact(contactId)` / `deleteFingerprint(fingerprintId)` — DAO deletes

Validation logic (`FingerprintValidator.normalize`, label length/empty checks) moves here from the ViewModel.

### InboxRepository

**File:** `data/InboxRepository.kt`
**Constructor:** `InboxRepository(inboxDao: InboxDao, pythonBridge: PythonBridgeInterface)`

Responsibilities:
- Expose `items: Flow<List<InboxItemEntity>>` (raw Flow from DAO). The ViewModel applies `.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())` to convert to `StateFlow`, since the repository doesn't have access to `viewModelScope`.
- `suspend fun cleanupAndSeedIds(): Set<String>` — runs `deleteExpired()`, returns IDs from `getAllOnce()` for `NostrReceiver.seedSeenIds()`. Must be `suspend` because both DAO calls are suspend functions.
- `suspend fun handleInboxEvent(item: InboxItemEntity)` — `insertIgnore` + parse PSBT for amount/network enrichment. Must be `suspend` because it calls DAO and `pythonBridge.parsePsbt` (on IO dispatcher). The caller (`NostrReceiver.onItem` lambda in ViewModel) wraps the call in `viewModelScope.launch(Dispatchers.IO)`.
- Pass-throughs: `updateStatus()`, `updateSigned()`, `updateBroadcast()`

Takes `PythonBridgeInterface` because `handleInboxEvent` calls `parsePsbt` for inbox enrichment.

### PythonBridgeInterface

**File:** `bridge/PythonBridgeInterface.kt`

`SigningCallback` is currently a nested interface inside `PythonBridge`. It must be extracted to a top-level interface in `PythonBridgeInterface.kt` to avoid a circular dependency (the interface can't reference its own implementation class). This changes import paths in `SigningCallbackImpl`, `SignerViewModel`, and `ChaquopyE2ETest` from `PythonBridge.SigningCallback` to `SigningCallback`.

```kotlin
// bridge/PythonBridgeInterface.kt

/** Callback interface for signing operations. Called from Python's thread via Chaquopy. */
interface SigningCallback {
    fun onStatus(status: String)
    fun requestPassphrase(availableOnDevice: Boolean): String
    fun requestAccountPath(): String
}

interface PythonBridgeInterface {
    fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?>
    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: SigningBridge,
        callback: SigningCallback?,
        network: String = "main",
    ): Map<String, Any?>
    fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?>
}
```

The existing `PythonBridge` class:
- Adds `: PythonBridgeInterface`
- Removes the nested `SigningCallback` interface (now top-level)
- Updates `signPsbt` parameter type from `PythonBridge.SigningCallback?` to `SigningCallback?`

`SigningCallbackImpl` changes its supertype from `PythonBridge.SigningCallback` to `SigningCallback`.

### SigningOrchestrator

**File:** `bridge/SigningOrchestrator.kt`
**Constructor:** `SigningOrchestrator(pythonBridge: PythonBridgeInterface, trezorUsb: TrezorUsbManager)`

Note: `inboxRepository` is removed from the constructor — the orchestrator returns `SigningResult` and the ViewModel handles all inbox status updates based on the result.

Responsibilities:
- USB device polling, permission handling, bridge open/close lifecycle
- Passphrase/account-path callback wiring (`_passphraseRequest`, `_accountPathRequest` StateFlows)
- Python signing call and result interpretation
- Returns `SigningResult` sealed class — does NOT mutate `AppState`

**Coroutine scope:** Signing methods (`signWithTrezor`, `signWithBridge`) are `suspend` functions. The ViewModel wraps calls in `viewModelScope.launch`. The device-polling loop runs inside the suspend function using `coroutineScope { }` or accepts a `CoroutineScope` parameter. `signingJob` stays in the ViewModel (it needs to cancel the launch), and the orchestrator just provides `cancel()` to clean up its internal state (bridge, callback).

Mutable state that moves here: `currentUsbBridge`, `currentSigningCallback`, `_passphraseRequest`, `_accountPathRequest`.

Mutable state that stays in ViewModel: `signingJob` (the ViewModel owns the coroutine launch/cancel lifecycle).

```kotlin
sealed class SigningResult {
    data class Complete(val rawHex: String?, val network: String) : SigningResult()
    data class Partial(val updatedPsbtBytes: ByteArray?, val network: String) : SigningResult()
    data object Cancelled : SigningResult()
    data class Error(val message: String, val log: String) : SigningResult()
}
```

The ViewModel maps `SigningResult` → `AppState` transitions and handles inbox status updates (SIGNING→SIGNED, SIGNING→FAILED, etc.).

Progress messages (`"Opening USB connection..."`, `"Python: ..."`) are reported via an `onProgress: (String) -> Unit` callback parameter on the suspend signing functions:

```kotlin
suspend fun signWithTrezor(
    psbtBytes: ByteArray,
    network: String,
    onProgress: (String) -> Unit,
): SigningResult

suspend fun signWithBridge(
    bridge: SigningBridge,
    psbtBytes: ByteArray,
    network: String,
    onProgress: (String) -> Unit,
    withPassphraseUI: Boolean = true,
): SigningResult
```

The ViewModel wraps `onProgress` to build `AppState.Signing` log state. Note: `signWithTrezor` uses `suspendCancellableCoroutine` to convert the callback-based `trezorUsb.requestPermission()` to a suspending call.

`signWithBridge()` remains for test use (`PlaybackBridge` / cassette replay).

### SignerViewModelFactory

**File:** `viewmodel/SignerViewModelFactory.kt`

```kotlin
class SignerViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
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
```

Activity usage changes from `by viewModels()` to `by viewModels { SignerViewModelFactory(application) }`.

### Updated SignerViewModel

**Constructor:**
```kotlin
class SignerViewModel(
    application: Application,
    private val pythonBridge: PythonBridgeInterface,
    private val contactRepository: ContactRepository,
    private val inboxRepository: InboxRepository,
    private val signingOrchestrator: SigningOrchestrator,
    val trezorUsb: TrezorUsbManager,
    val keyManager: NostrKeyManager,
) : AndroidViewModel(application)
```

**What stays (~350 lines):**
- `_state` / `state` — single owner of `AppState`
- `parsePsbt()` — maps Python dict → `AppState.TransactionReview`, uses `contactRepository.enrichSigners()`
- `loadPsbt(uri)` / `loadPsbt(bytes)` — entry points
- NFC state — `startNfcWaiting`, `stopNfcWaiting`, `onNfcTagResult`, `clearNfcResult`
- `encryptForNfc()` — one-liner
- `goHome()` — state cleanup
- `cancelSigning()` — cancels `signingJob`, delegates to `signingOrchestrator.cancel()` (which closes bridge, cancels callback, clears passphrase/account-path requests), then ViewModel cleans up NFC state and navigates back
- `broadcast()` — 20 lines, reads/writes `_state`, calls `pythonBridge.broadcast()` and `inboxRepository.updateBroadcast()`
- `goHome()` — state cleanup, delegates inbox status writes through `inboxRepository.updateStatus()`
- Nostr wiring — `NostrReceiver` created inside ViewModel (needs `viewModelScope`). The `onItem` lambda changes to: `{ item -> viewModelScope.launch(Dispatchers.IO) { inboxRepository.handleInboxEvent(item) } }`. The private `handleInboxEvent()` method is removed from the ViewModel.
- `relayConnectedCount` / `relayStatuses` — pass-throughs from `NostrReceiver`, unchanged
- `signInboxItem()` / `deleteInboxItem()` / `openInboxResult()` — thin delegators to `inboxRepository`
- `showContacts()` / `showEncryptPassphrase()` — one-liners

**Mutable vars that stay:** `currentPsbtBytes`, `currentNetwork`, `currentDescription`, `currentSigningInboxId` — PSBT-session state coordinated across parse→sign→result→home.

**Mutable vars that move to `SigningOrchestrator`:** `currentUsbBridge`, `currentSigningCallback`, `_passphraseRequest`, `_accountPathRequest`.

**`signingJob` stays in the ViewModel** — it owns the coroutine launch and needs to cancel it. The orchestrator's `cancel()` handles internal cleanup only (close bridge, cancel callback, clear passphrase/account-path flows).

**Contact methods become one-liners** delegating to `contactRepository` + calling `reEnrichSigners()`.

**`reEnrichSigners()` simplifies to:**
```kotlin
private suspend fun reEnrichSigners() {
    val s = _state.value
    if (s is AppState.TransactionReview) {
        _state.value = s.copy(signers = contactRepository.enrichSigners(s.signers))
    }
}
```

**`init` block simplifies to:**
```kotlin
init {
    inboxSeedJob = viewModelScope.launch {
        val ids = inboxRepository.cleanupAndSeedIds()
        if (ids.isNotEmpty()) nostrReceiver.seedSeenIds(ids)
    }
}
```

## Testing Strategy

With constructor injection, the ViewModel becomes testable with fakes:

```kotlin
val vm = SignerViewModel(
    application = app,
    pythonBridge = FakePythonBridge(),
    contactRepository = ContactRepository(fakeContactDao),
    inboxRepository = InboxRepository(fakeInboxDao, FakePythonBridge()),
    signingOrchestrator = SigningOrchestrator(FakePythonBridge(), fakeTrezorUsb),
    trezorUsb = fakeTrezorUsb,
    keyManager = fakeKeyManager,
)
```

This enables refactoring-todo #12 (ViewModel unit tests): state transitions, cancellation, inbox status, contact CRUD, error propagation.

Each extracted class is also independently testable:
- `ContactRepository` — with Room in-memory DB
- `InboxRepository` — with Room in-memory DB + `FakePythonBridge`
- `SigningOrchestrator` — with `FakePythonBridge` + `PlaybackBridge` (no `inboxRepository` dependency)

## Execution Order

1. `ContactRepository` — extract, update ViewModel to delegate, verify existing tests pass
2. `PythonBridgeInterface` — extract interface and top-level `SigningCallback`, make `PythonBridge` implement it
3. `InboxRepository` — extract, update ViewModel to delegate, verify existing tests pass
4. `SigningOrchestrator` — extract, update ViewModel to delegate, verify existing tests pass
5. `SignerViewModelFactory` — add factory, update Activity, verify app launches
6. Update existing tests — `ChaquopyE2ETest` currently calls `SignerViewModel(app)` directly. Must change to `SignerViewModelFactory(app).create(SignerViewModel::class.java)` since the constructor now takes 7 parameters. The `signWithBridge()` test-visible method still works. Import paths for `SigningCallback` change from `PythonBridge.SigningCallback` to `SigningCallback` in `SigningCallbackImpl` and any test files.

## Files Changed

**New files:**
- `app/src/main/kotlin/com/remotesigner/data/ContactRepository.kt`
- `app/src/main/kotlin/com/remotesigner/data/InboxRepository.kt`
- `app/src/main/kotlin/com/remotesigner/bridge/PythonBridgeInterface.kt`
- `app/src/main/kotlin/com/remotesigner/bridge/SigningOrchestrator.kt`
- `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModelFactory.kt`

**Modified files:**
- `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt` — add `: PythonBridgeInterface`, remove nested `SigningCallback` interface, update `signPsbt` param type
- `app/src/main/kotlin/com/remotesigner/bridge/SigningCallbackImpl.kt` (currently in `PythonBridge.kt`) — change supertype from `PythonBridge.SigningCallback` to `SigningCallback`
- `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt` — new constructor, delegate to extracted classes, remove `handleInboxEvent`, update NostrReceiver onItem lambda
- `app/src/main/kotlin/com/remotesigner/MainActivity.kt` — use `SignerViewModelFactory`
- `app/src/androidTest/kotlin/com/remotesigner/ChaquopyE2ETest.kt` — change `SignerViewModel(app)` to `SignerViewModelFactory(app).create(SignerViewModel::class.java)`, update `SigningCallback` import path
