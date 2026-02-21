package com.gigasoft.runtime

import com.gigasoft.api.ItemDefinition
import com.gigasoft.api.ModelDefinition
import com.gigasoft.api.TextureDefinition
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

    @Test
    fun `duplicate texture ids are rejected`() {
        val registry = RuntimeRegistry("demo")
        registry.registerTexture(TextureDefinition("gear_base", "assets/demo/textures/item/gear.png"))

        assertFailsWith<IllegalArgumentException> {
            registry.registerTexture(TextureDefinition("gear_base", "assets/demo/textures/item/gear_alt.png"))
        }
    }

    @Test
    fun `duplicate model ids are rejected`() {
        val registry = RuntimeRegistry("demo")
        registry.registerModel(ModelDefinition("gear_model", geometryPath = "assets/demo/models/item/gear.json"))

        assertFailsWith<IllegalArgumentException> {
            registry.registerModel(ModelDefinition("gear_model", geometryPath = "assets/demo/models/item/gear_v2.json"))
        }
    }
}
