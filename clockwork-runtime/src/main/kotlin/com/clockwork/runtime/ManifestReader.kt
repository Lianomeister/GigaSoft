package com.clockwork.runtime

import com.clockwork.api.DependencySpec
import com.clockwork.api.DependencyKind
import com.clockwork.api.PluginManifest
import org.yaml.snakeyaml.Yaml
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.jar.JarFile

object ManifestReader {
    const val PRIMARY_MANIFEST_FILE = "clockworkplugin.yml"
    const val TEST_MANIFEST_FILE = "clockworktestplugin.yml"
    const val DEMO_MANIFEST_FILE = "clockworkdemoplugin.yml"
    val SUPPORTED_MANIFEST_FILES = listOf(PRIMARY_MANIFEST_FILE, TEST_MANIFEST_FILE, DEMO_MANIFEST_FILE)

    data class ReadResult(
        val manifest: PluginManifest,
        val sourceFile: String
    )

    fun readFromJar(jarPath: Path): PluginManifest {
        return readFromJarDetailed(jarPath).manifest
    }

    fun readFromJarDetailed(jarPath: Path): ReadResult {
        JarFile(jarPath.toFile()).use { jar ->
            val selected = selectManifestEntry(jar)
            val entry = selected.first
            val sourceFile = selected.second
            val yaml = Yaml()
            InputStreamReader(jar.getInputStream(entry)).use { reader ->
                val data = yaml.load<Map<String, Any?>>(reader)
                val manifest = PluginManifest(
                    id = data["id"]?.toString() ?: error("Manifest missing id"),
                    name = data["name"]?.toString() ?: error("Manifest missing name"),
                    version = data["version"]?.toString() ?: error("Manifest missing version"),
                    main = data["main"]?.toString() ?: error("Manifest missing main"),
                    apiVersion = data["apiVersion"]?.toString() ?: "1",
                    dependencies = parseDependencies(data["dependencies"], data),
                    permissions = (data["permissions"] as? List<*>)?.map { it.toString() } ?: emptyList()
                )
                ManifestSecurity.validate(manifest)
                return ReadResult(
                    manifest = manifest,
                    sourceFile = sourceFile
                )
            }
        }
    }

    private fun selectManifestEntry(jar: JarFile): Pair<java.util.jar.JarEntry, String> {
        for (fileName in SUPPORTED_MANIFEST_FILES) {
            val entry = jar.getJarEntry(fileName)
            if (entry != null) return entry to fileName
        }
        error("Missing manifest. Expected one of: ${SUPPORTED_MANIFEST_FILES.joinToString(", ")}")
    }

    private fun parseDependencies(rawDependencies: Any?, root: Map<String, Any?>): List<DependencySpec> {
        val parsed = mutableListOf<DependencySpec>()

        when (rawDependencies) {
            null -> Unit
            is List<*> -> parsed += parseDependencyList(rawDependencies, DependencyKind.REQUIRED)
            is Map<*, *> -> {
                parsed += parseDependencyList(rawDependencies["required"] as? List<*>, DependencyKind.REQUIRED)
                parsed += parseDependencyList(rawDependencies["optional"] as? List<*>, DependencyKind.OPTIONAL)
                parsed += parseDependencyList(rawDependencies["softAfter"] as? List<*>, DependencyKind.SOFT_AFTER)
                parsed += parseDependencyList(rawDependencies["conflicts"] as? List<*>, DependencyKind.CONFLICTS)
            }
            else -> error("Unsupported dependencies type: ${rawDependencies::class.java.name}")
        }

        parsed += parseDependencyList(root["optionalDependencies"] as? List<*>, DependencyKind.OPTIONAL)
        parsed += parseDependencyList(root["softAfter"] as? List<*>, DependencyKind.SOFT_AFTER)
        parsed += parseDependencyList(root["conflicts"] as? List<*>, DependencyKind.CONFLICTS)

        return parsed
    }

    private fun parseDependencyList(rawList: List<*>?, kind: DependencyKind): List<DependencySpec> {
        if (rawList == null) return emptyList()
        return rawList.map { raw ->
            when (raw) {
                is String -> parseDependencyString(raw, kind)
                is Map<*, *> -> parseDependencyMap(raw, defaultKind = kind)
                else -> error("Unsupported dependency entry type: ${raw?.javaClass?.name}")
            }
        }
    }

    private fun parseDependencyMap(raw: Map<*, *>, defaultKind: DependencyKind): DependencySpec {
        val id = raw["id"]?.toString()?.trim().orEmpty()
        require(id.isNotEmpty()) { "Dependency map entry requires non-empty 'id'" }
        val version = raw["version"]?.toString()?.trim().orEmpty().ifBlank { null }
        val kind = parseKind(raw["kind"]?.toString(), defaultKind)
        return DependencySpec(id = id, versionRange = version, kind = kind)
    }

    private fun parseKind(raw: String?, fallback: DependencyKind): DependencyKind {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return fallback
        return when (value) {
            "required" -> DependencyKind.REQUIRED
            "optional" -> DependencyKind.OPTIONAL
            "softafter", "soft_after", "soft-after" -> DependencyKind.SOFT_AFTER
            "conflicts", "conflict" -> DependencyKind.CONFLICTS
            else -> error("Unsupported dependency kind '$raw'")
        }
    }

    private fun parseDependencyString(raw: String, kind: DependencyKind = DependencyKind.REQUIRED): DependencySpec {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Dependency string must not be empty" }
        val match = Regex("^([A-Za-z0-9_.-]+)\\s*(.*)$").find(trimmed)
            ?: error("Invalid dependency format: '$raw'")
        val id = match.groupValues[1]
        val tail = match.groupValues[2].trim()
        return if (tail.isBlank()) DependencySpec(id = id, kind = kind) else DependencySpec(id = id, versionRange = tail, kind = kind)
    }
}
