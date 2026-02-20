package com.gigasoft.runtime

import com.gigasoft.api.GigaLogger
import com.gigasoft.api.GigaPlugin
import com.gigasoft.api.PluginManifest
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.streams.toList

data class LoadedPlugin(
    val manifest: PluginManifest,
    val instance: GigaPlugin,
    val classLoader: URLClassLoader,
    val context: RuntimePluginContext,
    val scheduler: RuntimeScheduler,
    val sourceJarPath: Path,
    val runtimeJarPath: Path
)

class GigaRuntime(
    private val pluginsDirectory: Path,
    private val dataDirectory: Path,
    private val rootLogger: GigaLogger = GigaLogger { println("[GigaRuntime] $it") }
) {
    private val normalizedPluginsDirectory = pluginsDirectory.toAbsolutePath().normalize()
    private val normalizedDataDirectory = dataDirectory.toAbsolutePath().normalize()
    private val maxPluginJarBytes = 64L * 1024L * 1024L
    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    @Volatile
    private var lastScanRejected: Map<String, String> = emptyMap()
    @Volatile
    private var lastScanVersionMismatches: Map<String, String> = emptyMap()
    @Volatile
    private var lastScanApiCompatibility: Map<String, String> = emptyMap()

    init {
        Files.createDirectories(normalizedPluginsDirectory)
        Files.createDirectories(normalizedDataDirectory)
        Files.createDirectories(runtimeCacheDirectory())
    }

    fun scanAndLoad(): List<LoadedPlugin> {
        val jars = Files.list(normalizedPluginsDirectory).use { stream ->
            stream
                .filter { it.toString().endsWith(".jar") }
                .map { assertPluginJarPath(it) }
                .toList()
        }

        val discovered = jars.mapNotNull { jar ->
            try {
                PluginDescriptor(ManifestReader.readFromJar(jar), jar)
            } catch (t: Throwable) {
                rootLogger.info("Skipped '$jar': ${t.message}")
                null
            }
        }

        val candidates = discovered.filterNot {
            val alreadyLoaded = loaded.containsKey(it.manifest.id)
            if (alreadyLoaded) {
                rootLogger.info("Skipped '${it.manifest.id}': already loaded")
            }
            alreadyLoaded
        }

        val resolution = DependencyResolver.resolve(candidates, externallyAvailable = loadedVersions())
        lastScanRejected = resolution.rejected.toMap()
        lastScanVersionMismatches = resolution.versionMismatches.toMap()
        lastScanApiCompatibility = resolution.apiCompatibility.toMap()

        resolution.rejected.forEach { (id, reason) ->
            rootLogger.info("Skipped '$id': $reason")
        }

        return resolution.ordered.map(::loadDescriptor)
    }

    fun loadJar(jarPath: Path): LoadedPlugin {
        val safeJarPath = assertPluginJarPath(jarPath)
        val descriptor = PluginDescriptor(ManifestReader.readFromJar(safeJarPath), safeJarPath)
        require(!loaded.containsKey(descriptor.manifest.id)) { "Plugin '${descriptor.manifest.id}' is already loaded" }

        require(RuntimeVersion.isApiCompatible(descriptor.manifest.apiVersion)) {
            "Cannot load '${descriptor.manifest.id}': incompatible apiVersion ${descriptor.manifest.apiVersion} (runtime=${RuntimeVersion.API_VERSION})"
        }

        val missing = descriptor.manifest.dependencies.filter { dep -> !loaded.containsKey(dep.id) }
        require(missing.isEmpty()) {
            "Cannot load '${descriptor.manifest.id}': missing dependency/dependencies ${missing.joinToString(", ") { it.id }}"
        }

        val mismatched = descriptor.manifest.dependencies.filter { dep ->
            val range = dep.versionRange ?: return@filter false
            !VersionRange.matches(
                version = loaded.getValue(dep.id).manifest.version,
                expression = range
            )
        }
        require(mismatched.isEmpty()) {
            val details = mismatched.joinToString("; ") { dep ->
                "${dep.id} requires '${dep.versionRange}', found '${loaded.getValue(dep.id).manifest.version}'"
            }
            "Cannot load '${descriptor.manifest.id}': dependency version mismatch ($details)"
        }

        return loadDescriptor(descriptor)
    }

    fun unload(pluginId: String): Boolean = unloadInternal(pluginId, deleteRuntimeJar = true)

    fun reload(pluginId: String): Boolean = reloadWithReport(pluginId).status == ReloadStatus.SUCCESS

    fun reloadWithReport(pluginId: String): ReloadReport {
        if (!loaded.containsKey(pluginId)) {
            return ReloadReport(
                target = pluginId,
                affectedPlugins = emptyList(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.FAILED,
                reason = "Unknown plugin: $pluginId"
            )
        }

        val reloadSet = collectReloadSet(pluginId)
        val descriptors = try {
            reloadSet.mapNotNull { id ->
                loaded[id]?.let { PluginDescriptor(ManifestReader.readFromJar(it.sourceJarPath), it.sourceJarPath) }
            }
        } catch (t: Throwable) {
            return ReloadReport(
                target = pluginId,
                affectedPlugins = reloadSet.toList().sorted(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.FAILED,
                reason = "Failed to read updated manifest: ${t.message}"
            )
        }

        return reloadTransaction(
            target = pluginId,
            targetPluginIds = reloadSet,
            descriptors = descriptors
        )
    }

    fun reloadAll(): Int {
        val report = reloadAllWithReport()
        return if (report.status == ReloadStatus.SUCCESS) report.reloadedPlugins.size else 0
    }

    fun reloadAllWithReport(): ReloadReport {
        val descriptors = try {
            loaded.values
                .sortedBy { it.manifest.id }
                .map { PluginDescriptor(ManifestReader.readFromJar(it.sourceJarPath), it.sourceJarPath) }
        } catch (t: Throwable) {
            return ReloadReport(
                target = "all",
                affectedPlugins = loaded.keys.toList().sorted(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.FAILED,
                reason = "Failed to read updated manifest: ${t.message}"
            )
        }

        return reloadTransaction(
            target = "all",
            targetPluginIds = loaded.keys.toSet(),
            descriptors = descriptors
        )
    }

    fun loadedPlugins(): List<LoadedPlugin> = loaded.values.sortedBy { it.manifest.id }

    fun diagnostics(): RuntimeDiagnostics {
        val descriptors = loaded.values
            .sortedBy { it.manifest.id }
            .map { PluginDescriptor(it.manifest, it.sourceJarPath) }
        val resolution = DependencyResolver.resolve(descriptors)

        val graph = loaded.values
            .sortedBy { it.manifest.id }
            .associate { it.manifest.id to it.manifest.dependencies.map { dep -> dep.id }.sorted() }

        return RuntimeDiagnostics(
            loadedPlugins = loaded.keys.sorted(),
            currentLoadOrder = resolution.ordered.map { it.manifest.id },
            currentDependencyIssues = resolution.rejected.toMap(),
            versionMismatches = resolution.versionMismatches.toMap(),
            apiCompatibility = resolution.apiCompatibility.toMap(),
            lastScanRejected = lastScanRejected.toMap(),
            lastScanVersionMismatches = lastScanVersionMismatches.toMap(),
            lastScanApiCompatibility = lastScanApiCompatibility.toMap(),
            dependencyGraph = graph
        )
    }

    private fun reloadTransaction(
        target: String,
        targetPluginIds: Set<String>,
        descriptors: List<PluginDescriptor>
    ): ReloadReport {
        if (descriptors.isEmpty()) {
            return ReloadReport(
                target = target,
                affectedPlugins = emptyList(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.FAILED,
                reason = "No plugins selected for reload"
            )
        }

        val external = loaded.values
            .filter { it.manifest.id !in targetPluginIds }
            .associate { it.manifest.id to it.manifest.version }

        val resolution = DependencyResolver.resolve(descriptors, externallyAvailable = external)
        if (resolution.rejected.isNotEmpty()) {
            return ReloadReport(
                target = target,
                affectedPlugins = descriptors.map { it.manifest.id }.sorted(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.FAILED,
                reason = resolution.rejected.entries.joinToString("; ") { "${it.key}: ${it.value}" }
            )
        }

        val orderedIds = resolution.ordered.map { it.manifest.id }
        val previous = orderedIds.mapNotNull { loaded[it] }.associateBy { it.manifest.id }

        val checkpointRoot = createDataCheckpoint(previous.keys)

        orderedIds.asReversed().forEach { unloadInternal(it, deleteRuntimeJar = false) }

        return try {
            val reloaded = resolution.ordered.map(::loadDescriptor)
            previous.values.forEach { safeDeleteIfExists(it.runtimeJarPath) }
            deleteDirectoryIfExists(checkpointRoot)
            ReloadReport(
                target = target,
                affectedPlugins = orderedIds,
                reloadedPlugins = reloaded.map { it.manifest.id },
                status = ReloadStatus.SUCCESS
            )
        } catch (t: Throwable) {
            val failureMessage = t.message ?: t.javaClass.simpleName
            orderedIds.forEach { unloadInternal(it, deleteRuntimeJar = true) }
            restoreDataCheckpoint(checkpointRoot, previous.keys)

            val rollbackError = restorePrevious(previous, orderedIds)
            deleteDirectoryIfExists(checkpointRoot)

            if (rollbackError == null) {
                ReloadReport(
                    target = target,
                    affectedPlugins = orderedIds,
                    reloadedPlugins = orderedIds,
                    status = ReloadStatus.ROLLED_BACK,
                    reason = failureMessage
                )
            } else {
                ReloadReport(
                    target = target,
                    affectedPlugins = orderedIds,
                    reloadedPlugins = loaded.keys.intersect(orderedIds.toSet()).sorted(),
                    status = ReloadStatus.FAILED,
                    reason = "Reload failed: $failureMessage; rollback failed: $rollbackError"
                )
            }
        }
    }

    private fun restorePrevious(previous: Map<String, LoadedPlugin>, orderedIds: List<String>): String? {
        return try {
            orderedIds.forEach { id ->
                val plugin = previous[id] ?: return@forEach
                loadFromRuntimeArtifact(
                    manifest = plugin.manifest,
                    sourceJarPath = plugin.sourceJarPath,
                    runtimeJarPath = plugin.runtimeJarPath
                )
            }
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun collectReloadSet(rootId: String): Set<String> {
        val reverseDeps = mutableMapOf<String, MutableSet<String>>()
        loaded.values.forEach { plugin ->
            plugin.manifest.dependencies.forEach { dependency ->
                reverseDeps.computeIfAbsent(dependency.id) { linkedSetOf() }.add(plugin.manifest.id)
            }
        }

        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            reverseDeps[current].orEmpty().forEach(queue::addLast)
        }

        return visited
    }

    private fun loadDescriptor(descriptor: PluginDescriptor): LoadedPlugin {
        require(!loaded.containsKey(descriptor.manifest.id)) { "Plugin '${descriptor.manifest.id}' is already loaded" }
        val staged = stageRuntimeJar(descriptor.manifest.id, descriptor.jarPath)
        try {
            return loadFromRuntimeArtifact(
                manifest = descriptor.manifest,
                sourceJarPath = descriptor.jarPath,
                runtimeJarPath = staged
            )
        } catch (t: Throwable) {
            safeDeleteIfExists(staged)
            throw t
        }
    }

    private fun loadFromRuntimeArtifact(
        manifest: PluginManifest,
        sourceJarPath: Path,
        runtimeJarPath: Path
    ): LoadedPlugin {
        val loader = URLClassLoader(arrayOf(runtimeJarPath.toUri().toURL()), javaClass.classLoader)
        try {
            val plugin = instantiatePlugin(manifest, loader)
            val scheduler = RuntimeScheduler(manifest.id)
            val registry = RuntimeRegistry(manifest.id)
            val commandRegistry = RuntimeCommandRegistry()
            val eventBus = RuntimeEventBus()
            val storage = JsonStorageProvider(dataDirectory.resolve(manifest.id))
            val pluginLogger = GigaLogger { rootLogger.info("[${manifest.id}] $it") }
            val context = RuntimePluginContext(
                manifest,
                pluginLogger,
                scheduler,
                registry,
                storage,
                commandRegistry,
                eventBus
            )

            plugin.onEnable(context)

            val loadedPlugin = LoadedPlugin(
                manifest = manifest,
                instance = plugin,
                classLoader = loader,
                context = context,
                scheduler = scheduler,
                sourceJarPath = sourceJarPath,
                runtimeJarPath = runtimeJarPath
            )
            loaded[manifest.id] = loadedPlugin
            rootLogger.info("Loaded GigaPlugin ${manifest.id}@${manifest.version}")
            return loadedPlugin
        } catch (t: Throwable) {
            loader.close()
            throw t
        }
    }

    private fun unloadInternal(pluginId: String, deleteRuntimeJar: Boolean): Boolean {
        val plugin = loaded.remove(pluginId) ?: return false
        safely("disable $pluginId") { plugin.instance.onDisable(plugin.context) }
        plugin.scheduler.shutdown()
        val remainingTasks = plugin.scheduler.activeTaskCount()
        if (remainingTasks > 0) {
            rootLogger.info("Warning: plugin '$pluginId' still has $remainingTasks scheduled tasks after shutdown")
        }
        plugin.classLoader.close()
        if (deleteRuntimeJar) {
            safeDeleteIfExists(plugin.runtimeJarPath)
        }
        rootLogger.info("Unloaded GigaPlugin $pluginId")
        return true
    }

    private fun stageRuntimeJar(pluginId: String, sourceJarPath: Path): Path {
        val safeSourceJar = assertPluginJarPath(sourceJarPath)
        val cacheDir = runtimeCacheDirectory().resolve(pluginId)
        Files.createDirectories(cacheDir)
        val stagedName = "${System.currentTimeMillis()}-${UUID.randomUUID()}-${sourceJarPath.fileName.name}"
        val staged = cacheDir.resolve(stagedName).normalize()
        require(staged.startsWith(cacheDir.normalize())) { "Invalid staged runtime path for plugin '$pluginId'" }
        val jarSize = Files.size(safeSourceJar)
        require(jarSize in 1..maxPluginJarBytes) {
            "Plugin jar '$safeSourceJar' has invalid size ($jarSize bytes)"
        }
        Files.copy(safeSourceJar, staged, StandardCopyOption.REPLACE_EXISTING)
        return staged
    }

    private fun runtimeCacheDirectory(): Path = normalizedDataDirectory.resolve("runtime-cache")

    private fun createDataCheckpoint(pluginIds: Set<String>): Path {
        val root = normalizedDataDirectory.resolve("reload-checkpoints").resolve(UUID.randomUUID().toString()).normalize()
        require(root.startsWith(normalizedDataDirectory)) { "Unsafe checkpoint path '$root'" }
        Files.createDirectories(root)
        pluginIds.forEach { id ->
            val source = normalizedDataDirectory.resolve(id).normalize()
            val target = root.resolve(id)
            require(source.startsWith(normalizedDataDirectory)) { "Unsafe plugin data path '$source'" }
            if (Files.exists(source)) {
                copyDirectory(source, target)
            } else {
                Files.createDirectories(target)
            }
        }
        return root
    }

    private fun restoreDataCheckpoint(checkpointRoot: Path, pluginIds: Set<String>) {
        pluginIds.forEach { id ->
            val target = normalizedDataDirectory.resolve(id).normalize()
            require(target.startsWith(normalizedDataDirectory)) { "Unsafe restore target '$target'" }
            deleteDirectoryIfExists(target)
            val snapshot = checkpointRoot.resolve(id)
            if (Files.exists(snapshot)) {
                copyDirectory(snapshot, target)
            }
        }
    }

    private fun copyDirectory(source: Path, target: Path) {
        val normalizedSource = source.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        require(normalizedSource.startsWith(normalizedDataDirectory)) { "Unsafe copy source '$normalizedSource'" }
        require(normalizedTarget.startsWith(normalizedDataDirectory)) { "Unsafe copy target '$normalizedTarget'" }
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative).normalize()
                require(destination.startsWith(normalizedDataDirectory)) { "Unsafe destination '$destination'" }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun deleteDirectoryIfExists(path: Path) {
        if (!Files.exists(path)) return
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedDataDirectory)) { "Unsafe delete target '$normalized'" }
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    private fun safeDeleteIfExists(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Throwable) {
            // Best effort cleanup.
        }
    }

    private fun instantiatePlugin(manifest: PluginManifest, loader: URLClassLoader): GigaPlugin {
        val clazz = Class.forName(manifest.main, true, loader)
        require(GigaPlugin::class.java.isAssignableFrom(clazz)) {
            "Main class '${manifest.main}' does not implement GigaPlugin"
        }
        return clazz.getDeclaredConstructor().newInstance() as GigaPlugin
    }

    private fun safely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            rootLogger.info("Error during '$label': ${t.message}")
        }
    }

    private fun loadedVersions(): Map<String, String> {
        return loaded.values.associate { it.manifest.id to it.manifest.version }
    }

    private fun assertPluginJarPath(inputPath: Path): Path {
        val absolute = inputPath.toAbsolutePath().normalize()
        require(Files.exists(absolute)) { "Plugin jar does not exist: $absolute" }
        require(Files.isRegularFile(absolute)) { "Plugin jar path is not a file: $absolute" }
        val real = absolute.toRealPath()
        require(real.startsWith(normalizedPluginsDirectory)) {
            "Plugin jar path must be inside '$normalizedPluginsDirectory'"
        }
        return real
    }
}
