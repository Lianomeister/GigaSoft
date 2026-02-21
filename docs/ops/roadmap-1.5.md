# Clockwork v1.5 Fullversion Roadmap

## Zielbild
Clockwork `v1.5.0` wird der erste stabile Standalone-Release mit mod-like Plugin-Power, sauberer Operator-UX und produktionsreifen Sicherheits-/Stabilitaetsgrenzen.

## Produktziele
- Standalone ist der primäre, offiziell supportete Laufzeitpfad.
- Plugin-Entwickler koennen komplexe Gameplay-Systeme in Kotlin schnell bauen und sicher reloaden.
- Operatoren erhalten deterministische Diagnose, reproduzierbare Releases und sichere Defaults.
- API bleibt in `1.x` moeglichst kompatibel und erweiterbar.

## Release-Prinzipien
- Stabilitaet vor Feature-Menge.
- Additive API zuerst, Breaking Changes nur mit Migration-Plan.
- Jede neue Funktion braucht Tests, Doku, CLI/JSON-Operator-Sicht.

## Scope fuer v1.5.0 GA

### Muss (GA-Blocker)
- [x] Vollstaendige Stabilisierung von `scan/reload/doctor/profile` im Standalone-Core.
- [x] Harte Sicherheitsgrenzen komplett konfigurierbar, validiert und dokumentiert.
- [x] Deterministische Tick-/Reload-Pipeline unter Last.
- [x] Release-Artefakte + Checksums reproduzierbar in CI.
- [x] Migrations-Guide `v1.5.0-rc.1 -> v1.5.0` und `v1.1 -> v1.5.0`.

### Sollte
- [x] Starke DX-Verbesserungen im VS-Code Addon.
- [x] Bessere Plugin-Diagnostik inkl. Empfehlungen und Fehlerklassen.
- [x] Mehr Host-API Kontrakte fuer world/player/entity/inventory use-cases.

### Kann
- [x] Erste Preview fuer visuelle Debug-Ansichten und Profil-Exports.
- [x] Erweiterte Beispiel-Plugins fuer Maschinen-/Netzwerk-/UI-Patterns.

## Workstreams (GA)

### 1. Core Stabilitaet und Determinismus
- [x] Tick-loop Jitter-Messung + harte Budgets pro Plugin.
- [x] Reload-Transaction weiter haerten (state checkpoint diff + rollback quality metrics).
- [x] Deterministische Reihenfolge fuer systems/tasks/events dokumentieren und testen.
- [x] Verbesserte Fehlerisolation: plugin-local crash containment ohne Core-Absturz.

### 2. Security Hardening 2.0
- [x] Alle Runtime- und Adapter-Schwellenwerte nur aus Config/CLI + Validator.
- [x] Fault-budget Eskalationsstufen (warn -> throttle -> isolate) mit Telemetrie.
- [x] Payload-Policy Profile (`strict`, `balanced`, `perf`) fuer Operatoren.
- [x] Audit Retention fest begrenzen (entry count, age, memory budget).

### 3. API/SDK Reifegrad
- [x] API-Review fuer `clockwork-api` und `clockwork-host-api` (naming, nullability, contracts).
- [x] Additive DSL-Verbesserungen fuer commands/events/host mutations.
- [x] Contract-Tests fuer alle Public Interfaces.
- [x] API-Kompatibilitaetsbericht als Pflicht in CI.

### 4. Host API Ausbau
- Konsolidierte Ports fuer:
  - player lifecycle + permissions + status
  - world data/time/weather/block access
  - entity query/mutate/remove
  - inventory read/write primitives
- HostAccess Mutation-Batches mit besseren Fehlercodes.
- Dokumentierte Permission-Matrix je Host-Aktion.

### 5. Plugin Runtime und Lifecycle
- Lifecycle Hooks erweitern (`beforeEnable`, `afterDisable`, `beforeReload`).
- Hot-reload Leckschutz (classloader reference tracking + leak report).
- Plugin dependency graph diagnostics (`missing`, `incompatible`, `cycle`).
- Slow-system quarantining mit automatischer Rejoin-Strategie.

### 6. Commands, Diagnostics und Operator UX
- `doctor/profile` Recommendation-Codes finalisieren und versionieren.
- Output-Profile: `--pretty`, `--compact`, `--json` in allen relevanten Ops-Kommandos.
- Neue Diagnosekommandos:
  - `diag export`
  - `diag tail`
  - `diag budget`
- Einheitliches Fehlerformat fuer CLI + JSON.

### 7. Performance und Soak
- Baselines fuer:
  - tick time (p50/p95/p99)
  - reload latency
  - adapter invoke latency
  - memory growth nach N reloads
- 6h Soak als Pflicht vor GA.
- Regression-Alarmgrenzen in CI.

### 8. Packaging, Release, Supply Chain
- Release-Bundle immer mit:
  - `clockwork-standalone`
  - `clockwork-cli`
  - `clockwork-demo-standalone`
  - `ARTIFACTS.txt`
  - `ARTIFACTS.json`
  - `SHA256SUMS.txt`
- Reproduzierbarer Build-Report pro Release.
- Release Notes Template mit Migration, Breaking Notices, Known Limits.

### 9. Documentation und Website
- Docs-Index komplett auf Stand `v1.5.0`.
- Operator Playbook fuer incident handling + rollback.
- 5 neue Tutorials:
  - advanced reload-safe patterns
  - machine pipeline design
  - adapter security best practices
  - network channel design
  - performance tuning cookbook

### 10. Tooling und VS Code Addon
- Manifest Lint-Regeln erweitern (dependency ranges, permissions, IDs).
- Mehr Code Actions fuer CommandSpec/EventSubscriptionOptions.
- Snippet-Sets fuer Maschinen, Adapter und Host-Mutationen.
- Release-Check im Addon gegen GitHub Tags.

## Feature-Ideen Backlog (nach Prioritaet)

### A. High Impact (post-GA 1.5.x)
- Plugin sandbox profiles (trusted/untrusted).
- Deterministic simulation mode fuer integration tests.
- Built-in plugin benchmark harness.
- Persistent recipe graph inspector.
- State diff visualizer fuer reload debugging.
- Command permission simulator fuer admins.
- Host event replay aus audit logs.
- Plugin dependency lock file.
- Runtime memory pressure advisor.
- Canary plugin rollout mode.

### B. Medium Impact
- Namespaced scheduler pools.
- Plugin-owned metrics dashboards export (Prometheus-like text output).
- World partition update queues.
- Async snapshot compaction worker.
- Structured plugin health score.
- Policy packs fuer adapter security presets.
- Lightweight data schema registry.
- Deterministic random source per plugin/system.
- Queue backpressure hints in profile output.
- CLI session recording and replay.

### C. Creator Experience
- DSL module templates fuer common gameplay archetypes.
- Recipe DSL mit graph validation.
- Machine statechart helper API.
- UI flow DSL fuer menu/dialog flows.
- Plugin scaffolding presets (`basic`, `machine`, `network`, `ops-heavy`).
- Test fixtures fuer fake player/world/entity contexts.
- Golden snapshot testing helpers.
- Plugin contract docs auto-generation from code.
- Curated examples repository sync.
- Error message catalog mit recommended fixes.

### D. Ops and Platform
- Signed release metadata.
- Crash dump minimizer with sensitive field redaction.
- Live config reload fuer security thresholds.
- Health endpoint fuer headless deployments.
- Docker hardening profile (read-only rootfs option).
- Rolling world backup policy presets.
- Release channel management (`stable`, `rc`, `nightly`).
- Compatibility matrix exporter (plugins x runtime).
- Long-run memory leak detector task.
- Disaster recovery runbook automation.

## Milestones bis GA

### Milestone M1: Stabilize (Woche 1)
- Core determinism fixes + flaky test elimination.
- Security config validator finalisieren.
- Acceptance:
  - Zwei aufeinanderfolgende grüne full-gate runs.
  - Keine offenen P0 Bugs.

### Milestone M2: Harden (Woche 2)
- Soak/perf baselines final.
- Diagnostics und recommendation codes final.
- Acceptance:
  - `soakTest` gruen.
  - Baseline-Schwellen dokumentiert.

### Milestone M3: Polish (Woche 3)
- Docs, tutorials, website, addon finishing.
- Migration + release notes finalisieren.
- Acceptance:
  - Alle GA-Dokumente veroeffentlichbar.
  - CLI/JSON examples verifiziert.

### Milestone M4: Release (Woche 4)
- Final candidate build.
- Tag + GitHub release + checksums.
- Acceptance:
  - Vollstaendiges Artefakt-Set publiziert.
  - Keine offenen P0/P1 Bugs.

## GA Exit-Kriterien (hart)
- `./gradlew --no-daemon clean :clockwork-api:apiCheck test performanceBaseline standaloneReleaseCandidate integrationSmoke :clockwork-standalone:smokeTest` ist gruen.
- `./gradlew --no-daemon soakTest` ist gruen.
- Sicherheitsgrenzen voll konfigurierbar + validiert + dokumentiert.
- Admin Permission Gates und JSON Outputs end-to-end getestet.
- Release Notes + Migration Deltas final.
- Alle Release-Artefakte inkl. Checksums auf GitHub vorhanden.

## Was explizit aus alter Roadmap entfernt wurde
- RC-spezifische Zwischenpunkte und bereits abgeschlossene rc.2 Tracking-Abschnitte.
- Doppelte Statuslisten, die den GA-Fokus verwischen.
- Historische Zwischen-Checklisten ohne aktuellen Delivery-Wert.
