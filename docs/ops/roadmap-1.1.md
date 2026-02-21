# GigaSoft 1.1 Roadmap

## Scope

- Keep API 1.x compatibility stable while expanding plugin author ergonomics.
- 1.1 is primarily focused on Plugin API improvements and plugin developer workflows.

## Workstreams

### 1) Plugin API (Primary)
- Add ergonomic, non-breaking API helpers for common plugin tasks.
- Improve event/command/storage APIs for less boilerplate and safer defaults.
- Expand API reference and migration notes with plugin-first examples.

### 2) Runtime/Plugin Execution
- Improve diagnostics around slow systems/adapters from plugin perspective.
- Tighten plugin fault isolation behavior in long-running sessions.

### 3) Host and Network (Secondary)
- Keep throughput and backpressure improvements as secondary scope.
- Prioritize changes that directly improve plugin-facing behavior/contracts.

### 4) DX and Operations
- Expand docs with scenario-based troubleshooting for plugin authors.
- Keep CI release gates strict: `apiCheck`, `test`, `performanceBaseline`, `standaloneReleaseCandidate`, `smokeTest`, `soakTest`.

## Definition of Done for 1.1

- All release gates green from clean checkout.
- No API compatibility regressions against 1.0 contract.
- Measurable performance improvements documented in a new baseline report.
- Migration notes published for any behavior changes.
