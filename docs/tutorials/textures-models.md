# Tutorial: Add Textures, Models, Animations and Sounds from a Plugin

Goal: register plugin-owned textures/models/animations/sounds and build a validated resource bundle.

## DSL Example

```kotlin
val plugin = gigaPlugin(
    id = "demo-assets",
    version = "1.5.0-rc.2",
    apiVersion = "1"
) {
    textures {
        texture(
            id = "copper_ingot_icon",
            path = "assets/demo_assets/textures/item/copper_ingot.png",
            category = "item"
        )
        texture(
            id = "crusher_side",
            path = "assets/demo_assets/textures/block/crusher_side.png",
            category = "block"
        )
    }

    models {
        model(
            id = "copper_ingot_model",
            geometryPath = "assets/demo_assets/models/item/copper_ingot.json",
            textures = mapOf("layer0" to "copper_ingot_icon")
        )
        model(
            id = "crusher_block_model",
            geometryPath = "assets/demo_assets/models/block/crusher.geo.json",
            format = "gltf",
            textures = mapOf("side" to "crusher_side"),
            metadata = mapOf("lod" to "0")
        )
    }

    animations {
        animation(
            id = "crusher_spin",
            path = "assets/demo_assets/animations/crusher_spin.json",
            targetModelId = "crusher_block_model",
            loop = true
        )
    }

    sounds {
        sound(
            id = "crusher_spin_sfx",
            path = "assets/demo_assets/sounds/crusher_spin.ogg",
            category = "block"
        )
    }
}
```

## Runtime Rules

- Texture/model IDs must be unique per plugin.
- Use stable IDs and version file paths instead of changing IDs for hotfixes.
- Keep paths inside your plugin-owned asset namespace (`assets/<plugin_id>/...`).
- `models.textures` should reference registered texture IDs, not raw file names.
- Validate and bundle assets in build/runtime:
  - `val validation = ctx.validateAssets()`
  - `val bundle = ctx.buildResourcePackBundle()`

## Debug Checklist

- `registry.textures()` should include your texture IDs.
- `registry.models()` should include your model IDs.
- `registry.animations()` / `registry.sounds()` should include your IDs.
- `bundle.assets` should include every referenced file path.
- If registration fails, check duplicate IDs first.

