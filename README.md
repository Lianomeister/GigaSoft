# Clockwork V1

Standalone-first Minecraft server runtime focused on easy, powerful plugins with a Kotlin DSL.

## Standalone RC Support
- Standalone is an officially supported RC path in `v1.5.x`.
- CI runs standalone release gate plus integration smoke for:
  - boot + demo plugin flow
  - parity checks for `scan/reload/doctor/profile`
  - deterministic tick-loop and reload behavior

## Modules
- `clockwork-api`: public API + DSL
- `clockwork-host-api`: host abstraction contracts (server + player/world/entity domain ports) + shared host adapter ids
- `clockwork-net`: native standalone session/network layer
- `clockwork-runtime`: loader, lifecycle, scheduler, storage
- `clockwork-core`: standalone core runtime loop
- `clockwork-standalone`: executable standalone server process
- `clockwork-cli`: local developer CLI
- `clockwork-demo-standalone`: standalone reference GigaPlugin

## API Docs
- Frozen baseline API contract: `docs/api/v1.0.0.md`
- Current versioned API contract: `docs/api/v1.5.0.md`
- Full API reference: `docs/api/reference-v1.md`
- Migration guide (1.1 -> 1.5): `docs/migrations/v1.5.md`
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
./gradlew --no-daemon :clockwork-api:apiCheck
./gradlew --no-daemon test
./gradlew --no-daemon performanceBaseline
./gradlew --no-daemon standaloneReleaseCandidate
```

## Standalone Run
```bash
java -jar clockwork-standalone/build/libs/clockwork-standalone-1.5.0-rc.2.jar
```

Optional config file (`.properties`):
```bash
java -jar clockwork-standalone/build/libs/clockwork-standalone-1.5.0-rc.2.jar --config clockwork-standalone/standalone.example.properties
```

## Standalone Commands
- `help`
- `status [--json]`
- `save`
- `load`
- `plugins`
- `plugin list [--json]`
- `plugin error [pluginId] [--json]`
- `plugin scan`
- `plugin reload <id|all|changed>`
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
- `doctor [--json] [--pretty|--compact]`
- `profile <id> [--json] [--pretty|--compact]`
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
- Outcome counters in `profile --json` and `doctor --json` diagnostics snapshots
- Bounded in-memory adapter audit retention (per-plugin, per-adapter, max-age)
- Configurable via standalone CLI flags or `standalone.example.properties`
- Security thresholds config is schema-versioned (`securityConfigSchemaVersion=1`)
- Invalid/unsupported threshold values are validated and auto-fallback to safe defaults with startup warnings
- New threshold keys:
  - `adapterTimeoutMillis`
  - `adapterRateLimitPerMinute`
  - `adapterRateLimitPerMinutePerPlugin`
  - `adapterMaxPayloadEntries`
  - `adapterMaxPayloadTotalChars`
  - `adapterMaxPayloadKeyChars`
  - `adapterMaxPayloadValueChars`
  - `adapterAuditRetentionMaxEntriesPerPlugin`
  - `adapterAuditRetentionMaxEntriesPerAdapter`
  - `adapterAuditRetentionMaxAgeMillis`
  - `faultBudgetMaxFaultsPerWindow`
  - `faultBudgetWindowMillis`
- CLI overrides:
  - `--security-config-schema-version`
  - `--fault-budget-max-faults`
  - `--fault-budget-window-ms`
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
- Release gate: `./gradlew --no-daemon clean :clockwork-api:apiCheck test performanceBaseline standaloneReleaseCandidate`
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

