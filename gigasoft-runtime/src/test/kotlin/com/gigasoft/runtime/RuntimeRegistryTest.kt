package com.gigasoft.runtime

import com.gigasoft.api.AnimationDefinition
import com.gigasoft.api.ItemDefinition
import com.gigasoft.api.ModelDefinition
import com.gigasoft.api.SoundDefinition
import com.gigasoft.api.TextureDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `resource bundle validation catches missing model texture references`() {
        val registry = RuntimeRegistry("demo")
        registry.registerModel(
            ModelDefinition(
                id = "crusher",
                geometryPath = "assets/demo/models/block/crusher.json",
                textures = mapOf("side" to "missing_texture")
            )
        )

        val ex = assertFailsWith<IllegalStateException> {
            registry.buildResourcePackBundle()
        }
        assertTrue(ex.message?.contains("ASSET_MODEL_TEXTURE_MISSING") == true)
    }

    @Test
    fun `resource bundle validation catches wrong plugin namespace paths`() {
        val registry = RuntimeRegistry("demo")
        registry.registerTexture(TextureDefinition("gear", "assets/other/textures/item/gear.png"))

        val result = registry.validateAssets()
        assertEquals(false, result.valid)
        assertTrue(result.issues.any { it.code == "ASSET_TEXTURE_NAMESPACE" })
    }

    @Test
    fun `resource bundle includes textures models animations and sounds`() {
        val registry = RuntimeRegistry("demo")
        registry.registerTexture(TextureDefinition("gear", "assets/demo/textures/item/gear.png"))
        registry.registerModel(
            ModelDefinition(
                id = "gear_model",
                geometryPath = "assets/demo/models/item/gear.json",
                textures = mapOf("layer0" to "gear")
            )
        )
        registry.registerAnimation(
            AnimationDefinition(
                id = "gear_spin",
                path = "assets/demo/animations/gear_spin.json",
                targetModelId = "gear_model"
            )
        )
        registry.registerSound(
            SoundDefinition(
                id = "gear_spin_sfx",
                path = "assets/demo/sounds/gear_spin.ogg"
            )
        )

        val bundle = registry.buildResourcePackBundle()
        assertEquals("demo", bundle.pluginId)
        assertEquals(1, bundle.textures.size)
        assertEquals(1, bundle.models.size)
        assertEquals(1, bundle.animations.size)
        assertEquals(1, bundle.sounds.size)
        assertTrue(bundle.assets.any { it.id == "gear" })
        assertTrue(bundle.assets.any { it.id == "gear_spin_sfx" })
    }
}
