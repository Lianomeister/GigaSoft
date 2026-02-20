# GigaSoft V1

Hybrid Minecraft server runtime focused on easy, powerful plugins with a Kotlin DSL.

## Modules
- `gigasoft-api`: public API + DSL
- `gigasoft-runtime`: loader, lifecycle, scheduler, storage
- `gigasoft-bridge-paper`: Paper bridge plugin + `/giga` commands
- `gigasoft-cli`: local developer CLI
- `gigasoft-demo`: reference GigaPlugin (Create-style mini machine loop)
- `gigasoft-integration`: real Paper smoke integration test

## Requirements
- JDK 21
- Gradle 8.10+
- Paper 1.21.x

## Build
```bash
gradle test
gradle integrationSmoke
gradle buildPlugin\ngradle releaseCandidate
```

## Run on Paper
1. Install Paper server.
2. Place `gigasoft-bridge-paper/build/libs/gigasoft-bridge-paper-0.1.0-rc.1.jar` into Paper `plugins/`.
3. Start server once.
4. Place `gigasoft-demo/build/libs/gigasoft-demo-0.1.0-rc.1.jar` into `plugins/GigaSoftBridge/giga-plugins/`.
5. Use `/giga plugins` and `/giga reload all`.

## Manifest Dependency Format
`gigaplugin.yml` supports both simple and versioned dependencies:

```yaml
dependencies:
  - core
  - "machines >=1.2.0 <2.0.0"
  - id: economy
    version: ">=3.0.0 <4.0.0"
```

`apiVersion` is validated against runtime API major version (`1.x` currently).

## Runtime Commands
- `/giga plugins`
- `/giga reload <plugin|all>`
- `/giga doctor [--json]`
- `/giga profile <plugin>`
- `/giga run <plugin> <command...>`

## DSL Example
```kotlin
class MyPlugin : GigaPlugin {
    private val plugin = gigaPlugin(id = "my-plugin") {
        items { item("gear", "Gear") }
        systems { system("tick") { ctx -> ctx.logger.info("tick") } }
    }

    override fun onEnable(ctx: PluginContext) = plugin.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = plugin.onDisable(ctx)
}
```

## Docker (local)
```bash
docker compose up --build
```

Volumes:
- `./dev-runtime/world:/data/world`
- `./dev-runtime/plugins:/data/plugins`
- `./dev-runtime/config:/data/config`

## Tutorials
- `docs/tutorials/hello-plugin.md`
- `docs/tutorials/machine-basics.md`
- `docs/tutorials/reload-workflow.md`
