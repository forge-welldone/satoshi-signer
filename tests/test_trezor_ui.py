"""Unit tests for AndroidTrezorUi passphrase handling."""

import pytest
from unittest.mock import Mock
from remotesigner.trezor_ui import AndroidTrezorUi, PASSPHRASE_ON_DEVICE


class TestGetPassphrase:
    """Tests for get_passphrase() with the new callback-based flow."""

    def test_on_device_when_callback_returns_empty(self):
        """Empty string from callback means on-device entry."""
        callback = Mock()
        callback.requestPassphrase.return_value = ""
        ui = AndroidTrezorUi(callback)
        result = ui.get_passphrase(available_on_device=True)
        assert result is PASSPHRASE_ON_DEVICE
        callback.requestPassphrase.assert_called_once_with(True)

    def test_host_passphrase_returned_as_string(self):
        """Non-empty string from callback is returned as the passphrase."""
        callback = Mock()
        callback.requestPassphrase.return_value = "my secret"
        ui = AndroidTrezorUi(callback)
        result = ui.get_passphrase(available_on_device=True)
        assert result == "my secret"

    def test_host_passphrase_when_not_available_on_device(self):
        """When on-device is not available, callback must provide a passphrase."""
        callback = Mock()
        callback.requestPassphrase.return_value = "typed on phone"
        ui = AndroidTrezorUi(callback)
        result = ui.get_passphrase(available_on_device=False)
        assert result == "typed on phone"
        callback.requestPassphrase.assert_called_once_with(False)

    def test_empty_string_raises_when_not_available_on_device(self):
        """Empty string + no on-device = error (can't use on-device sentinel)."""
        callback = Mock()
        callback.requestPassphrase.return_value = ""
        ui = AndroidTrezorUi(callback)
        with pytest.raises(RuntimeError, match="not available"):
            ui.get_passphrase(available_on_device=False)

    def test_callback_exception_falls_back_to_on_device(self):
        """If callback raises, fall back to on-device when available."""
        callback = Mock()
        callback.requestPassphrase.side_effect = Exception("bridge error")
        ui = AndroidTrezorUi(callback)
        result = ui.get_passphrase(available_on_device=True)
        assert result is PASSPHRASE_ON_DEVICE

    def test_callback_exception_raises_when_no_on_device(self):
        """If callback raises and on-device not available, propagate error."""
        callback = Mock()
        callback.requestPassphrase.side_effect = Exception("bridge error")
        ui = AndroidTrezorUi(callback)
        with pytest.raises(RuntimeError, match="failed"):
            ui.get_passphrase(available_on_device=False)

    def test_no_callback_falls_back_to_on_device(self):
        """When no callback is provided, fall back to on-device."""
        ui = AndroidTrezorUi(callback=None)
        result = ui.get_passphrase(available_on_device=True)
        assert result is PASSPHRASE_ON_DEVICE

    def test_no_callback_raises_when_no_on_device(self):
        """When no callback and no on-device, raise error."""
        ui = AndroidTrezorUi(callback=None)
        with pytest.raises(RuntimeError):
            ui.get_passphrase(available_on_device=False)
