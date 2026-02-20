# Changelog

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
