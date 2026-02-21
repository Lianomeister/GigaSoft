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

## API v1.0 Freeze
- Frozen API contract: `docs/api/v1.0.0.md`
- Full API reference: `docs/api/reference-v1.md`
- Migration guide: `docs/migrations/v1.0.md`
- Changelog: `CHANGELOG.md`
- Performance targets + gate: `docs/performance/targets-v1.md`

## Developer Docs
- Docs index: `docs/index.md`
- Plugin start tutorial: `docs/tutorials/plugin-start.md`
- Events tutorial: `docs/tutorials/events.md`
- Adapters tutorial: `docs/tutorials/adapters.md`
- Persistence tutorial: `docs/tutorials/persistence.md`
- Reload-safe coding: `docs/tutorials/reload-safe-coding.md`
- Debug playbook: `docs/troubleshooting/plugin-debug-playbook.md`

## Requirements
- JDK 21
- Gradle 8.10+

## Build
```bash
./gradlew --no-daemon :gigasoft-api:apiCheck
./gradlew --no-daemon test
./gradlew --no-daemon performanceBaseline
./gradlew --no-daemon standaloneReleaseCandidate
```

## Standalone Run
```bash
java -jar gigasoft-standalone/build/libs/gigasoft-standalone-1.1.0-SNAPSHOT.jar
```

Optional config file (`.properties`):
```bash
java -jar gigasoft-standalone/build/libs/gigasoft-standalone-1.1.0-SNAPSHOT.jar --config gigasoft-standalone/standalone.example.properties
```

## Standalone Commands
- `help`
- `status [--json]`
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
- SAFE/FAST execution policy
- Optional capability check via `required_capability`
- Per-adapter rate limit
- Optional per-plugin rate limit
- Optional per-adapter concurrency cap
- Invocation timeout
- Audit logs (`[adapter-audit]`)
- Outcome counters in profile
- Configurable via standalone CLI flags or `standalone.example.properties`
- Security matrix + abuse tests: `docs/security/hardening-matrix.md`

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

## Ops Pipelines
- Release gate: `./gradlew --no-daemon clean :gigasoft-api:apiCheck test performanceBaseline standaloneReleaseCandidate`
- Smoke: `./gradlew --no-daemon smokeTest`
- Soak: `./gradlew --no-daemon soakTest`

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

Reload-safe command lifecycle:

```kotlin
ctx.commands.registerOrReplace("ping", "Health check") { _, sender, _ ->
    "pong from $sender"
}
ctx.commands.unregister("ping")
```

Event dispatch mode:

- default: `exact`
- optional: `polymorphic` via config `eventDispatchMode=polymorphic` or CLI `--event-dispatch-mode polymorphic`
