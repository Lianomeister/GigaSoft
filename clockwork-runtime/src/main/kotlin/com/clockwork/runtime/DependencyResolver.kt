package com.clockwork.runtime

import com.clockwork.api.DependencySpec
import com.clockwork.api.PluginManifest
import java.nio.file.Path
import java.util.PriorityQueue

data class PluginDescriptor(
    val manifest: PluginManifest,
    val jarPath: Path
)

data class DependencyResolution(
    val ordered: List<PluginDescriptor>,
    val rejected: Map<String, String>,
    val versionMismatches: Map<String, String>,
    val apiCompatibility: Map<String, String>
)

object DependencyResolver {
    fun resolve(
        descriptors: List<PluginDescriptor>,
        externallyAvailable: Map<String, String> = emptyMap()
    ): DependencyResolution {
        val rejected = linkedMapOf<String, String>()
        val versionMismatches = linkedMapOf<String, String>()
        val apiCompatibility = linkedMapOf<String, String>()

        val byId = descriptors.groupBy { it.manifest.id }
        val duplicates = byId.filterValues { it.size > 1 }
        duplicates.forEach { (id, _) ->
            rejected[id] = "Duplicate plugin id '$id'"
        }

        val working = byId
            .filterKeys { it !in duplicates.keys }
            .mapValues { it.value.first() }
            .toMutableMap()

        val apiIncompatible = mutableListOf<String>()
        working.forEach { (id, descriptor) ->
            val apiVersion = descriptor.manifest.apiVersion
            if (RuntimeVersion.isApiCompatible(apiVersion)) {
                apiCompatibility[id] = "compatible"
            } else {
                val reason = "incompatible (plugin=$apiVersion, runtime=${RuntimeVersion.API_VERSION})"
                apiCompatibility[id] = reason
                rejected[id] = "Incompatible apiVersion: plugin=$apiVersion runtime=${RuntimeVersion.API_VERSION}"
                apiIncompatible += id
            }
        }
        apiIncompatible.forEach(working::remove)

        var changed = true
        while (changed) {
            changed = false
            val ids = working.keys.toSet()
            val localVersions = working.mapValues { it.value.manifest.version }
            val toRemove = mutableListOf<String>()

            working.forEach { (id, descriptor) ->
                val missingDeps = mutableListOf<String>()
                var versionError: String? = null

                descriptor.manifest.dependencies.forEach { dependency ->
                    val version = localVersions[dependency.id] ?: externallyAvailable[dependency.id]
                    if (version == null) {
                        missingDeps += dependency.id
                    } else if (!dependency.matchesVersion(version)) {
                        versionError = "Dependency version mismatch: ${dependency.id} requires '${dependency.versionRange}', found '$version'"
                    }
                }

                if (missingDeps.isNotEmpty()) {
                    rejected[id] = "Missing dependency/dependencies: ${missingDeps.joinToString(", ")}"
                    toRemove += id
                } else if (versionError != null) {
                    rejected[id] = versionError!!
                    versionMismatches[id] = versionError!!
                    toRemove += id
                }
            }

            if (toRemove.isNotEmpty()) {
                changed = true
                toRemove.forEach(working::remove)
            }
        }

        val inDegree = mutableMapOf<String, Int>()
        val outgoing = mutableMapOf<String, MutableSet<String>>()

        working.keys.forEach { id ->
            inDegree[id] = 0
            outgoing[id] = linkedSetOf()
        }

        working.forEach { (id, descriptor) ->
            descriptor.manifest.dependencies
                .map { it.id }
                .filter { dep -> dep in working.keys }
                .forEach { dep ->
                    outgoing.getValue(dep).add(id)
                    inDegree[id] = inDegree.getValue(id) + 1
                }
        }

        val queue = PriorityQueue<String>()
        inDegree.filterValues { it == 0 }.keys.sorted().forEach(queue::add)

        val orderedIds = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.poll()
            orderedIds += id
            outgoing.getValue(id).sorted().forEach { dependent ->
                val next = inDegree.getValue(dependent) - 1
                inDegree[dependent] = next
                if (next == 0) queue.add(dependent)
            }
        }

        if (orderedIds.size != working.size) {
            val cycleIds = working.keys - orderedIds.toSet()
            cycleIds.forEach { id ->
                rejected[id] = "Dependency cycle detected"
            }
        }

        val ordered = orderedIds
            .filter { it in working && it !in rejected.keys }
            .map { working.getValue(it) }

        working.keys
            .filter { it !in apiCompatibility.keys }
            .forEach { apiCompatibility[it] = "compatible" }

        return DependencyResolution(
            ordered = ordered,
            rejected = rejected,
            versionMismatches = versionMismatches,
            apiCompatibility = apiCompatibility
        )
    }

    private fun DependencySpec.matchesVersion(version: String): Boolean {
        val range = versionRange?.trim().orEmpty()
        if (range.isEmpty()) return true
        return VersionRange.matches(version = version, expression = range)
    }
}
