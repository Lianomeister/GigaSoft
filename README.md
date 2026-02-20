# GigaSoft V1

Standalone-first Minecraft server runtime focused on easy, powerful plugins with a Kotlin DSL.

## Modules
- `gigasoft-api`: public API + DSL
- `gigasoft-host-api`: host abstraction contracts + shared host adapter ids
- `gigasoft-net`: native standalone session/network layer
- `gigasoft-runtime`: loader, lifecycle, scheduler, storage
- `gigasoft-core`: standalone core runtime loop
- `gigasoft-standalone`: executable standalone server process
- `gigasoft-cli`: local developer CLI
- `gigasoft-demo-standalone`: standalone reference GigaPlugin

## Requirements
- JDK 21
- Gradle 8.10+

## Build
```bash
gradle test
gradle performanceBaseline
gradle standaloneReleaseCandidate
```

## Standalone Run
```bash
java -jar gigasoft-standalone/build/libs/gigasoft-standalone-0.1.0-rc.2.jar
```

Optional config file (`.properties`):
```bash
java -jar gigasoft-standalone/build/libs/gigasoft-standalone-0.1.0-rc.2.jar --config gigasoft-standalone/standalone.example.properties
```

## Standalone Commands
- `help`
- `status`
- `save`
- `load`
- `plugins`
- `worlds`
- `world create <name> [seed]`
- `entities [world]`
- `entity spawn <type> <world> <x> <y> <z>`
- `players`
- `player join <name> [world] [x] [y] [z]`
- `player leave <name>`
- `player move <name> <x> <y> <z> [world]`
- `inventory <player>`
- `inventory set <player> <slot> <itemId|air>`
- `scan`
- `reload <id|all>`
- `doctor [--json]`
- `profile <id> [--json]`
- `run <plugin> <command...>`
- `adapters <plugin> [--json]`
- `adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]`
- `stop`

## Built-in Bridge Adapters
- `bridge.host.server`
- `bridge.host.player`

## Adapter Security Guardrails
- Action/ID validation
- Payload limits
- Optional capability check via `required_capability`
- Per-adapter rate limit
- Invocation timeout
- Outcome counters in profile
- Configurable via standalone CLI flags or `standalone.example.properties`

## Host Access In PluginContext
```kotlin
val info = ctx.host.serverInfo()
if (info != null) {
    ctx.logger.info("Host ${info.name} players=${info.onlinePlayers}/${info.maxPlayers}")
}
```

## CLI Server Control
- `giga server start`
- `giga server stop`
- `giga server status`
- `giga server logs`
- `giga server logs --follow`

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
