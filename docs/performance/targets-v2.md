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

## Minecraft Bridge Presets

Use these presets for `clockwork-standalone` when running the Minecraft bridge on the same host.

## Native Preset (No Upstream)

Use this when Clockwork should run without an external Paper/Spigot/Vanilla backend.

### Native Offline Bootstrap (1.21.11)

```bash
java -Xms1G -Xmx2G -jar clockwork-standalone-0.18.3.jar \
  --net-minecraft-mode native-offline \
  --net-minecraft-protocol-version 774 \
  --net-minecraft-status "Clockwork Native 1.21.11"
```

Notes:

- This removes the dependency on an upstream server process.
- Current state is native login bootstrap for protocol gating and telemetry.
- Full native play packet pipeline is the next step.

### Native Online (Mojang Session Verify)

```bash
java -Xms1G -Xmx2G -jar clockwork-standalone-0.18.3.jar \
  --net-minecraft-mode native-online \
  --net-minecraft-protocol-version 774 \
  --net-minecraft-status "Clockwork Native 1.21.11 Online" \
  --net-minecraft-online-auth-timeout-ms 7000
```

Optional local/mock session endpoint for integration testing:

```bash
--net-minecraft-online-session-url http://127.0.0.1:8080/session/minecraft/hasJoined
```

### Low-Latency (smaller bursts, faster connect fail)

Best for PvP/small servers where response time matters more than peak throughput.

```bash
java -Xms1G -Xmx2G -jar clockwork-standalone-0.18.3.jar \
  --net-minecraft-bridge-enabled true \
  --net-minecraft-bridge-host 127.0.0.1 \
  --net-minecraft-bridge-port 25565 \
  --net-minecraft-bridge-connect-timeout-ms 1000 \
  --net-minecraft-bridge-stream-buffer-bytes 16384 \
  --net-minecraft-bridge-socket-buffer-bytes 65536
```

### High-Throughput (larger buffers, steadier long sessions)

Best for many concurrent players and long voice/chat sessions.

```bash
java -Xms1G -Xmx2G -jar clockwork-standalone-0.18.3.jar \
  --net-minecraft-bridge-enabled true \
  --net-minecraft-bridge-host 127.0.0.1 \
  --net-minecraft-bridge-port 25565 \
  --net-minecraft-bridge-connect-timeout-ms 5000 \
  --net-minecraft-bridge-stream-buffer-bytes 131072 \
  --net-minecraft-bridge-socket-buffer-bytes 524288
```

### Verify Runtime Health

After start, run:

```text
bridge status
status
status --json
```

Check these fields:

- `reachable=true` in `bridge status`
- low growth of `connectFailures` in `net.minecraftBridge`
- stable `activeProxiedSessions` after player churn
- expected byte flow in `bytesClientToUpstream` and `bytesUpstreamToClient`

## Vanilla-Like Native Probe (Opt-In)

Run an integration probe with a `minecraft-protocol` client against native-offline mode:

```bash
CLOCKWORK_RUN_VANILLA_PROBE=1 ./gradlew --no-daemon :clockwork-net:test --tests "*native offline vanilla-like probe reaches play bootstrap when enabled*"
```

The probe script lives in `scripts/native_vanilla_probe.js` and validates that the connection reaches Play bootstrap (`login` + `position` packets).
