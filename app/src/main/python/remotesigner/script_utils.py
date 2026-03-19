"""Shared Bitcoin script parsing utilities.

Pure functions — no trezorlib or embit dependencies.
"""

from dataclasses import dataclass
from typing import List, Optional

OP_CHECKMULTISIG = 0xAE


@dataclass
class MultisigInfo:
    """Parsed m-of-n multisig script data."""
    m: int
    n: int
    pubkeys: List[bytes]


def parse_multisig_script(script: bytes) -> Optional[MultisigInfo]:
    """Parse an OP_CHECKMULTISIG script into m, n, and pubkeys.

    Supports standard scripts of the form:
        OP_m <pub1> <pub2> ... <pubN> OP_n OP_CHECKMULTISIG

    Returns None if the script is not a recognizable multisig.
    """
    if len(script) < 37:
        return None
    if script[-1] != OP_CHECKMULTISIG:
        return None

    # OP_1..OP_16 => 0x51..0x60
    m_byte = script[0]
    if not (0x51 <= m_byte <= 0x60):
        return None
    m = m_byte - 0x50

    n_byte = script[-2]
    if not (0x51 <= n_byte <= 0x60):
        return None
    n = n_byte - 0x50

    # Extract public keys
    pubkeys: List[bytes] = []
    pos = 1
    for _ in range(n):
        if pos >= len(script) - 2:
            return None
        key_len = script[pos]
        pos += 1
        if pos + key_len > len(script) - 2:
            return None
        pubkeys.append(script[pos : pos + key_len])
        pos += key_len

    if len(pubkeys) != n:
        return None

    return MultisigInfo(m=m, n=n, pubkeys=pubkeys)
