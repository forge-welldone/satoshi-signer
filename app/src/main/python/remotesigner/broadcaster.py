"""Broadcast signed Bitcoin transactions to the network."""

import requests

ENDPOINTS = {
    "main": [
        "https://mempool.space/api/tx",
        "https://blockstream.info/api/tx",
    ],
    "test": [  # backward compat alias for testnet3
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
    "testnet3": [
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
    "testnet4": [
        "https://mempool.space/testnet4/api/tx",
    ],
    "signet": [
        "https://mempool.space/signet/api/tx",
    ],
}

TIMEOUT = 10


def broadcast_transaction(raw_hex: str, network: str = "main") -> dict:
    """Broadcast a raw transaction hex to the Bitcoin network.

    Tries primary endpoint with one retry, then falls back to secondary.

    Returns:
        {"status": "ok", "txid": "..."} on success.
        {"status": "error", "message": "...", "raw_hex": "..."} on failure.
    """
    if network not in ENDPOINTS:
        raise ValueError(
            f"Unknown network: {network!r}. "
            f"Valid: {', '.join(sorted(ENDPOINTS))}"
        )
    if not raw_hex or not isinstance(raw_hex, str):
        raise ValueError("raw_hex must be a non-empty string")
    if len(raw_hex) % 2 != 0:
        raise ValueError("raw_hex has odd length — not valid hex")
    try:
        bytes.fromhex(raw_hex)
    except ValueError:
        raise ValueError("raw_hex contains non-hex characters")
    MAX_TX_SIZE = 400_000  # 400KB, Bitcoin's max standard tx is ~400KB
    if len(raw_hex) // 2 > MAX_TX_SIZE:
        raise ValueError(
            f"Transaction too large: {len(raw_hex) // 2} bytes (max {MAX_TX_SIZE})"
        )
    endpoints = ENDPOINTS[network]
    last_error = ""

    for url in endpoints:
        for attempt in range(2):
            try:
                resp = requests.post(url, data=raw_hex, timeout=TIMEOUT)
                if resp.status_code == 200:
                    return {"status": "ok", "txid": resp.text.strip()}
                last_error = f"{url}: HTTP {resp.status_code} - {resp.text.strip()}"
            except requests.RequestException as e:
                last_error = f"{url}: {e}"

    return {
        "status": "error",
        "message": last_error,
        "raw_hex": raw_hex,
    }
