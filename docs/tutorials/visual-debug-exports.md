# Visual Debug Views + Profile Exports (Preview)

Goal: generate a first visual diagnostics view and machine-readable profile exports from standalone runtime.

## Commands

1. Full diagnostics export (JSON + HTML):

```bash
diag export
```

Default output directory:
- `data/diagnostics/latest`

Generated files:
- `diagnostics.json`
- `index.html`

2. Compact JSON export:

```bash
diag export --compact
```

3. Export to custom path:

```bash
diag export C:\tmp\clockwork-diag
```

4. Export profile for a single plugin:

```bash
profile export clockwork-demo
```

Optional compact mode:

```bash
profile export clockwork-demo --compact
```

## What the Preview HTML Shows

- Plugin list
- Slow-system count
- Adapter-hotspot count
- Fault-budget usage
- Recommendation codes including severity + error class

## Typical RC Debug Flow

1. `doctor --json --pretty`
2. `profile <plugin> --json --pretty`
3. `diag export`
4. Open `index.html` and compare with `diagnostics.json`
