package com.clockwork.api

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

inline fun <reified T : Any> EventBus.subscribe(noinline listener: (T) -> Unit) {
    subscribe(T::class.java, listener)
}

inline fun <reified T : Any> EventBus.subscribe(
    options: EventSubscriptionOptions,
    noinline listener: (T) -> Unit
) {
    subscribe(T::class.java, options, listener)
}

inline fun <reified T : Any> EventBus.subscribeOnce(noinline listener: (T) -> Unit) {
    lateinit var wrapper: (T) -> Unit
    wrapper = { event ->
        listener(event)
        unsubscribe(T::class.java, wrapper)
    }
    subscribe(T::class.java, wrapper)
}

fun EventBus.publishAsyncUnit(event: Any): java.util.concurrent.CompletableFuture<Unit> = publishAsync(event)

inline fun <reified T : Any> StorageProvider.store(key: String, version: Int = 1): PersistentStore<T> {
    return store(key, T::class.java, version)
}

inline fun <reified T : Any> PluginContext.store(key: String, version: Int = 1): PersistentStore<T> {
    return storage.store(key, T::class.java, version)
}

inline fun <reified T : Any> PluginContext.loadOrDefault(
    key: String,
    version: Int = 1,
    default: () -> T
): T {
    return store<T>(key, version).load() ?: default()
}

inline fun <reified T : Any> PluginContext.saveState(
    key: String,
    value: T,
    version: Int = 1
) {
    store<T>(key, version).save(value)
}

inline fun <reified T : Any> PluginContext.updateState(
    key: String,
    version: Int = 1,
    default: () -> T,
    updater: (T) -> T
): T {
    val store = store<T>(key, version)
    val current = store.load() ?: default()
    val next = updater(current)
    store.save(next)
    return next
}

fun CommandRegistry.registerAliasOrThrow(alias: String, command: String) {
    val created = registerAlias(alias, command)
    require(created || resolve(alias) == normalizeCommandToken(command)) {
        "Failed to register alias '$alias' for command '$command'"
    }
}

fun CommandRegistry.unregisterAll(vararg commands: String): Int {
    var removed = 0
    commands.forEach {
        if (unregister(it)) removed++
    }
    return removed
}

fun CommandRegistry.registerSpec(
    spec: CommandSpec,
    middleware: List<CommandMiddlewareBinding> = emptyList(),
    completion: CommandCompletionContract? = null,
    completionAsync: CommandCompletionAsyncContract? = null,
    policy: CommandPolicyProfile? = null,
    clockMillis: () -> Long = { System.currentTimeMillis() },
    action: (CommandInvocationContext) -> CommandResult
) {
    val effectivePolicy = policy
    val effectivePermission = spec.permission?.trim()?.takeIf { it.isNotEmpty() } ?: run {
        val prefix = effectivePolicy?.permissionPrefix?.trim().orEmpty()
        if (prefix.isBlank()) null else "$prefix.${spec.command.trim().lowercase()}"
    }
    val normalizedSpec = spec.copy(
        command = spec.command.trim().lowercase(),
        permission = effectivePermission,
        cooldownMillis = if (spec.cooldownMillis > 0L) spec.cooldownMillis else (effectivePolicy?.defaultCooldownMillis ?: 0L),
        rateLimitPerMinute = if (spec.rateLimitPerMinute > 0) spec.rateLimitPerMinute else (effectivePolicy?.defaultRateLimitPerMinute ?: 0)
    )
    val completionProvider = completion ?: CommandCompletionContract { _, _, commandSpec, args ->
        commandSpec.defaultCompletions(args)
    }
    val cooldownBySender = ConcurrentHashMap<String, Long>()
    val rateWindowBySender = ConcurrentHashMap<String, ArrayDeque<Long>>()

    CommandCompletionCatalog.register(
        command = normalizedSpec.command,
        provider = completionProvider,
        providerAsync = completionAsync,
        spec = normalizedSpec
    )
    normalizedSpec.aliases.forEach {
        CommandCompletionCatalog.register(
            command = it,
            provider = completionProvider,
            providerAsync = completionAsync,
            spec = normalizedSpec
        )
    }

    registerOrReplaceSpec(
        spec = normalizedSpec,
        middleware = middleware,
        completion = completionProvider,
        completionAsync = completionAsync,
        policy = effectivePolicy
    ) { invocation ->
        val ctx = invocation.pluginContext
        val sender = invocation.sender
        val args = invocation.rawArgs

        val route = resolveCommandRoute(normalizedSpec, args)
        val routedSpec = route.spec
        val routedArgs = if (route.consumedArgs <= 0) args else args.drop(route.consumedArgs)

        if (args.isNotEmpty() && (args[0].equals("help", ignoreCase = true) || args[0] == "--help")) {
            return@registerOrReplaceSpec CommandResult.ok(routedSpec.helpText())
        }

        val parsedResult = parseCommandArgs(routedSpec, routedArgs)
        parsedResult.error?.let { return@registerOrReplaceSpec it }

        if (!routedSpec.permission.isNullOrBlank() && !ctx.hasPermission(routedSpec.permission)) {
            return@registerOrReplaceSpec CommandResult.error(
                "Missing permission '${routedSpec.permission}' for command '${routedSpec.command}'",
                code = "E_PERMISSION"
            )
        }

        val now = clockMillis()
        val senderId = sender.id
        if (routedSpec.rateLimitPerMinute > 0) {
            val queue = rateWindowBySender.computeIfAbsent(senderId) { ArrayDeque() }
            synchronized(queue) {
                while (queue.isNotEmpty() && now - queue.first() > 60_000L) {
                    queue.removeFirst()
                }
                if (queue.size >= routedSpec.rateLimitPerMinute) {
                    return@registerOrReplaceSpec CommandResult.error(
                        "Rate limit exceeded for '${routedSpec.command}' (${routedSpec.rateLimitPerMinute}/min)",
                        code = "E_RATE_LIMIT"
                    )
                }
                queue.addLast(now)
            }
        }

        if (routedSpec.cooldownMillis > 0L) {
            val last = cooldownBySender[senderId]
            if (last != null && now - last < routedSpec.cooldownMillis) {
                val waitMillis = routedSpec.cooldownMillis - (now - last)
                return@registerOrReplaceSpec CommandResult.error(
                    "Command '${routedSpec.command}' is on cooldown (${waitMillis}ms remaining)",
                    code = "E_COOLDOWN"
                )
            }
        }

        val routedInvocation = CommandInvocationContext(
            pluginContext = ctx,
            sender = sender,
            rawArgs = routedArgs,
            spec = routedSpec,
            parsedArgs = parsedResult.parsed
        )

        val orderedMiddleware = middleware.sortedWith(
            compareBy<CommandMiddlewareBinding> { it.phase.ordinal }
                .thenBy { it.order }
                .thenBy { it.id }
        )

        var index = -1
        fun executeNext(): CommandResult {
            index++
            if (index < orderedMiddleware.size) {
                return orderedMiddleware[index].middleware.invoke(routedInvocation, ::executeNext)
            }
            return action(routedInvocation)
        }

        val result = executeNext()
        if (result.success && routedSpec.cooldownMillis > 0L) {
            cooldownBySender[senderId] = now
        }
        result
    }
    normalizedSpec.aliases.forEach { alias ->
        registerAliasOrThrow(alias, normalizedSpec.command)
    }
}

fun CommandRegistry.registerSpec(
    spec: CommandSpec,
    middleware: List<CommandMiddlewareBinding> = emptyList(),
    completion: CommandCompletionContract? = null,
    completionAsync: CommandCompletionAsyncContract? = null,
    policy: CommandPolicyProfile? = null,
    clockMillis: () -> Long = { System.currentTimeMillis() },
    action: (sender: CommandSender, args: CommandParsedArgs) -> CommandResult
) {
    registerSpec(
        spec = spec,
        middleware = middleware,
        completion = completion,
        completionAsync = completionAsync,
        policy = policy,
        clockMillis = clockMillis
    ) { invocation ->
        action(invocation.sender, invocation.parsedArgs)
    }
}

fun PluginContext.hasPermission(permission: String): Boolean {
    val expected = permission.trim()
    if (expected.isEmpty()) return false
    return manifest.permissions.any { it.equals(expected, ignoreCase = true) }
}

fun PluginContext.requirePermission(permission: String) {
    require(hasPermission(permission)) {
        "Plugin '${manifest.id}' requires permission '$permission' in manifest permissions"
    }
}

fun PluginContext.notifyInfo(player: String, message: String, title: String = ""): Boolean {
    return ui.notify(
        player = player,
        notice = UiNotice(title = title, message = message, level = UiLevel.INFO)
    )
}

fun PluginContext.notifySuccess(player: String, message: String, title: String = ""): Boolean {
    return ui.notify(
        player = player,
        notice = UiNotice(title = title, message = message, level = UiLevel.SUCCESS)
    )
}

fun PluginContext.notifyWarning(player: String, message: String, title: String = ""): Boolean {
    return ui.notify(
        player = player,
        notice = UiNotice(title = title, message = message, level = UiLevel.WARNING)
    )
}

fun PluginContext.notifyError(player: String, message: String, title: String = ""): Boolean {
    return ui.notify(
        player = player,
        notice = UiNotice(title = title, message = message, level = UiLevel.ERROR)
    )
}

fun PluginContext.notify(
    player: String,
    level: UiLevel,
    message: String,
    title: String = "",
    durationMillis: Long = 3_000L
): Boolean {
    return ui.notify(
        player = player,
        notice = UiNotice(
            title = title,
            message = message,
            level = level,
            durationMillis = durationMillis
        )
    )
}

fun PluginContext.actionBar(player: String, message: String, durationTicks: Int = 40): Boolean {
    return ui.actionBar(player, message, durationTicks)
}

fun PluginContext.showMenu(player: String, menu: UiMenu): Boolean = ui.openMenu(player, menu)

fun PluginContext.showDialog(player: String, dialog: UiDialog): Boolean = ui.openDialog(player, dialog)

fun PluginContext.closeUi(player: String): Boolean = ui.close(player)

fun PluginContext.broadcastNotice(
    message: String,
    level: UiLevel = UiLevel.INFO,
    title: String = ""
): Boolean {
    val prefix = when (level) {
        UiLevel.SUCCESS -> "[OK]"
        UiLevel.WARNING -> "[WARN]"
        UiLevel.ERROR -> "[ERR]"
        UiLevel.INFO -> "[INFO]"
    }
    val body = buildString {
        if (title.isNotBlank()) {
            append(title.trim())
            append(": ")
        }
        append(message.trim())
    }.trim()
    if (body.isEmpty()) return false
    return host.broadcast("$prefix $body")
}

fun PluginContext.validateAssets(options: ResourcePackBundleOptions = ResourcePackBundleOptions()): AssetValidationResult {
    return registry.validateAssets(options)
}

fun PluginContext.buildResourcePackBundle(options: ResourcePackBundleOptions = ResourcePackBundleOptions()): ResourcePackBundle {
    return registry.buildResourcePackBundle(options)
}

fun PluginContext.registerNetworkChannel(spec: PluginChannelSpec): Boolean = network.registerChannel(spec)

fun PluginContext.sendPluginMessage(channel: String, payload: Map<String, String>): PluginMessageResult {
    return network.send(
        channel = channel,
        message = PluginMessage(
            channel = channel,
            payload = payload,
            sourcePluginId = manifest.id
        )
    )
}

fun PluginContext.applyHostMutationBatch(
    batch: HostMutationBatch,
    onRollback: ((HostMutationBatchResult) -> Unit)? = null
): HostMutationBatchResult {
    val result = host.applyMutationBatch(batch)
    if (!result.success && result.rolledBack) {
        onRollback?.invoke(result)
    }
    return result
}

class HostMutationBatchDsl internal constructor() {
    private val operations = mutableListOf<HostMutationOp>()

    fun createWorld(name: String, seed: Long = 0L) {
        operations += HostMutationOp(type = HostMutationType.CREATE_WORLD, target = name, longValue = seed)
    }

    fun setWorldTime(name: String, time: Long) {
        operations += HostMutationOp(type = HostMutationType.SET_WORLD_TIME, target = name, longValue = time)
    }

    fun setWorldData(name: String, data: Map<String, String>) {
        operations += HostMutationOp(type = HostMutationType.SET_WORLD_DATA, target = name, data = data)
    }

    fun spawnEntity(type: String, world: String, x: Double, y: Double, z: Double) {
        operations += HostMutationOp(
            type = HostMutationType.SPAWN_ENTITY,
            target = type,
            world = world,
            x = x,
            y = y,
            z = z
        )
    }

    fun setPlayerInventoryItem(player: String, slot: Int, itemId: String) {
        operations += HostMutationOp(
            type = HostMutationType.SET_PLAYER_INVENTORY_ITEM,
            target = player,
            intValue = slot,
            stringValue = itemId
        )
    }

    fun movePlayer(name: String, world: String, x: Double, y: Double, z: Double) {
        operations += HostMutationOp(
            type = HostMutationType.MOVE_PLAYER,
            target = name,
            world = world,
            x = x,
            y = y,
            z = z
        )
    }

    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String) {
        operations += HostMutationOp(
            type = HostMutationType.SET_BLOCK,
            world = world,
            x = x.toDouble(),
            y = y.toDouble(),
            z = z.toDouble(),
            stringValue = blockId
        )
    }

    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true) {
        operations += HostMutationOp(
            type = HostMutationType.BREAK_BLOCK,
            world = world,
            x = x.toDouble(),
            y = y.toDouble(),
            z = z.toDouble(),
            boolValue = dropLoot
        )
    }

    internal fun build(): List<HostMutationOp> = operations.toList()
}

fun PluginContext.hostMutationBatch(
    id: String,
    rollbackReason: String = "rollback",
    block: HostMutationBatchDsl.() -> Unit
): HostMutationBatch {
    val normalizedId = id.trim().ifEmpty { "batch-${manifest.id}" }
    val dsl = HostMutationBatchDsl().apply(block)
    return HostMutationBatch(
        id = normalizedId,
        operations = dsl.build(),
        rollbackReason = rollbackReason
    )
}

fun PluginContext.mutateHost(
    id: String,
    rollbackReason: String = "rollback",
    onRollback: ((HostMutationBatchResult) -> Unit)? = null,
    block: HostMutationBatchDsl.() -> Unit
): HostMutationBatchResult {
    return applyHostMutationBatch(
        batch = hostMutationBatch(
            id = id,
            rollbackReason = rollbackReason,
            block = block
        ),
        onRollback = onRollback
    )
}

inline fun <reified T : Any> PluginContext.onEvent(
    noinline listener: (T) -> Unit
) {
    events.subscribe(T::class.java, listener)
}

inline fun <reified T : Any> PluginContext.onEvent(
    options: EventSubscriptionOptions,
    noinline listener: (T) -> Unit
) {
    events.subscribe(T::class.java, options, listener)
}

inline fun <reified T : Any> PluginContext.onEventOnce(
    noinline listener: (T) -> Unit
) {
    events.subscribeOnce(listener)
}

fun CommandResult.render(): String {
    val normalizedCode = code?.trim()?.takeIf { it.isNotEmpty() }
    val hint = error?.hint?.trim()?.takeIf { it.isNotEmpty() }
    val body = if (normalizedCode == null) message else "[$normalizedCode] $message"
    return if (hint == null) body else "$body (hint: $hint)"
}

fun CommandParsedArgs.requiredInt(name: String): Int {
    return int(name) ?: throw IllegalArgumentException("Missing or invalid Int argument '$name'")
}

fun CommandParsedArgs.requiredLong(name: String): Long {
    return long(name) ?: throw IllegalArgumentException("Missing or invalid Long argument '$name'")
}

fun CommandParsedArgs.requiredDouble(name: String): Double {
    return double(name) ?: throw IllegalArgumentException("Missing or invalid Double argument '$name'")
}

fun CommandParsedArgs.requiredBoolean(name: String): Boolean {
    return boolean(name) ?: throw IllegalArgumentException("Missing or invalid Boolean argument '$name'")
}

fun HostLocationRef.distanceTo(other: HostLocationRef): Double {
    if (!world.equals(other.world, ignoreCase = true)) return Double.POSITIVE_INFINITY
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

fun HostLocationRef.horizontalDistanceTo(other: HostLocationRef): Double {
    if (!world.equals(other.world, ignoreCase = true)) return Double.POSITIVE_INFINITY
    val dx = x - other.x
    val dz = z - other.z
    return sqrt(dx * dx + dz * dz)
}

fun HostLocationRef.verticalDistanceTo(other: HostLocationRef): Double {
    if (!world.equals(other.world, ignoreCase = true)) return Double.POSITIVE_INFINITY
    return abs(y - other.y)
}

fun GigaPlayerMoveEvent.distance3d(): Double = current.location.distanceTo(previous.location)

fun GigaPlayerMoveEvent.horizontalDistance(): Double = current.location.horizontalDistanceTo(previous.location)

fun GigaPlayerMoveEvent.verticalDistanceAbs(): Double = current.location.verticalDistanceTo(previous.location)

fun AdapterInvocation.payloadString(key: String): String? {
    val normalizedKey = key.trim()
    if (normalizedKey.isEmpty()) return null
    return payload[normalizedKey]
}

fun AdapterInvocation.payloadTrimmed(key: String): String? {
    return payloadString(key)?.trim()
}

fun AdapterInvocation.payloadRequired(key: String): String {
    val value = payloadTrimmed(key)
    require(!value.isNullOrEmpty()) { "Missing required payload key '$key'" }
    return value
}

fun AdapterInvocation.payloadInt(key: String): Int? {
    return payloadTrimmed(key)?.toIntOrNull()
}

fun AdapterInvocation.payloadLong(key: String): Long? {
    return payloadTrimmed(key)?.toLongOrNull()
}

fun AdapterInvocation.payloadDouble(key: String): Double? {
    return payloadTrimmed(key)?.toDoubleOrNull()
}

fun AdapterInvocation.payloadCsv(
    key: String,
    separator: Char = ',',
    trimValues: Boolean = true,
    dropEmpty: Boolean = true
): List<String> {
    val raw = payloadString(key) ?: return emptyList()
    val parts = raw.split(separator).map { if (trimValues) it.trim() else it }
    return if (dropEmpty) parts.filter { it.isNotEmpty() } else parts
}

inline fun <reified T : Enum<T>> AdapterInvocation.payloadEnum(
    key: String,
    ignoreCase: Boolean = true
): T? {
    val raw = payloadTrimmed(key) ?: return null
    return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = ignoreCase) }
}

fun AdapterInvocation.payloadIntRequired(key: String): Int {
    return payloadInt(key) ?: throw IllegalArgumentException("Payload key '$key' must be a valid Int")
}

fun AdapterInvocation.payloadLongRequired(key: String): Long {
    return payloadLong(key) ?: throw IllegalArgumentException("Payload key '$key' must be a valid Long")
}

fun AdapterInvocation.payloadDoubleRequired(key: String): Double {
    return payloadDouble(key) ?: throw IllegalArgumentException("Payload key '$key' must be a valid Double")
}

fun AdapterInvocation.payloadBoolRequired(key: String): Boolean {
    return payloadBool(key) ?: throw IllegalArgumentException("Payload key '$key' must be a valid Boolean")
}

fun AdapterInvocation.payloadByPrefix(
    prefix: String,
    stripPrefix: Boolean = true,
    ignoreCase: Boolean = false
): Map<String, String> {
    val normalizedPrefix = prefix.trim()
    if (normalizedPrefix.isEmpty()) return emptyMap()
    return payload.entries
        .asSequence()
        .filter { (key, _) -> key.startsWith(normalizedPrefix, ignoreCase = ignoreCase) }
        .map { (key, value) ->
            val outKey = if (stripPrefix) key.removePrefix(normalizedPrefix) else key
            outKey to value
        }
        .filter { (key, _) -> key.isNotEmpty() }
        .toMap()
}

fun AdapterInvocation.payloadDurationMillis(key: String): Long? {
    val raw = payloadTrimmed(key)?.lowercase() ?: return null
    if (raw.isEmpty()) return null
    val match = Regex("""^(-?\d+)(ms|s|m|h)?$""").matchEntire(raw) ?: return null
    val value = match.groupValues[1].toLongOrNull() ?: return null
    return when (match.groupValues[2]) {
        "", "ms" -> value
        "s" -> value * 1_000L
        "m" -> value * 60_000L
        "h" -> value * 3_600_000L
        else -> null
    }
}

fun AdapterInvocation.payloadMap(
    key: String,
    entrySeparator: Char = ';',
    keyValueSeparator: Char = '='
): Map<String, String> {
    val raw = payloadString(key) ?: return emptyMap()
    if (raw.isBlank()) return emptyMap()
    val out = linkedMapOf<String, String>()
    raw.split(entrySeparator).forEach { entry ->
        val item = entry.trim()
        if (item.isEmpty()) return@forEach
        val idx = item.indexOf(keyValueSeparator)
        if (idx <= 0) return@forEach
        val mapKey = item.substring(0, idx).trim()
        val mapValue = item.substring(idx + 1).trim()
        if (mapKey.isNotEmpty()) {
            out[mapKey] = mapValue
        }
    }
    return out
}

inline fun <reified T : Enum<T>> AdapterInvocation.payloadEnumRequired(
    key: String,
    ignoreCase: Boolean = true
): T {
    return payloadEnum<T>(key, ignoreCase)
        ?: throw IllegalArgumentException("Payload key '$key' must be a valid ${T::class.simpleName} enum value")
}

fun AdapterInvocation.payloadBool(key: String): Boolean? {
    val value = payloadTrimmed(key)?.lowercase() ?: return null
    return when (value) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }
}

private fun normalizeCommandToken(command: String): String {
    val normalized = command.trim().lowercase()
    require(normalized.isNotEmpty()) { "Command name must not be blank" }
    return normalized
}
