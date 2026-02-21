# Clockwork 1.5 Roadmap

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

## 1.5.0-rc.2 Proposed Additions

### Priority A (must-have for rc.2)

1. Standalone Core Maturity Pass
- Promote standalone path from preview to officially supported rc path.
- Add parity checks for `scan/reload/doctor/profile` between Paper bridge and standalone.
- Acceptance:
  - Standalone boot + demo plugin flow documented and tested in CI.
  - Deterministic tick-loop and reload behavior confirmed in integration smoke.
Status:
- Standalone RC support is now explicitly documented in `README.md`.
- Added smoke-tagged parity test (`StandaloneParitySmokeTest`) covering scan/reload/doctor/profile and demo flow boot checks.
- Added root `integrationSmoke` pipeline task and wired CI smoke stage to run it.

2. Configurable Security Thresholds
- Move hardcoded adapter/runtime thresholds to config:
  - timeout
  - per-window rate limits
  - payload limits
  - fault budget thresholds
- Acceptance:
  - Config schema versioned.
  - Runtime validates config and falls back to safe defaults.
  - Test matrix covers valid/invalid/edge configs.
Status:
- Added schema-versioned security thresholds (`securityConfigSchemaVersion=1`) in standalone config flow.
- Added runtime-backed normalization/validation with safe fallback defaults (`RuntimeSecurityThresholdsValidator`).
- Adapter thresholds + fault budget thresholds are now configurable and injected into runtime.
- Added test coverage for valid, invalid, and unsupported schema edge cases.

3. Command Integration Test Expansion
- Add command-level integration tests for:
  - `/giga adapters <plugin> [--json]`
  - `/giga adapter invoke ... [--json]`
  - permission gates (`clockwork.admin.*`)
- Acceptance:
  - Positive + negative authorization cases.
  - JSON output schema assertions.
Status:
- Added standalone command-parity integration coverage for adapter command flows:
  - `adapters <plugin> [--json]` JSON schema assertions
  - `adapter invoke <plugin> <adapterId> <action> [--json]` JSON schema assertions
- Added positive + negative authorization tests via adapter permission scopes (`adapter.invoke.*`) in standalone runtime.

4. Metrics + Audit Stability
- Add adapter audit retention policy and bounded memory model.
- Expose adapter counters in both `profile --json` and diagnostics snapshots.
- Acceptance:
  - No unbounded metric growth under sustained invoke load.
  - Soak test includes repeated invoke/reload cycles.
Status:
- Added bounded in-memory adapter audit retention policy with configurable limits:
  - max entries per plugin
  - max entries per adapter
  - max entry age (ms)
- Adapter counters are now explicit snapshots in both:
  - `profile --json` (`profile.adapterCounters`)
  - `doctor --json` diagnostics (`diagnostics.pluginPerformance.<plugin>.adapterCounters`)
- Added retained audit snapshot surfaces in both profile and diagnostics (`adapterAudit`) with retention metadata.
- Added runtime stability tests for sustained adapter invoke pressure to confirm bounded retained audit memory.
- Added standalone soak coverage that exercises repeated invoke/reload cycles and asserts bounded audit retention post-cycle.

### Priority B (should-have in rc.2 if time allows)

5. Host API Extraction (first cut)
- Start explicit `host-api` contracts to reduce Paper coupling in shared runtime paths.
- Acceptance:
  - New interfaces for world/player/entity access introduced.
  - Core/runtime compile path stays host-agnostic.
Status:
- Introduced explicit host domain ports in `clockwork-host-api`:
  - `HostPlayerPort`
  - `HostWorldPort`
  - `HostEntityPort`
- `HostBridgePort` now composes these domain ports instead of carrying all contracts monolithically.
- Added host-api adapter coverage test to validate world/player/entity mapping through `asHostAccess()`.
- Runtime/core compile path remains host-agnostic (`clockwork-runtime` has no dependency on Paper bridge artifacts).

6. Release Artifact Hardening
- Ensure release bundle always includes:
  - standalone jar
  - cli jar
  - demo jar
- Add checksum manifest (`sha256`) in release output.
- Acceptance:
  - Artifact list and checksums auto-generated in CI.
Status:
- `standaloneReleaseCandidateArtifacts` now enforces exactly 3 jars in bundle:
  - `clockwork-standalone-*`
  - `clockwork-cli-*`
  - `clockwork-demo-standalone-*`
- Gradle release output now includes:
  - `ARTIFACTS.txt`
  - `ARTIFACTS.json`
  - `SHA256SUMS.txt`
- CI release workflow (`release-assets.yml`) now auto-generates and uploads artifact list + checksums for the final release asset filenames.

### Priority C (nice-to-have)

7. Operator UX Improvements
- Add `--pretty` and `--compact` output modes for `doctor/profile`.
- Add recommendation codes in diagnostics for easier automation.
Status:
- Added operator output modes to standalone commands:
  - `doctor [--json] [--pretty|--compact]`
  - `profile <id> [--json] [--pretty|--compact]`
- Added structured recommendation objects for diagnostics/profile outputs:
  - `code`
  - `severity`
  - `message`
- Introduced stable recommendation codes for automation:
  - `SYS_SLOW`
  - `ADAPTER_HOTSPOT`
  - `SYSTEM_ISOLATED`
  - `FAULT_BUDGET_PRESSURE`

## rc.2 Exit Criteria (recommended)

- `test`, `integrationSmoke`, and standalone smoke all green from clean checkout.
- Security thresholds fully config-driven and documented.
- Admin command permissions + JSON output tested end-to-end.
- Release notes include migration deltas from `v1.5.0-rc.1` to rc.2.
Status:
- Verified on 2026-02-21 via:
  - `./gradlew --no-daemon clean test integrationSmoke :clockwork-standalone:smokeTest`
- Security thresholds are config-driven and documented in:
  - `clockwork-standalone/standalone.example.properties`
  - `README.md`
  - `docs/ops/release-readiness.md`
- Admin permission + JSON output flows are covered in integration tests:
  - `StandaloneLifecycleIntegrationTest` (adapter invoke/list + allow/deny authorization + JSON assertions)
- Release notes now include explicit migration deltas:
  - `CHANGELOG.md` (`1.5.0-rc.2` section)

## 1.5.0 Full Release Roadmap (post-rc.2)

### Objective
- Promote `1.5.0-rc.2` to stable `1.5.0` with production-focused hardening, docs completion, and zero known release blockers.

### Milestone 1: Stabilization and Regression Sweep
- Run clean-checkout gate on CI and at least one fresh local clone:
  - `./gradlew --no-daemon clean :clockwork-api:apiCheck test performanceBaseline standaloneReleaseCandidate integrationSmoke :clockwork-standalone:smokeTest`
- Run soak validation with repeated invoke/reload cycles:
  - `./gradlew --no-daemon soakTest`
- Triage and fix any flaky tests until two consecutive green runs.
- Acceptance:
  - No flaky failures in two consecutive full gate runs.
  - No known crash-level runtime defects.

### Milestone 2: Docs and Operator Readiness
- Finalize operator docs for:
  - security threshold schema + fallback behavior
  - `doctor/profile` JSON modes and recommendation codes
  - release artifact manifests and checksum verification workflow
- Refresh website/docs index and ensure command examples match rc.2/final binaries.
- Acceptance:
  - `README.md`, `docs/ops/release-readiness.md`, and migration docs are internally consistent.
  - All documented commands verified against current standalone runtime.

### Milestone 3: API and Compatibility Lock
- Freeze `clockwork-api` surface for `1.5.0`.
- Re-run API compatibility gate and document any intentional exceptions.
- Validate plugin migration path from `v1.1` and `v1.5.0-rc.1`.
- Acceptance:
  - `:clockwork-api:apiCheck` green with no undocumented breaks.
  - Migration guide examples compile and run.

### Milestone 4: Release Packaging and Provenance
- Build final release bundle with:
  - standalone jar
  - cli jar
  - demo jar
  - `ARTIFACTS.txt`
  - `ARTIFACTS.json`
  - `SHA256SUMS.txt`
- Verify CI release asset workflow output matches local bundle checksums.
- Acceptance:
  - Artifact list complete and checksum-verifiable.
  - Release notes contain upgrade/migration deltas and known limitations.

### Final 1.5.0 Exit Criteria
- All gates green from clean checkout:
  - `test`
  - `integrationSmoke`
  - `:clockwork-standalone:smokeTest`
  - `soakTest`
  - `performanceBaseline`
  - `standaloneReleaseCandidate`
- Security thresholds fully config-driven, validated, and documented.
- Admin command permission + JSON output paths verified end-to-end.
- No open P0/P1 issues in release tracker.

