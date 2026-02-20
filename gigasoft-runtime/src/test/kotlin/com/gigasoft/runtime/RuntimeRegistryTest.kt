package com.gigasoft.runtime

import com.gigasoft.api.ItemDefinition
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RuntimeRegistryTest {
    @Test
    fun `duplicate item ids are rejected`() {
        val registry = RuntimeRegistry("demo")
        registry.registerItem(ItemDefinition("gear", "Gear"))

        assertFailsWith<IllegalArgumentException> {
            registry.registerItem(ItemDefinition("gear", "Gear 2"))
        }
    }
}
