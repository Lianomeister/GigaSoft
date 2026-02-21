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
