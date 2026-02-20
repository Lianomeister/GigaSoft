package com.gigasoft.api

class DslGigaPlugin(
    private val manifestFactory: () -> PluginManifest,
    private val configure: GigaPluginDsl.() -> Unit
) : GigaPlugin {
    private var dsl: GigaPluginDsl? = null

    override fun onEnable(ctx: PluginContext) {
        val pluginDsl = GigaPluginDsl(ctx).apply(configure)
        pluginDsl.install()
        dsl = pluginDsl
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.scheduler.clear()
    }

    override fun onReload(ctx: PluginContext) {
        onDisable(ctx)
        onEnable(ctx)
    }

    fun manifest(): PluginManifest = manifestFactory()
}

fun gigaPlugin(
    id: String,
    name: String = id,
    version: String = "0.1.0",
    apiVersion: String = "1",
    dependencies: List<String> = emptyList(),
    permissions: List<String> = emptyList(),
    configure: GigaPluginDsl.() -> Unit
): DslGigaPlugin {
    val dependencySpecs = dependencies.map(::parseDependencySpec)
    return DslGigaPlugin(
        manifestFactory = {
            PluginManifest(
                id = id,
                name = name,
                version = version,
                main = "dsl:$id",
                apiVersion = apiVersion,
                dependencies = dependencySpecs,
                permissions = permissions
            )
        },
        configure = configure
    )
}

private fun parseDependencySpec(raw: String): DependencySpec {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "Dependency string must not be empty" }
    val match = Regex("^([A-Za-z0-9_.-]+)\\s*(.*)$").find(trimmed)
        ?: error("Invalid dependency format: '$raw'")
    val id = match.groupValues[1]
    val tail = match.groupValues[2].trim()
    return if (tail.isBlank()) DependencySpec(id) else DependencySpec(id, tail)
}

class GigaPluginDsl(private val ctx: PluginContext) {
    private val itemDefs = mutableListOf<ItemDefinition>()
    private val blockDefs = mutableListOf<BlockDefinition>()
    private val recipeDefs = mutableListOf<RecipeDefinition>()
    private val machineDefs = mutableListOf<MachineDefinition>()
    private val systems = linkedMapOf<String, TickSystem>()
    private val commandDefs = mutableListOf<Triple<String, String, (PluginContext, String, List<String>) -> String>>()

    fun items(block: ItemDsl.() -> Unit) {
        itemDefs += ItemDsl().apply(block).items
    }

    fun blocks(block: BlockDsl.() -> Unit) {
        blockDefs += BlockDsl().apply(block).blocks
    }

    fun recipes(block: RecipeDsl.() -> Unit) {
        recipeDefs += RecipeDsl().apply(block).recipes
    }

    fun machines(block: MachineDsl.() -> Unit) {
        machineDefs += MachineDsl().apply(block).machines
    }

    fun systems(block: SystemDsl.() -> Unit) {
        systems.putAll(SystemDsl().apply(block).systems)
    }

    fun commands(block: CommandDsl.() -> Unit) {
        commandDefs += CommandDsl().apply(block).commands
    }

    fun install() {
        itemDefs.forEach(ctx.registry::registerItem)
        blockDefs.forEach(ctx.registry::registerBlock)
        recipeDefs.forEach(ctx.registry::registerRecipe)
        machineDefs.forEach(ctx.registry::registerMachine)
        systems.forEach(ctx.registry::registerSystem)
        commandDefs.forEach { (cmd, description, action) -> ctx.commands.register(cmd, description, action) }
    }
}

class ItemDsl {
    internal val items = mutableListOf<ItemDefinition>()
    fun item(id: String, displayName: String) {
        items += ItemDefinition(id, displayName)
    }
}

class BlockDsl {
    internal val blocks = mutableListOf<BlockDefinition>()
    fun block(id: String, displayName: String) {
        blocks += BlockDefinition(id, displayName)
    }
}

class RecipeDsl {
    internal val recipes = mutableListOf<RecipeDefinition>()
    fun recipe(id: String, input: String, output: String, durationTicks: Int) {
        recipes += RecipeDefinition(id, input, output, durationTicks)
    }
}

class MachineDsl {
    internal val machines = mutableListOf<MachineDefinition>()
    fun machine(id: String, displayName: String, behavior: MachineBehavior) {
        machines += MachineDefinition(id, displayName, behavior)
    }
}

class SystemDsl {
    internal val systems = linkedMapOf<String, TickSystem>()
    fun system(id: String, block: (PluginContext) -> Unit) {
        systems[id] = TickSystem { ctx -> block(ctx) }
    }
}

class CommandDsl {
    internal val commands = mutableListOf<Triple<String, String, (PluginContext, String, List<String>) -> String>>()
    fun command(
        name: String,
        description: String = "",
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    ) {
        commands += Triple(name, description, action)
    }
}
