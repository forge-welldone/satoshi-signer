"""
Dependency validation for Chaquopy.
Run from Kotlin to verify all Python deps import correctly on Android.
Returns a dict with status for each dependency.
"""


def validate():
    """Test all dependency imports and basic functionality. Returns dict of results."""
    results = {}

    # 1. Test embit (PSBT parsing)
    try:
        from embit.psbt import PSBT
        from embit.transaction import Transaction
        from embit import script
        from embit.networks import NETWORKS
        # Also verify finalize_psbt location
        try:
            from embit.finalizer import finalize_psbt
            finalize_loc = "embit.finalizer"
        except ImportError:
            finalize_loc = "not found in embit.finalizer"
        results["embit"] = {"status": "ok", "detail": f"PSBT, Transaction, script imported. finalize: {finalize_loc}"}
    except Exception as e:
        results["embit"] = {"status": "error", "detail": str(e)}

    # 2. Test embit PSBT parsing with BIP-174 test vector
    try:
        import base64
        test_psbt_b64 = (
            "cHNidP8BAHUCAAAAASaBcTce3/KF6Tti/j+kvTVNP4Hc8TnREBEJCgAAAAAA"
            "/////wIA0kIAAAAAAAAZdqkUdopAu9dAy+gdmI5x3ipNXHE5ax2IrI4GAAAA"
            "AAAAGXapFGBBiQn4uaVhYVMwwSxAkZfvFymsiKwAAAAAAAEA/QABAQAAAAAB"
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP////8EAlIAAP////8C"
            "gJaYAAAAAAAZdqkUdopAu9dAy+gdmI5x3ipNXHE5ax2IrADh9QUAAAAAGXAP"
            "FIfu4mrLiIx25bW3hHiKRsp2h3dXiKwAAAAAIgYCCa0zHV2vKIiDQUzH/Y7r"
            "RihFHBApdtI/KHNbbsLGO7sY//////////8AAIABAACAAAAAAAAAAA=="
        )
        psbt = PSBT.parse(base64.b64decode(test_psbt_b64))
        assert len(psbt.inputs) >= 1
        results["embit_psbt"] = {"status": "ok", "detail": f"Parsed PSBT with {len(psbt.inputs)} inputs"}
    except Exception as e:
        results["embit_psbt"] = {"status": "error", "detail": str(e)}

    # 3. Test trezorlib core imports (bypass transport/libusb)
    try:
        from trezorlib import messages
        from trezorlib import btc
        results["trezorlib_core"] = {"status": "ok", "detail": "messages, btc imported"}
    except Exception as e:
        results["trezorlib_core"] = {"status": "error", "detail": str(e)}

    # 4. Test trezorlib Transport base class (for subclassing)
    try:
        from trezorlib.transport import Transport
        results["trezorlib_transport"] = {"status": "ok", "detail": "Transport base class imported"}
    except Exception as e:
        results["trezorlib_transport"] = {"status": "error", "detail": str(e)}

    # 5. Test trezorlib message construction
    try:
        from trezorlib import messages
        tx_input = messages.TxInputType(
            address_n=[0x80000000 | 84, 0x80000000 | 0, 0x80000000 | 0, 0, 0],
            prev_hash=bytes(32),
            prev_index=0,
            amount=100000,
            script_type=messages.InputScriptType.SPENDWITNESS,
        )
        assert tx_input.amount == 100000
        results["trezorlib_messages"] = {"status": "ok", "detail": "TxInputType constructed"}
    except Exception as e:
        results["trezorlib_messages"] = {"status": "error", "detail": str(e)}

    # 5b. Test PASSPHRASE_ON_DEVICE location
    try:
        try:
            from trezorlib.client import PASSPHRASE_ON_DEVICE
            pp_loc = "trezorlib.client"
        except ImportError:
            from trezorlib.tools import PASSPHRASE_ON_DEVICE
            pp_loc = "trezorlib.tools"
        results["passphrase_on_device"] = {"status": "ok", "detail": f"Found in {pp_loc}"}
    except Exception as e:
        results["passphrase_on_device"] = {"status": "error", "detail": str(e)}

    # 6. Test requests
    try:
        import requests
        results["requests"] = {"status": "ok", "detail": "requests imported"}
    except Exception as e:
        results["requests"] = {"status": "error", "detail": str(e)}

    # 7. Document trezorlib API version info
    try:
        from trezorlib.transport import Transport
        has_chunk_size = hasattr(Transport, 'CHUNK_SIZE')
        methods = [m for m in dir(Transport) if not m.startswith('_')]
        results["trezorlib_api"] = {
            "status": "info",
            "has_CHUNK_SIZE": has_chunk_size,
            "transport_methods": methods,
        }
    except Exception as e:
        results["trezorlib_api"] = {"status": "error", "detail": str(e)}

    # 8. Check if trezorlib imports trigger libusb loading
    try:
        from trezorlib.client import TrezorClient
        results["trezorlib_client"] = {"status": "ok", "detail": "TrezorClient imported"}
    except ImportError as e:
        if "libusb" in str(e) or "usb" in str(e).lower():
            results["trezorlib_client"] = {
                "status": "warning",
                "detail": f"libusb import issue (expected): {e}. Need to monkeypatch.",
            }
        else:
            results["trezorlib_client"] = {"status": "error", "detail": str(e)}
    except Exception as e:
        results["trezorlib_client"] = {"status": "error", "detail": str(e)}

    return results
