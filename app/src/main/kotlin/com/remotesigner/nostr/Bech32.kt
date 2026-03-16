package com.remotesigner.nostr

/**
 * Bech32 encoding/decoding for NIP-19 (npub/nsec).
 * Implements BIP-173 bech32 (not bech32m).
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.also { rev ->
        CHARSET.forEachIndexed { i, c -> rev[c.code] = i }
    }

    fun npubEncode(pubkey32: ByteArray): String {
        require(pubkey32.size == 32)
        return encode("npub", convertBits(pubkey32, 8, 5, true))
    }

    fun nsecEncode(privkey32: ByteArray): String {
        require(privkey32.size == 32)
        return encode("nsec", convertBits(privkey32, 8, 5, true))
    }

    fun decode(bech32: String): Pair<String, ByteArray> {
        val lower = bech32.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= lower.length) { "Invalid bech32" }
        val hrp = lower.substring(0, pos)
        val dataChars = lower.substring(pos + 1)
        val data = IntArray(dataChars.length) { CHARSET_REV[dataChars[it].code].also { v -> require(v != -1) } }
        require(verifyChecksum(hrp, data)) { "Invalid checksum" }
        val payload = data.sliceArray(0 until data.size - 6)
        return hrp to convertBits(payload.map { it.toByte() }.toByteArray(), 5, 8, false)
    }

    fun encode(hrp: String, data5bit: ByteArray): String {
        val values = data5bit.map { it.toInt() }.toIntArray()
        val checksum = createChecksum(hrp, values)
        val sb = StringBuilder(hrp).append('1')
        for (v in values) sb.append(CHARSET[v])
        for (v in checksum) sb.append(CHARSET[v])
        return sb.toString()
    }

    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val maxV = (1 shl toBits) - 1
        val result = mutableListOf<Byte>()
        for (b in data) {
            acc = (acc shl fromBits) or (b.toInt() and 0xFF)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxV).toByte())
            }
        }
        if (pad && bits > 0) {
            result.add(((acc shl (toBits - bits)) and maxV).toByte())
        } else if (!pad) {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxV) == 0) { "Invalid padding" }
        }
        return result.toByteArray()
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1FFFFFF) shl 5) xor v
            for (i in 0..4) {
                if ((b shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) result[i] = hrp[i].code shr 5
        // result[hrp.length] = 0 (separator)
        for (i in hrp.indices) result[hrp.length + 1 + i] = hrp[i].code and 31
        return result
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        return polymod(hrpExpand(hrp) + data) == 1
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(values) xor 1
        return IntArray(6) { (polymod shr (5 * (5 - it))) and 31 }
    }
}
