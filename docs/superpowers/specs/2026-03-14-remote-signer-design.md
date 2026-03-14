# Satoshi Signer — Design Specification

## Overview

An Android app that imports unsigned (or partially-signed) Bitcoin PSBTs, signs them with a Trezor hardware wallet connected via USB-C, and broadcasts the signed transaction to the Bitcoin network.

**Motivation:** Electrum requires a desktop computer to interact with hardware wallets. This app enables a mobile-only workflow: create unsigned transactions in Electrum on a remote machine, transfer the PSBT file to your phone, sign with Trezor, and broadcast — no laptop needed.

**Target hardware:** Trezor Safe 3 via USB-C OTG on Android.

**Minimum Android version:** API 28 (Android 9.0) — reliable USB Host OTG support.

**Device scope:** Trezor Safe 3 (on-device PIN, on-device passphrase). Older models (Model One with host-side PIN matrix) are not supported in v1.

## User Workflow

1. Create unsigned transaction in Electrum on a remote machine
2. Send `.psbt` file to phone (email, cloud storage, messenger, etc.)
3. Open `.psbt` file with Satoshi Signer (via file picker or Android file intent)
4. App displays transaction details for review:
   - Destination outputs (addresses + amounts)
   - Change outputs identified via BIP32 derivation matching (addresses + amounts)
   - Network fee
   - Total amount actually leaving the wallet (excluding change)
   - For multisig: which signers have signed (by fingerprint), which are pending
5. User taps "Sign with Trezor"
6. App communicates with Trezor via USB-C OTG
7. User confirms transaction on Trezor's physical screen
8. Result:
   - **Single-sig or multisig threshold met:** Broadcast to network, show txid
   - **Multisig below threshold:** Export updated PSBT with new signature added

## Supported PSBT Types

- Single-sig standard wallets (P2WPKH, P2SH-P2WPKH, P2TR)
- Multisig wallets (P2WSH, P2SH-P2WSH) — partially-signed PSBTs accepted, Trezor's signature added

## Architecture

```
+--------------------------------------------------+
|                 Android (Kotlin)                  |
|                                                   |
|  +-------------+  +--------------+                |
|  | File Import  |  | TX Review    |                |
|  | (Intent /    |  | Screen       |                |
|  | file picker) |  | (Compose)    |                |
|  +-------------+  +--------------+                |
|  | USB Manager  |  | Broadcast    |                |
|  | (permissions |  | Result       |                |
|  |  + I/O)      |  | Screen       |                |
|  +------+-------+  +--------------+                |
|         |                                          |
| ========+======================================== |
|  Chaquopy Bridge                                   |
|         |                                          |
|  +------v--------------------------------------+   |
|  |           Python Backend                     |   |
|  |                                              |   |
|  |  +--------------+  +---------------------+   |   |
|  |  | psbt_parser   |  | usb_transport       |   |   |
|  |  | (parse PSBT,  |  | (custom trezorlib   |   |   |
|  |  |  detect change |  |  Handle using       |   |   |
|  |  |  show details) |  |  Kotlin callbacks)  |   |   |
|  |  +--------------+  +---------------------+   |   |
|  |  +--------------+  +---------------------+   |   |
|  |  | signer        |  | broadcaster         |   |   |
|  |  | (trezorlib    |  | (POST to            |   |   |
|  |  |  sign_tx)     |  |  mempool.space)      |   |   |
|  |  +--------------+  +---------------------+   |   |
|  +----------------------------------------------+   |
+--------------------------------------------------+
         |
    USB-C OTG
         |
    +----v----+
    | Trezor  |
    | Safe 3  |
    +---------+
```

### Kotlin Side (Thin Shell)

Responsibilities:
- Jetpack Compose UI (4 screens)
- Android file picker and `.psbt` intent registration
- USB permission management via `UsbManager`
- USB I/O via interrupt endpoint transfers (see USB Bridge Design)
- Chaquopy bridge: call Python functions, provide USB callback object

### Python Side (All Bitcoin/Trezor Logic)

Four modules:

1. **`psbt_parser`** — Parse PSBT bytes using `embit`. Extract inputs, outputs, fee. Detect change outputs by matching BIP32 derivation paths (same master fingerprint + change derivation pattern `m/.../1/x`). Return structured dict to Kotlin for display.

2. **`usb_transport`** — Custom trezorlib `Handle` implementation. Does not use libusb. Implements `open()`, `close()`, `write_chunk(chunk: bytes)`, and `read_chunk() -> bytes` operating on 64-byte USB HID reports. Calls back into Kotlin's `UsbBridge` for actual I/O. Protocol V1 message framing (header, chunking, reassembly) is handled by trezorlib's existing `ProtocolV1` class — the custom Handle only provides raw 64-byte chunk I/O.

3. **`signer`** — Drives the signing flow using trezorlib directly (see "Signing Approach" section). Takes parsed PSBT + transport, converts PSBT fields to trezorlib protobuf messages, drives the `SignTx`/`TxRequest`/`TxAck` conversation, returns signed PSBT or finalized raw transaction.

4. **`broadcaster`** — POSTs finalized raw transaction hex to `mempool.space/api/tx` (primary) and `blockstream.info/api/tx` (fallback). Returns txid on success. On failure, returns raw hex for manual broadcast.

### Threading Model

- All signing and USB I/O runs on a Kotlin coroutine dispatched to `Dispatchers.IO`
- Chaquopy Python calls are blocking from Kotlin's perspective (run within the IO coroutine)
- USB I/O in the `UsbBridge` is synchronized (one read/write at a time)
- UI state is updated via `StateFlow` observed from the Compose layer
- If the app is backgrounded during signing, the coroutine continues; if the process is killed, the signing state is lost (user must re-import the PSBT and retry — no persistent state needed since the Trezor hasn't committed anything)

## USB Bridge Design

The core engineering challenge: making trezorlib communicate through Android's USB stack.

### Problem

trezorlib uses `libusb` via `pyusb` to access USB devices. On Android, apps cannot access USB devices directly — they must use `UsbManager` to get permission and a connection.

### USB Transfer Type

Trezor devices use USB **interrupt** endpoints (not bulk endpoints). The Safe 3 exposes `USB_ENDPOINT_XFER_INT` with endpoint addresses `0x01` (OUT) and `0x81` (IN). The Kotlin `UsbBridge` must use `UsbRequest.queue()` + `UsbRequest.requestWait()` for reliable interrupt transfers, following the same approach as the official [`trezor-android` library](https://github.com/trezor/trezor-android). Using `bulkTransfer()` on interrupt endpoints is an undocumented Android behavior and not guaranteed across devices.

### Solution: Kotlin Callback Bridge

```
Kotlin:                          Python:
+-------------------+            +---------------------+
| UsbBridge         |<-Chaquopy-| AndroidHandle       |
|                   |  callback  | (trezorlib Handle)  |
| .writeChunk(      |            |                     |
|   ByteArray[64])  |            | write_chunk(bytes): |
| .readChunk():     |            |   bridge.writeChunk |
|   ByteArray[64]   |            | read_chunk() ->     |
| .open()           |            |   bridge.readChunk  |
| .close()          |            |                     |
|                   |            | (64-byte HID reports|
| uses UsbRequest   |            |  ProtocolV1 framing |
| .queue() for      |            |  handled by         |
| interrupt xfers   |            |  trezorlib)         |
+-------------------+            +---------------------+
```

1. Kotlin: `UsbManager.requestPermission()` → user grants USB access
2. Kotlin: `UsbDeviceConnection.open()` → find interrupt endpoints (IN: `0x81`, OUT: `0x01`)
3. Kotlin: Create `UsbBridge` object with `writeChunk(ByteArray)`, `readChunk() -> ByteArray`, `open()`, `close()` methods using `UsbRequest` interrupt transfers
4. Kotlin: Pass `UsbBridge` to Python via Chaquopy
5. Python: `AndroidHandle` wraps the bridge, implements trezorlib's `Handle` interface (`write_chunk`, `read_chunk`, `open`, `close`)
6. Python: `AndroidTransport` wraps `AndroidHandle`, paired with trezorlib's `ProtocolV1` for message framing
7. trezorlib uses the transport transparently

## Signing Approach

### Decision: Use trezorlib directly, port PSBT conversion from HWI

We use `trezorlib.btc.sign_tx()` directly rather than trying to inject a custom transport into HWI's `TrezorClient`. HWI has its own device enumeration, client initialization, and session management that assumes it controls the transport lifecycle. Injecting a custom Android transport would require brittle subclassing.

Instead, we port the PSBT-to-trezorlib conversion logic from HWI's [`hwilib/devices/trezor.py`](https://github.com/bitcoin-core/HWI/blob/master/hwilib/devices/trezor.py). This involves:

- **PSBT field extraction:** Parse inputs, outputs, previous transactions from the PSBT
- **Protobuf message construction:** Convert to trezorlib's `TxInput`, `TxOutput`, `PrevTx`, `PrevInput`, `PrevOutput` messages
- **Script type mapping:** `SPENDADDRESS` (P2PKH), `SPENDP2SHWITNESS` (P2SH-P2WPKH), `SPENDWITNESS` (P2WPKH), `SPENDTAPROOT` (P2TR), `SPENDMULTISIG` (bare multisig), plus multisig variants
- **Calling `trezorlib.btc.sign_tx()`:** This function drives the multi-round `SignTx`/`TxRequest`/`TxAck` conversation internally
- **Collecting signatures:** Insert returned signatures back into the PSBT

**Library roles:**
- **trezorlib** — USB wire protocol, protobuf messaging, `sign_tx()` state machine, device session management
- **embit** — PSBT parsing/serialization, transaction finalization, signature completeness checking
- **HWI source** — reference for the PSBT-to-trezorlib conversion logic (ported, not used as a dependency)

This approach means we don't depend on HWI at runtime, avoiding its dependency tree (`hidapi`, `pyserial`, etc.) and import-time libusb issues.

## UI Screens

### Screen 1: Home

- "Open PSBT File" button → launches Android file picker (`.psbt` filter)
- App registers as handler for `.psbt` file type via Android intent filter

**Intent filter configuration** (in `AndroidManifest.xml`):
```xml
<!-- file:// URIs (rare but works with pathPattern) -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="file" />
    <data android:pathPattern=".*\\.psbt" />
    <data android:mimeType="*/*" />
</intent-filter>
<!-- content:// URIs (common, but pathPattern unreliable) -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="content" />
    <data android:mimeType="application/octet-stream" />
</intent-filter>
```

**Known limitation:** Android's `pathPattern` does not reliably match `content://` URIs because file managers and email apps typically don't preserve the original filename in the URI path. The `content://` intent filter matches broadly on `application/octet-stream`, which means the app may appear in "Open with" for non-PSBT binary files. The app validates the file content on import and shows a clear error for non-PSBT files. **The file picker on Screen 1 is the primary, reliable import path.** Intent-based "Open with" is best-effort.

### Screen 2: Transaction Review

```
---------------------------------
  Transaction Details
---------------------------------
  Sending:
    -> bc1q...xyz    0.05000000 BTC
    -> bc1q...abc    0.02000000 BTC

  Change (back to wallet):
    <- bc1q...chg    0.42850000 BTC

  Fee:           0.00015000 BTC
  Total sent:    0.07000000 BTC
---------------------------------
  Status: Unsigned
  -- or --
  Status: Partially signed (1 of 3)
  Signers:
    [check] 3a4b5c6d  (signed)
    [ ]     7c8d9e0f  (pending)  <- this device
    [ ]     ef012345  (pending)
---------------------------------
  [ Sign with Trezor ]
---------------------------------
```

- Change outputs identified by BIP32 derivation matching (same master fingerprint + `.../1/x` path pattern)
- Warning if any outputs lack BIP32 derivation data: "Cannot verify change — review all outputs carefully"
- Warning if fee exceeds 0.01 BTC (sanity check)
- Connected Trezor's fingerprint highlighted with " <- this device"

### Screen 3: Signing in Progress

- Status: "Connect Trezor via USB-C" → "Communicating with Trezor..." → "Confirm on device..."
- Handles: device not found, wrong PIN, user rejection

### Screen 4: Result

**If fully signed:**
- "Transaction signed successfully"
- [ Broadcast ] button (disabled after first tap; re-enabled on failure)
- After broadcast: txid displayed as tappable link to `mempool.space/tx/{txid}`
- Raw hex in scrollable text field with "Copy to Clipboard" button for manual broadcast

**If partially signed (multisig below threshold):**
- "Signature added (2 of 3)"
- [ Export PSBT ] button → Android share sheet

## Signing Flow (Python)

```python
def sign_psbt(psbt_bytes: bytes, bridge: UsbBridge) -> dict:
    # 1. Parse PSBT
    psbt = parse_psbt(psbt_bytes)  # using embit

    # 2. Connect to Trezor via custom transport
    transport = AndroidTransport(bridge)
    client = TrezorClient(transport, ui=AndroidTrezorUi(status_callback))

    # 3. Convert PSBT fields to trezorlib protobuf messages
    #    (logic ported from HWI's hwilib/devices/trezor.py)
    inputs, outputs, prev_txes = psbt_to_trezor_args(psbt)

    # 4. Sign via trezorlib (drives multi-round protocol internally)
    signatures, serialized_tx = trezorlib.btc.sign_tx(
        client, coin_name="Bitcoin",
        inputs=inputs, outputs=outputs,
        prev_txes=prev_txes,
    )

    # 5. Insert signatures back into PSBT
    signed_psbt = insert_signatures(psbt, signatures)

    # 6. Check completeness
    if is_complete(signed_psbt):
        final_tx = finalize_and_extract(signed_psbt)
        return {"status": "complete", "raw_tx": final_tx.hex()}
    else:
        return {"status": "partial", "psbt": signed_psbt.to_bytes()}
```

**`AndroidTrezorUi`** implements trezorlib's UI callback interface for Safe 3 (on-device PIN/passphrase):
- `get_pin()` — raises `NotImplementedError` (Safe 3 uses on-device PIN only)
- `get_passphrase()` — returns `PassphraseAck(on_device=True)` to signal on-device entry
- `button_request()` — calls `status_callback("confirm_on_device")` to update Kotlin UI to show "Confirm on your Trezor"

**`psbt_to_trezor_args()`** is the core conversion function, ported from HWI. Estimated ~300-500 lines including multisig `MultisigRedeemScriptType` construction, Taproot handling (no `prev_tx` needed when `TXORIGINS` capability is present), and script type mapping. It:
- Extracts inputs with derivation paths, amounts, script types
- Extracts outputs with derivation paths (for change identification)
- Extracts full previous transactions for non-Taproot input verification
- Maps script types to trezorlib's `InputScriptType` enum
- Handles multisig structures (`MultisigRedeemScriptType`)

## Broadcasting

POST raw hex to public APIs:

1. Primary: `POST https://mempool.space/api/tx` (body: raw hex string)
2. Fallback: `POST https://blockstream.info/api/tx`

- HTTP timeout: 10 seconds per request
- Single retry per endpoint on transient failure (timeout, 5xx) before failover
- Broadcast button disabled after first tap to prevent double-submission (re-enabled on failure)
- On failure of both: raw hex displayed in scrollable text field with "Copy to Clipboard" button

## PIN and Passphrase Handling

**PIN:** Trezor Safe 3 uses on-device PIN entry exclusively. No host-side PIN UI is needed. The app simply displays "Enter PIN on your Trezor" during the PIN phase. Older models that use host-side PIN matrix (Model One with firmware < 1.9.0) are not supported.

**Passphrase:** Only on-device passphrase entry is supported. Users must configure their Trezor for on-device passphrase input (this is the default for Safe 3). The app displays "Enter passphrase on your Trezor" if prompted. Host-side passphrase input is not implemented in v1.

## Error Handling

| Error | When | User Sees |
|-------|------|-----------|
| No Trezor detected | USB not connected or no OTG | "Connect Trezor via USB-C cable" |
| USB permission denied | User declined Android prompt | "USB permission required — tap to retry" |
| Wrong PIN | Entered incorrectly on Trezor | "Wrong PIN — try again (X attempts left)" |
| User rejected on Trezor | Declined tx on device screen | "Transaction cancelled on device" |
| PSBT missing derivation paths | Malformed/stripped PSBT | "Cannot identify signing keys — re-export from Electrum" |
| PSBT missing prev tx data | Incomplete PSBT export | "Missing previous transaction data — re-export with full UTXO info" |
| No matching key on device | PSBT for different wallet | "This Trezor does not hold keys for this transaction" |
| Passphrase needed (host-side) | Hidden wallet with host passphrase config | "Please configure on-device passphrase on your Trezor" |
| Broadcast rejected | Invalid tx / already confirmed | Show node's error message verbatim |
| Fee too high | Sanity check > 0.01 BTC | Warning before signing, user can proceed or cancel |
| Network unavailable | Airplane mode / no connectivity | "No network connection — use Copy Raw Hex to broadcast manually" |

## Security

- The app never touches private keys — all signing happens on the Trezor's secure element
- No seed phrases, no key material stored on the phone
- PSBT files may contain xpubs/derivation info — standard risk, same as Electrum
- USB communication is direct (no network intermediary)
- Broadcasting uses HTTPS to public APIs
- Signing works fully offline; only broadcasting requires network

## Dependency Validation Plan

**This must be the first implementation task — a blocking validation before any feature work.**

The risk: Python packages with native C extensions may not import under Chaquopy on Android. Validate each dependency:

| Dependency | Risk | Validation |
|------------|------|------------|
| `trezorlib` | Medium — depends on `protobuf`, may eagerly import `libusb`/`pyusb` transports | Import trezorlib in Chaquopy; patch or monkeypatch transport imports to skip libusb; confirm core protobuf and signing modules load |
| `embit` | Low — pure Python with optional `libsecp256k1` | Import embit in Chaquopy; confirm PSBT parsing works with pure-Python secp256k1 fallback; benchmark parse time |
| `requests` | None — pure Python | Should work out of the box |
| `protobuf` | Low — has pure-Python backend | Force pure-Python protobuf implementation if C extension unavailable |

**Validation deliverable:** A minimal Chaquopy Android project that successfully imports all four dependencies and runs a basic PSBT parse + trezorlib protobuf message construction (without a real Trezor connected).

## Dependencies (Pinned)

### Kotlin/Android
- Jetpack Compose (BOM 2024.x+)
- Android USB Host API (SDK 28+)
- Chaquopy 17.0.0 (Python 3.13 runtime)

### Python
- `trezor` == 0.13.9 (provides `trezorlib` — Trezor communication + protobuf; pinned to avoid breaking changes to Handle/Transport API)
- `embit` >= 0.7 (PSBT parsing, lightweight, pure-Python fallback)
- `requests` >= 2.28 (HTTP broadcasting)

**Not a runtime dependency:**
- HWI source code — referenced for porting `psbt_to_trezor_args()` conversion logic only

## State Management

- No persistent state. The app is stateless between sessions.
- PSBT data lives in memory only during the active signing flow.
- If the process is killed mid-signing, no harm done — the Trezor hasn't committed anything. User re-imports the PSBT and retries.
- No local database, no wallet storage, no key caching.

## Testnet Support (Development)

Testnet/signet support is required during development for testing the full signing + broadcast flow without risking real funds. Implementation:
- Toggle in a developer settings screen (not exposed in production builds) or via build variant
- Changes `coin_name` parameter to `"Testnet"` in `trezorlib.btc.sign_tx()`
- Changes broadcast endpoint to `mempool.space/testnet/api/tx`
- Trezor must have testnet accounts set up (different derivation paths: `m/84'/1'/0'`)

## Future Enhancements (Out of Scope for v1)

- Nostr-based PSBT transfer from remote machine
- QR code scanning for PSBT import
- User-assigned labels for multisig signer fingerprints
- Ledger support (Bluetooth transport)
- Testnet/signet toggle in production UI
- Host-side passphrase input for non-Safe-3 Trezor models
