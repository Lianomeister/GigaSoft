package com.gigasoft.api

data class DependencySpec(
    val id: String,
    val versionRange: String? = null
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val apiVersion: String = "1",
    val dependencies: List<DependencySpec> = emptyList(),
    val permissions: List<String> = emptyList()
)

data class ItemDefinition(val id: String, val displayName: String)
data class BlockDefinition(val id: String, val displayName: String)
data class RecipeDefinition(val id: String, val input: String, val output: String, val durationTicks: Int)
data class MachineDefinition(val id: String, val displayName: String, val behavior: MachineBehavior)
data class TextureDefinition(
    val id: String,
    val path: String,
    val category: String = "item",
    val animated: Boolean = false
)

data class ModelBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double
)

data class ModelLod(
    val distance: Double,
    val geometryPath: String,
    val format: String = "json"
)

data class ModelDefinition(
    val id: String,
    val format: String = "json",
    val geometryPath: String,
    val textures: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val material: String = "opaque",
    val doubleSided: Boolean = false,
    val scale: Double = 1.0,
    val collision: Boolean = true,
    val bounds: ModelBounds? = null,
    val lods: List<ModelLod> = emptyList(),
    val animations: Map<String, String> = emptyMap()
)

data class MachineState(
    val machineId: String,
    val metadata: MutableMap<String, String> = linkedMapOf(),
    var progressTicks: Int = 0
)

data class InteractionContext(
    val actor: String,
    val metadata: Map<String, String> = emptyMap()
)
