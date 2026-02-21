# Plugin Debug Playbook

This checklist is optimized for "plugin does not load / command not found / behavior missing".

## 1. Basic Sanity

- build plugin jar successfully
- `gigaplugin.yml` exists in jar root
- `id`, `main`, `version`, `apiVersion` are valid
- `apiVersion: 1`

## 2. Runtime Visibility

Use standalone console:

- `plugins`
- `doctor --json`
- `status --json`

What to check:

- plugin id appears in `plugins`
- `doctor.currentDependencyIssues` is empty for your plugin
- `status.core.loadedPlugins` increments after load

## 3. Reload Safety Checks

- run `reload <your-plugin-id>`
- inspect `profile <your-plugin-id> --json`
- verify no duplicate scheduled tasks after repeated reload
- inspect `profile.<id>.slowSystems` for hot system loops
- inspect `profile.<id>.adapterHotspots` for denial/timeout/failure pressure
- inspect `profile.<id>.isolatedSystems` for fault-isolated systems in cooldown windows

If duplicates exist:

- ensure deterministic task IDs
- cancel tasks in `onDisable`
- avoid registering the same command/adapter with new random IDs

## 4. Adapter Failures

- run `adapters <your-plugin-id> --json`
- run `adapter invoke <plugin> <adapterId> <action> --json`
- inspect `profile <plugin> --json` adapter counters:
  - `deny`
  - `timeout`
  - `fail`
  - plus `adapterHotspots` reasons (`timeout_rate`, `failure_rate`, `denied_rate`)

When denied/timeouts occur:

- review payload size and key/value lengths
- check required capabilities
- verify SAFE/FAST mode and security limits

## 5. Event Debugging

Temporary debug pattern:

```kotlin
ctx.events.subscribe(GigaTickEvent::class.java) {
    if (it.tick % 200L == 0L) ctx.logger.info("tick=${it.tick}")
}
```

Use low-frequency logs to avoid flooding.

## 6. Persistence Issues

- confirm storage key and schema version are stable
- validate migration path from previous version
- never rely on mutable global state outside storage for required data

## 7. Final Verification Before Release

- `./gradlew --no-daemon test`
- `./gradlew --no-daemon performanceBaseline`
- `./gradlew --no-daemon standaloneReleaseCandidate`
- optional:
  - `./gradlew --no-daemon smokeTest`
  - `./gradlew --no-daemon soakTest`
