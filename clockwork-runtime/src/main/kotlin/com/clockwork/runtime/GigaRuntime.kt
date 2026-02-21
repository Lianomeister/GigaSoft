package com.clockwork.runtime

import com.clockwork.api.GigaLogger
import com.clockwork.api.GigaPlugin
import com.clockwork.api.HostAccess
import com.clockwork.api.DependencyKind
import com.clockwork.api.PluginManifest
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.name
import kotlin.streams.toList

data class LoadedPlugin(
    val manifest: PluginManifest,
    val instance: GigaPlugin,
    val classLoader: URLClassLoader,
    val context: RuntimePluginContext,
    val scheduler: RuntimeScheduler,
    val sourceJarPath: Path,
    val runtimeJarPath: Path,
    val sourceJarSizeBytes: Long,
    val sourceJarLastModifiedMillis: Long
)

class GigaRuntime(
    private val pluginsDirectory: Path,
    private val dataDirectory: Path,
    private val schedulerWorkerThreads: Int = 1,
    private val adapterSecurity: AdapterSecurityConfig = AdapterSecurityConfig(),
    private val faultBudgetPolicy: FaultBudgetPolicy = FaultBudgetPolicy(),
    private val faultBudgetEscalationPolicy: FaultBudgetEscalationPolicy = FaultBudgetEscalationPolicy(),
    private val eventDispatchMode: EventDispatchMode = EventDispatchMode.EXACT,
    private val hostAccess: HostAccess = HostAccess.unavailable(),
    private val rootLogger: GigaLogger = GigaLogger { println("[GigaRuntime] $it") }
) {
    private val normalizedPluginsDirectory = pluginsDirectory.toAbsolutePath().normalize()
    private val normalizedDataDirectory = dataDirectory.toAbsolutePath().normalize()
    private val maxPluginJarBytes = 64L * 1024L * 1024L
    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    private val pluginNetworkHub = RuntimePluginNetworkHub()
    private val isolationAuditor = RuntimeIsolationAuditor()
    private val metrics = RuntimeMetrics(
        faultBudgetPolicy = faultBudgetPolicy,
        faultBudgetEscalationPolicy = faultBudgetEscalationPolicy,
        adapterAuditRetention = AdapterAuditRetentionPolicy(
            maxEntriesPerPlugin = adapterSecurity.auditRetentionMaxEntriesPerPlugin,
            maxEntriesPerAdapter = adapterSecurity.auditRetentionMaxEntriesPerAdapter,
            maxAgeMillis = adapterSecurity.auditRetentionMaxAgeMillis,
            maxMemoryBytes = adapterSecurity.auditRetentionMaxMemoryBytes
        )
    )
    private val isolatedSystems = ConcurrentHashMap<String, ConcurrentHashMap<String, SystemIsolationSnapshot>>()
    @Volatile
    private var lastScanRejected: Map<String, String> = emptyMap()
    @Volatile
    private var lastScanVersionMismatches: Map<String, String> = emptyMap()
    @Volatile
    private var lastScanApiCompatibility: Map<String, String> = emptyMap()
    @Volatile
    private var lastScanDependencyDiagnostics: Map<String, DependencyDiagnostic> = emptyMap()

    init {
        Files.createDirectories(normalizedPluginsDirectory)
        Files.createDirectories(normalizedDataDirectory)
        Files.createDirectories(runtimeCacheDirectory())
    }

    fun scanAndLoad(): List<LoadedPlugin> {
        val pluginEntries = Files.list(normalizedPluginsDirectory).use { stream ->
            stream
                .filter { it.toString().endsWith(".jar") }
                .toList()
        }

        val skipped = ConcurrentLinkedQueue<Pair<Path, String>>()
        val discovered = if (pluginEntries.size > 1) {
            pluginEntries
                .parallelStream()
                .map { jarCandidate ->
                    try {
                        descriptorFromSource(jarCandidate)
                    } catch (t: Throwable) {
                        skipped += jarCandidate to (t.message ?: t.javaClass.simpleName)
                        null
                    }
                }
                .filter { it != null }
                .map { it!! }
                .toList()
        } else {
            pluginEntries.mapNotNull { jarCandidate ->
                try {
                    descriptorFromSource(jarCandidate)
                } catch (t: Throwable) {
                    skipped += jarCandidate to (t.message ?: t.javaClass.simpleName)
                    null
                }
            }
        }
            .sortedWith(compareBy<PluginDescriptor> { it.manifest.id }.thenBy { it.jarPath.toString() })

        skipped
            .sortedBy { it.first.toString() }
            .forEach { (jarCandidate, reason) ->
                rootLogger.info("Skipped '$jarCandidate': $reason")
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
        lastScanDependencyDiagnostics = resolution.diagnostics.toMap()

        resolution.rejected.forEach { (id, reason) ->
            rootLogger.info("Skipped '$id': $reason")
        }

        return resolution.ordered.map(::loadDescriptor)
    }

    fun reloadChangedWithReport(): ReloadReport {
        val changedRoots = loaded.values
            .filter { plugin ->
                val source = plugin.sourceJarPath
                if (!Files.exists(source)) return@filter false
                val fingerprint = sourceFingerprint(source)
                fingerprint.sizeBytes != plugin.sourceJarSizeBytes ||
                    fingerprint.lastModifiedMillis != plugin.sourceJarLastModifiedMillis
            }
            .map { it.manifest.id }
            .toSet()

        if (changedRoots.isEmpty()) {
            return ReloadReport(
                target = "changed",
                affectedPlugins = emptyList(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.SUCCESS,
                reason = "No changed plugin jars detected"
            )
        }

        val reloadSet = collectReloadSet(changedRoots)
        val descriptors = try {
            reloadSet.mapNotNull { id ->
                loaded[id]?.let { descriptorFromSource(it.sourceJarPath) }
            }
        } catch (t: Throwable) {
            return ReloadReport(
                target = "changed",
                affectedPlugins = reloadSet.toList().sorted(),
                reloadedPlugins = emptyList(),
                status = ReloadStatus.FAILED,
                reason = "Failed to read updated manifest: ${t.message}"
            )
        }

        return reloadTransaction(
            target = "changed",
            targetPluginIds = reloadSet,
            descriptors = descriptors
        )
    }

    fun loadJar(jarPath: Path): LoadedPlugin {
        val safeJarPath = assertPluginJarPath(jarPath)
        val descriptor = PluginDescriptor(ManifestReader.readFromJar(safeJarPath), safeJarPath)
        require(!loaded.containsKey(descriptor.manifest.id)) { "Plugin '${descriptor.manifest.id}' is already loaded" }

        require(RuntimeVersion.isApiCompatible(descriptor.manifest.apiVersion)) {
            "Cannot load '${descriptor.manifest.id}': incompatible apiVersion ${descriptor.manifest.apiVersion} (runtime=${RuntimeVersion.API_VERSION})"
        }

        val requiredDeps = descriptor.manifest.dependencies.filter { it.kind == DependencyKind.REQUIRED }
        val conflictDeps = descriptor.manifest.dependencies.filter { it.kind == DependencyKind.CONFLICTS }

        val missing = requiredDeps.filter { dep -> !loaded.containsKey(dep.id) }
        require(missing.isEmpty()) {
            "Cannot load '${descriptor.manifest.id}': missing dependency/dependencies ${missing.joinToString(", ") { it.id }}"
        }

        val mismatched = requiredDeps.filter { dep ->
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

        val conflicts = conflictDeps.filter { dep ->
            val loadedDep = loaded[dep.id] ?: return@filter false
            val range = dep.versionRange
            range.isNullOrBlank() || VersionRange.matches(
                version = loadedDep.manifest.version,
                expression = range
            )
        }
        require(conflicts.isEmpty()) {
            "Cannot load '${descriptor.manifest.id}': conflict(s) ${conflicts.joinToString(", ") { it.id }}"
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
                loaded[id]?.let { descriptorFromSource(it.sourceJarPath) }
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
                .map { descriptorFromSource(it.sourceJarPath) }
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

    fun loadedPluginsView(): Collection<LoadedPlugin> = loaded.values

    fun loadedPlugin(pluginId: String): LoadedPlugin? = loaded[pluginId]

    fun recordSystemTick(pluginId: String, systemId: String, durationNanos: Long, success: Boolean) {
        metrics.recordSystemTick(pluginId, systemId, durationNanos, success)
    }

    fun recordPluginFault(pluginId: String, source: String) {
        metrics.recordPluginFault(pluginId, source)
    }

    fun profile(pluginId: String): PluginRuntimeProfile? {
        val plugin = loaded[pluginId] ?: return null
        return metrics.snapshot(
            pluginId = pluginId,
            activeTaskIds = plugin.scheduler.activeTaskIds(),
            isolatedSystems = isolatedSystems[pluginId].orEmpty().values.toList()
        )
    }

    fun faultBudgetStage(pluginId: String): FaultBudgetStage {
        if (!loaded.containsKey(pluginId)) return FaultBudgetStage.NORMAL
        return metrics.faultBudgetStage(pluginId)
    }

    fun recordSystemIsolation(pluginId: String, snapshot: SystemIsolationSnapshot) {
        isolatedSystems.computeIfAbsent(pluginId) { ConcurrentHashMap() }[snapshot.systemId] = snapshot
    }

    fun removeSystemIsolation(pluginId: String, systemId: String) {
        isolatedSystems[pluginId]?.remove(systemId)
    }

    fun clearPluginIsolation(pluginId: String) {
        isolatedSystems.remove(pluginId)
    }

    fun diagnostics(): RuntimeDiagnostics {
        val descriptors = loaded.values
            .sortedBy { it.manifest.id }
            .map { PluginDescriptor(it.manifest, it.sourceJarPath) }
        val resolution = DependencyResolver.resolve(descriptors)

        val graph = loaded.values
            .sortedBy { it.manifest.id }
            .associate {
                it.manifest.id to it.manifest.dependencies
                    .filter { dep -> dep.kind == DependencyKind.REQUIRED }
                    .map { dep -> dep.id }
                    .sorted()
            }

        val pluginPerformance = loaded.values
            .sortedBy { it.manifest.id }
            .associate { plugin ->
                val profile = metrics.snapshot(plugin.manifest.id, plugin.scheduler.activeTaskIds())
                plugin.manifest.id to PluginPerformanceDiagnostics(
                    adapterCounters = profile.adapterCounters,
                    adapterAudit = profile.adapterAudit,
                    slowSystems = profile.slowSystems,
                    adapterHotspots = profile.adapterHotspots,
                    isolatedSystems = profile.isolatedSystems,
                    faultBudget = profile.faultBudget
                )
            }

        return RuntimeDiagnostics(
            loadedPlugins = loaded.keys.sorted(),
            currentLoadOrder = resolution.ordered.map { it.manifest.id },
            currentDependencyIssues = resolution.rejected.toMap(),
            versionMismatches = resolution.versionMismatches.toMap(),
            apiCompatibility = resolution.apiCompatibility.toMap(),
            currentDependencyDiagnostics = resolution.diagnostics.toMap(),
            lastScanRejected = lastScanRejected.toMap(),
            lastScanVersionMismatches = lastScanVersionMismatches.toMap(),
            lastScanApiCompatibility = lastScanApiCompatibility.toMap(),
            lastScanDependencyDiagnostics = lastScanDependencyDiagnostics.toMap(),
            dependencyGraph = graph,
            pluginPerformance = pluginPerformance,
            isolationViolations = isolationAuditor.snapshotByPlugin()
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
        val beforeDataHash = pluginDataHash(previous.keys)

        val checkpointRoot = createDataCheckpoint(previous.keys)

        orderedIds.asReversed().forEach { unloadInternal(it, deleteRuntimeJar = false) }

        return try {
            val reloaded = resolution.ordered.map(::loadDescriptor)
            val afterDataHash = pluginDataHash(previous.keys)
            val changedPlugins = previous.keys
                .filter { id -> beforeDataHash[id] != afterDataHash[id] }
                .sorted()
            previous.values.forEach { safeDeleteIfExists(it.runtimeJarPath) }
            deleteDirectoryIfExists(checkpointRoot)
            ReloadReport(
                target = target,
                affectedPlugins = orderedIds,
                reloadedPlugins = reloaded.map { it.manifest.id },
                status = ReloadStatus.SUCCESS,
                checkpointChangedPlugins = changedPlugins
            )
        } catch (t: Throwable) {
            val failureMessage = t.message ?: t.javaClass.simpleName
            orderedIds.forEach { unloadInternal(it, deleteRuntimeJar = true) }
            restoreDataCheckpoint(checkpointRoot, previous.keys)

            val rollbackError = restorePrevious(previous, orderedIds)
            val afterRollbackDataHash = pluginDataHash(previous.keys)
            val rollbackDataRestored = previous.keys.all { id -> beforeDataHash[id] == afterRollbackDataHash[id] }
            deleteDirectoryIfExists(checkpointRoot)

            if (rollbackError == null) {
                val recovered = orderedIds.filter { loaded.containsKey(it) }.sorted()
                val failed = orderedIds.filterNot { loaded.containsKey(it) }.sorted()
                ReloadReport(
                    target = target,
                    affectedPlugins = orderedIds,
                    reloadedPlugins = orderedIds,
                    status = ReloadStatus.ROLLED_BACK,
                    reason = failureMessage,
                    rollbackRecoveredPlugins = recovered,
                    rollbackFailedPlugins = failed,
                    rollbackDataRestored = rollbackDataRestored
                )
            } else {
                val recovered = orderedIds.filter { loaded.containsKey(it) }.sorted()
                val failed = orderedIds.filterNot { loaded.containsKey(it) }.sorted()
                ReloadReport(
                    target = target,
                    affectedPlugins = orderedIds,
                    reloadedPlugins = loaded.keys.intersect(orderedIds.toSet()).sorted(),
                    status = ReloadStatus.FAILED,
                    reason = "Reload failed: $failureMessage; rollback failed: $rollbackError",
                    rollbackRecoveredPlugins = recovered,
                    rollbackFailedPlugins = failed,
                    rollbackDataRestored = rollbackDataRestored
                )
            }
        }
    }

    private fun pluginDataHash(pluginIds: Set<String>): Map<String, String> {
        return pluginIds.associateWith { id ->
            val pluginDir = normalizedDataDirectory.resolve(id).normalize()
            require(pluginDir.startsWith(normalizedDataDirectory)) { "Unsafe plugin data path '$pluginDir'" }
            if (!Files.exists(pluginDir)) {
                "absent"
            } else {
                hashDirectory(pluginDir)
            }
        }
    }

    private fun hashDirectory(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream
                .sorted()
                .forEach { entry ->
                    val relative = root.relativize(entry).toString().replace('\\', '/')
                    digest.update(relative.toByteArray(Charsets.UTF_8))
                    if (Files.isRegularFile(entry)) {
                        digest.update(longToBytes(Files.size(entry)))
                        digest.update(longToBytes(Files.getLastModifiedTime(entry).toMillis()))
                    }
                }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun restorePrevious(previous: Map<String, LoadedPlugin>, orderedIds: List<String>): String? {
        return try {
            orderedIds.forEach { id ->
                val plugin = previous[id] ?: return@forEach
                loadFromRuntimeArtifact(
                    manifest = plugin.manifest,
                    sourceJarPath = plugin.sourceJarPath,
                    runtimeJarPath = plugin.runtimeJarPath,
                    sourceFingerprint = JarFingerprint(
                        sizeBytes = plugin.sourceJarSizeBytes,
                        lastModifiedMillis = plugin.sourceJarLastModifiedMillis
                    )
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
            plugin.manifest.dependencies
                .filter { it.kind == DependencyKind.REQUIRED }
                .forEach { dependency ->
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
        val sourceJar = descriptor.jarPath
        val sourceFingerprint = sourceFingerprintFromValidated(sourceJar)
        val staged = stageRuntimeJarFromValidated(descriptor.manifest.id, sourceJar)
        try {
            return loadFromRuntimeArtifact(
                manifest = descriptor.manifest,
                sourceJarPath = sourceJar,
                runtimeJarPath = staged,
                sourceFingerprint = sourceFingerprint
            )
        } catch (t: Throwable) {
            safeDeleteIfExists(staged)
            throw t
        }
    }

    private fun loadFromRuntimeArtifact(
        manifest: PluginManifest,
        sourceJarPath: Path,
        runtimeJarPath: Path,
        sourceFingerprint: JarFingerprint
    ): LoadedPlugin {
        val loader = URLClassLoader(arrayOf(runtimeJarPath.toUri().toURL()), javaClass.classLoader)
        try {
            val plugin = instantiatePlugin(manifest, loader)
            val scheduler = RuntimeScheduler(
                pluginId = manifest.id,
                workerThreads = schedulerWorkerThreads,
                runObserver = { pluginId, taskId, durationNanos, success ->
                    metrics.recordTaskRun(pluginId, taskId, durationNanos, success)
                }
            )
            val registry = RuntimeRegistry(manifest.id)
            val eventBus = RuntimeEventBus(mode = eventDispatchMode)
            val pluginLogger = GigaLogger { rootLogger.info("[${manifest.id}] $it") }
            val isolationPolicy = RuntimeIsolationPolicyCompiler.fromManifest(manifest)
            val commandRegistry = RuntimeIsolatedCommandRegistry(
                delegate = RuntimeCommandRegistry(pluginId = manifest.id),
                pluginId = manifest.id,
                policy = isolationPolicy,
                logger = pluginLogger,
                auditor = isolationAuditor,
                eventBus = eventBus
            )
            val storage = RuntimeIsolatedStorageProvider(
                delegate = JsonStorageProvider(dataDirectory.resolve(manifest.id)),
                pluginId = manifest.id,
                policy = isolationPolicy,
                logger = pluginLogger,
                auditor = isolationAuditor,
                eventBus = eventBus
            )
            val pluginHostAccess = RuntimeHostAccess(
                delegate = hostAccess,
                pluginId = manifest.id,
                rawPermissions = manifest.permissions,
                logger = pluginLogger,
                isolationPolicy = isolationPolicy,
                isolationAuditor = isolationAuditor,
                eventBus = eventBus
            )
            val adapters = RuntimeModAdapterRegistry(
                pluginId = manifest.id,
                logger = pluginLogger,
                securityConfig = adapterSecurity,
                rawPermissions = manifest.permissions,
                eventBus = eventBus,
                invocationObserver = { event ->
                    metrics.recordAdapterInvocation(
                        pluginId = manifest.id,
                        adapterId = event.adapterId,
                        outcome = event.outcome,
                        action = event.action,
                        detail = event.detail,
                        durationNanos = event.durationNanos,
                        payloadEntries = event.payloadEntries
                    )
                }
            )
            val pluginUi = RuntimePluginUi(
                hostAccess = pluginHostAccess,
                events = eventBus
            )
            val context = RuntimePluginContext(
                manifest,
                pluginLogger,
                scheduler,
                registry,
                adapters,
                storage,
                commandRegistry,
                eventBus,
                RuntimeIsolatedPluginNetwork(
                    delegate = pluginNetworkHub.viewFor(manifest.id),
                    pluginId = manifest.id,
                    policy = isolationPolicy,
                    logger = pluginLogger,
                    auditor = isolationAuditor,
                    eventBus = eventBus
                ),
                pluginUi,
                pluginHostAccess
            )

            plugin.onEnable(context)
            if (registry.hasResourceAssets()) {
                val assetValidation = registry.validateAssets()
                if (!assetValidation.valid) {
                    val details = assetValidation.issues
                        .filter { it.severity == com.clockwork.api.AssetValidationSeverity.ERROR }
                        .joinToString("; ") { issue ->
                            val idPart = issue.assetId?.let { "[$it] " } ?: ""
                            "${issue.code}: $idPart${issue.message}"
                        }
                    throw IllegalStateException("Asset validation failed for plugin '${manifest.id}': $details")
                }
                // Resource-pack bundle build is intentionally lazy to keep initial boot fast.
                // Plugins can build it on demand via registry/buildResourcePackBundle APIs.
            }

            val loadedPlugin = LoadedPlugin(
                manifest = manifest,
                instance = plugin,
                classLoader = loader,
                context = context,
                scheduler = scheduler,
                sourceJarPath = sourceJarPath,
                runtimeJarPath = runtimeJarPath,
                sourceJarSizeBytes = sourceFingerprint.sizeBytes,
                sourceJarLastModifiedMillis = sourceFingerprint.lastModifiedMillis
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
        pluginNetworkHub.removePlugin(pluginId)
        clearPluginIsolation(pluginId)
        metrics.clearPlugin(pluginId)
        safely("disable $pluginId") { plugin.instance.onDisable(plugin.context) }
        safely("adapter shutdown $pluginId") {
            (plugin.context.adapters as? RuntimeModAdapterRegistry)?.shutdown()
        }
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

    private fun stageRuntimeJarFromValidated(pluginId: String, safeSourceJar: Path): Path {
        val cacheDir = runtimeCacheDirectory().resolve(pluginId)
        Files.createDirectories(cacheDir)
        val stagedName = "${System.currentTimeMillis()}-${UUID.randomUUID()}-${safeSourceJar.fileName.name}"
        val staged = cacheDir.resolve(stagedName).normalize()
        require(staged.startsWith(cacheDir.normalize())) { "Invalid staged runtime path for plugin '$pluginId'" }
        val jarSize = Files.size(safeSourceJar)
        require(jarSize in 1..maxPluginJarBytes) {
            "Plugin jar '$safeSourceJar' has invalid size ($jarSize bytes)"
        }
        try {
            Files.createLink(staged, safeSourceJar)
        } catch (_: Throwable) {
            Files.copy(safeSourceJar, staged, StandardCopyOption.REPLACE_EXISTING)
        }
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

    private fun collectReloadSet(rootIds: Set<String>): Set<String> {
        val reverseDeps = mutableMapOf<String, MutableSet<String>>()
        loaded.values.forEach { plugin ->
            plugin.manifest.dependencies
                .filter { it.kind == DependencyKind.REQUIRED }
                .forEach { dependency ->
                reverseDeps.computeIfAbsent(dependency.id) { linkedSetOf() }.add(plugin.manifest.id)
                }
        }
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        rootIds.forEach(queue::addLast)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            reverseDeps[current].orEmpty().forEach(queue::addLast)
        }
        return visited
    }

    private fun descriptorFromSource(jarCandidate: Path): PluginDescriptor {
        val jar = assertPluginJarPath(jarCandidate)
        var lastError: Throwable? = null
        repeat(5) { attempt ->
            try {
                val read = ManifestReader.readFromJarDetailed(jar)
                return PluginDescriptor(read.manifest, jar)
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 4) Thread.sleep(80L)
            }
        }
        throw IllegalStateException("Failed to read manifest from '$jar': ${lastError?.message}", lastError)
    }

    private fun sourceFingerprint(jarPath: Path): JarFingerprint {
        val safe = assertPluginJarPath(jarPath)
        return sourceFingerprintFromValidated(safe)
    }

    private fun sourceFingerprintFromValidated(validatedJarPath: Path): JarFingerprint {
        val size = Files.size(validatedJarPath)
        val modified = Files.getLastModifiedTime(validatedJarPath).toMillis()
        return JarFingerprint(sizeBytes = size, lastModifiedMillis = modified)
    }

    private data class JarFingerprint(
        val sizeBytes: Long,
        val lastModifiedMillis: Long
    )

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
