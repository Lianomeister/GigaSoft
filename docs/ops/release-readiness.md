# Ops Release Readiness

## Diagnostics Contract

Standalone console diagnostics are expected to be machine-readable:

- `status --json`
- `doctor --json [--pretty|--compact]`
- `profile <id> --json [--pretty|--compact]`

`status --json` now includes tick-loop stability and budget guardrails:

- `core.averageTickJitterNanos`
- `core.maxTickJitterNanos`
- `core.tickOverruns`
- `core.pluginBudgetExhaustions`

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

Reload transaction quality is exposed in reload reports:

- `checkpointChangedPlugins`
- `rollbackRecoveredPlugins`
- `rollbackFailedPlugins`
- `rollbackDataRestored`

Deterministic runtime ordering guarantees:

- plugin system execution order is lexicographically sorted by `pluginId`, then `systemId`
- event listeners are ordered by priority + subscription sequence
- scheduler `activeTaskIds()` is sorted for deterministic diagnostics snapshots

## Pipelines

- Release gate:
  - `./gradlew --no-daemon clean :clockwork-api:apiCheck :clockwork-host-api:apiCheck :clockwork-api:contractTest :clockwork-host-api:contractTest apiContractsFreezeGate apiCompatibilityReport test performanceBaseline standaloneReleaseCandidate`
- Smoke:
  - `./gradlew --no-daemon integrationSmoke`
- Soak:
  - `./gradlew --no-daemon soakTest`

## Security Thresholds (Config-Driven)

Runtime and adapter security limits are fully config-driven in standalone launch config.

- Config file:
  - `clockwork-standalone/standalone.example.properties`
- CLI overrides:
  - `--security-config-schema-version`
  - `--adapter-payload-policy-profile`
  - `--adapter-audit-retention-max-memory-bytes`
  - `--fault-budget-max-faults`
  - `--fault-budget-window-ms`
  - `--fault-budget-warn-ratio`
  - `--fault-budget-throttle-ratio`
  - `--fault-budget-isolate-ratio`
  - `--fault-budget-throttle-budget-multiplier`
  - plus adapter threshold flags in `clockwork-standalone` launch config.

Schema and validation:

- `securityConfigSchemaVersion=1` is the currently supported schema.
- Values are normalized through `RuntimeSecurityThresholdsValidator`.
- Invalid values fall back to safe defaults with explicit startup warnings.
- Unsupported schema versions fall back to full safe defaults (schema `1`).

Covered thresholds include:

- adapter timeout
- adapter global/per-plugin rate limits
- adapter payload limits (entries/key/value/total)
- adapter payload policy profile (`strict|balanced|perf`)
- adapter per-adapter concurrency
- adapter audit logging and bounded retention (entries/age/memory budget)
- fault budget window + max faults
- fault budget escalation thresholds (`warn -> throttle -> isolate`)
- throttle budget multiplier for runtime tick-budget pressure control

Fault-budget escalation telemetry:

- `profile.<plugin>.faultBudget.stage` (`NORMAL|WARN|THROTTLE|ISOLATE`)
- `profile.<plugin>.faultBudget.usageRatio`
- `status.core.faultBudgetWarnTicks`
- `status.core.faultBudgetThrottleTicks`
- `status.core.faultBudgetIsolateTicks`

Validation coverage:

- `clockwork-runtime/src/test/kotlin/com/clockwork/runtime/RuntimeSecurityThresholdsValidatorTest.kt`
- `clockwork-standalone/src/test/kotlin/com/clockwork/standalone/StandaloneLaunchConfigTest.kt`
- `clockwork-api/src/test/kotlin/com/clockwork/api/PublicInterfaceContractsTest.kt`
- `clockwork-host-api/src/test/kotlin/com/clockwork/host/api/HostDomainPortsContractTest.kt`

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
  - hard-verifies in CI before upload:
    - `ARTIFACTS.txt` matches exact jar set
    - `sha256sum -c SHA256SUMS.txt` succeeds
    - `ARTIFACTS.json` names/size/checksum match on-disk artifacts

## Definition of Done

- `test` green
- `:clockwork-api:apiCheck` green
- `:clockwork-host-api:apiCheck` green
- `:clockwork-api:contractTest` green
- `:clockwork-host-api:contractTest` green
- `apiContractsFreezeGate` green (`build/reports/api-compatibility/v1.7-freeze-report.md`)
- `apiCompatibilityReport` generated (`build/reports/api-compatibility/report.md`)
- `performanceBaseline` green
- `standaloneReleaseCandidate` green
- smoke pipeline green
- migration docs published and current:
  - `docs/migrations/v1.1-to-v1.5.0.md`
  - `docs/migrations/v1.5.0-rc.1-to-v1.5.0.md`
