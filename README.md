# GigaSoft V1

Hybrid Minecraft server runtime focused on easy, powerful plugins with a Kotlin DSL.

## Modules
- `gigasoft-api`: public API + DSL
- `gigasoft-runtime`: loader, lifecycle, scheduler, storage
- `gigasoft-core`: standalone core runtime loop (no Paper required)
- `gigasoft-standalone`: executable standalone server process
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
gradle buildPlugin
gradle releaseCandidate
```

## Standalone Core (preview)
Run GigaSoft without Paper:
```bash
java -jar gigasoft-standalone/build/libs/gigasoft-standalone-0.1.0-rc.2.jar
```

Optional directories:
```bash
java -jar gigasoft-standalone/build/libs/gigasoft-standalone-0.1.0-rc.2.jar --plugins dev-runtime/giga-plugins --data dev-runtime/giga-data
```

Console commands:
- `help`
- `plugins`
- `scan`
- `reload <id|all>`
- `doctor`
- `profile <id>`
- `stop`

## Run on Paper
1. Install Paper server.
2. Place `gigasoft-bridge-paper/build/libs/gigasoft-bridge-paper-0.1.0-rc.2.jar` into Paper `plugins/`.
3. Start server once.
4. Place `gigasoft-demo/build/libs/gigasoft-demo-0.1.0-rc.2.jar` into `plugins/GigaSoftBridge/giga-plugins/`.
5. Use `/giga plugins` and `/giga reload all`.

## Paper/Spigot Compatibility
- Legacy Paper/Spigot plugins continue to load natively via Paper.
- GigaSoftBridge now improves interoperability by auto-provisioning protocol compatibility plugins.
- ViaVersion and ViaBackwards are auto-installed (Modrinth) and dynamically loaded by default.

Config: `plugins/GigaSoftBridge/config.yml`
```yaml
compatibility:
  via:
    auto_install: true
    auto_load: true
    viaversion:
      enabled: true
    viabackwards:
      enabled: true
```

Set `enabled: false` per plugin to disable.

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
- `/giga profile <plugin> [--json]`
- `/giga run <plugin> <command...>`
- `/giga adapters <plugin> [--json]`
- `/giga adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]`

Admin permission nodes:
- `gigasoft.admin.plugins`
- `gigasoft.admin.reload`
- `gigasoft.admin.doctor`
- `gigasoft.admin.profile`
- `gigasoft.admin.run`
- `gigasoft.admin.adapters.view`
- `gigasoft.admin.adapters.invoke`

## Built-in Bridge Adapters
- `bridge.paper.server`
  - actions: `server.info`, `server.broadcast`
- `bridge.paper.player`
  - actions: `player.lookup`
- Bridge adapters are auto-registered for each loaded GigaPlugin (including after reload).

## Adapter Security Guardrails
- Adapter/action id validation.
- Payload limits (entry count, key/value size, total size).
- Optional capability check via payload key `required_capability`.
- Per-adapter rate limit (sliding 60s window).
- Invocation timeout (fail-fast response instead of hanging call path).
- Adapter outcome counters in profile: `accepted`, `denied`, `timeouts`, `failures`.
- Admin command invokes are audit-logged to server log.

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

## Docker (local)
```bash
docker compose up --build
```

Volumes:
- `./dev-runtime/world:/data/world`
- `./dev-runtime/plugins:/data/plugins`
- `./dev-runtime/config:/data/config`

## Info Website (Showcase)
- Files: `website/index.html`, `website/styles.css`, `website/app.js`
- Open locally: Datei `website/index.html` direkt im Browser öffnen.
- Zweck: Marketing/Info-Seite mit Platzhalter-Video-Slots für kommende Plugin-Demos.

## Tutorials
- `docs/tutorials/hello-plugin.md`
- `docs/tutorials/machine-basics.md`
- `docs/tutorials/reload-workflow.md`
