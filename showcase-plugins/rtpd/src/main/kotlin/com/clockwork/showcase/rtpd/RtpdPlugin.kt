package com.clockwork.showcase.rtpd

import com.clockwork.api.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

data class RtpdConfig(
    val minDistance: Int = 180,
    val maxDistance: Int = 1400
)

class RtpdPlugin : GigaPlugin {
    private val configKey = "rtpd-config"
    private val random = Random(System.nanoTime())
    @Volatile
    private var config = RtpdConfig()

    private val delegate = gigaPlugin(id = "showcase-rtpd", name = "Showcase Rtpd", version = "1.0.0") {
        commands {
            spec(
                command = "rtp",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "rtp [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val snap = ctx.host.findPlayer(player) ?: return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                val radius = random.nextInt(config.minDistance, config.maxDistance + 1)
                val angle = random.nextDouble(0.0, Math.PI * 2.0)
                val x = snap.location.x + cos(angle) * radius
                val z = snap.location.z + sin(angle) * radius
                val y = snap.location.y.coerceIn(64.0, 160.0)
                val moved = ctx.host.movePlayer(player, HostLocationRef(snap.location.world, x, y, z))
                    ?: return@spec CommandResult.error("Teleport failed", code = "E_HOST")
                CommandResult.ok("Teleported ${moved.name} to ${x.roundToInt()}, ${y.roundToInt()}, ${z.roundToInt()}")
            }

            spec(
                command = "rtp-config",
                argsSchema = listOf(CommandArgSpec("minDistance", CommandArgType.INT), CommandArgSpec("maxDistance", CommandArgType.INT)),
                usage = "rtp-config <minDistance> <maxDistance>"
            ) { inv ->
                val min = inv.parsedArgs.requiredInt("minDistance").coerceIn(16, 25000)
                val max = inv.parsedArgs.requiredInt("maxDistance").coerceIn(min + 1, 30000)
                config = RtpdConfig(minDistance = min, maxDistance = max)
                inv.pluginContext.saveState(configKey, config, version = 1)
                CommandResult.ok("RTP range updated: $min..$max")
            }

            spec(command = "rtp-status", usage = "rtp-status") { _ ->
                CommandResult.ok("RTP range: ${config.minDistance}..${config.maxDistance}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        config = ctx.loadOrDefault(configKey, version = 1) { RtpdConfig() }
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(configKey, config, version = 1)
        delegate.onDisable(ctx)
    }
}
