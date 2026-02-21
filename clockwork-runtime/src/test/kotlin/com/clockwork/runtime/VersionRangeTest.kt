package com.clockwork.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionRangeTest {
    @Test
    fun `matches compound range`() {
        assertTrue(VersionRange.matches("1.2.5", ">=1.2.0 <2.0.0"))
        assertFalse(VersionRange.matches("2.0.0", ">=1.2.0 <2.0.0"))
    }

    @Test
    fun `supports equals`() {
        assertTrue(VersionRange.matches("1.2.0", "=1.2.0"))
        assertFalse(VersionRange.matches("1.2.1", "=1.2.0"))
    }
}
