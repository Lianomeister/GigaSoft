package com.gigasoft.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class CommandArgType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    ENUM
}

enum class CommandSenderType {
    PLAYER,
    CONSOLE,
    SYSTEM
}

data class CommandSender(
    val id: String,
    val type: CommandSenderType = CommandSenderType.SYSTEM
) {
    init {
        require(id.isNotBlank()) { "Command sender id must not be blank" }
    }

    companion object {
        fun player(name: String): CommandSender = CommandSender(name, CommandSenderType.PLAYER)
        fun console(id: String = "console"): CommandSender = CommandSender(id, CommandSenderType.CONSOLE)
        fun system(id: String): CommandSender = CommandSender(id, CommandSenderType.SYSTEM)
    }
}

data class CommandError(
    val code: String,
    val field: String? = null,
    val hint: String? = null
) {
    init {
        require(code.isNotBlank()) { "Command error code must not be blank" }
    }
}

data class CommandPolicyProfile(
    val permissionPrefix: String = "",
    val defaultCooldownMillis: Long = 0L,
    val defaultRateLimitPerMinute: Int = 0
) {
    init {
        require(defaultCooldownMillis >= 0L) { "defaultCooldownMillis must be >= 0" }
        require(defaultRateLimitPerMinute >= 0) { "defaultRateLimitPerMinute must be >= 0" }
    }
}

object CommandPolicyProfiles {
    private val byPluginId = ConcurrentHashMap<String, CommandPolicyProfile>()

    fun set(pluginId: String, profile: CommandPolicyProfile) {
        val key = pluginId.trim().lowercase()
        if (key.isEmpty()) return
        byPluginId[key] = profile
    }

    fun forPlugin(pluginId: String): CommandPolicyProfile? {
        val key = pluginId.trim().lowercase()
        if (key.isEmpty()) return null
        return byPluginId[key]
    }

    fun clear(pluginId: String): Boolean {
        val key = pluginId.trim().lowercase()
        if (key.isEmpty()) return false
        return byPluginId.remove(key) != null
    }
}

data class CommandArgSpec(
    val name: String,
    val type: CommandArgType = CommandArgType.STRING,
    val required: Boolean = true,
    val description: String = "",
    val enumValues: List<String> = emptyList()
) {
    init {
        require(name.isNotBlank()) { "Argument name must not be blank" }
        require(type != CommandArgType.ENUM || enumValues.isNotEmpty()) {
            "enumValues must not be empty for ENUM argument '$name'"
        }
    }
}

data class CommandSpec(
    val command: String,
    val description: String = "",
    val aliases: List<String> = emptyList(),
    val permission: String? = null,
    val argsSchema: List<CommandArgSpec> = emptyList(),
    val cooldownMillis: Long = 0L,
    val rateLimitPerMinute: Int = 0,
    val usage: String = "",
    val help: String = "",
    val subcommands: List<CommandSpec> = emptyList()
) {
    init {
        require(command.isNotBlank()) { "Command must not be blank" }
        require(cooldownMillis >= 0L) { "cooldownMillis must be >= 0" }
        require(rateLimitPerMinute >= 0) { "rateLimitPerMinute must be >= 0" }
    }
}

data class CommandCompletion(
    val value: String,
    val description: String = ""
)

fun interface CommandCompletionContract {
    fun suggest(ctx: PluginContext, sender: CommandSender, spec: CommandSpec, args: List<String>): List<CommandCompletion>
}

fun interface CommandCompletionAsyncContract {
    fun suggestAsync(
        ctx: PluginContext,
        sender: CommandSender,
        spec: CommandSpec,
        args: List<String>
    ): CompletableFuture<List<CommandCompletion>>
}

object CommandCompletionCatalog {
    private data class ProviderEntry(
        val provider: CommandCompletionContract?,
        val providerAsync: CommandCompletionAsyncContract?,
        val spec: CommandSpec
    )

    private val providers = ConcurrentHashMap<String, ProviderEntry>()

    fun register(
        command: String,
        provider: CommandCompletionContract?,
        providerAsync: CommandCompletionAsyncContract?,
        spec: CommandSpec
    ) {
        val key = command.trim().lowercase()
        if (key.isNotEmpty()) {
            providers[key] = ProviderEntry(
                provider = provider,
                providerAsync = providerAsync,
                spec = spec
            )
        }
    }

    fun unregister(command: String): Boolean {
        val key = command.trim().lowercase()
        if (key.isEmpty()) return false
        return providers.remove(key) != null
    }

    fun suggest(
        commandOrAlias: String,
        ctx: PluginContext,
        sender: CommandSender,
        args: List<String>
    ): List<CommandCompletion> {
        val key = commandOrAlias.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        val entry = providers[key] ?: return emptyList()
        entry.provider?.let { provider ->
            return provider.suggest(
                ctx = ctx,
                sender = sender,
                spec = entry.spec,
                args = args
            )
        }
        val async = entry.providerAsync ?: return emptyList()
        return runCatching { async.suggestAsync(ctx, sender, entry.spec, args).get() }.getOrDefault(emptyList())
    }

    fun suggestAsync(
        commandOrAlias: String,
        ctx: PluginContext,
        sender: CommandSender,
        args: List<String>
    ): CompletableFuture<List<CommandCompletion>> {
        val key = commandOrAlias.trim().lowercase()
        if (key.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        val entry = providers[key] ?: return CompletableFuture.completedFuture(emptyList())
        entry.providerAsync?.let { async ->
            return async.suggestAsync(ctx, sender, entry.spec, args)
        }
        val sync = entry.provider ?: return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.completedFuture(sync.suggest(ctx, sender, entry.spec, args))
    }
}

enum class CommandMiddlewarePhase {
    AUTH,
    VALIDATION,
    AUDIT
}

fun interface CommandMiddleware {
    fun invoke(context: CommandInvocationContext, next: () -> CommandResult): CommandResult
}

data class CommandMiddlewareBinding(
    val id: String,
    val phase: CommandMiddlewarePhase,
    val order: Int = 0,
    val middleware: CommandMiddleware
) {
    init {
        require(id.isNotBlank()) { "Middleware id must not be blank" }
    }
}

data class CommandInvocationContext(
    val pluginContext: PluginContext,
    val sender: CommandSender,
    val rawArgs: List<String>,
    val spec: CommandSpec,
    val parsedArgs: CommandParsedArgs
)

class CommandParsedArgs internal constructor(
    private val values: Map<String, String>,
    private val schema: List<CommandArgSpec>
) {
    fun raw(name: String): String? = values[name]
    fun string(name: String): String? = values[name]
    fun int(name: String): Int? = values[name]?.toIntOrNull()
    fun long(name: String): Long? = values[name]?.toLongOrNull()
    fun double(name: String): Double? = values[name]?.toDoubleOrNull()
    fun boolean(name: String): Boolean? = parseBoolean(values[name])

    fun enum(name: String): String? {
        val arg = schema.firstOrNull { it.name == name } ?: return values[name]
        val raw = values[name] ?: return null
        if (arg.type != CommandArgType.ENUM) return raw
        return arg.enumValues.firstOrNull { it.equals(raw, ignoreCase = true) }
    }

    fun requiredString(name: String): String {
        return string(name) ?: throw IllegalArgumentException("Missing required argument '$name'")
    }
}

data class ResolvedCommandRoute(
    val spec: CommandSpec,
    val consumedArgs: Int
)

data class CommandParseResult(
    val parsed: CommandParsedArgs,
    val error: CommandResult? = null
)

fun resolveCommandRoute(root: CommandSpec, args: List<String>): ResolvedCommandRoute {
    if (args.isEmpty() || root.subcommands.isEmpty()) {
        return ResolvedCommandRoute(root, 0)
    }
    val next = args.first().trim().lowercase()
    if (next.isEmpty()) return ResolvedCommandRoute(root, 0)
    val child = root.subcommands.firstOrNull { sub ->
        sub.command.equals(next, ignoreCase = true) ||
            sub.aliases.any { it.equals(next, ignoreCase = true) }
    } ?: return ResolvedCommandRoute(root, 0)
    val nested = resolveCommandRoute(child, args.drop(1))
    return nested.copy(consumedArgs = nested.consumedArgs + 1)
}

fun parseCommandArgs(spec: CommandSpec, args: List<String>): CommandParseResult {
    val values = linkedMapOf<String, String>()
    val schema = spec.argsSchema

    if (args.size > schema.size && schema.isNotEmpty()) {
        return CommandParseResult(
            parsed = CommandParsedArgs(values, schema),
            error = CommandResult.error(
                "Too many arguments. Usage: ${spec.usageLine()}",
                code = "E_ARGS",
                field = "args",
                hint = "Use --help for command usage."
            )
        )
    }

    schema.forEachIndexed { index, argSpec ->
        val raw = args.getOrNull(index)
        if (raw == null) {
            if (argSpec.required) {
                return CommandParseResult(
                    parsed = CommandParsedArgs(values, schema),
                    error = CommandResult.error(
                        "Missing argument '${argSpec.name}'. Usage: ${spec.usageLine()}",
                        code = "E_ARGS",
                        field = argSpec.name,
                        hint = "Provide required argument '${argSpec.name}'."
                    )
                )
            }
            return@forEachIndexed
        }
        val valid = when (argSpec.type) {
            CommandArgType.STRING -> true
            CommandArgType.INT -> raw.toIntOrNull() != null
            CommandArgType.LONG -> raw.toLongOrNull() != null
            CommandArgType.DOUBLE -> raw.toDoubleOrNull() != null
            CommandArgType.BOOLEAN -> parseBoolean(raw) != null
            CommandArgType.ENUM -> argSpec.enumValues.any { it.equals(raw, ignoreCase = true) }
        }
        if (!valid) {
            val extra = if (argSpec.type == CommandArgType.ENUM) {
                " (expected one of: ${argSpec.enumValues.joinToString(", ")})"
            } else {
                ""
            }
            return CommandParseResult(
                parsed = CommandParsedArgs(values, schema),
                error = CommandResult.error(
                    "Invalid value for '${argSpec.name}'$extra. Usage: ${spec.usageLine()}",
                    code = "E_ARGS",
                    field = argSpec.name,
                    hint = "Check argument type '${argSpec.type.name.lowercase()}'."
                )
            )
        }
        values[argSpec.name] = raw
    }

    if (schema.isEmpty()) {
        args.forEachIndexed { index, raw -> values["arg$index"] = raw }
    }

    return CommandParseResult(CommandParsedArgs(values, schema))
}

internal fun parseBoolean(raw: String?): Boolean? {
    return when (raw?.trim()?.lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }
}

fun CommandSpec.usageLine(): String {
    val configured = usage.trim()
    if (configured.isNotEmpty()) return configured
    if (subcommands.isNotEmpty()) {
        val subText = subcommands.joinToString("|") { it.command }
        return if (argsSchema.isEmpty()) "$command <$subText>" else "$command <$subText> ${argsPlaceholderText()}"
    }
    if (argsSchema.isEmpty()) return command
    return "$command ${argsPlaceholderText()}"
}

private fun CommandSpec.argsPlaceholderText(): String {
    return argsSchema.joinToString(" ") { spec ->
        if (spec.required) "<${spec.name}>" else "[${spec.name}]"
    }
}

fun CommandSpec.helpText(): String {
    val summary = buildString {
        append(description.ifBlank { "No description available." })
        append("\nUsage: ")
        append(usageLine())
        if (aliases.isNotEmpty()) {
            append("\nAliases: ")
            append(aliases.joinToString(", "))
        }
        if (subcommands.isNotEmpty()) {
            append("\nSubcommands: ")
            append(subcommands.joinToString(", ") { it.command })
        }
        if (permission?.isNotBlank() == true) {
            append("\nPermission: ")
            append(permission)
        }
        if (help.isNotBlank()) {
            append("\n")
            append(help.trim())
        }
    }
    return summary
}

fun CommandSpec.defaultCompletions(args: List<String>): List<CommandCompletion> {
    if (subcommands.isNotEmpty() && args.size <= 1) {
        val prefix = args.firstOrNull()?.trim().orEmpty()
        return subcommands
            .map { CommandCompletion(it.command, it.description) }
            .filter { prefix.isBlank() || it.value.startsWith(prefix, ignoreCase = true) }
    }
    if (argsSchema.isEmpty()) return emptyList()
    val index = if (args.isEmpty()) 0 else args.size - 1
    val argSpec = argsSchema.getOrNull(index) ?: return emptyList()
    return when (argSpec.type) {
        CommandArgType.BOOLEAN -> listOf("true", "false").map { CommandCompletion(it) }
        CommandArgType.ENUM -> argSpec.enumValues.map { value -> CommandCompletion(value) }
        else -> emptyList()
    }
}

fun authMiddleware(
    id: String = "auth",
    order: Int = 0,
    check: (CommandInvocationContext) -> CommandResult?
): CommandMiddlewareBinding {
    return CommandMiddlewareBinding(
        id = id,
        phase = CommandMiddlewarePhase.AUTH,
        order = order,
        middleware = CommandMiddleware { ctx, next -> check(ctx) ?: next() }
    )
}

fun validationMiddleware(
    id: String = "validation",
    order: Int = 0,
    check: (CommandInvocationContext) -> CommandResult?
): CommandMiddlewareBinding {
    return CommandMiddlewareBinding(
        id = id,
        phase = CommandMiddlewarePhase.VALIDATION,
        order = order,
        middleware = CommandMiddleware { ctx, next -> check(ctx) ?: next() }
    )
}

fun auditMiddleware(
    id: String = "audit",
    order: Int = 0,
    handler: (CommandInvocationContext, CommandResult) -> Unit
): CommandMiddlewareBinding {
    return CommandMiddlewareBinding(
        id = id,
        phase = CommandMiddlewarePhase.AUDIT,
        order = order,
        middleware = CommandMiddleware { ctx, next ->
            val result = next()
            handler(ctx, result)
            result
        }
    )
}

fun telemetryMiddleware(
    id: String = "telemetry",
    order: Int = Int.MAX_VALUE,
    handler: (CommandInvocationContext, Long, CommandResult) -> Unit
): CommandMiddlewareBinding {
    return auditMiddleware(id = id, order = order) { ctx, result ->
        val nanos = System.nanoTime()
        handler(ctx, nanos, result)
    }
}

data class CommandErrorCount(
    val code: String,
    val count: Long
)

data class CommandTelemetrySnapshot(
    val command: String,
    val totalRuns: Long,
    val failures: Long,
    val failRate: Double,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val topErrors: List<CommandErrorCount>
)
