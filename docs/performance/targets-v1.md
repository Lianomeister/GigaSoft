# Performance Targets v1 (Prod Gate)

These targets are enforced by `performanceBaseline` tests and act as regression thresholds.

Date: 2026-02-20

## Runtime Adapter Hotpath

Test: `RuntimeAdapterPerformanceTest`

- `perInvokeMicros < 500.0`

## Core HostState Hotpaths

Test: `StandaloneHostStatePerformanceTest`

- `joinMedian < 250 ms` for 2,000 joins
- `moveMedian < 150 ms` for 2,000 moves
- `spawnMedian < 350 ms` for 5,000 spawns

## Net Hotpath

Test: `StandaloneNetPerformanceTest`

- `perPingMicros < 1500.0`
- `serverAvgReqNs < 500000`

## Gate Command

```bash
./gradlew --no-daemon performanceBaseline
```

CI must stay green on this gate before release.
