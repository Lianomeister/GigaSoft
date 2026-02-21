package com.gigasoft.api

inline fun <reified T : Any> EventBus.subscribe(noinline listener: (T) -> Unit) {
    subscribe(T::class.java, listener)
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

fun AdapterInvocation.payloadBool(key: String): Boolean? {
    val value = payloadTrimmed(key)?.lowercase() ?: return null
    return when (value) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }
}
