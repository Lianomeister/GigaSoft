# Tutorial: Add Textures and 3D Models from a Plugin

Goal: register plugin-owned textures and models so your gameplay content can reference custom visuals.

## DSL Example

```kotlin
val plugin = gigaPlugin(
    id = "demo-assets",
    version = "1.1.0-SNAPSHOT",
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
}
```

## Runtime Rules

- Texture/model IDs must be unique per plugin.
- Use stable IDs and version file paths instead of changing IDs for hotfixes.
- Keep paths inside your plugin-owned asset namespace (`assets/<plugin_id>/...`).
- `models.textures` should reference registered texture IDs, not raw file names.

## Debug Checklist

- `registry.textures()` should include your texture IDs.
- `registry.models()` should include your model IDs.
- If registration fails, check duplicate IDs first.
