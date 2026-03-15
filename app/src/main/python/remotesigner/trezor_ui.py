"""
Trezor UI callbacks for Trezor Safe 3.

Implements the TrezorClientUI protocol (trezorlib.ui.TrezorClientUI) for
Android use.  PIN is always entered on the device.  Passphrase entry is
delegated to a Java callback object that can show a UI dialog — the user
chooses between on-device and host-side entry at runtime.

The callback object (bridged via Chaquopy) must implement:
  - onStatus(str) — for status messages
  - requestPassphrase(bool) -> str — for passphrase entry
    Returns "" for on-device, non-empty string for host-side entry.
"""

from typing import Optional, Union

from trezorlib import messages
from trezorlib.ui import PASSPHRASE_ON_DEVICE, PinMatrixRequestType


class AndroidTrezorUi:
    """
    UI handler for Trezor Safe 3 running inside an Android app.

    PIN is always entered on-device.  Passphrase entry is delegated to
    the callback's ``requestPassphrase(bool)`` method, which lets the
    user choose between on-device and host-side entry at runtime.
    Status strings are forwarded via ``callback.onStatus(message)``.

    Parameters
    ----------
    callback:
        A Java/Chaquopy object that implements ``onStatus(String)``
        and ``requestPassphrase(bool) -> str``.
        Pass ``None`` to fall back to on-device passphrase entry.
    """

    def __init__(self, callback=None) -> None:
        self._callback = callback

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _send_status(self, message: str) -> None:
        """Forward *message* to the Java callback if one is registered."""
        if self._callback is not None:
            try:
                self._callback.onStatus(message)
            except Exception:
                # Never let a callback error propagate into trezorlib.
                pass

    # ------------------------------------------------------------------
    # TrezorClientUI protocol
    # ------------------------------------------------------------------

    def button_request(self, br: messages.ButtonRequest) -> None:
        """
        Called by trezorlib whenever the device needs the user to press a
        button.  Maps the ButtonRequestType code to a descriptive status
        string that is forwarded to the Android callback.
        """
        code = br.code if br is not None else None

        if code == messages.ButtonRequestType.PinEntry:
            msg = "Please enter PIN on your Trezor device."
        elif code == messages.ButtonRequestType.PassphraseEntry:
            msg = "Please enter passphrase on your Trezor device."
        elif code == messages.ButtonRequestType.ConfirmOutput:
            msg = "Please confirm the output on your Trezor device."
        elif code == messages.ButtonRequestType.SignTx:
            msg = "Please confirm the transaction on your Trezor device."
        elif code == messages.ButtonRequestType.Address:
            msg = "Please confirm the address on your Trezor device."
        elif code == messages.ButtonRequestType.PublicKey:
            msg = "Please confirm the public key export on your Trezor device."
        elif code == messages.ButtonRequestType.Warning:
            msg = "Warning shown on Trezor device — please review and confirm."
        elif code == messages.ButtonRequestType.Success:
            msg = "Operation successful — please dismiss on your Trezor device."
        elif code == messages.ButtonRequestType.ResetDevice:
            msg = "Please confirm the reset on your Trezor device."
        elif code == messages.ButtonRequestType.WipeDevice:
            msg = "Please confirm the wipe on your Trezor device."
        else:
            msg = "Please confirm the action on your Trezor device."

        self._send_status(msg)

    def get_pin(self, code: Optional[PinMatrixRequestType] = None) -> str:
        """
        Called by trezorlib when a PIN is required from the host.

        Trezor Safe 3 handles PIN entirely on the device, so this path
        should never be reached.  Raise RuntimeError to make any
        accidental invocation visible rather than silently hanging.
        """
        raise RuntimeError(
            "Host-side PIN entry is not supported. "
            "Trezor Safe 3 handles PIN on-device."
        )

    def get_passphrase(self, available_on_device: bool) -> Union[str, object]:
        """
        Called by trezorlib when a passphrase is required.

        Delegates to the callback's ``requestPassphrase(availableOnDevice)``
        method.  The callback returns:
        - ``""`` (empty string) -> on-device entry (returns PASSPHRASE_ON_DEVICE)
        - non-empty string -> host-side entry (returns the string)

        Falls back to on-device entry if the callback is missing or fails.
        """
        if self._callback is not None:
            try:
                response = str(self._callback.requestPassphrase(available_on_device))
            except Exception:
                if available_on_device:
                    self._send_status("Please enter passphrase on your Trezor device.")
                    return PASSPHRASE_ON_DEVICE
                raise RuntimeError(
                    "Passphrase entry failed and on-device entry is not available."
                )

            if response == "":
                if available_on_device:
                    self._send_status("Please enter passphrase on your Trezor device.")
                    return PASSPHRASE_ON_DEVICE
                raise RuntimeError(
                    "On-device passphrase requested but not available on this device."
                )
            return response

        # No callback -- fall back to on-device if possible
        if available_on_device:
            self._send_status("Please enter passphrase on your Trezor device.")
            return PASSPHRASE_ON_DEVICE

        raise RuntimeError(
            "On-device passphrase entry is not available and no callback provided."
        )
