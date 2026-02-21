# Hello Plugin (Quick Path)

Use this if you want the smallest possible plugin first.

## 1. Implement Plugin Class

```kotlin
import com.clockwork.api.GigaPlugin
import com.clockwork.api.PluginContext
import com.clockwork.api.gigaPlugin

class HelloPlugin : GigaPlugin {
    private val delegate = gigaPlugin(
        id = "hello",
        name = "Hello Plugin",
        version = "1.0.0",
        apiVersion = "1"
    ) {
        commands {
            command("hello", "health check") { _, sender, _ ->
                "hello from plugin, sender=$sender"
            }
        }
        systems {
            system("heartbeat") { ctx ->
                // Log every tick only for demo; reduce frequency in production.
                ctx.logger.info("hello tick")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
}
```

## 2. Add `gigaplugin.yml`

```yaml
id: hello
name: Hello Plugin
version: 1.0.0
main: your.package.HelloPlugin
apiVersion: 1
dependencies: []
permissions: []
```

## 3. Build and Run

1. Build your jar.
2. Put jar into your configured plugins directory (default: `dev-runtime/giga-plugins`).
3. Start standalone server.
4. Run `scan` and then `plugins`.
5. Run `run hello hello`.

## 4. Debug If Missing

1. `doctor --json`
2. `status --json`
3. `profile hello --json`

Detailed debugging checklist:

- `docs/troubleshooting/plugin-debug-playbook.md`
