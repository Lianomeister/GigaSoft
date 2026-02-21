package com.clockwork.api

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
    version: String = "1.5.0",
    apiVersion: String = "1",
    dependencySpecs: List<DependencySpec> = emptyList(),
    permissions: List<String> = emptyList(),
    configure: GigaPluginDsl.() -> Unit
): DslGigaPlugin {
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

fun dependency(id: String, versionRange: String? = null): DependencySpec {
    val trimmedId = id.trim()
    require(trimmedId.isNotEmpty()) { "Dependency id must not be empty" }
    val range = versionRange?.trim()?.takeIf { it.isNotEmpty() }
    return DependencySpec(id = trimmedId, versionRange = range)
}

class GigaPluginDsl(private val ctx: PluginContext) {
    private val itemDefs = mutableListOf<ItemDefinition>()
    private val blockDefs = mutableListOf<BlockDefinition>()
    private val recipeDefs = mutableListOf<RecipeDefinition>()
    private val machineDefs = mutableListOf<MachineDefinition>()
    private val textureDefs = mutableListOf<TextureDefinition>()
    private val modelDefs = mutableListOf<ModelDefinition>()
    private val animationDefs = mutableListOf<AnimationDefinition>()
    private val soundDefs = mutableListOf<SoundDefinition>()
    private val systems = linkedMapOf<String, TickSystem>()
    private val commandDefs = mutableListOf<CommandDsl.Definition>()
    private val eventBindings = mutableListOf<(EventBus) -> Unit>()
    private val adapterDefs = mutableListOf<ModAdapter>()

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

    fun textures(block: TextureDsl.() -> Unit) {
        textureDefs += TextureDsl().apply(block).textures
    }

    fun models(block: ModelDsl.() -> Unit) {
        modelDefs += ModelDsl().apply(block).models
    }

    fun animations(block: AnimationDsl.() -> Unit) {
        animationDefs += AnimationDsl().apply(block).animations
    }

    fun sounds(block: SoundDsl.() -> Unit) {
        soundDefs += SoundDsl().apply(block).sounds
    }

    fun systems(block: SystemDsl.() -> Unit) {
        systems.putAll(SystemDsl().apply(block).systems)
    }

    fun commands(block: CommandDsl.() -> Unit) {
        commandDefs += CommandDsl().apply(block).commands
    }

    fun events(block: EventDsl.() -> Unit) {
        eventBindings += EventDsl().apply(block).bindings
    }

    fun adapters(block: AdapterDsl.() -> Unit) {
        adapterDefs += AdapterDsl().apply(block).adapters
    }

    fun hostMutations(
        id: String = "startup-${ctx.manifest.id}",
        rollbackReason: String = "rollback",
        onRollback: ((HostMutationBatchResult) -> Unit)? = null,
        block: HostMutationBatchDsl.() -> Unit
    ): HostMutationBatchResult {
        return ctx.mutateHost(
            id = id,
            rollbackReason = rollbackReason,
            onRollback = onRollback,
            block = block
        )
    }

    fun install() {
        itemDefs.forEach(ctx.registry::registerItem)
        blockDefs.forEach(ctx.registry::registerBlock)
        recipeDefs.forEach(ctx.registry::registerRecipe)
        machineDefs.forEach(ctx.registry::registerMachine)
        textureDefs.forEach {
            ctx.registry.registerTexture(it)
            ctx.events.publish(GigaTextureRegisteredEvent(it))
        }
        modelDefs.forEach {
            ctx.registry.registerModel(it)
            ctx.events.publish(GigaModelRegisteredEvent(it))
        }
        animationDefs.forEach {
            ctx.registry.registerAnimation(it)
            ctx.events.publish(GigaAnimationRegisteredEvent(it))
        }
        soundDefs.forEach {
            ctx.registry.registerSound(it)
            ctx.events.publish(GigaSoundRegisteredEvent(it))
        }
        val validation = ctx.registry.validateAssets()
        val bundle = ctx.registry.buildResourcePackBundle()
        ctx.events.publish(GigaResourcePackBundleEvent(bundle, validation))
        systems.forEach(ctx.registry::registerSystem)
        eventBindings.forEach { it(ctx.events) }
        commandDefs.forEach { command ->
            ctx.commands.registerSpec(
                spec = command.spec,
                middleware = command.middleware,
                completion = command.completion,
                completionAsync = command.completionAsync,
                policy = command.policy,
                action = command.action
            )
        }
        adapterDefs.forEach(ctx.adapters::register)
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

class TextureDsl {
    internal val textures = mutableListOf<TextureDefinition>()

    fun texture(
        id: String,
        path: String,
        category: String = "item",
        animated: Boolean = false
    ) {
        textures += TextureDefinition(
            id = id,
            path = path,
            category = category,
            animated = animated
        )
    }
}

class ModelDsl {
    internal val models = mutableListOf<ModelDefinition>()

    fun model(
        id: String,
        geometryPath: String,
        format: String = "json",
        textures: Map<String, String> = emptyMap(),
        metadata: Map<String, String> = emptyMap(),
        material: String = "opaque",
        doubleSided: Boolean = false,
        scale: Double = 1.0,
        collision: Boolean = true,
        bounds: ModelBounds? = null,
        lods: List<ModelLod> = emptyList(),
        animations: Map<String, String> = emptyMap()
    ) {
        models += ModelDefinition(
            id = id,
            format = format,
            geometryPath = geometryPath,
            textures = textures,
            metadata = metadata,
            material = material,
            doubleSided = doubleSided,
            scale = scale,
            collision = collision,
            bounds = bounds,
            lods = lods,
            animations = animations
        )
    }
}

class AnimationDsl {
    internal val animations = mutableListOf<AnimationDefinition>()

    fun animation(
        id: String,
        path: String,
        targetModelId: String? = null,
        loop: Boolean = false
    ) {
        animations += AnimationDefinition(
            id = id,
            path = path,
            targetModelId = targetModelId,
            loop = loop
        )
    }
}

class SoundDsl {
    internal val sounds = mutableListOf<SoundDefinition>()

    fun sound(
        id: String,
        path: String,
        category: String = "master",
        stream: Boolean = false,
        volume: Double = 1.0,
        pitch: Double = 1.0
    ) {
        sounds += SoundDefinition(
            id = id,
            path = path,
            category = category,
            stream = stream,
            volume = volume,
            pitch = pitch
        )
    }
}

class SystemDsl {
    internal val systems = linkedMapOf<String, TickSystem>()
    fun system(id: String, block: (PluginContext) -> Unit) {
        systems[id] = TickSystem { ctx -> block(ctx) }
    }
}

class CommandDsl {
    internal data class Definition(
        val spec: CommandSpec,
        val middleware: List<CommandMiddlewareBinding>,
        val completion: CommandCompletionContract?,
        val completionAsync: CommandCompletionAsyncContract?,
        val policy: CommandPolicyProfile?,
        val action: (CommandInvocationContext) -> CommandResult
    )

    internal val commands = mutableListOf<Definition>()

    fun command(
        spec: CommandSpec,
        middleware: List<CommandMiddlewareBinding> = emptyList(),
        completion: CommandCompletionContract? = null,
        completionAsync: CommandCompletionAsyncContract? = null,
        policy: CommandPolicyProfile? = null,
        action: (CommandInvocationContext) -> CommandResult
    ) {
        commands += Definition(
            spec = spec,
            middleware = middleware,
            completion = completion,
            completionAsync = completionAsync,
            policy = policy,
            action = action
        )
    }

    fun command(
        spec: CommandSpec,
        middleware: List<CommandMiddlewareBinding> = emptyList(),
        completion: CommandCompletionContract? = null,
        completionAsync: CommandCompletionAsyncContract? = null,
        policy: CommandPolicyProfile? = null,
        action: (sender: CommandSender, args: CommandParsedArgs) -> CommandResult
    ) {
        command(
            spec = spec,
            middleware = middleware,
            completion = completion,
            completionAsync = completionAsync,
            policy = policy
        ) { invocation ->
            action(invocation.sender, invocation.parsedArgs)
        }
    }

    fun spec(
        command: String,
        description: String = "",
        aliases: List<String> = emptyList(),
        permission: String? = null,
        argsSchema: List<CommandArgSpec> = emptyList(),
        cooldownMillis: Long = 0L,
        rateLimitPerMinute: Int = 0,
        usage: String = "",
        help: String = "",
        middleware: List<CommandMiddlewareBinding> = emptyList(),
        completion: CommandCompletionContract? = null,
        completionAsync: CommandCompletionAsyncContract? = null,
        policy: CommandPolicyProfile? = null,
        action: (CommandInvocationContext) -> CommandResult
    ) {
        command(
            spec = CommandSpec(
                command = command,
                description = description,
                aliases = aliases,
                permission = permission,
                argsSchema = argsSchema,
                cooldownMillis = cooldownMillis,
                rateLimitPerMinute = rateLimitPerMinute,
                usage = usage,
                help = help
            ),
            middleware = middleware,
            completion = completion,
            completionAsync = completionAsync,
            policy = policy,
            action = action
        )
    }

    fun command(
        id: String,
        description: String = "",
        aliases: List<String> = emptyList(),
        permission: String? = null,
        argsSchema: List<CommandArgSpec> = emptyList(),
        cooldownMillis: Long = 0L,
        rateLimitPerMinute: Int = 0,
        usage: String = "",
        help: String = "",
        middleware: List<CommandMiddlewareBinding> = emptyList(),
        completion: CommandCompletionContract? = null,
        completionAsync: CommandCompletionAsyncContract? = null,
        policy: CommandPolicyProfile? = null,
        action: (sender: CommandSender, args: CommandParsedArgs) -> CommandResult
    ) {
        command(
            spec = CommandSpec(
                command = id,
                description = description,
                aliases = aliases,
                permission = permission,
                argsSchema = argsSchema,
                cooldownMillis = cooldownMillis,
                rateLimitPerMinute = rateLimitPerMinute,
                usage = usage,
                help = help
            ),
            middleware = middleware,
            completion = completion,
            completionAsync = completionAsync,
            policy = policy,
            action = action
        )
    }
}

class AdapterDsl {
    internal val adapters = mutableListOf<ModAdapter>()

    fun adapter(definition: ModAdapter) {
        adapters += definition
    }

    fun adapter(
        id: String,
        name: String,
        version: String = "1.5.0",
        capabilities: Set<String> = emptySet(),
        handler: (AdapterInvocation) -> AdapterResponse
    ) {
        adapters += object : ModAdapter {
            override val id: String = id
            override val name: String = name
            override val version: String = version
            override val capabilities: Set<String> = capabilities

            override fun invoke(invocation: AdapterInvocation): AdapterResponse = handler(invocation)
        }
    }
}

class EventDsl {
    internal val bindings = mutableListOf<(EventBus) -> Unit>()

    fun <T : Any> on(
        eventType: Class<T>,
        listener: (T) -> Unit
    ) {
        bindings += { bus -> bus.subscribe(eventType, listener) }
    }

    inline fun <reified T : Any> on(
        noinline listener: (T) -> Unit
    ) {
        on(T::class.java, listener)
    }

    fun <T : Any> on(
        eventType: Class<T>,
        options: EventSubscriptionOptions,
        listener: (T) -> Unit
    ) {
        bindings += { bus -> bus.subscribe(eventType, options, listener) }
    }

    inline fun <reified T : Any> on(
        options: EventSubscriptionOptions,
        noinline listener: (T) -> Unit
    ) {
        on(T::class.java, options, listener)
    }

    fun <T : Any> once(
        eventType: Class<T>,
        listener: (T) -> Unit
    ) {
        bindings += { bus ->
            lateinit var wrapper: (T) -> Unit
            wrapper = { event ->
                listener(event)
                bus.unsubscribe(eventType, wrapper)
            }
            bus.subscribe(eventType, wrapper)
        }
    }

    inline fun <reified T : Any> once(
        noinline listener: (T) -> Unit
    ) {
        once(T::class.java, listener)
    }
}




