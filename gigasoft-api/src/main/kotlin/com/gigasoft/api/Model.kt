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

data class MachineState(
    val machineId: String,
    val metadata: MutableMap<String, String> = linkedMapOf(),
    var progressTicks: Int = 0
)

data class InteractionContext(
    val actor: String,
    val metadata: Map<String, String> = emptyMap()
)
