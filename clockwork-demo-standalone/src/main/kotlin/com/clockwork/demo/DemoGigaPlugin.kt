package com.clockwork.demo

import com.clockwork.api.*

data class DemoCounter(var produced: Int = 0)

class CrusherBehavior(
    private val machineState: MachineState
) : MachineBehavior {
    override fun onTick(state: MachineState, ctx: PluginContext) {
        state.progressTicks += 1
        if (state.progressTicks % 40 == 0) {
            val store = ctx.storage.store("demo_counter", DemoCounter::class.java, version = 1)
            val counter = store.load() ?: DemoCounter()
            counter.produced += 1
            store.save(counter)
            ctx.logger.info("Crusher produced item #${counter.produced}")
        }
    }
}

class DemoGigaPlugin : GigaPlugin {
    companion object {
        private const val DEMO_CHANNEL = "clockwork-demo:events"
    }

    @Volatile
    private var joinEvents: Int = 0
    @Volatile
    private var networkEvents: Int = 0
    private val crusherMachineState = MachineState(machineId = "crusher_machine")

    private val delegate = gigaPlugin(
        id = "clockwork-demo",
        name = "Clockwork Demo",
        version = "1.5.0-rc.2"
    ) {
        items {
            item("raw_ore_chunk", "Raw Ore Chunk")
            item("crushed_ore", "Crushed Ore")
        }

        blocks {
            block("hand_crusher", "Hand Crusher")
        }

        recipes {
            recipe(
                id = "crush_raw_ore",
                input = "raw_ore_chunk",
                output = "crushed_ore",
                durationTicks = 40
            )
        }

        machines {
            machine("crusher_machine", "Crusher Machine", CrusherBehavior(crusherMachineState))
        }

        textures {
            texture(
                id = "demo_crusher_side",
                path = "assets/clockwork-demo/textures/block/demo_crusher_side.png",
                category = "block"
            )
        }

        models {
            model(
                id = "demo_crusher_model",
                geometryPath = "assets/clockwork-demo/models/block/demo_crusher.json",
                textures = mapOf("side" to "demo_crusher_side"),
                animations = mapOf("spin" to "assets/clockwork-demo/animations/demo_crusher_spin.json")
            )
        }

        animations {
            animation(
                id = "demo_crusher_spin",
                path = "assets/clockwork-demo/animations/demo_crusher_spin.json",
                targetModelId = "demo_crusher_model",
                loop = true
            )
        }

        sounds {
            sound(
                id = "demo_crusher_hum",
                path = "assets/clockwork-demo/sounds/demo_crusher_hum.ogg",
                category = "block",
                stream = false
            )
        }

        systems {
            system("crusher_tick") { ctx ->
                val machine = crusherMachineState
                ctx.registry.machines().firstOrNull { it.id == machine.machineId }?.behavior?.onTick(machine, ctx)
            }
        }

        adapters {
            adapter(
                id = "clockwork-demo.tools",
                name = "Demo Tools",
                capabilities = setOf("demo.read")
            ) { invocation ->
                when (invocation.action) {
                    "stats" -> AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "joins" to joinEvents.toString(),
                            "networkEvents" to networkEvents.toString(),
                            "machineProgressTicks" to crusherMachineState.progressTicks.toString()
                        )
                    )
                    else -> AdapterResponse(success = false, message = "Unknown action '${invocation.action}'")
                }
            }
        }

        commands {
            spec(
                command = "demo-stats",
                description = "Show demo production stats",
                aliases = listOf("dstats"),
                usage = "demo-stats",
                help = "Shows how CommandSpec help/usage and audit middleware work.",
                cooldownMillis = 150L,
                rateLimitPerMinute = 240,
                middleware = listOf(
                    authMiddleware { inv ->
                        if (inv.sender.id.isBlank()) CommandResult.error("Sender must not be blank", code = "E_SENDER") else null
                    },
                    auditMiddleware { _, _ -> }
                )
            ) { inv ->
                val ctx = inv.pluginContext
                val store = ctx.storage.store("demo_counter", DemoCounter::class.java, version = 1)
                val counter = store.load() ?: DemoCounter()
                CommandResult.ok("Produced: ${counter.produced}")
            }

            spec(
                command = "demo-host",
                description = "Show host server info",
                permission = HostPermissions.SERVER_READ,
                usage = "demo-host",
                help = "Requires permission host.server.read."
            ) { inv ->
                val ctx = inv.pluginContext
                val info = ctx.host.serverInfo()
                    ?: return@spec CommandResult.error("Host access unavailable", code = "E_HOST")
                CommandResult.ok("Host=${info.name} version=${info.version} players=${info.onlinePlayers}/${info.maxPlayers} worlds=${info.worldCount}")
            }

            spec(
                command = "demo-joins",
                description = "Show observed player join event count",
                usage = "demo-joins"
            ) { _ ->
                CommandResult.ok("Joins=$joinEvents")
            }

            spec(
                command = "demo-assets",
                description = "Validate and summarize registered assets",
                usage = "demo-assets",
                help = "Runs runtime asset validation and bundle build using the plugin registry."
            ) { inv ->
                val ctx = inv.pluginContext
                val validation = ctx.validateAssets()
                val bundle = ctx.buildResourcePackBundle(ResourcePackBundleOptions(strict = false))
                val status = if (validation.valid) "valid" else "invalid:${validation.issues.size}"
                CommandResult.ok(
                    "Assets=$status textures=${bundle.textures.size} models=${bundle.models.size} animations=${bundle.animations.size} sounds=${bundle.sounds.size}"
                )
            }

            spec(
                command = "demo-network",
                description = "Send a plugin network message",
                argsSchema = listOf(
                    CommandArgSpec("type", CommandArgType.ENUM, enumValues = listOf("chat", "metrics")),
                    CommandArgSpec("text", CommandArgType.STRING, required = false, description = "Message payload text")
                ),
                usage = "demo-network <chat|metrics> [text]",
                help = "Sends a message over the demo plugin channel and reports delivery stats.",
                cooldownMillis = 200L,
                rateLimitPerMinute = 120,
                completion = CommandCompletionContract { ctx, _, spec, args ->
                    when (args.size) {
                        0, 1 -> spec.defaultCompletions(args)
                        2 -> {
                            val world = ctx.host.worlds().firstOrNull()?.name ?: "world"
                            listOf(
                                CommandCompletion("hello"),
                                CommandCompletion("tick=42"),
                                CommandCompletion("world:$world")
                            )
                        }
                        else -> emptyList()
                    }
                },
                middleware = listOf(
                    validationMiddleware { inv ->
                        val text = inv.parsedArgs.string("text") ?: return@validationMiddleware null
                        if (text.length > 128) {
                            CommandResult.error("text must be <= 128 chars", code = "E_ARGS")
                        } else {
                            null
                        }
                    },
                    auditMiddleware { inv, result ->
                        inv.pluginContext.logger.info("demo-network sender=${inv.sender.id} success=${result.success}")
                    }
                )
            ) { inv ->
                val ctx = inv.pluginContext
                val type = inv.parsedArgs.enum("type") ?: "chat"
                val text = inv.parsedArgs.string("text")?.takeIf { it.isNotBlank() } ?: "hello"
                val result = ctx.sendPluginMessage(
                    channel = DEMO_CHANNEL,
                    payload = mapOf("type" to type, "sender" to inv.sender.id, "text" to text)
                )
                CommandResult.ok("Network status=${result.status} delivered=${result.deliveredSubscribers}")
            }

            spec(
                command = "demo-notify",
                description = "Send an in-game UI notice to a player",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("level", CommandArgType.ENUM, enumValues = listOf("info", "success", "warning", "error")),
                    CommandArgSpec("text", CommandArgType.STRING, required = false)
                ),
                usage = "demo-notify <player> <info|success|warning|error> [text]",
                help = "Shows PluginUi notice rendering and emits GigaUiNoticeEvent."
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val level = (inv.parsedArgs.enum("level") ?: "info").lowercase()
                val text = collectTail(inv.rawArgs, fromIndex = 2).ifBlank { "Hello from Clockwork Demo" }
                val uiLevel = when (level) {
                    "success" -> UiLevel.SUCCESS
                    "warning" -> UiLevel.WARNING
                    "error" -> UiLevel.ERROR
                    else -> UiLevel.INFO
                }
                val delivered = ctx.notify(
                    player = player,
                    level = uiLevel,
                    title = "Demo",
                    message = text,
                    durationMillis = 3_000L
                )
                if (delivered) CommandResult.ok("UI notice delivered to '$player'") else CommandResult.error("Player '$player' unavailable", code = "E_UI")
            }

            spec(
                command = "demo-actionbar",
                description = "Send an in-game actionbar to a player",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("text", CommandArgType.STRING, required = false)
                ),
                usage = "demo-actionbar <player> [text]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val text = collectTail(inv.rawArgs, fromIndex = 1).ifBlank { "Actionbar demo" }
                val delivered = ctx.actionBar(player, text, durationTicks = 40)
                if (delivered) CommandResult.ok("Actionbar delivered to '$player'") else CommandResult.error("Player '$player' unavailable", code = "E_UI")
            }

            spec(
                command = "demo-menu",
                description = "Open a demo in-game menu for a player",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "demo-menu <player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val opened = ctx.showMenu(
                    player,
                    UiMenu(
                        id = "demo.main",
                        title = "Demo Menu",
                        items = listOf(
                            UiMenuItem(id = "craft", label = "Craft", description = "Open crafting tools", enabled = true),
                            UiMenuItem(id = "profile", label = "Profile", description = "View runtime profile", enabled = true),
                            UiMenuItem(id = "debug", label = "Debug", description = "Debug tools locked", enabled = false)
                        )
                    )
                )
                if (opened) CommandResult.ok("Menu opened for '$player'") else CommandResult.error("Player '$player' unavailable", code = "E_UI")
            }

            spec(
                command = "demo-dialog",
                description = "Open a demo in-game dialog for a player",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "demo-dialog <player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val opened = ctx.showDialog(
                    player,
                    UiDialog(
                        id = "demo.settings",
                        title = "Demo Settings",
                        fields = listOf(
                            UiDialogField("name", "Display Name", required = true, placeholder = "PlayerName"),
                            UiDialogField("volume", "Volume", type = UiDialogFieldType.NUMBER, required = false, placeholder = "0-100"),
                            UiDialogField("difficulty", "Difficulty", type = UiDialogFieldType.SELECT, options = listOf("easy", "normal", "hard"))
                        )
                    )
                )
                if (opened) CommandResult.ok("Dialog opened for '$player'") else CommandResult.error("Player '$player' unavailable", code = "E_UI")
            }

            spec(
                command = "demo-ui-close",
                description = "Close active plugin UI for a player",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "demo-ui-close <player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val closed = ctx.closeUi(player)
                if (closed) CommandResult.ok("UI closed for '$player'") else CommandResult.error("Player '$player' unavailable", code = "E_UI")
            }

            spec(
                command = "demo-chat",
                description = "Send chat message via host broadcast or direct message",
                argsSchema = listOf(
                    CommandArgSpec("mode", CommandArgType.ENUM, enumValues = listOf("broadcast", "direct")),
                    CommandArgSpec("target", CommandArgType.STRING, required = false),
                    CommandArgSpec("text", CommandArgType.STRING, required = false)
                ),
                usage = "demo-chat <broadcast|direct> [target] [text]",
                help = "broadcast uses host.broadcast, direct uses host.sendPlayerMessage."
            ) { inv ->
                val ctx = inv.pluginContext
                val mode = (inv.parsedArgs.enum("mode") ?: "broadcast").lowercase()
                if (mode == "broadcast") {
                    if (!ctx.hasPermission(HostPermissions.SERVER_BROADCAST)) {
                        return@spec CommandResult.error("Missing permission '${HostPermissions.SERVER_BROADCAST}'", code = "E_PERMISSION")
                    }
                    val text = collectTail(inv.rawArgs, fromIndex = 1).ifBlank { "Demo broadcast message" }
                    val ok = ctx.broadcastNotice(message = text, level = UiLevel.INFO, title = "Demo Broadcast")
                    if (ok) CommandResult.ok("Broadcast sent") else CommandResult.error("Broadcast failed", code = "E_CHAT")
                } else {
                    if (!ctx.hasPermission(HostPermissions.PLAYER_MESSAGE)) {
                        return@spec CommandResult.error("Missing permission '${HostPermissions.PLAYER_MESSAGE}'", code = "E_PERMISSION")
                    }
                    val target = inv.parsedArgs.string("target") ?: return@spec CommandResult.error("Missing target player", code = "E_ARGS")
                    val text = collectTail(inv.rawArgs, fromIndex = 2).ifBlank { "Demo direct message" }
                    val ok = ctx.host.sendPlayerMessage(target, "[DM] $text")
                    if (ok) CommandResult.ok("Direct message sent to '$target'") else CommandResult.error("Player '$target' unavailable", code = "E_CHAT")
                }
            }

            spec(
                command = "demo-time",
                description = "Set world time via transactional host mutation batch",
                permission = HostPermissions.MUTATION_BATCH,
                argsSchema = listOf(
                    CommandArgSpec("world", CommandArgType.STRING),
                    CommandArgSpec("time", CommandArgType.LONG)
                ),
                usage = "demo-time <world> <time>",
                help = "Uses transactional host mutation batch and requires host.mutation.batch + host.world.write.",
                completion = CommandCompletionContract { ctx, _, _, args ->
                    if (args.size <= 1) {
                        ctx.host.worlds()
                            .map { it.name }
                            .distinct()
                            .sorted()
                            .map { CommandCompletion(it) }
                    } else {
                        emptyList()
                    }
                },
                middleware = listOf(
                    validationMiddleware { inv ->
                        val time = inv.parsedArgs.long("time") ?: return@validationMiddleware null
                        if (time < 0L || time > 24_000L) {
                            CommandResult.error("time must be between 0 and 24000", code = "E_ARGS")
                        } else {
                            null
                        }
                    }
                )
            ) { inv ->
                val ctx = inv.pluginContext
                val world = inv.parsedArgs.requiredString("world")
                val time = inv.parsedArgs.long("time") ?: return@spec CommandResult.error("Invalid time", code = "E_ARGS")
                val result = ctx.applyHostMutationBatch(
                    HostMutationBatch(
                        id = "demo-time-$world",
                        operations = listOf(
                            HostMutationOp(
                                type = HostMutationType.SET_WORLD_TIME,
                                target = world,
                                longValue = time
                            )
                        )
                    )
                )
                if (!result.success) {
                    CommandResult.error("Batch failed: ${result.error ?: "unknown"}", code = "E_BATCH")
                } else {
                    CommandResult.ok("World '$world' time updated to $time")
                }
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        joinEvents = 0
        networkEvents = 0
        crusherMachineState.progressTicks = 0
        delegate.onEnable(ctx)
        ctx.registerNetworkChannel(
            PluginChannelSpec(
                id = DEMO_CHANNEL,
                schemaVersion = 1,
                maxInFlight = 16,
                maxMessagesPerMinute = 240
            )
        )
        ctx.network.subscribe(DEMO_CHANNEL) {
            networkEvents += 1
        }
        ctx.events.subscribe(
            GigaPlayerJoinEvent::class.java,
            EventSubscriptionOptions(
                priority = EventPriority.HIGH,
                mainThreadOnly = true
            )
        ) { event ->
            joinEvents += 1
            ctx.logger.info("Player joined via host event: ${event.player.name}")
        }
        ctx.events.setTracingEnabled(true)
    }
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
    override fun onReload(ctx: PluginContext) = delegate.onReload(ctx)

    private fun collectTail(tokens: List<String>, fromIndex: Int): String {
        if (fromIndex >= tokens.size) return ""
        return tokens.drop(fromIndex).joinToString(" ").trim()
    }
}


