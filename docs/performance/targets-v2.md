# Performance Targets v2 (Prod Gate)

These targets are enforced by `performanceBaseline` plus `scripts/perf_v2_gate.py`.

Date: 2026-02-21

## Scope

- Cold Start Latency (`standalone.cold_start.ms`)
- Tick Jitter (`standalone.tick_jitter.ms`)
- Reload Latency (`runtime.reload.latency_ms`)
- Chunk/World Workload (`core.chunk.write_ms`, `core.chunk.read_ms`)
- Runtime/Core/Net hot paths (`runtime.adapter.invoke.per_invoke_micros`, `core.hoststate.*`, `standalone.net.ping.per_request_micros`)

## Baseline File

- `docs/performance/targets-v2.json`

## Gate Commands

```bash
./gradlew --no-daemon performanceBaseline
python3 scripts/perf_v2_gate.py --baseline docs/performance/targets-v2.json --results-root . --report build/reports/performance/perf-v2-report.md
```
