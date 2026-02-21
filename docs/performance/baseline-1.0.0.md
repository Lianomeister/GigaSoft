# Performance Baseline Result (1.0.0)

Run timestamp: 2026-02-21 01:26:10 +01:00

Command:

```bash
./gradlew --no-daemon clean performanceBaseline
```

Result:

- `BUILD SUCCESSFUL`
- `performanceBaseline` gate passed

Interpretation against `docs/performance/targets-v1.md`:

- Runtime adapter threshold checks: passed
- Core HostState threshold checks: passed
- Net hotpath threshold checks: passed

Notes:

- This run is suitable as pre-release evidence for v1.0.0.
