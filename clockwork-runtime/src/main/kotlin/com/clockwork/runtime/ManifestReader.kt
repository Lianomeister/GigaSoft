package com.clockwork.runtime

import com.clockwork.api.DependencySpec
import com.clockwork.api.PluginManifest
import org.yaml.snakeyaml.Yaml
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.jar.JarFile

object ManifestReader {
    fun readFromJar(jarPath: Path): PluginManifest {
        JarFile(jarPath.toFile()).use { jar ->
            val entry = jar.getJarEntry("gigaplugin.yml")
                ?: error("Missing gigaplugin.yml in $jarPath")
            val yaml = Yaml()
            InputStreamReader(jar.getInputStream(entry)).use { reader ->
                val data = yaml.load<Map<String, Any?>>(reader)
                val manifest = PluginManifest(
                    id = data["id"]?.toString() ?: error("Manifest missing id"),
                    name = data["name"]?.toString() ?: error("Manifest missing name"),
                    version = data["version"]?.toString() ?: error("Manifest missing version"),
                    main = data["main"]?.toString() ?: error("Manifest missing main"),
                    apiVersion = data["apiVersion"]?.toString() ?: "1",
                    dependencies = parseDependencies(data["dependencies"]),
                    permissions = (data["permissions"] as? List<*>)?.map { it.toString() } ?: emptyList()
                )
                ManifestSecurity.validate(manifest)
                return manifest
            }
        }
    }

    private fun parseDependencies(rawDependencies: Any?): List<DependencySpec> {
        val rawList = rawDependencies as? List<*> ?: return emptyList()
        return rawList.map { raw ->
            when (raw) {
                is String -> parseDependencyString(raw)
                is Map<*, *> -> {
                    val id = raw["id"]?.toString()?.trim().orEmpty()
                    require(id.isNotEmpty()) { "Dependency map entry requires non-empty 'id'" }
                    val version = raw["version"]?.toString()?.trim().orEmpty().ifBlank { null }
                    DependencySpec(id = id, versionRange = version)
                }
                else -> error("Unsupported dependency entry type: ${raw?.javaClass?.name}")
            }
        }
    }

    private fun parseDependencyString(raw: String): DependencySpec {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Dependency string must not be empty" }
        val match = Regex("^([A-Za-z0-9_.-]+)\\s*(.*)$").find(trimmed)
            ?: error("Invalid dependency format: '$raw'")
        val id = match.groupValues[1]
        val tail = match.groupValues[2].trim()
        return if (tail.isBlank()) DependencySpec(id = id) else DependencySpec(id = id, versionRange = tail)
    }
}
