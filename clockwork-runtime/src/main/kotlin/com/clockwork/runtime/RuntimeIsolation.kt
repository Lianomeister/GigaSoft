package com.clockwork.runtime

import com.clockwork.api.*
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal object IsolationDiagnosticCodes {
    const val CAPABILITY_MISSING = "ISO_CAPABILITY_MISSING"
    const val FILESYSTEM_CAPABILITY_REQUIRED = "ISO_FILESYSTEM_CAPABILITY_REQUIRED"
    const val FILESYSTEM_PATH_DENIED = "ISO_FILESYSTEM_PATH_DENIED"
    const val NETWORK_CAPABILITY_REQUIRED = "ISO_NETWORK_CAPABILITY_REQUIRED"
    const val NETWORK_PROTOCOL_DENIED = "ISO_NETWORK_PROTOCOL_DENIED"
    const val NETWORK_HOST_DENIED = "ISO_NETWORK_HOST_DENIED"
    const val NETWORK_PATH_DENIED = "ISO_NETWORK_PATH_DENIED"
    const val COMMANDS_CAPABILITY_REQUIRED = "ISO_COMMANDS_CAPABILITY_REQUIRED"
    const val COMMAND_PATH_DENIED = "ISO_COMMAND_PATH_DENIED"
    const val WORLD_MUTATION_CAPABILITY_REQUIRED = "ISO_WORLD_MUTATION_CAPABILITY_REQUIRED"
}

internal enum class RuntimeCapability(val key: String) {
    FILESYSTEM("filesystem"),
    NETWORK("network"),
    COMMANDS("commands"),
    WORLD_MUTATION("world-mutation");

    companion object {
        fun parse(raw: String): RuntimeCapability? {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.key == normalized }
        }
    }
}

internal data class RuntimeIsolationPolicy(
    val enabled: Boolean,
    val capabilities: Set<RuntimeCapability>,
    val filesystemAllowlist: List<String>,
    val networkProtocolAllowlist: List<String>,
    val networkHostAllowlist: List<String>,
    val networkPathAllowlist: List<String>,
    val commandAllowlist: List<String>
) {
    fun hasCapability(capability: RuntimeCapability): Boolean = capability in capabilities

    fun isFilesystemPathAllowed(path: String): Boolean = matchesAllowlist(path, filesystemAllowlist)

    fun isCommandAllowed(command: String): Boolean = matchesAllowlist(command, commandAllowlist)

    fun validateUrl(url: String): UrlValidation {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return UrlValidation(false, IsolationDiagnosticCodes.NETWORK_PATH_DENIED, "Invalid URL")
        val protocol = uri.scheme?.trim()?.lowercase().orEmpty()
        if (protocol.isEmpty()) {
            return UrlValidation(false, IsolationDiagnosticCodes.NETWORK_PROTOCOL_DENIED, "Missing URL protocol")
        }
        if (!matchesAllowlist(protocol, networkProtocolAllowlist)) {
            return UrlValidation(false, IsolationDiagnosticCodes.NETWORK_PROTOCOL_DENIED, "Protocol '$protocol' denied")
        }
        val host = uri.host?.trim()?.lowercase().orEmpty()
        if (host.isEmpty()) {
            return UrlValidation(false, IsolationDiagnosticCodes.NETWORK_HOST_DENIED, "Missing URL host")
        }
        if (!matchesAllowlist(host, networkHostAllowlist)) {
            return UrlValidation(false, IsolationDiagnosticCodes.NETWORK_HOST_DENIED, "Host '$host' denied")
        }
        val path = uri.path.orEmpty().ifBlank { "/" }
        if (!matchesAllowlist(path, networkPathAllowlist)) {
            return UrlValidation(false, IsolationDiagnosticCodes.NETWORK_PATH_DENIED, "Path '$path' denied")
        }
        return UrlValidation(true, null, null)
    }

    companion object {
        fun legacy(rawPermissions: Collection<String>): RuntimeIsolationPolicy {
            val permissions = rawPermissions.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val caps = linkedSetOf(RuntimeCapability.COMMANDS, RuntimeCapability.FILESYSTEM)
            if (permissions.any { it == HostPermissions.INTERNET_HTTP_GET || it == HostPermissions.PLUGIN_INSTALL }) {
                caps += RuntimeCapability.NETWORK
            }
            if (permissions.any { isWorldMutationPermission(it) }) {
                caps += RuntimeCapability.WORLD_MUTATION
            }
            return RuntimeIsolationPolicy(
                enabled = false,
                capabilities = caps,
                filesystemAllowlist = listOf("*"),
                networkProtocolAllowlist = listOf("*"),
                networkHostAllowlist = listOf("*"),
                networkPathAllowlist = listOf("*"),
                commandAllowlist = listOf("*")
            )
        }

        private fun isWorldMutationPermission(permission: String): Boolean {
            return when (permission) {
                HostPermissions.MUTATION_BATCH,
                HostPermissions.WORLD_WRITE,
                HostPermissions.WORLD_DATA_WRITE,
                HostPermissions.WORLD_WEATHER_WRITE,
                HostPermissions.ENTITY_SPAWN,
                HostPermissions.ENTITY_REMOVE,
                HostPermissions.ENTITY_DATA_WRITE,
                HostPermissions.PLAYER_MOVE,
                HostPermissions.PLAYER_GAMEMODE_WRITE,
                HostPermissions.PLAYER_STATUS_WRITE,
                HostPermissions.PLAYER_EFFECT_WRITE,
                HostPermissions.INVENTORY_WRITE,
                HostPermissions.BLOCK_WRITE,
                HostPermissions.BLOCK_DATA_WRITE -> true
                else -> false
            }
        }
    }
}

internal data class UrlValidation(
    val ok: Boolean,
    val code: String?,
    val detail: String?
)

internal object RuntimeIsolationPolicyCompiler {
    fun fromManifest(manifest: PluginManifest): RuntimeIsolationPolicy {
        val declaredCapabilities = linkedSetOf<RuntimeCapability>()
        manifest.capabilities.forEach { raw ->
            RuntimeCapability.parse(raw)?.let(declaredCapabilities::add)
        }
        manifest.permissions
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.startsWith("capability.") }
            .map { it.removePrefix("capability.") }
            .forEach { raw -> RuntimeCapability.parse(raw)?.let(declaredCapabilities::add) }

        val explicitIsolation =
            declaredCapabilities.isNotEmpty() ||
                manifest.isolation.filesystemAllowlist.isNotEmpty() ||
                manifest.isolation.networkProtocolAllowlist.isNotEmpty() ||
                manifest.isolation.networkHostAllowlist.isNotEmpty() ||
                manifest.isolation.networkPathAllowlist.isNotEmpty() ||
                manifest.isolation.commandAllowlist.isNotEmpty()

        if (!explicitIsolation) {
            return RuntimeIsolationPolicy.legacy(manifest.permissions)
        }

        return RuntimeIsolationPolicy(
            enabled = true,
            capabilities = declaredCapabilities,
            filesystemAllowlist = normalizeAllowlist(manifest.isolation.filesystemAllowlist),
            networkProtocolAllowlist = normalizeAllowlist(manifest.isolation.networkProtocolAllowlist),
            networkHostAllowlist = normalizeAllowlist(manifest.isolation.networkHostAllowlist),
            networkPathAllowlist = normalizeAllowlist(manifest.isolation.networkPathAllowlist, lowercase = false),
            commandAllowlist = normalizeAllowlist(manifest.isolation.commandAllowlist)
        )
    }

    private fun normalizeAllowlist(values: List<String>, lowercase: Boolean = true): List<String> {
        return values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (lowercase) it.lowercase() else it }
            .distinct()
            .sorted()
    }
}

data class RuntimeIsolationViolation(
    val pluginId: String,
    val capability: String,
    val action: String,
    val code: String,
    val detail: String,
    val timestampMillis: Long
)

internal class RuntimeIsolationAuditor(
    private val maxEntriesPerPlugin: Int = 256
) {
    private val violations = ConcurrentHashMap<String, ArrayDeque<RuntimeIsolationViolation>>()

    fun record(
        pluginId: String,
        capability: RuntimeCapability,
        action: String,
        code: String,
        detail: String,
        logger: GigaLogger,
        eventBus: EventBus? = null
    ) {
        val now = System.currentTimeMillis()
        val entry = RuntimeIsolationViolation(
            pluginId = pluginId,
            capability = capability.key,
            action = action,
            code = code,
            detail = detail,
            timestampMillis = now
        )
        val queue = violations.computeIfAbsent(pluginId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(entry)
            while (queue.size > maxEntriesPerPlugin) queue.removeFirst()
        }
        logger.info("[isolation-audit] plugin=$pluginId capability=${capability.key} action=$action code=$code detail=$detail")
        eventBus?.publish(
            GigaIsolationViolationEvent(
                pluginId = pluginId,
                capability = capability.key,
                action = action,
                code = code,
                detail = detail,
                timestampMillis = now
            )
        )
    }

    fun snapshotByPlugin(): Map<String, List<RuntimeIsolationViolation>> {
        return violations.entries
            .sortedBy { it.key }
            .associate { (pluginId, queue) ->
                pluginId to synchronized(queue) { queue.toList() }
            }
    }
}

internal class RuntimeIsolatedStorageProvider(
    private val delegate: StorageProvider,
    private val pluginId: String,
    private val policy: RuntimeIsolationPolicy,
    private val logger: GigaLogger,
    private val auditor: RuntimeIsolationAuditor,
    private val eventBus: EventBus
) : StorageProvider {
    override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
        val normalizedKey = key.trim()
        if (!policy.enabled) return delegate.store(normalizedKey, type, version)
        if (!capabilityAllowed(RuntimeCapability.FILESYSTEM, "storage.store", IsolationDiagnosticCodes.FILESYSTEM_CAPABILITY_REQUIRED, "Missing filesystem capability")) {
            return deniedStore()
        }
        if (!policy.isFilesystemPathAllowed(normalizedKey)) {
            record(
                capability = RuntimeCapability.FILESYSTEM,
                action = "storage.store",
                code = IsolationDiagnosticCodes.FILESYSTEM_PATH_DENIED,
                detail = "Storage key '$normalizedKey' is outside filesystem allowlist"
            )
            return deniedStore()
        }
        return delegate.store(normalizedKey, type, version)
    }

    private fun capabilityAllowed(capability: RuntimeCapability, action: String, code: String, detail: String): Boolean {
        if (policy.hasCapability(capability)) return true
        record(capability, action, code, detail)
        return false
    }

    private fun record(capability: RuntimeCapability, action: String, code: String, detail: String) {
        auditor.record(
            pluginId = pluginId,
            capability = capability,
            action = action,
            code = code,
            detail = detail,
            logger = logger,
            eventBus = eventBus
        )
    }

    private fun <T : Any> deniedStore(): PersistentStore<T> {
        return object : PersistentStore<T> {
            override fun load(): T? = null
            override fun save(value: T) {}
            override fun migrate(fromVersion: Int, migration: (T) -> T) {}
        }
    }
}

internal class RuntimeIsolatedCommandRegistry(
    private val delegate: CommandRegistry,
    private val pluginId: String,
    private val policy: RuntimeIsolationPolicy,
    private val logger: GigaLogger,
    private val auditor: RuntimeIsolationAuditor,
    private val eventBus: EventBus
) : CommandRegistry, RuntimeCommandExecution {
    override fun registerSpec(
        spec: CommandSpec,
        middleware: List<CommandMiddlewareBinding>,
        completion: CommandCompletionContract?,
        completionAsync: CommandCompletionAsyncContract?,
        policy: CommandPolicyProfile?,
        action: (CommandInvocationContext) -> CommandResult
    ) {
        guardRegisterable(spec.command)
        spec.aliases.forEach(::guardRegisterable)
        delegate.registerSpec(spec, middleware, completion, completionAsync, policy, action)
    }

    override fun registerOrReplaceSpec(
        spec: CommandSpec,
        middleware: List<CommandMiddlewareBinding>,
        completion: CommandCompletionContract?,
        completionAsync: CommandCompletionAsyncContract?,
        policy: CommandPolicyProfile?,
        action: (CommandInvocationContext) -> CommandResult
    ) {
        guardRegisterable(spec.command)
        spec.aliases.forEach(::guardRegisterable)
        delegate.registerOrReplaceSpec(spec, middleware, completion, completionAsync, policy, action)
    }

    override fun unregister(command: String): Boolean {
        if (!guardCommand(command.trim().lowercase(), "commands.unregister")) return false
        return delegate.unregister(command)
    }

    override fun registerAlias(alias: String, command: String): Boolean {
        if (!guardCommand(alias.trim().lowercase(), "commands.alias.register")) return false
        if (!guardCommand(command.trim().lowercase(), "commands.alias.register")) return false
        return delegate.registerAlias(alias, command)
    }

    override fun unregisterAlias(alias: String): Boolean {
        if (!guardCommand(alias.trim().lowercase(), "commands.alias.unregister")) return false
        return delegate.unregisterAlias(alias)
    }

    override fun resolve(commandOrAlias: String): String? = delegate.resolve(commandOrAlias)

    override fun registeredCommands(): Map<String, CommandSpec> = delegate.registeredCommands()

    override fun commandTelemetry(commandOrAlias: String): CommandTelemetrySnapshot? = delegate.commandTelemetry(commandOrAlias)

    override fun commandTelemetry(): Map<String, CommandTelemetrySnapshot> = delegate.commandTelemetry()

    override fun execute(ctx: PluginContext, sender: CommandSender, commandLine: String): String {
        if (!policy.enabled) {
            val executable = delegate as? RuntimeCommandExecution
                ?: return "Plugin command registry unavailable"
            return executable.execute(ctx, sender, commandLine)
        }
        if (!policy.hasCapability(RuntimeCapability.COMMANDS)) {
            record(
                capability = RuntimeCapability.COMMANDS,
                action = "commands.execute",
                code = IsolationDiagnosticCodes.COMMANDS_CAPABILITY_REQUIRED,
                detail = "Missing commands capability"
            )
            return "[${IsolationDiagnosticCodes.COMMANDS_CAPABILITY_REQUIRED}] Missing commands capability"
        }
        val executable = delegate as? RuntimeCommandExecution
            ?: return "Plugin command registry unavailable"
        return executable.execute(ctx, sender, commandLine)
    }

    private fun guardRegisterable(command: String) {
        if (!guardCommand(command.trim().lowercase(), "commands.register")) {
            throw IllegalStateException("[${
                IsolationDiagnosticCodes.COMMANDS_CAPABILITY_REQUIRED
            }] Plugin '$pluginId' is not allowed to register command '$command'")
        }
    }

    private fun guardCommand(command: String, action: String): Boolean {
        if (!policy.enabled) return true
        if (!policy.hasCapability(RuntimeCapability.COMMANDS)) {
            record(
                capability = RuntimeCapability.COMMANDS,
                action = action,
                code = IsolationDiagnosticCodes.COMMANDS_CAPABILITY_REQUIRED,
                detail = "Missing commands capability"
            )
            return false
        }
        if (!policy.isCommandAllowed(command)) {
            record(
                capability = RuntimeCapability.COMMANDS,
                action = action,
                code = IsolationDiagnosticCodes.COMMAND_PATH_DENIED,
                detail = "Command '$command' is outside command allowlist"
            )
            return false
        }
        return true
    }

    private fun record(capability: RuntimeCapability, action: String, code: String, detail: String) {
        auditor.record(
            pluginId = pluginId,
            capability = capability,
            action = action,
            code = code,
            detail = detail,
            logger = logger,
            eventBus = eventBus
        )
    }
}

internal class RuntimeIsolatedPluginNetwork(
    private val delegate: PluginNetwork,
    private val pluginId: String,
    private val policy: RuntimeIsolationPolicy,
    private val logger: GigaLogger,
    private val auditor: RuntimeIsolationAuditor,
    private val eventBus: EventBus
) : PluginNetwork {
    override fun registerChannel(spec: PluginChannelSpec): Boolean {
        if (!networkAllowed("network.register-channel")) return false
        return delegate.registerChannel(spec)
    }

    override fun listChannels(): List<PluginChannelSpec> {
        if (!networkAllowed("network.list-channels")) return emptyList()
        return delegate.listChannels()
    }

    override fun subscribe(channel: String, listener: (PluginMessage) -> Unit) {
        if (!networkAllowed("network.subscribe")) return
        delegate.subscribe(channel, listener)
    }

    override fun unsubscribe(channel: String, listener: (PluginMessage) -> Unit): Boolean {
        if (!networkAllowed("network.unsubscribe")) return false
        return delegate.unsubscribe(channel, listener)
    }

    override fun send(channel: String, message: PluginMessage): PluginMessageResult {
        if (!networkAllowed("network.send")) {
            return PluginMessageResult(
                status = PluginMessageStatus.DENIED,
                reason = "[${IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED}] Missing network capability"
            )
        }
        return delegate.send(channel, message)
    }

    override fun channelStats(channel: String): PluginChannelStats? {
        if (!networkAllowed("network.channel-stats")) return null
        return delegate.channelStats(channel)
    }

    private fun networkAllowed(action: String): Boolean {
        if (!policy.enabled) return true
        if (policy.hasCapability(RuntimeCapability.NETWORK)) return true
        auditor.record(
            pluginId = pluginId,
            capability = RuntimeCapability.NETWORK,
            action = action,
            code = IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED,
            detail = "Missing network capability",
            logger = logger,
            eventBus = eventBus
        )
        return false
    }
}

internal fun matchesAllowlist(value: String, allowlist: List<String>): Boolean {
    if (allowlist.isEmpty()) return false
    val normalized = value.trim()
    if (normalized.isEmpty()) return false
    val normalizedLower = normalized.lowercase()
    return allowlist.any { rawPattern ->
        val pattern = rawPattern.trim()
        if (pattern == "*") return@any true
        if (pattern.endsWith("*")) {
            val prefix = pattern.removeSuffix("*")
            return@any if (containsUppercase(prefix)) normalized.startsWith(prefix) else normalizedLower.startsWith(prefix.lowercase())
        }
        if (containsUppercase(pattern)) normalized == pattern else normalizedLower == pattern.lowercase()
    }
}

private fun containsUppercase(value: String): Boolean = value.any { it.isUpperCase() }
