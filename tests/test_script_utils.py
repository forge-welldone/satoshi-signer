"""Tests for script_utils — shared multisig script parsing."""

from remotesigner.script_utils import parse_multisig_script, MultisigInfo


def _make_multisig_script(pubs, m):
    """Helper: build OP_m <pubs...> OP_n OP_CHECKMULTISIG."""
    n = len(pubs)
    script = bytes([0x50 + m])
    for pub in pubs:
        script += bytes([len(pub)]) + pub
    script += bytes([0x50 + n, 0xAE])
    return script


class TestParseMultisigScript:
    def test_valid_2_of_3(self):
        pub1 = b"\x02" + b"\x01" * 32
        pub2 = b"\x02" + b"\x02" * 32
        pub3 = b"\x02" + b"\x03" * 32
        script = _make_multisig_script([pub1, pub2, pub3], m=2)

        info = parse_multisig_script(script)
        assert info is not None
        assert info.m == 2
        assert info.n == 3
        assert info.pubkeys == [pub1, pub2, pub3]

    def test_valid_1_of_1(self):
        pub = b"\x02" + b"\xaa" * 32
        script = _make_multisig_script([pub], m=1)

        info = parse_multisig_script(script)
        assert info is not None
        assert info.m == 1
        assert info.n == 1
        assert info.pubkeys == [pub]

    def test_returns_none_empty_script(self):
        assert parse_multisig_script(b"") is None

    def test_returns_none_too_short(self):
        assert parse_multisig_script(b"\x51\x51\xae") is None

    def test_returns_none_no_checkmultisig(self):
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x51, len(pub)]) + pub + bytes([0x51, 0x00])
        assert parse_multisig_script(script) is None

    def test_returns_none_invalid_m_byte(self):
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x50, len(pub)]) + pub + bytes([0x51, 0xAE])  # 0x50 < OP_1
        assert parse_multisig_script(script) is None

    def test_returns_none_invalid_n_byte(self):
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x51, len(pub)]) + pub + bytes([0x61, 0xAE])  # 0x61 > OP_16
        assert parse_multisig_script(script) is None

    def test_returns_none_truncated_pubkeys(self):
        # Script claims 2-of-2 but only has 1 pubkey worth of data
        pub = b"\x02" + b"\x01" * 32
        script = bytes([0x52, len(pub)]) + pub + bytes([0x52, 0xAE])
        assert parse_multisig_script(script) is None

    def test_returns_multisig_info_type(self):
        pub = b"\x02" + b"\xbb" * 32
        script = _make_multisig_script([pub], m=1)
        info = parse_multisig_script(script)
        assert isinstance(info, MultisigInfo)

    def test_p2wpkh_not_multisig(self):
        script = b"\x00\x14" + b"\xab" * 20
        assert parse_multisig_script(script) is None

    def test_valid_15_of_15(self):
        """Upper boundary: OP_15 is 0x5f, the max commonly used."""
        pubs = [b"\x02" + bytes([i]) * 32 for i in range(15)]
        script = _make_multisig_script(pubs, m=15)
        info = parse_multisig_script(script)
        assert info is not None
        assert info.m == 15
        assert info.n == 15
        assert len(info.pubkeys) == 15
