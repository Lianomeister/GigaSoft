package com.gigasoft.api

inline fun <reified T : Any> EventBus.subscribe(noinline listener: (T) -> Unit) {
    subscribe(T::class.java, listener)
}

inline fun <reified T : Any> EventBus.subscribeOnce(noinline listener: (T) -> Unit) {
    lateinit var wrapper: (T) -> Unit
    wrapper = { event ->
        listener(event)
        unsubscribe(T::class.java, wrapper)
    }
    subscribe(T::class.java, wrapper)
}

inline fun <reified T : Any> StorageProvider.store(key: String, version: Int = 1): PersistentStore<T> {
    return store(key, T::class.java, version)
}

fun CommandRegistry.register(
    command: String,
    description: String = "",
    action: (sender: String, args: List<String>) -> String
) {
    register(command, description) { _, sender, args -> action(sender, args) }
}

fun CommandRegistry.registerOrReplace(
    command: String,
    description: String = "",
    action: (sender: String, args: List<String>) -> String
) {
    registerOrReplace(command, description) { _, sender, args -> action(sender, args) }
}

fun CommandRegistry.registerWithAliases(
    command: String,
    description: String = "",
    aliases: List<String> = emptyList(),
    action: (ctx: PluginContext, sender: String, args: List<String>) -> String
) {
    register(command, description, action)
    aliases.forEach { registerAliasOrThrow(it, command) }
}

fun CommandRegistry.registerWithAliases(
    command: String,
    description: String = "",
    aliases: List<String> = emptyList(),
    action: (sender: String, args: List<String>) -> String
) {
    registerWithAliases(command, description, aliases) { _, sender, args -> action(sender, args) }
}

fun CommandRegistry.registerOrReplaceWithAliases(
    command: String,
    description: String = "",
    aliases: List<String> = emptyList(),
    action: (ctx: PluginContext, sender: String, args: List<String>) -> String
) {
    registerOrReplace(command, description, action)
    aliases.forEach { registerAliasOrThrow(it, command) }
}

fun CommandRegistry.registerOrReplaceWithAliases(
    command: String,
    description: String = "",
    aliases: List<String> = emptyList(),
    action: (sender: String, args: List<String>) -> String
) {
    registerOrReplaceWithAliases(command, description, aliases) { _, sender, args -> action(sender, args) }
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

fun CommandRegistry.registerValidated(
    command: String,
    description: String = "",
    validator: (sender: String, args: List<String>) -> CommandResult?,
    action: (ctx: PluginContext, sender: String, args: List<String>) -> CommandResult
) {
    register(command, description) { ctx, sender, args ->
        val validation = validator(sender, args)
        if (validation != null) return@register validation.render()
        action(ctx, sender, args).render()
    }
}

fun CommandRegistry.registerOrReplaceValidated(
    command: String,
    description: String = "",
    validator: (sender: String, args: List<String>) -> CommandResult?,
    action: (ctx: PluginContext, sender: String, args: List<String>) -> CommandResult
) {
    registerOrReplace(command, description) { ctx, sender, args ->
        val validation = validator(sender, args)
        if (validation != null) return@registerOrReplace validation.render()
        action(ctx, sender, args).render()
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

fun CommandResult.render(): String {
    val normalizedCode = code?.trim()?.takeIf { it.isNotEmpty() }
    return if (normalizedCode == null) {
        message
    } else {
        "[$normalizedCode] $message"
    }
}

fun CommandRegistry.registerResult(
    command: String,
    description: String = "",
    action: (ctx: PluginContext, sender: String, args: List<String>) -> CommandResult
) {
    register(command, description) { ctx, sender, args -> action(ctx, sender, args).render() }
}

fun CommandRegistry.registerOrReplaceResult(
    command: String,
    description: String = "",
    action: (ctx: PluginContext, sender: String, args: List<String>) -> CommandResult
) {
    registerOrReplace(command, description) { ctx, sender, args -> action(ctx, sender, args).render() }
}

fun CommandRegistry.registerResult(
    command: String,
    description: String = "",
    action: (sender: String, args: List<String>) -> CommandResult
) {
    registerResult(command, description) { _, sender, args -> action(sender, args) }
}

fun CommandRegistry.registerOrReplaceResult(
    command: String,
    description: String = "",
    action: (sender: String, args: List<String>) -> CommandResult
) {
    registerOrReplaceResult(command, description) { _, sender, args -> action(sender, args) }
}

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
