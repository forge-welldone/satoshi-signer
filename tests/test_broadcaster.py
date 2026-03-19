import pytest
from unittest.mock import patch, MagicMock
from remotesigner.broadcaster import broadcast_transaction


class TestBroadcaster:
    @patch("remotesigner.broadcaster.requests.post")
    def test_broadcasts_to_primary_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="abc123txid")
        result = broadcast_transaction("deadbeef")
        assert result["status"] == "ok"
        assert result["txid"] == "abc123txid"
        mock_post.assert_called_once()
        assert "mempool.space" in mock_post.call_args[0][0]

    @patch("remotesigner.broadcaster.requests.post")
    def test_falls_back_to_secondary(self, mock_post):
        mock_post.side_effect = [
            MagicMock(status_code=500, text="error"),
            MagicMock(status_code=500, text="error"),
            MagicMock(status_code=200, text="txid456"),
        ]
        result = broadcast_transaction("deadbeef")
        assert result["status"] == "ok"
        assert result["txid"] == "txid456"

    @patch("remotesigner.broadcaster.requests.post")
    def test_returns_raw_hex_on_total_failure(self, mock_post):
        mock_post.return_value = MagicMock(status_code=500, text="server error")
        result = broadcast_transaction("deadbeef")
        assert result["status"] == "error"
        assert result["raw_hex"] == "deadbeef"

    @patch("remotesigner.broadcaster.requests.post")
    def test_uses_testnet_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="txid789")
        result = broadcast_transaction("deadbeef", network="test")
        assert "testnet" in mock_post.call_args[0][0]

    def test_rejects_unknown_network(self):
        with pytest.raises(ValueError, match="Unknown network"):
            broadcast_transaction("deadbeef", network="typo")

    def test_rejects_unknown_network_testt(self):
        with pytest.raises(ValueError, match="Unknown network"):
            broadcast_transaction("deadbeef", network="testt")

    @patch("remotesigner.broadcaster.requests.post")
    def test_uses_testnet4_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="txid_t4")
        result = broadcast_transaction("deadbeef", network="testnet4")
        assert result["status"] == "ok"
        assert "testnet4" in mock_post.call_args[0][0]

    @patch("remotesigner.broadcaster.requests.post")
    def test_uses_signet_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="txid_sig")
        result = broadcast_transaction("deadbeef", network="signet")
        assert result["status"] == "ok"
        assert "signet" in mock_post.call_args[0][0]

    @patch("remotesigner.broadcaster.requests.post")
    def test_uses_testnet3_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="txid_t3")
        result = broadcast_transaction("deadbeef", network="testnet3")
        assert result["status"] == "ok"
        assert "testnet" in mock_post.call_args[0][0]
        assert "testnet4" not in mock_post.call_args[0][0]

    @patch("remotesigner.broadcaster.requests.post")
    def test_test_backward_compat_uses_testnet3_endpoints(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="txid_bc")
        result = broadcast_transaction("deadbeef", network="test")
        assert result["status"] == "ok"
        assert "testnet" in mock_post.call_args[0][0]
