package com.clockwork.runtime

import com.clockwork.api.DependencySpec
import com.clockwork.api.DependencyKind
import com.clockwork.api.PluginManifest

object ManifestSecurity {
    private val pluginIdRegex = Regex("^[a-z0-9._-]{2,64}$")
    private val nameRegex = Regex("^[A-Za-z0-9 _.-]{2,80}$")
    private val classRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val permissionRegex = Regex("^[a-z0-9._-]{2,80}$")
    private val apiVersionRegex = Regex("^\\d+(\\.\\d+)?$")
    private val versionRangeRegex = Regex("^[<>=0-9.\\s-]+$")
    private val capabilityRegex = Regex("^[a-z][a-z0-9-]{1,31}$")
    private const val maxDependencies = 32
    private const val maxPermissions = 64
    private const val maxCapabilities = 16
    private const val maxIsolationEntriesPerList = 64
    private const val maxIsolationEntryLength = 180
    private const val maxVersionRangeLength = 64

    fun validate(manifest: PluginManifest) {
        require(pluginIdRegex.matches(manifest.id)) { "Invalid plugin id '${manifest.id}'" }
        require(nameRegex.matches(manifest.name)) { "Invalid plugin name '${manifest.name}'" }
        require(SemVer.parse(manifest.version) != null) { "Invalid plugin version '${manifest.version}'" }
        require(classRegex.matches(manifest.main)) { "Invalid main class '${manifest.main}'" }
        require(!manifest.main.startsWith("java.") && !manifest.main.startsWith("kotlin.")) {
            "Invalid main class '${manifest.main}'"
        }
        require(apiVersionRegex.matches(manifest.apiVersion)) { "Invalid apiVersion '${manifest.apiVersion}'" }
        require(manifest.permissions.size <= maxPermissions) {
            "Too many permissions (${manifest.permissions.size} > $maxPermissions)"
        }
        val seenPermissions = mutableSetOf<String>()
        manifest.permissions.forEach { permission ->
            require(permissionRegex.matches(permission)) { "Invalid permission '$permission'" }
            require(seenPermissions.add(permission)) { "Duplicate permission '$permission'" }
        }
        require(manifest.capabilities.size <= maxCapabilities) {
            "Too many capabilities (${manifest.capabilities.size} > $maxCapabilities)"
        }
        val seenCapabilities = mutableSetOf<String>()
        manifest.capabilities.forEach { capability ->
            val normalized = capability.trim().lowercase()
            require(capabilityRegex.matches(normalized)) { "Invalid capability '$capability'" }
            require(seenCapabilities.add(normalized)) { "Duplicate capability '$capability'" }
        }
        validateIsolationList("filesystemAllowlist", manifest.isolation.filesystemAllowlist)
        validateIsolationList("networkProtocolAllowlist", manifest.isolation.networkProtocolAllowlist)
        validateIsolationList("networkHostAllowlist", manifest.isolation.networkHostAllowlist)
        validateIsolationList("networkPathAllowlist", manifest.isolation.networkPathAllowlist)
        validateIsolationList("commandAllowlist", manifest.isolation.commandAllowlist)
        validateDependencies(manifest.id, manifest.dependencies)
    }

    private fun validateIsolationList(name: String, values: List<String>) {
        require(values.size <= maxIsolationEntriesPerList) {
            "$name has too many entries (${values.size} > $maxIsolationEntriesPerList)"
        }
        values.forEach { raw ->
            val value = raw.trim()
            require(value.isNotEmpty()) { "$name contains blank entry" }
            require(value.length <= maxIsolationEntryLength) {
                "$name entry exceeds max length ($maxIsolationEntryLength): '$value'"
            }
        }
    }

    private fun validateDependencies(pluginId: String, dependencies: List<DependencySpec>) {
        require(dependencies.size <= maxDependencies) {
            "Too many dependencies (${dependencies.size} > $maxDependencies)"
        }
        val keys = mutableSetOf<Pair<String, DependencyKind>>()
        dependencies.forEach { dependency ->
            require(pluginIdRegex.matches(dependency.id)) {
                "Invalid dependency id '${dependency.id}'"
            }
            require(dependency.id != pluginId) {
                "Plugin '$pluginId' cannot depend on itself"
            }
            require(keys.add(dependency.id to dependency.kind)) {
                "Duplicate dependency '${dependency.id}' (${dependency.kind}) in plugin '$pluginId'"
            }
            val range = dependency.versionRange?.trim().orEmpty()
            if (range.isNotBlank()) {
                require(range.length <= maxVersionRangeLength) {
                    "Version range too long for dependency '${dependency.id}'"
                }
                require(versionRangeRegex.matches(range)) {
                    "Invalid version range '$range' for dependency '${dependency.id}'"
                }
            }
        }
    }
}
