package com.clockwork.showcase.softanticheat

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

data class MovementWindow(
    var lastX: Double = 0.0,
    var lastY: Double = 0.0,
    var lastZ: Double = 0.0,
    var strikes: Int = 0
)

class SoftAnticheatPlugin : GigaPlugin {
    private val state = ConcurrentHashMap<String, MovementWindow>()

    private val delegate = gigaPlugin(id = "showcase-softanticheat", name = "Showcase SoftAnticheat", version = "1.0.0") {
        events {
            subscribe(GigaPlayerMoveEvent::class.java) { event ->
                val name = event.current.name.lowercase()
                val cur = event.current.location
                val prev = event.previous.location
                val dx = cur.x - prev.x
                val dy = cur.y - prev.y
                val dz = cur.z - prev.z
                val dist = sqrt(dx * dx + dy * dy + dz * dz)

                val win = state.computeIfAbsent(name) { MovementWindow() }
                if (dist > 10.0) {
                    win.strikes += 1
                } else {
                    win.strikes = maxOf(0, win.strikes - 1)
                }

                if (win.strikes >= 3) {
                    val ctx = pluginContextRef ?: return@subscribe
                    ctx.host.kickPlayer(event.current.name, "SoftAnticheat: suspicious movement")
                    win.strikes = 0
                }

                win.lastX = cur.x
                win.lastY = cur.y
                win.lastZ = cur.z
            }
        }

        commands {
            spec(
                command = "softac-status",
                description = "Show tracked movement states",
                usage = "softac-status"
            ) { _ ->
                CommandResult.ok("SoftAC tracking ${state.size} players")
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        pluginContextRef = null
        delegate.onDisable(ctx)
    }
}
