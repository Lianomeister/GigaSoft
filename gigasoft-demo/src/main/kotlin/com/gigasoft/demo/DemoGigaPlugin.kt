package com.gigasoft.demo

import com.gigasoft.api.*

data class DemoCounter(var produced: Int = 0)

class CrusherBehavior : MachineBehavior {
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
    private val delegate = gigaPlugin(
        id = "gigasoft-demo",
        name = "GigaSoft Demo",
        version = "0.1.0-rc.2"
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
            machine("crusher_machine", "Crusher Machine", CrusherBehavior())
        }

        systems {
            system("crusher_tick") { ctx ->
                val machine = MachineState(machineId = "crusher_machine")
                ctx.registry.machines().firstOrNull { it.id == machine.machineId }?.behavior?.onTick(machine, ctx)
            }
        }

        commands {
            command("demo-stats", "Show demo production stats") { ctx, _, _ ->
                val store = ctx.storage.store("demo_counter", DemoCounter::class.java, version = 1)
                val counter = store.load() ?: DemoCounter()
                "Produced: ${counter.produced}"
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
    override fun onReload(ctx: PluginContext) = delegate.onReload(ctx)
}
