# GigaSoft 1.5 Roadmap

## Scope

- Focus 1.5 on Plugin API evolution into a real modding framework.
- Keep API compatibility within 1.x wherever possible, with additive-first design.
- Prioritize production-ready contracts over one-off features.

## Workstreams

### 1) Command/API UX 2.0
- Introduce `CommandSpec` with:
  - permission
  - args schema
  - cooldown
  - rate-limit
  - usage/help
- Add typed args parser plus auto-help and auto-completion contracts.
- Add deterministic command middleware chain (auth, validation, audit).

### 2) Events 2.0
- Add priorities and `ignoreCancelled`.
- Add async-safe event channel and strict main-thread guards.
- Add event tracing/profiling API for plugin authors.
Status:
- Implemented in API/runtime (`EventSubscriptionOptions`, priority ordering, `publishAsync`, strict main-thread guard mode, trace snapshot/reset API).
- Covered by runtime tests (`RuntimeMessagingTest`) including deterministic priority order, cancellation filtering, off-thread guard behavior, and trace metrics.

### 3) Data/State API
- Add transactional world/entity/inventory mutation API.
- Add atomic batch updates and rollback hooks.
- Improve persistence snapshots and migration tooling.
Status:
- Implemented baseline transactional mutation batches via `HostAccess.applyMutationBatch(...)` with atomic rollback semantics in core.
- Added plugin helper `ctx.applyHostMutationBatch(...)` with rollback callback hook.
- Covered by runtime/core/api tests for permission gating, atomic success/rollback behavior, and rollback callback flow.
- Persistence upgraded to schema `v2` envelope metadata (checksum + migration history) with deterministic migration reports (`v0 -> v1 -> v2`) and in-place rewrite on load.

### 4) Assets/Content Pipeline
- Add resource pack bundling API (textures/models/animations/sounds).
- Add validation and build-time checks (missing refs, format mismatch).
- Add runtime hot-reload with deterministic fallback behavior.
Status:
- Implemented `RegistryFacade` asset expansion (`registerAnimation`, `registerSound`) and bundle/validation APIs (`validateAssets`, `buildResourcePackBundle`).
- Runtime registry now validates namespace, extension/format compatibility, and missing references before producing bundle artifacts.
- Runtime load/reload now hard-fails invalid asset packs and relies on existing reload rollback transaction for deterministic fallback to last-good plugin build.

### 5) Network API for Plugins
- Add stable plugin messaging channels.
- Add payload schema/versioning and backpressure contracts.
- Add quotas and per-plugin throughput controls.
Status:
- Implemented plugin messaging channels via `PluginNetwork` (`registerChannel`, `send`, `subscribe`, `channelStats`).
- Added schema-versioned payload contracts (`PluginChannelSpec`, `PluginMessage`, `PluginMessageResult`, `PluginMessageStatus`).
- Runtime enforces per-channel payload limits, in-flight backpressure, and per-plugin throughput quotas.

### 6) Security and Isolation
- Add finer-grained host permissions.
- Add capability-based adapter execution.
- Add fault-budget policy per plugin and isolation telemetry.
Status:
- Added explicit host mutation batch gate permission: `host.mutation.batch` (in addition to per-operation permissions).
- Adapter execution now supports permission-scoped controls:
  - adapter allow-list (`adapter.invoke.<id>` / `adapter.invoke.*`)
  - capability allow-list (`adapter.capability.<name>` / `adapter.capability.*`).
- Added plugin fault-budget telemetry in runtime profiles/doctor diagnostics (`faultBudget` snapshot with used/remaining/tripped and source breakdown).

### 7) DX and Tooling
- Improve VS Code extension:
  - diagnostics
  - quick-fixes
  - code actions
  - command/event snippets
- Expand doc site with full 1.5 reference and migration guide 1.1 -> 1.5.
- Add plugin-focused recommendations in `doctor` and `profile`.
Status:
- VS Code extension expanded with manifest + Kotlin diagnostics, quick fixes/code actions, and new snippets for CommandSpec/Event 2.0/Plugin Network.
- Added versioned docs page `docs/api/v1.5.0.md` and migration guide `docs/migrations/v1.5.md`.
- `doctor`/`profile` now surface plugin-focused recommendations (human + JSON output) based on slow systems, adapter hotspots, isolation state, and fault budget pressure.

### 8) Release Gates 1.5
- API compatibility gate.
- Security matrix and abuse tests.
- Performance baseline thresholds.
- Smoke + soak + deterministic integration suite.

## Definition of Done for 1.5

- 1.5 plugin API features are documented with reference + examples.
- Migration guide from 1.1 to 1.5 is published and validated.
- Release gates are green from clean checkout.
- API compatibility report is clean (or documented intentional exceptions).
- Security/performance baselines are reproducible and attached to release notes.

