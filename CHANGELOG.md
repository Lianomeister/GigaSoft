# Changelog

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
