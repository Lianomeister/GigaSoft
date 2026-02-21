# Security Hardening Matrix (Standalone)

This matrix defines the expected behavior for adapter/runtime and net abuse scenarios.

## Adapter Policies

## SAFE mode

- Payload validation: enabled
- Per-adapter quota (`maxCallsPerMinute`): enabled
- Per-plugin quota (`maxCallsPerMinutePerPlugin`): enabled when configured
- Per-adapter concurrency guard (`maxConcurrentInvocationsPerAdapter`): enabled when configured
- Invocation timeout (`invocationTimeoutMillis`): enabled
- Audit logging (`[adapter-audit]`): enabled by default

## FAST mode

- Payload validation: enabled
- Quotas: bypassed
- Concurrency guard: bypassed
- Invocation timeout: bypassed
- Audit logging: still available (if enabled)

Use FAST only in trusted/internal deployments.

## Net Hardening

- Frame size cap (`maxFrameBytes`)
- Text line cap (`maxTextLineBytes`)
- Read timeout (`readTimeoutMillis`)
- Session caps (`maxConcurrentSessions`, `maxSessionsPerIp`)
- Request rate caps (`maxRequestsPerMinutePerConnection`, `maxRequestsPerMinutePerIp`)
- JSON payload limits (`maxJsonPayload*`)
- Audit logging (`[net-audit]`)

## Reproducible Abuse Tests

Run:

```bash
./gradlew --no-daemon :clockwork-runtime:test --tests "*RuntimeModAdapterRegistryTest"
./gradlew --no-daemon :clockwork-net:test --tests "*StandaloneNetServerTest"
```

Focus cases covered:

- adapter timeout abuse
- adapter quota abuse (per-adapter and per-plugin)
- adapter concurrency abuse
- adapter audit log emission
- net oversized frame and line abuse
- net request burst rate abuse
- net per-IP session abuse
- net oversized JSON payload abuse
