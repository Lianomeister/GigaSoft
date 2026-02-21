# Changelog

## Unreleased (1.5.0)

### Changed
- Project version moved to `1.5.0-SNAPSHOT` for the expanded post-1.1 scope.
- Default standalone server version moved to `1.5.0-SNAPSHOT`.
- DSL default plugin version moved to `1.5.0-SNAPSHOT` for active development.

### Added
- Plugin API ergonomic helpers:
  - reified `EventBus.subscribe<T>`,
  - reified `StorageProvider.store<T>`,
  - simplified command registration helpers without explicit `PluginContext` argument,
  - `PluginContext.hasPermission(...)` and `PluginContext.requirePermission(...)`.
  - typed command responses via `CommandResult` and `registerResult` / `registerOrReplaceResult`.
  - typed adapter payload parsing helpers on `AdapterInvocation` (`payloadString/Trimmed/Required/Int/Long/Double/Bool`).
  - expanded host gameplay API surface for mod-like plugins:
    - `sendPlayerMessage`, `kickPlayer`,
    - `playerIsOp`, `setPlayerOp`,
    - `playerPermissions`, `hasPlayerPermission`, `grantPlayerPermission`, `revokePlayerPermission`,
    - `createWorld`, `worldTime`, `setWorldTime`,
    - `worldData`, `setWorldData`,
    - `worldWeather`, `setWorldWeather`,
    - `findEntity`, `removeEntity`,
    - `entityData`, `setEntityData`,
    - `movePlayer`,
    - `playerGameMode`, `setPlayerGameMode`,
    - `playerStatus`, `setPlayerStatus`,
    - `addPlayerEffect`, `removePlayerEffect`,
    - `inventoryItem`, `givePlayerItem`,
    - `blockAt`, `setBlock`, `breakBlock`,
    - `blockData`, `setBlockData`.
  - plugin asset pipeline for custom visuals:
    - new registry contracts `registerTexture` / `registerModel`,
    - new model types `TextureDefinition` / `ModelDefinition`,
    - DSL blocks `textures {}` / `models {}` for plugin-side declaration,
    - richer model contracts (`ModelBounds`, `ModelLod`, material/scale/collision/animations metadata).
  - runtime diagnostics for plugin performance tuning:
    - `PluginRuntimeProfile.slowSystems` with threshold-based reasons,
    - `PluginRuntimeProfile.adapterHotspots` with denied/timeout/failure rates,
    - `PluginRuntimeProfile.isolatedSystems` for system cooldown/isolation state in long-running sessions,
    - `RuntimeDiagnostics.pluginPerformance` cross-plugin hotspot summary.
  - core fault isolation hardening:
    - repeated system failures trigger bounded cooldown isolation with exponential backoff,
    - isolation state is surfaced in `doctor`/`profile` output for plugin-side debugging.
  - plugin lifecycle events expanded:
    - command lifecycle: `GigaCommandPreExecuteEvent`, `GigaCommandPostExecuteEvent`,
    - adapter lifecycle: `GigaAdapterPreInvokeEvent`, `GigaAdapterPostInvokeEvent`,
    - gameplay lifecycle: `GigaPlayerMovePre/PostEvent`, `GigaEntitySpawnPre/PostEvent`, `GigaBlockBreakPre/PostEvent`,
    - pre events support cancel/override-style policy control for runtime actions.
  - event dispatch expansion:
    - new `HYBRID` mode (exact listeners first, then polymorphic listeners in deterministic order).
  - payload helper expansion on `AdapterInvocation`:
    - `payloadCsv`, `payloadEnum`, `payloadByPrefix`,
    - required numeric/boolean helpers (`payloadIntRequired`, `payloadLongRequired`, `payloadDoubleRequired`, `payloadBoolRequired`),
    - `payloadEnumRequired`, `payloadDurationMillis`, `payloadMap`.
  - event bus ergonomics:
    - `EventBus.unsubscribe(...)`,
    - `subscribeOnce<T> { ... }` helper for one-shot listeners.
  - command registration expansion:
    - `CommandRegistry.registerAlias` / `unregisterAlias` / `resolve` / `registeredCommands`,
    - high-level helpers `registerWithAliases`, `registerOrReplaceWithAliases`,
    - validation-aware helpers `registerValidated`, `registerOrReplaceValidated`.
  - command API UX 2.0 baseline:
    - `CommandSpec` with permission/args-schema/cooldown/rate-limit/usage/help contracts,
    - typed args parser (`CommandParsedArgs`) and built-in auto-help flow,
    - deterministic command middleware chain (`AUTH`/`VALIDATION`/`AUDIT`),
    - completion contracts via `CommandCompletionContract` and `CommandCompletionCatalog`.
    - `CommandDsl` is now spec-first capable (`command(spec=...)` / `spec(...)`) with middleware and completion wiring.
  - Events 2.0 baseline:
    - listener priorities (`EventPriority`) and per-listener `ignoreCancelled` / `mainThreadOnly`,
    - async event publish API (`publishAsync`) and helper (`publishAsyncUnit`),
    - strict main-thread guard support in runtime event bus,
    - plugin-facing event tracing/profiling snapshots (`EventTraceSnapshot`, `EventTypeTraceSnapshot`).
  - Data/State API baseline:
    - transactional host mutation batches (`HostMutationBatch`, `HostMutationOp`, `HostMutationType`),
    - atomic apply+rollback result contract (`HostMutationBatchResult`),
    - host API entrypoint `HostAccess.applyMutationBatch(...)`,
    - plugin helper `PluginContext.applyHostMutationBatch(...)` with rollback callback hook.
    - stronger standalone persistence snapshots:
      - schema v2 envelope metadata (`savedAtEpochMillis`, `snapshotSha256`, migration history),
      - deterministic migration pipeline/reporting (`v0 -> v1 -> v2`) via `loadWithReport`/`inspectMigrationReport`/`migrateInPlace`,
      - checksum warning diagnostics on corrupted/tampered snapshots,
      - automatic rewritten save after successful migration during core load.
  - Assets/Content pipeline baseline:
    - new asset contracts for `AnimationDefinition` and `SoundDefinition`,
    - resource pack bundling/validation contracts (`validateAssets`, `buildResourcePackBundle`),
    - runtime validation checks for missing refs, format mismatch, and namespace/path policy,
    - deterministic reload fallback on invalid assets via rollback transaction.
  - Network API baseline for plugins:
    - stable plugin messaging channels (`PluginNetwork`) with registration/subscription/send contracts,
    - schema-versioned payload envelope and typed delivery status,
    - runtime backpressure + per-plugin throughput quotas + payload size limits.
  - Security and isolation hardening:
    - finer-grained host permission gate for mutation batches (`host.mutation.batch`),
    - capability-scoped adapter invocation policies (`adapter.invoke.*`, `adapter.capability.*`),
    - plugin fault-budget telemetry added to runtime `profile`/`doctor` diagnostics.
  - hot-reload developer workflow improvements:
    - changed-jar detection with `reloadChangedWithReport()` in runtime,
    - standalone `sync` command path for load-new + reload-changed in one step,
    - `reload changed` command path in standalone CLI,
    - longer mutation timeout for heavy reload transactions.
  - plugin UI API expansion:
    - new `PluginUi` surface on `PluginContext` (`ctx.ui`),
    - UI contracts for notice/actionbar/menu/dialog payloads,
    - convenience UI helpers (`notifyInfo/Success/Warning/Error`, `showMenu`, `showDialog`),
    - runtime UI bridge publishing UI lifecycle events (`GigaUi*`).
  - new gameplay events: `GigaEntityRemoveEvent`, `GigaEntityDataChangeEvent`, `GigaPlayerTeleportEvent`, `GigaPlayerGameModeChangeEvent`, `GigaPlayerMessageEvent`, `GigaPlayerKickEvent`, `GigaPlayerOpChangeEvent`, `GigaPlayerPermissionChangeEvent`, `GigaWorldTimeChangeEvent`, `GigaWorldDataChangeEvent`, `GigaWorldWeatherChangeEvent`, `GigaPlayerStatusChangeEvent`, `GigaPlayerEffectChangeEvent`, `GigaBlockChangeEvent`, `GigaBlockDataChangeEvent`, plus asset events `GigaTextureRegisteredEvent` and `GigaModelRegisteredEvent`.

### Planned
- Network v1.1 improvements (session throughput and backpressure tuning).
- Core host consistency/performance expansion for production gameplay scenarios.
- Plugin DX and ops tooling enhancements for faster troubleshooting.

## 1.5.0-rc.2 - 2026-02-21

### Added
- Standalone maturity + parity hardening:
  - integration smoke parity for `scan/reload/doctor/profile`,
  - deterministic reload/tick-loop smoke coverage.
- Configurable, schema-versioned security thresholds:
  - adapter timeout/rate/payload limits,
  - fault budget thresholds,
  - validated fallback to safe defaults on invalid/unsupported config.
- Expanded command-level integration tests:
  - adapter list/invoke JSON schema checks,
  - positive/negative permission gate scenarios.
- Metrics + audit stability:
  - bounded in-memory adapter audit retention model,
  - adapter counters + audit snapshots in `profile --json` and `doctor --json`.
- Host API extraction (first cut):
  - explicit `host-api` domain ports for player/world/entity contracts,
  - host access adapter contract coverage tests.
- Release artifact hardening:
  - strict release bundle set (standalone + cli + demo),
  - generated `ARTIFACTS.txt`, `ARTIFACTS.json`, `SHA256SUMS.txt`,
  - release CI auto-generates and uploads artifact/checksum manifests.
- Operator UX improvements:
  - `doctor/profile` output modes: `--pretty` and `--compact`,
  - structured recommendation objects with automation-friendly codes/severity.

### Changed
- Standalone command surface now supports:
  - `doctor [--json] [--pretty|--compact]`
  - `profile <id> [--json] [--pretty|--compact]`
- Recommendation payloads changed from plain strings to structured objects:
  - `code`, `severity`, `message`.

### Migration Deltas (v1.5.0-rc.1 -> rc.2)
- Diagnostics automation consumers should migrate recommendation parsing:
  - old: string lines,
  - new: structured recommendation objects with stable codes.
- Operator scripts can now choose JSON rendering mode:
  - `--pretty` for readable logs,
  - `--compact` for machine/pipe workflows.
- Security tuning should move to schema-backed config keys (see standalone config + docs) instead of implicit defaults.
- Release automation should consume generated release manifests/checksums rather than deriving asset lists ad hoc.

## 1.0.0 - 2026-02-20

### Added
- API freeze marker and constants in `clockwork-api` (`GigaApiVersion`).
- Versioned API documentation for frozen contracts:
  - `docs/api/v1.0.0.md`
  - `docs/migrations/v1.0.md`

### Changed
- DSL default plugin version updated to `1.0.0`.
- `gigaPlugin(...)` now supports explicit `dependencySpecs: List<DependencySpec>` for stable dependency declarations.
- Security hardening expanded:
  - adapter SAFE/FAST policy behavior documented and enforced,
  - adapter quotas/concurrency/audit logging improved,
  - net DoS guardrails added (session caps, request rate caps, JSON payload limits),
  - reproducible abuse/security matrix added in `docs/security/hardening-matrix.md`.
- Command runtime hardened:
  - duplicate command IDs are rejected by default,
  - lifecycle-safe command operations added (`registerOrReplace`, `unregister`).
- Event dispatch behavior is now configurable (`exact` vs `polymorphic`).
- Storage durability improved:
  - checksum files for persisted state,
  - backup-based recovery when primary state is corrupted.
- Performance hardening for prod:
  - hotpath optimizations in adapter invoke path (token validation cache + zero-payload fast path),
  - net hotpath reductions (fast JSON detection + prebuilt rate-limit response),
  - explicit v1 performance target document (`docs/performance/targets-v1.md`),
  - CI split with dedicated `performanceBaseline` gate step.
- API compatibility gate added to release flow via `:clockwork-api:apiCheck`.

### Removed
- `HostServerSnapshot.bukkitVersion` removed from API/host models.

### Security
- Host access and host bridge adapter actions now enforce manifest permissions.

## 0.1.0-rc.2 - 2026-02-20

### Added
- Runtime metrics subsystem for profiling plugin execution (`RuntimeMetrics`).
- `/giga profile <plugin>` now reports real runtime metrics:
  - active task count and task ids,
  - per-system runs/failures/avg/max runtime,
  - per-scheduler-task runs/failures/avg/max runtime.
- New reload stress test for repeated reload stability.
- Additional security-focused tests for manifest and runtime path validation.

### Changed
- Runtime scheduler now emits execution timing/failure observations for metrics.
- Bridge tick loop now records per-system tick runtime and failures.
- Manifest security tightened with additional limits and checks:
  - forbidden main class packages (`java.*`, `kotlin.*`),
  - maximum dependency and permission counts,
  - version-range length constraints.

### Build and verification
- `test + integrationSmoke` validated successfully on rc.2.

## 0.1.0-rc.1 - 2026-02-20

### Added
- Dependency resolver with API compatibility checks and semantic version range validation.
- Safe reload transaction with rollback and checkpoint restore.
- `/giga doctor --json` diagnostics including dependency and compatibility state.
- Security hardening for manifest validation and plugin jar path restrictions.
- Extended unit/integration test coverage (resolver, version ranges, manifest parsing/validation, runtime rollback, security checks).

### Changed
- Project version bumped to `0.1.0-rc.1`.
- `/giga reload` now returns explicit success/rollback/failure status with reason.

### Build
- New `releaseCandidate` task to run verification and collect RC artifacts.



