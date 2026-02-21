# GigaSoft 1.1 Roadmap

## Scope

- Keep API 1.x compatibility stable while improving runtime and standalone behavior.
- Focus on production readiness for plugin-heavy deployments.

## Workstreams

### 1) Network
- Improve request pipeline throughput under sustained load.
- Add stricter backpressure behavior for overloaded connections.
- Extend performance and abuse tests with reproducible scenarios.

### 2) Core Gameplay Host
- Harden atomic updates for worlds/entities/inventories under concurrent mutation pressure.
- Reduce tick-loop overhead in high-entity simulations.
- Add deterministic restore tests for larger snapshots.

### 3) Runtime/Plugin Execution
- Optimize event dispatch and adapter invoke hotpaths further.
- Improve runtime diagnostics for slow systems/adapters.
- Tighten plugin fault-isolation behavior in long-running sessions.

### 4) DX and Operations
- Expand docs with scenario-based troubleshooting.
- Improve CLI/operator workflows for smoke/soak/perf runs.
- Keep CI release gates strict: `apiCheck`, `test`, `performanceBaseline`, `standaloneReleaseCandidate`, `smokeTest`, `soakTest`.

## Definition of Done for 1.1

- All release gates green from clean checkout.
- No API compatibility regressions against 1.0 contract.
- Measurable performance improvements documented in a new baseline report.
- Migration notes published for any behavior changes.
