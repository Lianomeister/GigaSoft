# Ops Release Readiness

## Diagnostics Contract

Standalone console diagnostics are expected to be machine-readable:

- `status --json`
- `doctor --json [--pretty|--compact]`
- `profile <id> --json [--pretty|--compact]`

`profile <id> --json` returns:

- `{ "profile": { ... }, "recommendations": [ ... ] }` when present
- `{ "pluginId": "<id>", "found": false }` when not present
- includes hotspot diagnostics for plugin tuning:
  - `slowSystems`
  - `adapterHotspots`
  - `isolatedSystems`
  - `faultBudget`
  - `recommendations[].code` (automation-friendly)
  - `recommendations[].severity`
  - `recommendations[].message`

`doctor --json` includes cross-plugin hotspot overview:

- `diagnostics.pluginPerformance.<pluginId>.slowSystems`
- `diagnostics.pluginPerformance.<pluginId>.adapterHotspots`
- `diagnostics.pluginPerformance.<pluginId>.isolatedSystems`
- `diagnostics.pluginPerformance.<pluginId>.faultBudget`
- `recommendations.<pluginId>[].code`
- `recommendations.<pluginId>[].severity`
- `recommendations.<pluginId>[].message`

## Pipelines

- Release gate:
  - `./gradlew --no-daemon clean :clockwork-api:apiCheck test performanceBaseline standaloneReleaseCandidate`
- Smoke:
  - `./gradlew --no-daemon integrationSmoke`
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
  - integration smoke pipeline (`integrationSmoke`)
- `.github/workflows/soak.yml`
  - scheduled + manual soak run
- `.github/workflows/release-assets.yml`
  - builds standalone release bundle and uploads:
    - standalone jar
    - cli jar
    - demo jar
  - auto-generates:
    - `ARTIFACTS.txt`
    - `ARTIFACTS.json`
    - `SHA256SUMS.txt`

## Definition of Done

- `test` green
- `:clockwork-api:apiCheck` green
- `performanceBaseline` green
- `standaloneReleaseCandidate` green
- smoke pipeline green
