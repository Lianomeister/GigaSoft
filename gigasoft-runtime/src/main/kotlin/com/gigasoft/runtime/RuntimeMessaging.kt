package com.gigasoft.runtime

import com.gigasoft.api.CommandRegistry
import com.gigasoft.api.EventBus
import com.gigasoft.api.EventPriority
import com.gigasoft.api.EventSubscriptionOptions
import com.gigasoft.api.EventTraceSnapshot
import com.gigasoft.api.EventTypeTraceSnapshot
import com.gigasoft.api.GigaCommandPostExecuteEvent
import com.gigasoft.api.GigaCommandPreExecuteEvent
import com.gigasoft.api.PluginContext
import com.gigasoft.api.defaultCompletions
import com.gigasoft.api.hasPermission
import com.gigasoft.api.helpText
import com.gigasoft.api.render
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicLong

class RuntimeCommandRegistry(
    private val pluginId: String = "unknown"
) : CommandRegistry {
    private data class RegisteredCommand(
        val spec: com.gigasoft.api.CommandSpec,
        val middleware: List<com.gigasoft.api.CommandMiddlewareBinding>,
        val action: (com.gigasoft.api.CommandInvocationContext) -> com.gigasoft.api.CommandResult,
        val cooldownBySender: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        val rateWindowBySender: ConcurrentHashMap<String, ArrayDeque<Long>> = ConcurrentHashMap()
    )

    private class MutableCommandTelemetry {
        private val totalRuns = AtomicLong(0)
        private val failures = AtomicLong(0)
        private val durations = ArrayDeque<Long>()
        private val errorCounts = ConcurrentHashMap<String, AtomicLong>()

        fun record(result: com.gigasoft.api.CommandResult, durationNanos: Long) {
            totalRuns.incrementAndGet()
            if (!result.success) failures.incrementAndGet()
            if (!result.success) {
                val errorCode = result.error?.code ?: result.code ?: "E_COMMAND"
                errorCounts.computeIfAbsent(errorCode) { AtomicLong(0) }.incrementAndGet()
            }
            synchronized(durations) {
                durations.addLast(durationNanos)
                while (durations.size > 2048) durations.removeFirst()
            }
        }

        fun snapshot(command: String): com.gigasoft.api.CommandTelemetrySnapshot {
            val sortedDurations = synchronized(durations) { durations.toList().sorted() }
            fun percentile(p: Double): Long {
                if (sortedDurations.isEmpty()) return 0L
                val idx = ((sortedDurations.size - 1) * p).toInt().coerceIn(0, sortedDurations.lastIndex)
                return sortedDurations[idx]
            }
            val total = totalRuns.get()
            val failed = failures.get()
            val topErrors = errorCounts.entries
                .map { com.gigasoft.api.CommandErrorCount(it.key, it.value.get()) }
                .sortedByDescending { it.count }
                .take(5)
            return com.gigasoft.api.CommandTelemetrySnapshot(
                command = command,
                totalRuns = total,
                failures = failed,
                failRate = if (total == 0L) 0.0 else failed.toDouble() / total.toDouble(),
                p50Nanos = percentile(0.50),
                p95Nanos = percentile(0.95),
                topErrors = topErrors
            )
        }
    }

    private val handlers = ConcurrentHashMap<String, RegisteredCommand>()
    private val aliases = ConcurrentHashMap<String, String>()
    private val telemetry = ConcurrentHashMap<String, MutableCommandTelemetry>()

    override fun registerSpec(
        spec: com.gigasoft.api.CommandSpec,
        middleware: List<com.gigasoft.api.CommandMiddlewareBinding>,
        completion: com.gigasoft.api.CommandCompletionContract?,
        completionAsync: com.gigasoft.api.CommandCompletionAsyncContract?,
        policy: com.gigasoft.api.CommandPolicyProfile?,
        action: (com.gigasoft.api.CommandInvocationContext) -> com.gigasoft.api.CommandResult
    ) {
        val profile = policy ?: com.gigasoft.api.CommandPolicyProfiles.forPlugin(pluginId)
        val effectivePermission = spec.permission?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            val prefix = profile?.permissionPrefix?.trim().orEmpty()
            if (prefix.isBlank()) null else "$prefix.${spec.command.trim().lowercase()}"
        }
        val normalizedSpec = spec.copy(
            command = normalizeCommand(spec.command),
            permission = effectivePermission,
            cooldownMillis = if (spec.cooldownMillis > 0L) spec.cooldownMillis else (profile?.defaultCooldownMillis ?: 0L),
            rateLimitPerMinute = if (spec.rateLimitPerMinute > 0) spec.rateLimitPerMinute else (profile?.defaultRateLimitPerMinute ?: 0)
        )
        val key = normalizedSpec.command
        require(!aliases.containsKey(key)) { "Command '$key' collides with existing alias" }
        val previous = handlers.putIfAbsent(
            key,
            RegisteredCommand(
                spec = normalizedSpec,
                middleware = middleware,
                action = action
            )
        )
        require(previous == null) { "Duplicate command '$key'" }
        normalizedSpec.aliases.forEach { alias ->
            registerAliasOrThrow(alias, key)
        }
        val completionProvider = completion ?: com.gigasoft.api.CommandCompletionContract { _, _, commandSpec, args ->
            commandSpec.defaultCompletions(args)
        }
        com.gigasoft.api.CommandCompletionCatalog.register(
            command = key,
            provider = completionProvider,
            providerAsync = completionAsync,
            spec = normalizedSpec
        )
        normalizedSpec.aliases.forEach { alias ->
            com.gigasoft.api.CommandCompletionCatalog.register(
                command = alias,
                provider = completionProvider,
                providerAsync = completionAsync,
                spec = normalizedSpec
            )
        }
    }

    override fun registerOrReplaceSpec(
        spec: com.gigasoft.api.CommandSpec,
        middleware: List<com.gigasoft.api.CommandMiddlewareBinding>,
        completion: com.gigasoft.api.CommandCompletionContract?,
        completionAsync: com.gigasoft.api.CommandCompletionAsyncContract?,
        policy: com.gigasoft.api.CommandPolicyProfile?,
        action: (com.gigasoft.api.CommandInvocationContext) -> com.gigasoft.api.CommandResult
    ) {
        val profile = policy ?: com.gigasoft.api.CommandPolicyProfiles.forPlugin(pluginId)
        val effectivePermission = spec.permission?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            val prefix = profile?.permissionPrefix?.trim().orEmpty()
            if (prefix.isBlank()) null else "$prefix.${spec.command.trim().lowercase()}"
        }
        val normalizedSpec = spec.copy(
            command = normalizeCommand(spec.command),
            permission = effectivePermission,
            cooldownMillis = if (spec.cooldownMillis > 0L) spec.cooldownMillis else (profile?.defaultCooldownMillis ?: 0L),
            rateLimitPerMinute = if (spec.rateLimitPerMinute > 0) spec.rateLimitPerMinute else (profile?.defaultRateLimitPerMinute ?: 0)
        )
        val key = normalizedSpec.command
        require(!aliases.containsKey(key)) { "Command '$key' collides with existing alias" }
        handlers[key] = RegisteredCommand(
            spec = normalizedSpec,
            middleware = middleware,
            action = action
        )
        aliases.entries.removeIf { (_, target) -> target == key }
        normalizedSpec.aliases.forEach { alias ->
            registerAliasOrThrow(alias, key)
        }
        val completionProvider = completion ?: com.gigasoft.api.CommandCompletionContract { _, _, commandSpec, args ->
            commandSpec.defaultCompletions(args)
        }
        com.gigasoft.api.CommandCompletionCatalog.register(
            command = key,
            provider = completionProvider,
            providerAsync = completionAsync,
            spec = normalizedSpec
        )
        normalizedSpec.aliases.forEach { alias ->
            com.gigasoft.api.CommandCompletionCatalog.register(
                command = alias,
                provider = completionProvider,
                providerAsync = completionAsync,
                spec = normalizedSpec
            )
        }
    }

    override fun unregister(command: String): Boolean {
        val key = normalizeCommand(command)
        val removed = handlers.remove(key) != null
        if (removed) {
            aliases.entries.removeIf { (_, target) -> target == key }
        }
        return removed
    }

    override fun registerAlias(alias: String, command: String): Boolean {
        val aliasKey = normalizeCommand(alias)
        val commandKey = normalizeCommand(command)
        require(aliasKey != commandKey) { "Alias '$aliasKey' must not equal command id" }
        require(handlers.containsKey(commandKey)) { "Cannot alias unknown command '$commandKey'" }
        require(!handlers.containsKey(aliasKey)) { "Alias '$aliasKey' collides with command id" }
        val previous = aliases.putIfAbsent(aliasKey, commandKey)
        if (previous == null) return true
        require(previous == commandKey) { "Alias '$aliasKey' already points to '$previous'" }
        return false
    }

    override fun unregisterAlias(alias: String): Boolean {
        val aliasKey = normalizeCommand(alias)
        return aliases.remove(aliasKey) != null
    }

    override fun resolve(commandOrAlias: String): String? {
        val key = normalizeCommand(commandOrAlias)
        return when {
            handlers.containsKey(key) -> key
            else -> aliases[key]
        }
    }

    override fun registeredCommands(): Map<String, com.gigasoft.api.CommandSpec> {
        return handlers.mapValues { it.value.spec }
    }

    override fun commandTelemetry(commandOrAlias: String): com.gigasoft.api.CommandTelemetrySnapshot? {
        val key = resolve(commandOrAlias) ?: return null
        val metric = telemetry[key] ?: return null
        return metric.snapshot(command = key)
    }

    override fun commandTelemetry(): Map<String, com.gigasoft.api.CommandTelemetrySnapshot> {
        return telemetry.entries.associate { it.key to it.value.snapshot(it.key) }.toSortedMap()
    }

    fun aliases(): Map<String, String> = aliases.toMap()

    fun unregisterAll(): Int {
        val count = handlers.size
        handlers.clear()
        aliases.clear()
        return count
    }

    fun execute(ctx: PluginContext, sender: com.gigasoft.api.CommandSender, commandLine: String): String {
        val tokens = tokenizeCommand(commandLine)
        if (tokens.isEmpty()) return ""
        val requested = tokens[0].lowercase()
        val key = resolve(requested) ?: return "Unknown command: $requested"
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()
        val handler = handlers[key] ?: return "Unknown command: $requested"
        val route = com.gigasoft.api.resolveCommandRoute(handler.spec, args)
        val routedSpec = route.spec
        val routedArgs = if (route.consumedArgs <= 0) args else args.drop(route.consumedArgs)
        val started = System.nanoTime()
        val pre = GigaCommandPreExecuteEvent(
            pluginId = pluginId,
            command = routedSpec.command,
            sender = sender,
            args = routedArgs,
            rawCommandLine = commandLine
        )
        ctx.events.publish(pre)
        if (pre.cancelled) {
            val response = pre.overrideResponse ?: com.gigasoft.api.CommandResult.error(
                pre.cancelReason ?: "Command '${routedSpec.command}' cancelled",
                code = "E_CANCELLED"
            )
            val duration = System.nanoTime() - started
            telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(response, duration)
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = routedSpec.command,
                    sender = sender,
                    args = routedArgs,
                    rawCommandLine = commandLine,
                    response = response,
                    success = false,
                    durationNanos = duration,
                    error = response.error
                )
            )
            return response.render()
        }
        pre.overrideResponse?.let { response ->
            val duration = System.nanoTime() - started
            telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(response, duration)
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = routedSpec.command,
                    sender = sender,
                    args = routedArgs,
                    rawCommandLine = commandLine,
                    response = response,
                    success = true,
                    durationNanos = duration
                )
            )
            return response.render()
        }
        return try {
            if (routedArgs.isNotEmpty() && (routedArgs[0].equals("help", ignoreCase = true) || routedArgs[0] == "--help")) {
                val help = com.gigasoft.api.CommandResult.ok(routedSpec.helpText())
                val duration = System.nanoTime() - started
                telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(help, duration)
                ctx.events.publish(
                    GigaCommandPostExecuteEvent(
                        pluginId = pluginId,
                        command = routedSpec.command,
                        sender = sender,
                        args = routedArgs,
                        rawCommandLine = commandLine,
                        response = help,
                        success = true,
                        durationNanos = duration
                    )
                )
                return help.render()
            }

            val parsedResult = com.gigasoft.api.parseCommandArgs(routedSpec, routedArgs)
            parsedResult.error?.let { parseError ->
                val duration = System.nanoTime() - started
                telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(parseError, duration)
                ctx.events.publish(
                    GigaCommandPostExecuteEvent(
                        pluginId = pluginId,
                        command = routedSpec.command,
                        sender = sender,
                        args = routedArgs,
                        rawCommandLine = commandLine,
                        response = parseError,
                        success = false,
                        durationNanos = duration,
                        error = parseError.error
                    )
                )
                return parseError.render()
            }

            val requiredPermission = routedSpec.permission?.trim()?.takeIf { it.isNotEmpty() }
            if (requiredPermission != null && !ctx.hasPermission(requiredPermission)) {
                val denied = com.gigasoft.api.CommandResult.error(
                    "Missing permission '$requiredPermission' for command '${routedSpec.command}'",
                    code = "E_PERMISSION",
                    field = "permission"
                )
                val duration = System.nanoTime() - started
                telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(denied, duration)
                ctx.events.publish(
                    GigaCommandPostExecuteEvent(
                        pluginId = pluginId,
                        command = routedSpec.command,
                        sender = sender,
                        args = routedArgs,
                        rawCommandLine = commandLine,
                        response = denied,
                        success = false,
                        durationNanos = duration,
                        error = denied.error
                    )
                )
                return denied.render()
            }

            val now = System.currentTimeMillis()
            if (routedSpec.rateLimitPerMinute > 0) {
                val queue = handler.rateWindowBySender.computeIfAbsent(sender.id) { ArrayDeque() }
                synchronized(queue) {
                    while (queue.isNotEmpty() && now - queue.first() > 60_000L) {
                        queue.removeFirst()
                    }
                    if (queue.size >= routedSpec.rateLimitPerMinute) {
                        val limited = com.gigasoft.api.CommandResult.error(
                            "Rate limit exceeded for '${routedSpec.command}' (${routedSpec.rateLimitPerMinute}/min)",
                            code = "E_RATE_LIMIT",
                            field = "rateLimitPerMinute",
                            hint = "Reduce command frequency."
                        )
                        val duration = System.nanoTime() - started
                        telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(limited, duration)
                        ctx.events.publish(
                            GigaCommandPostExecuteEvent(
                                pluginId = pluginId,
                                command = routedSpec.command,
                                sender = sender,
                                args = routedArgs,
                                rawCommandLine = commandLine,
                                response = limited,
                                success = false,
                                durationNanos = duration,
                                error = limited.error
                            )
                        )
                        return limited.render()
                    }
                    queue.addLast(now)
                }
            }

            if (routedSpec.cooldownMillis > 0L) {
                val last = handler.cooldownBySender[sender.id]
                if (last != null && now - last < routedSpec.cooldownMillis) {
                    val waitMillis = routedSpec.cooldownMillis - (now - last)
                    val cooling = com.gigasoft.api.CommandResult.error(
                        "Command '${routedSpec.command}' is on cooldown (${waitMillis}ms remaining)",
                        code = "E_COOLDOWN",
                        field = "cooldownMillis",
                        hint = "Retry after cooldown expires."
                    )
                    val duration = System.nanoTime() - started
                    telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(cooling, duration)
                    ctx.events.publish(
                        GigaCommandPostExecuteEvent(
                            pluginId = pluginId,
                            command = routedSpec.command,
                            sender = sender,
                            args = routedArgs,
                            rawCommandLine = commandLine,
                            response = cooling,
                            success = false,
                            durationNanos = duration,
                            error = cooling.error
                        )
                    )
                    return cooling.render()
                }
            }

            val invocation = com.gigasoft.api.CommandInvocationContext(
                pluginContext = ctx,
                sender = sender,
                rawArgs = routedArgs,
                spec = routedSpec,
                parsedArgs = parsedResult.parsed
            )
            val orderedMiddleware = handler.middleware.sortedWith(
                compareBy<com.gigasoft.api.CommandMiddlewareBinding> { it.phase.ordinal }
                    .thenBy { it.order }
                    .thenBy { it.id }
            )
            var index = -1
            fun executeNext(): com.gigasoft.api.CommandResult {
                index++
                if (index < orderedMiddleware.size) {
                    return orderedMiddleware[index].middleware.invoke(invocation, ::executeNext)
                }
                return handler.action(invocation)
            }
            val response = executeNext()
            if (response.success && routedSpec.cooldownMillis > 0L) {
                handler.cooldownBySender[sender.id] = now
            }
            val duration = System.nanoTime() - started
            telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(response, duration)
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = routedSpec.command,
                    sender = sender,
                    args = routedArgs,
                    rawCommandLine = commandLine,
                    response = response,
                    success = response.success,
                    durationNanos = duration,
                    error = response.error
                )
            )
            response.render()
        } catch (t: Throwable) {
            val response = com.gigasoft.api.CommandResult.error(
                message = "Command '${routedSpec.command}' failed: ${t.message ?: t.javaClass.simpleName}",
                code = "E_COMMAND",
                hint = "Check plugin logs for stack trace."
            )
            val duration = System.nanoTime() - started
            telemetry.computeIfAbsent(key) { MutableCommandTelemetry() }.record(response, duration)
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = routedSpec.command,
                    sender = sender,
                    args = routedArgs,
                    rawCommandLine = commandLine,
                    response = response,
                    success = false,
                    durationNanos = duration,
                    error = response.error
                )
            )
            response.render()
        }
    }

    fun commands(): Map<String, String> = handlers.mapValues { it.value.spec.description }

    private fun normalizeCommand(command: String): String {
        val key = command.trim().lowercase()
        require(key.isNotEmpty()) { "Command name must not be blank" }
        return key
    }

    private fun registerAliasOrThrow(alias: String, command: String) {
        val created = registerAlias(alias, command)
        require(created || resolve(alias) == command) {
            "Failed to register alias '$alias' for command '$command'"
        }
    }

    private fun tokenizeCommand(input: String): List<String> {
        val out = ArrayList<String>(8)
        val len = input.length
        var i = 0
        while (i < len) {
            while (i < len && input[i].isWhitespace()) i++
            if (i >= len) break
            val start = i
            while (i < len && !input[i].isWhitespace()) i++
            out.add(input.substring(start, i))
        }
        return out
    }
}

class RuntimeEventBus(
    private val mode: EventDispatchMode = EventDispatchMode.EXACT,
    private val mainThreadCheck: () -> Boolean = { true },
    private val strictMainThreadGuards: Boolean = false,
    private val asyncExecutor: Executor = ForkJoinPool.commonPool()
) : EventBus {
    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<ListenerEntry>>()
    private val dispatchCache = ConcurrentHashMap<Class<*>, Array<ListenerEntry>>()
    private val trace = ConcurrentHashMap<Class<*>, MutableTrace>()
    @Volatile
    private var tracingEnabled: Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
        subscribe(eventType, EventSubscriptionOptions(), listener)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(
        eventType: Class<T>,
        options: EventSubscriptionOptions,
        listener: (T) -> Unit
    ) {
        listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
            .add(
                ListenerEntry(
                    rawListener = listener as (Any) -> Unit,
                    callback = { event -> listener(event as T) },
                    options = options,
                    sequence = sequenceCounter.incrementAndGet()
                )
            )
        if (mode != EventDispatchMode.EXACT) {
            dispatchCache.clear()
        } else {
            dispatchCache.remove(eventType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> unsubscribe(eventType: Class<T>, listener: (T) -> Unit): Boolean {
        val bucket = listeners[eventType] ?: return false
        val removed = bucket.removeIf { entry ->
            entry.rawListener === listener || entry.rawListener == listener
        }
        if (removed) {
            if (mode != EventDispatchMode.EXACT) {
                dispatchCache.clear()
            } else {
                dispatchCache.remove(eventType)
            }
        }
        return removed
    }

    override fun publish(event: Any) {
        val eventType = event::class.java
        val started = if (tracingEnabled) System.nanoTime() else 0L
        var listenerCalls = 0L
        var errors = 0L
        val callbacks = dispatchCache.computeIfAbsent(eventType) {
            when (mode) {
                EventDispatchMode.EXACT -> listeners[eventType]
                    ?.asSequence()
                    ?.sortedWith(
                        compareByDescending<ListenerEntry> { it.options.priority.weight() }
                            .thenBy { it.sequence }
                    )
                    ?.toList()
                    ?.toTypedArray()
                    ?: emptyArray()
                EventDispatchMode.POLYMORPHIC -> resolveHierarchical(eventType, exactFirst = false)
                EventDispatchMode.HYBRID -> resolveHierarchical(eventType, exactFirst = true)
            }
        }
        for (entry in callbacks) {
            try {
                if (entry.options.ignoreCancelled && isEventCancelled(event)) {
                    continue
                }
                if (entry.options.mainThreadOnly && !mainThreadCheck()) {
                    if (strictMainThreadGuards) {
                        throw IllegalStateException("Main-thread-only listener executed off main thread")
                    }
                    continue
                }
                entry.callback(event)
                listenerCalls++
            } catch (t: Throwable) {
                errors++
                throw t
            }
        }
        if (tracingEnabled) {
            val duration = System.nanoTime() - started
            val bucket = trace.computeIfAbsent(eventType) { MutableTrace() }
            bucket.events.incrementAndGet()
            bucket.listenerCalls.addAndGet(listenerCalls)
            bucket.errors.addAndGet(errors)
            bucket.totalNanos.addAndGet(duration)
            bucket.lastDurationNanos.set(duration)
            bucket.maxNanos.updateAndGet { current -> if (duration > current) duration else current }
            bucket.lastThread = Thread.currentThread().name
        }
    }

    override fun publishAsync(event: Any): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync(
            {
                publish(event)
                Unit
            },
            asyncExecutor
        )
    }

    override fun setTracingEnabled(enabled: Boolean): Boolean {
        val previous = tracingEnabled
        tracingEnabled = enabled
        return previous
    }

    override fun eventTraceSnapshot(): EventTraceSnapshot {
        val types = trace.entries
            .map { (eventType, stats) ->
                val events = stats.events.get()
                val totalNanos = stats.totalNanos.get()
                EventTypeTraceSnapshot(
                    eventType = eventType.name,
                    events = events,
                    listenerCalls = stats.listenerCalls.get(),
                    errors = stats.errors.get(),
                    averageNanos = if (events == 0L) 0L else totalNanos / events,
                    maxNanos = stats.maxNanos.get(),
                    lastDurationNanos = stats.lastDurationNanos.get(),
                    lastThread = stats.lastThread
                )
            }
            .sortedByDescending { it.averageNanos }
        return EventTraceSnapshot(
            enabled = tracingEnabled,
            totalEvents = types.sumOf { it.events },
            totalListenerCalls = types.sumOf { it.listenerCalls },
            totalErrors = types.sumOf { it.errors },
            eventTypes = types
        )
    }

    override fun resetEventTrace() {
        trace.clear()
    }

    private fun resolveHierarchical(eventType: Class<*>, exactFirst: Boolean): Array<ListenerEntry> {
        val resolved = ArrayList<ListenerEntry>()
        val ordered = listeners.entries
            .asSequence()
            .filter { (listenerType, _) -> listenerType.isAssignableFrom(eventType) }
            .sortedWith(
                compareBy<Map.Entry<Class<*>, CopyOnWriteArrayList<ListenerEntry>>> { entry ->
                    if (exactFirst && entry.key == eventType) 0 else 1
                }
                    .thenBy { entry -> inheritanceDistance(eventType, entry.key) }
                    .thenBy { entry -> entry.key.name }
            )
            .toList()
        ordered.forEach { bucket ->
            bucket.value
                .asSequence()
                .sortedWith(
                    compareByDescending<ListenerEntry> { it.options.priority.weight() }
                        .thenBy { it.sequence }
                )
                .forEach { resolved.add(it) }
        }
        return resolved.toTypedArray()
    }

    private fun isEventCancelled(event: Any): Boolean {
        // Cancellation state is detected from a mutable boolean field/property named "cancelled".
        return try {
            val field = event::class.java.declaredFields
                .firstOrNull { it.name == "cancelled" && (it.type == Boolean::class.java || it.type == java.lang.Boolean::class.java) }
                ?: return false
            field.isAccessible = true
            (field.get(event) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun inheritanceDistance(eventType: Class<*>, listenerType: Class<*>): Int {
        if (eventType == listenerType) return 0
        var distance = 1
        var current = eventType.superclass
        while (current != null) {
            if (current == listenerType) return distance
            distance++
            current = current.superclass
        }
        return if (listenerType.isInterface && listenerType.isAssignableFrom(eventType)) {
            distance + 64
        } else {
            Int.MAX_VALUE
        }
    }

    private data class ListenerEntry(
        val rawListener: (Any) -> Unit,
        val callback: (Any) -> Unit,
        val options: EventSubscriptionOptions,
        val sequence: Long
    )

    private data class MutableTrace(
        val events: AtomicLong = AtomicLong(0L),
        val listenerCalls: AtomicLong = AtomicLong(0L),
        val errors: AtomicLong = AtomicLong(0L),
        val totalNanos: AtomicLong = AtomicLong(0L),
        val maxNanos: AtomicLong = AtomicLong(0L),
        val lastDurationNanos: AtomicLong = AtomicLong(0L),
        @Volatile var lastThread: String? = null
    )

    private companion object {
        private val sequenceCounter = AtomicLong(0L)
    }
}

private fun EventPriority.weight(): Int {
    return when (this) {
        EventPriority.HIGHEST -> 5
        EventPriority.HIGH -> 4
        EventPriority.NORMAL -> 3
        EventPriority.LOW -> 2
        EventPriority.LOWEST -> 1
    }
}
