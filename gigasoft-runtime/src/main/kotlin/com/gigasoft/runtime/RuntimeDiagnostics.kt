package com.gigasoft.runtime

data class RuntimeDiagnostics(
    val loadedPlugins: List<String>,
    val currentLoadOrder: List<String>,
    val currentDependencyIssues: Map<String, String>,
    val versionMismatches: Map<String, String>,
    val apiCompatibility: Map<String, String>,
    val lastScanRejected: Map<String, String>,
    val lastScanVersionMismatches: Map<String, String>,
    val lastScanApiCompatibility: Map<String, String>,
    val dependencyGraph: Map<String, List<String>>,
    val pluginPerformance: Map<String, PluginPerformanceDiagnostics>
)

data class PluginPerformanceDiagnostics(
    val slowSystems: List<SlowSystemSnapshot>,
    val adapterHotspots: List<AdapterHotspotSnapshot>,
    val isolatedSystems: List<SystemIsolationSnapshot>,
    val faultBudget: PluginFaultBudgetSnapshot
)
