package com.clockwork.runtime

import com.clockwork.api.DependencyKind
import com.clockwork.api.DependencySpec
import com.clockwork.api.PluginManifest
import java.nio.file.Path
import java.util.PriorityQueue

data class PluginDescriptor(
    val manifest: PluginManifest,
    val jarPath: Path
)

data class DependencyDiagnostic(
    val code: String,
    val message: String,
    val hint: String,
    val causes: List<String> = emptyList()
)

data class DependencyResolution(
    val ordered: List<PluginDescriptor>,
    val rejected: Map<String, String>,
    val versionMismatches: Map<String, String>,
    val apiCompatibility: Map<String, String>,
    val diagnostics: Map<String, DependencyDiagnostic>
)

object DependencyResolver {
    fun resolve(
        descriptors: List<PluginDescriptor>,
        externallyAvailable: Map<String, String> = emptyMap()
    ): DependencyResolution {
        val diagnostics = linkedMapOf<String, DependencyDiagnostic>()
        val versionMismatches = linkedMapOf<String, String>()
        val apiCompatibility = linkedMapOf<String, String>()

        val grouped = descriptors.groupBy { it.manifest.id }
        grouped.filterValues { it.size > 1 }.keys.sorted().forEach { id ->
            diagnostics[id] = diagnostic(
                code = "DEP_DUPLICATE_ID",
                message = "Duplicate plugin id '$id'",
                hint = "Ensure each plugin jar has a unique manifest id."
            )
        }

        val working = grouped
            .filterKeys { it !in diagnostics.keys }
            .mapValues { it.value.first() }
            .toMutableMap()

        working.toSortedMap().forEach { (id, descriptor) ->
            val apiVersion = descriptor.manifest.apiVersion
            if (RuntimeVersion.isApiCompatible(apiVersion)) {
                apiCompatibility[id] = "compatible"
            } else {
                val reason = "incompatible (plugin=$apiVersion, runtime=${RuntimeVersion.API_VERSION})"
                apiCompatibility[id] = reason
                diagnostics[id] = diagnostic(
                    code = "DEP_API_INCOMPATIBLE",
                    message = "Incompatible apiVersion: plugin=$apiVersion runtime=${RuntimeVersion.API_VERSION}",
                    hint = "Upgrade/downgrade the plugin or runtime so apiVersion is compatible."
                )
            }
        }
        diagnostics.keys.forEach(working::remove)

        var changed = true
        while (changed) {
            changed = false
            val localVersions = working.mapValues { it.value.manifest.version }
            val newlyRejected = mutableListOf<String>()

            working.toSortedMap().forEach { (id, descriptor) ->
                if (id in diagnostics) return@forEach
                val issue = validateDescriptor(
                    descriptor = descriptor,
                    working = working,
                    localVersions = localVersions,
                    externallyAvailable = externallyAvailable,
                    diagnostics = diagnostics
                )
                if (issue != null) {
                    diagnostics[id] = issue
                    if (issue.code.contains("VERSION_MISMATCH")) {
                        versionMismatches[id] = issue.message
                    }
                    newlyRejected += id
                }
            }

            if (newlyRejected.isNotEmpty()) {
                changed = true
                newlyRejected.forEach(working::remove)
            }
        }

        val requiredEdges = buildEdges(working, includeSoftAfter = false)
        val requiredTopo = topologicalSort(working.keys, requiredEdges)
        if (requiredTopo.unresolved.isNotEmpty()) {
            requiredTopo.unresolved.sorted().forEach { id ->
                diagnostics[id] = diagnostic(
                    code = "DEP_REQUIRED_CYCLE",
                    message = "Dependency cycle detected among required dependencies",
                    hint = "Break the required dependency cycle or convert one edge to softAfter.",
                    causes = cycleCauseFor(id, requiredEdges)
                )
            }
            requiredTopo.unresolved.forEach(working::remove)
        }

        val finalOrderIds = if (working.isEmpty()) {
            emptyList()
        } else {
            val combinedEdges = buildEdges(working, includeSoftAfter = true)
            val combinedTopo = topologicalSort(working.keys, combinedEdges)
            if (combinedTopo.unresolved.isNotEmpty()) {
                topologicalSort(working.keys, buildEdges(working, includeSoftAfter = false)).ordered
            } else {
                combinedTopo.ordered
            }
        }

        val ordered = finalOrderIds.mapNotNull { working[it] }
        val rejected = diagnostics.toSortedMap().mapValues { it.value.message }

        working.keys
            .filter { it !in apiCompatibility.keys }
            .forEach { apiCompatibility[it] = "compatible" }

        return DependencyResolution(
            ordered = ordered,
            rejected = rejected,
            versionMismatches = versionMismatches.toSortedMap(),
            apiCompatibility = apiCompatibility.toSortedMap(),
            diagnostics = diagnostics.toSortedMap()
        )
    }

    private fun validateDescriptor(
        descriptor: PluginDescriptor,
        working: Map<String, PluginDescriptor>,
        localVersions: Map<String, String>,
        externallyAvailable: Map<String, String>,
        diagnostics: Map<String, DependencyDiagnostic>
    ): DependencyDiagnostic? {
        val manifest = descriptor.manifest

        val conflict = manifest.dependencies
            .filter { it.kind == DependencyKind.CONFLICTS }
            .firstOrNull { dependency ->
                val target = dependency.id
                (target in working.keys || target in externallyAvailable.keys) &&
                    dependency.matchesVersion(localVersions[target] ?: externallyAvailable[target] ?: "")
            }
        if (conflict != null) {
            return diagnostic(
                code = "DEP_CONFLICT",
                message = "Conflict detected: '${manifest.id}' conflicts with '${conflict.id}'",
                hint = "Remove one of the conflicting plugins or adjust conflict ranges.",
                causes = listOf("${manifest.id} conflicts ${conflict.id}")
            )
        }

        val requiredDeps = manifest.dependencies.filter { it.kind == DependencyKind.REQUIRED }
        for (dependency in requiredDeps) {
            val localVersion = localVersions[dependency.id]
            val externalVersion = externallyAvailable[dependency.id]
            val resolvedVersion = localVersion ?: externalVersion
            if (resolvedVersion == null) {
                return diagnostic(
                    code = "DEP_REQUIRED_MISSING",
                    message = "Missing required dependency '${dependency.id}'",
                    hint = "Install '${dependency.id}' or remove it from required dependencies.",
                    causes = listOf("${manifest.id} -> ${dependency.id}")
                )
            }
            if (!dependency.matchesVersion(resolvedVersion)) {
                return diagnostic(
                    code = "DEP_REQUIRED_VERSION_MISMATCH",
                    message = "Dependency version mismatch: ${dependency.id} requires '${dependency.versionRange}', found '$resolvedVersion'",
                    hint = "Install a compatible version of '${dependency.id}' or relax versionRange.",
                    causes = listOf("${manifest.id} -> ${dependency.id}@${dependency.versionRange}")
                )
            }
            if (dependency.id in diagnostics) {
                val depIssue = diagnostics.getValue(dependency.id)
                return diagnostic(
                    code = "DEP_REQUIRED_UNRESOLVED",
                    message = "Required dependency '${dependency.id}' is unresolved",
                    hint = "Resolve '${dependency.id}' first, then retry loading '${manifest.id}'.",
                    causes = buildList {
                        add("${manifest.id} -> ${dependency.id} [${depIssue.code}]")
                        addAll(depIssue.causes)
                    }
                )
            }
        }

        return null
    }

    private fun buildEdges(
        working: Map<String, PluginDescriptor>,
        includeSoftAfter: Boolean
    ): Map<String, Set<String>> {
        val outgoing = working.keys.associateWith { linkedSetOf<String>() }.toMutableMap()
        working.forEach { (id, descriptor) ->
            descriptor.manifest.dependencies.forEach { dependency ->
                when (dependency.kind) {
                    DependencyKind.REQUIRED -> {
                        if (dependency.id in working.keys) {
                            outgoing.getValue(dependency.id).add(id)
                        }
                    }
                    DependencyKind.SOFT_AFTER -> {
                        if (includeSoftAfter && dependency.id in working.keys) {
                            outgoing.getValue(dependency.id).add(id)
                        }
                    }
                    else -> Unit
                }
            }
        }
        return outgoing
    }

    private data class TopologicalResult(
        val ordered: List<String>,
        val unresolved: Set<String>
    )

    private fun topologicalSort(
        ids: Set<String>,
        outgoing: Map<String, Set<String>>
    ): TopologicalResult {
        val inDegree = ids.associateWith { 0 }.toMutableMap()
        outgoing.forEach { (_, dependents) ->
            dependents.forEach { dependent ->
                inDegree[dependent] = (inDegree[dependent] ?: 0) + 1
            }
        }

        val queue = PriorityQueue<String>()
        inDegree.filterValues { it == 0 }.keys.sorted().forEach(queue::add)

        val ordered = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.poll()
            ordered += id
            outgoing[id].orEmpty().sorted().forEach { dependent ->
                val next = (inDegree[dependent] ?: 0) - 1
                inDegree[dependent] = next
                if (next == 0) queue.add(dependent)
            }
        }

        val unresolved = ids - ordered.toSet()
        return TopologicalResult(ordered = ordered, unresolved = unresolved)
    }

    private fun cycleCauseFor(id: String, edges: Map<String, Set<String>>): List<String> {
        val direct = edges.entries
            .filter { (_, dependents) -> id in dependents }
            .map { (from, _) -> "$from -> $id" }
            .sorted()
        return if (direct.isEmpty()) listOf("cycle includes $id") else direct
    }

    private fun diagnostic(
        code: String,
        message: String,
        hint: String,
        causes: List<String> = emptyList()
    ): DependencyDiagnostic {
        return DependencyDiagnostic(
            code = code,
            message = message,
            hint = hint,
            causes = causes
        )
    }

    private fun DependencySpec.matchesVersion(version: String): Boolean {
        val range = versionRange?.trim().orEmpty()
        if (range.isEmpty()) return true
        return VersionRange.matches(version = version, expression = range)
    }
}
