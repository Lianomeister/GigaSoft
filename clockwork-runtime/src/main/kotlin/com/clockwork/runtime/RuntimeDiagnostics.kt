package com.clockwork.runtime

data class RuntimeDiagnostics(
    val loadedPlugins: List<String>,
    val currentLoadOrder: List<String>,
    val currentDependencyIssues: Map<String, String>,
    val versionMismatches: Map<String, String>,
    val apiCompatibility: Map<String, String>,
    val currentDependencyDiagnostics: Map<String, DependencyDiagnostic> = emptyMap(),
    val lastScanRejected: Map<String, String>,
    val lastScanVersionMismatches: Map<String, String>,
    val lastScanApiCompatibility: Map<String, String>,
    val lastScanDependencyDiagnostics: Map<String, DependencyDiagnostic> = emptyMap(),
    val dependencyGraph: Map<String, List<String>>,
    val pluginPerformance: Map<String, PluginPerformanceDiagnostics>
)

data class PluginPerformanceDiagnostics(
    val adapterCounters: AdapterCountersSnapshot,
    val adapterAudit: AdapterAuditSnapshot,
    val slowSystems: List<SlowSystemSnapshot>,
    val adapterHotspots: List<AdapterHotspotSnapshot>,
    val isolatedSystems: List<SystemIsolationSnapshot>,
    val faultBudget: PluginFaultBudgetSnapshot
)
