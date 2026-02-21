package com.clockwork.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeVersionTest {
    @Test
    fun `api compatibility matches major version`() {
        assertTrue(RuntimeVersion.isApiCompatible("1"))
        assertTrue(RuntimeVersion.isApiCompatible("1.9"))
        assertFalse(RuntimeVersion.isApiCompatible("2"))
    }
}
