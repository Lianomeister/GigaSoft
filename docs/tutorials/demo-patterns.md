# Demo Patterns: Machine / Network / UI

Goal: use the bundled `clockwork-demo` plugin as pattern reference for real gameplay systems.

## Machine Pattern

Command:

```bash
run clockwork-demo demo-machine status
run clockwork-demo demo-machine tick 20
run clockwork-demo demo-machine reset
```

What it demonstrates:
- deterministic machine tick progression
- explicit reset behavior for test loops
- runtime-safe machine state inspection

## Network Pattern

Command:

```bash
run clockwork-demo demo-network-burst 25 metrics
```

What it demonstrates:
- channel fan-out burst behavior
- simple load pattern for diagnostics/profile checks
- message schema re-use on plugin network channel

## UI Pattern

Command:

```bash
run clockwork-demo demo-ui-tour Alex
```

What it demonstrates:
- chained UI flow (`notify`, `actionBar`, `menu`, `dialog`)
- player-availability handling with stable error code (`E_UI`)
- minimal end-to-end creator UX for in-game interaction flows

## Recommended Validation

1. `profile clockwork-demo --json --pretty`
2. `diag export`
3. verify recommendation codes and hotspot counters after pattern commands
