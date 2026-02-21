# Changelog

## Unreleased (1.1.0)

### Changed
- Project version moved to `1.1.0-SNAPSHOT` after the `1.0.0` release.
- Default standalone server version moved to `1.1.0-SNAPSHOT`.
- DSL default plugin version moved to `1.1.0-SNAPSHOT` for active development.

### Added
- Plugin API ergonomic helpers:
  - reified `EventBus.subscribe<T>`,
  - reified `StorageProvider.store<T>`,
  - simplified command registration helpers without explicit `PluginContext` argument,
  - `PluginContext.hasPermission(...)` and `PluginContext.requirePermission(...)`.
  - typed command responses via `CommandResult` and `registerResult` / `registerOrReplaceResult`.
  - typed adapter payload parsing helpers on `AdapterInvocation` (`payloadString/Trimmed/Required/Int/Long/Double/Bool`).
  - expanded host gameplay API surface for mod-like plugins:
    - `createWorld`, `worldTime`, `setWorldTime`,
    - `findEntity`, `removeEntity`,
    - `movePlayer`,
    - `inventoryItem`, `givePlayerItem`.
  - new gameplay events: `GigaEntityRemoveEvent`, `GigaPlayerTeleportEvent`, `GigaWorldTimeChangeEvent`.

### Planned
- Network v1.1 improvements (session throughput and backpressure tuning).
- Core host consistency/performance expansion for production gameplay scenarios.
- Plugin DX and ops tooling enhancements for faster troubleshooting.

## 1.0.0 - 2026-02-20

### Added
- API freeze marker and constants in `gigasoft-api` (`GigaApiVersion`).
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
- API compatibility gate added to release flow via `:gigasoft-api:apiCheck`.

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


