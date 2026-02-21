# Tutorial: Reload-safe Coding

Goal: make plugin reload deterministic and side-effect safe.

## Core Rules

- do not spawn unmanaged threads
- track scheduled task IDs and cancel them in `onDisable`
- avoid global mutable singletons for runtime state
- idempotent registration: same IDs for commands/systems/adapters
- close/release external resources in `onDisable`

## Pattern

```kotlin
class ReloadSafePlugin : GigaPlugin {
    private val plugin = gigaPlugin(id = "reload-safe") {
        systems {
            system("tick") { ctx ->
                // cheap deterministic tick work
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        plugin.onEnable(ctx)
        ctx.scheduler.repeating("reload-safe:metrics", 20) {
            ctx.logger.info("metrics heartbeat")
        }
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.scheduler.cancel("reload-safe:metrics")
        plugin.onDisable(ctx)
    }
}
```

## Reload Validation Steps

1. `reload <plugin-id>`
2. `profile <plugin-id> --json`
3. repeat reload 5-10 times
4. ensure no growing task count / no duplicate effects

## Useful Diagnostics

- `doctor --json`
- `status --json`
- `profile <plugin-id> --json`
