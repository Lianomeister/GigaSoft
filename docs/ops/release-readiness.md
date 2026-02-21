# Ops Release Readiness

## Diagnostics Contract

Standalone console diagnostics are expected to be machine-readable:

- `status --json`
- `doctor --json`
- `profile <id> --json`

`profile <id> --json` returns:

- full profile object when present
- `{ "pluginId": "<id>", "found": false }` when not present
- includes hotspot diagnostics for plugin tuning:
  - `slowSystems`
  - `adapterHotspots`
  - `isolatedSystems`

`doctor --json` includes cross-plugin hotspot overview:

- `pluginPerformance.<pluginId>.slowSystems`
- `pluginPerformance.<pluginId>.adapterHotspots`
- `pluginPerformance.<pluginId>.isolatedSystems`

## Pipelines

- Release gate:
  - `./gradlew --no-daemon clean :gigasoft-api:apiCheck test performanceBaseline standaloneReleaseCandidate`
- Smoke:
  - `./gradlew --no-daemon smokeTest`
- Soak:
  - `./gradlew --no-daemon soakTest`

## Kotlin Compiler Stability

- Default build setting uses in-process Kotlin compilation (`kotlin.compiler.execution.strategy=in-process` in `gradle.properties`).
- Reason: avoids intermittent Kotlin daemon incremental-cache file-lock issues seen on Windows during repeated smoke/soak runs.
- If local cache behavior still looks inconsistent, run:
  - `./gradlew --stop`
  - then rerun with `--no-daemon`.

## CI Workflows

- `.github/workflows/ci.yml`
  - release gate command
  - smoke pipeline
- `.github/workflows/soak.yml`
  - scheduled + manual soak run

## Definition of Done

- `test` green
- `:gigasoft-api:apiCheck` green
- `performanceBaseline` green
- `standaloneReleaseCandidate` green
- smoke pipeline green
