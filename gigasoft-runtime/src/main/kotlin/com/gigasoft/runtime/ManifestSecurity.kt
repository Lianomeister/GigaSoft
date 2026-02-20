package com.gigasoft.runtime

import com.gigasoft.api.DependencySpec
import com.gigasoft.api.PluginManifest

object ManifestSecurity {
    private val pluginIdRegex = Regex("^[a-z0-9._-]{2,64}$")
    private val nameRegex = Regex("^[A-Za-z0-9 _.-]{2,80}$")
    private val classRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val permissionRegex = Regex("^[a-z0-9._-]{2,80}$")
    private val apiVersionRegex = Regex("^\\d+(\\.\\d+)?$")
    private val versionRangeRegex = Regex("^[<>=0-9.\\s-]+$")

    fun validate(manifest: PluginManifest) {
        require(pluginIdRegex.matches(manifest.id)) { "Invalid plugin id '${manifest.id}'" }
        require(nameRegex.matches(manifest.name)) { "Invalid plugin name '${manifest.name}'" }
        require(SemVer.parse(manifest.version) != null) { "Invalid plugin version '${manifest.version}'" }
        require(classRegex.matches(manifest.main)) { "Invalid main class '${manifest.main}'" }
        require(apiVersionRegex.matches(manifest.apiVersion)) { "Invalid apiVersion '${manifest.apiVersion}'" }
        manifest.permissions.forEach { permission ->
            require(permissionRegex.matches(permission)) { "Invalid permission '$permission'" }
        }
        validateDependencies(manifest.id, manifest.dependencies)
    }

    private fun validateDependencies(pluginId: String, dependencies: List<DependencySpec>) {
        val ids = mutableSetOf<String>()
        dependencies.forEach { dependency ->
            require(pluginIdRegex.matches(dependency.id)) {
                "Invalid dependency id '${dependency.id}'"
            }
            require(dependency.id != pluginId) {
                "Plugin '$pluginId' cannot depend on itself"
            }
            require(ids.add(dependency.id)) {
                "Duplicate dependency '${dependency.id}' in plugin '$pluginId'"
            }
            val range = dependency.versionRange?.trim().orEmpty()
            if (range.isNotBlank()) {
                require(versionRangeRegex.matches(range)) {
                    "Invalid version range '$range' for dependency '${dependency.id}'"
                }
            }
        }
    }
}

