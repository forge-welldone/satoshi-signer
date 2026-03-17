package com.remotesigner.data

import org.junit.Assert.*
import org.junit.Test

class FingerprintValidatorTest {

    @Test
    fun valid_lowercase_fingerprint() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("a1b2c3d4"))
    }

    @Test
    fun valid_uppercase_normalized_to_lowercase() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("A1B2C3D4"))
    }

    @Test
    fun valid_mixed_case_normalized() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("A1b2C3d4"))
    }

    @Test
    fun too_short_returns_null() {
        assertNull(FingerprintValidator.normalize("a1b2c3"))
    }

    @Test
    fun too_long_returns_null() {
        assertNull(FingerprintValidator.normalize("a1b2c3d4e5"))
    }

    @Test
    fun empty_returns_null() {
        assertNull(FingerprintValidator.normalize(""))
    }

    @Test
    fun non_hex_returns_null() {
        assertNull(FingerprintValidator.normalize("g1h2i3j4"))
    }

    @Test
    fun whitespace_trimmed_before_validation() {
        assertEquals("a1b2c3d4", FingerprintValidator.normalize("  a1b2c3d4  "))
    }
}
