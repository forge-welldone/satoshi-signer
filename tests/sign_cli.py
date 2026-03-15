#!/usr/bin/env python3
"""CLI for desktop PSBT signing and cassette recording.

Usage:
    python tests/sign_cli.py parse <psbt_file>
    python tests/sign_cli.py sign <psbt_file> [--record <cassette_path>] [--network main|test]
"""

import argparse
import base64
import json
import sys
import getpass
import os

# Add Python source to path (same as conftest.py)
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'python'))
sys.path.insert(0, os.path.dirname(__file__))

from remotesigner.psbt_parser import parse_psbt
from remotesigner.signer import sign_psbt


class PrintStatusCallback:
    """Status callback that prints to stdout and handles passphrase prompts."""

    def onStatus(self, message):
        print(f"  [{message}]")

    def requestPassphrase(self, available_on_device):
        if available_on_device:
            choice = input("  Enter passphrase on (d)evice or (p)hone? [d]: ").strip().lower()
            if choice != "p":
                return ""  # empty string = on-device
        passphrase = getpass.getpass("  Passphrase: ")
        return passphrase


def load_psbt(path: str) -> bytes:
    """Load a PSBT from file. Accepts base64-encoded or raw binary."""
    with open(path, "rb") as f:
        raw = f.read()

    # Check for PSBT magic bytes (raw binary)
    if raw.startswith(b"psbt\xff"):
        return raw

    # Try base64 decode (strip whitespace first)
    try:
        return base64.b64decode(raw.strip())
    except Exception:
        raise ValueError(f"Cannot parse {path}: not a valid PSBT (binary or base64)")


def cmd_parse(args):
    """Parse and display PSBT contents."""
    psbt_bytes = load_psbt(args.psbt_file)
    result = parse_psbt(psbt_bytes, network=args.network)

    print(f"\nPSBT Summary ({result['status']}):")
    print(f"  Inputs:  {len(result['inputs'])}")
    print(f"  Outputs: {len(result['outputs'])}")
    print(f"  Fee:     {result['fee']} sats")

    for inp in result["inputs"]:
        print(f"\n  Input #{inp['index']}:")
        print(f"    TXID:   {inp['txid']}")
        print(f"    Vout:   {inp['vout']}")
        print(f"    Amount: {inp['amount']} sats")

    for out in result["outputs"]:
        change = " (change)" if out["is_change"] else ""
        print(f"\n  Output #{out['index']}{change}:")
        print(f"    Address: {out['address']}")
        print(f"    Amount:  {out['amount']} sats")

    if result["signers"]:
        print("\n  Signers:")
        for s in result["signers"]:
            status = "signed" if s["signed"] else "unsigned"
            print(f"    {s['fingerprint']}: {status}")


def _find_trezor_transport():
    """Find a Trezor device, trying WebUSB first then HID."""
    from trezorlib.models import TREZORS

    try:
        from trezorlib.transport.webusb import WebUsbTransport
        devices = list(WebUsbTransport.enumerate(models=TREZORS))
        if devices:
            return devices[0]
    except Exception:
        pass

    try:
        from trezorlib.transport.hid import HidTransport
        devices = list(HidTransport.enumerate(models=TREZORS))
        if devices:
            return devices[0]
    except Exception:
        pass

    return None


def _query_device_info() -> dict:
    """Query Trezor model and firmware version. Returns metadata dict."""
    try:
        from trezorlib.client import TrezorClient
        from remotesigner.trezor_ui import AndroidTrezorUi

        transport = _find_trezor_transport()
        if transport:
            transport.open()
            client = TrezorClient(transport, ui=AndroidTrezorUi())
            features = client.features
            info = {
                "trezor_model": features.model or "",
                "firmware_version": (
                    f"{features.fw_major}.{features.fw_minor}.{features.fw_patch}"
                    if features.fw_major is not None
                    else ""
                ),
            }
            client.close()
            return info
    except Exception:
        pass
    return {"trezor_model": "", "firmware_version": ""}


def cmd_sign(args):
    """Sign a PSBT with a real Trezor, optionally recording USB exchanges."""
    from desktop_bridge import DesktopUsbBridge, RecordingBridge

    psbt_bytes = load_psbt(args.psbt_file)

    # Query device info BEFORE signing (avoids opening a second USB connection after)
    device_info = {}
    if args.record:
        print("Querying Trezor device info...")
        device_info = _query_device_info()

    # Build the bridge stack
    bridge = DesktopUsbBridge()
    recorder = None
    if args.record:
        recorder = RecordingBridge(bridge)
        bridge = recorder

    print(f"Signing PSBT from {args.psbt_file} (network={args.network})...")
    if args.record:
        print(f"Recording USB exchanges to {args.record}")

    # Open the bridge before signing (mirrors Android ViewModel lifecycle).
    # AndroidTransport.open() is a no-op, so we open the bridge directly.
    bridge.open()
    callback = PrintStatusCallback()
    try:
        result = sign_psbt(psbt_bytes, bridge, status_callback=callback, network=args.network)
    finally:
        bridge.close()

    if result["status"] in ("complete", "partial"):
        print(f"\nSigning successful! (status: {result['status']})")
        print(f"  Signed PSBT (base64): {result['psbt'][:80]}...")
        if "raw_tx" in result:
            print(f"  Raw TX (hex): {result['raw_tx'][:80]}...")
    else:
        print(f"\nSigning failed: {result.get('message', 'unknown error')}")
        sys.exit(1)

    # Save cassette if recording
    if recorder and args.record:
        recorder.save_cassette(
            args.record,
            scenario=os.path.splitext(os.path.basename(args.record))[0],
            network=args.network,
            input_psbt_b64=base64.b64encode(psbt_bytes).decode(),
            **device_info,
        )
        print(f"  Cassette saved to {args.record}")


def main():
    parser = argparse.ArgumentParser(
        description="Desktop PSBT signing and cassette recording"
    )
    parser.add_argument(
        "--network", default="main", choices=["main", "test"],
        help="Bitcoin network (default: main)"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # parse command
    p_parse = subparsers.add_parser("parse", help="Parse and display a PSBT")
    p_parse.add_argument("psbt_file", help="Path to PSBT file (binary or base64)")

    # sign command
    p_sign = subparsers.add_parser("sign", help="Sign a PSBT with Trezor")
    p_sign.add_argument("psbt_file", help="Path to PSBT file (binary or base64)")
    p_sign.add_argument("--record", help="Path to save cassette JSON file")

    args = parser.parse_args()

    if args.command == "parse":
        cmd_parse(args)
    elif args.command == "sign":
        cmd_sign(args)


if __name__ == "__main__":
    main()
