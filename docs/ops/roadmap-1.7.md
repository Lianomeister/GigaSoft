# Clockwork v1.7 Roadmap

## Zielbild
`v1.7` liefert den Sprung von stabiler Standalone-Basis zu einer produktionsreifen Creator- und Operator-Plattform mit erstklassigem Marketplace-Flow, härterer Isolation und deutlich besserer Performance unter Last.

## Leitprinzipien
- Sicherheit vor Komfort bei Runtime-Entscheidungen.
- Additive API-Entwicklung ohne unnötige Breaking Changes.
- Jede größere Funktion nur mit Tests, Doku und CLI/JSON-Sicht.
- Determinismus und Reproduzierbarkeit bleiben Release-Gates.

## Scope v1.7

### Muss (GA-Blocker)
- Plugin Dependency Resolver v2:
  - Unterstützt `required`, `optional`, `softAfter`, `conflicts`.
  - Liefert deterministische Load-Order inkl. Ursachenkette bei Resolver-Fehlern.
  - Gibt klare, maschinenlesbare Diagnosecodes + konkrete Fix-Hinweise aus (`doctor/profile --json` parity).
- Runtime Isolation v2:
  - Capability-Modell für Host-Zugriffe (`filesystem`, `network`, `commands`, `world-mutation`).
  - Striktere Plugin-Grenzen für Datei-/Netzwerkpfade (allowlist-basiert, deny by default).
  - Isolationsverletzungen erzeugen eindeutige Audit-Events und Diagnosecodes.
- Marketplace Submission Pipeline v1:
  - End-to-End Flow: `draft -> review -> approved -> published` inkl. nachvollziehbarer Status-Historie.
  - Validation-Gates für Metadaten, Artefakte, Version-Matrix und Mindest-Qualitätschecks (schema + policy).
  - Publish nur nach erfolgreicher Validierung; ungeprüfte Submissions bleiben strikt nicht-public.
- Performance Pass v2:
  - Messbare Budgets für Cold Start, Tick Jitter, Reload Latency und Chunk/World Workload (p50/p95).
  - Reproduzierbare Benchmarks in CI + Baseline-Vergleich mit Regression-Thresholds.
  - Soak-Fokus: Langlaufstabilität ohne unbounded Memory- oder Latency-Drift.
- API Contracts v1.7 Freeze:
  - `clockwork-api` und `clockwork-host-api` additive finalisieren, Breaking Changes nur per expliziter Ausnahme.
  - Automatischer Kompatibilitätsbericht in CI (Diff gegen v1.6/v1.x Baseline, inklusive Freigabe-Flag für Ausnahmen).
  - Contract-Tests + Dokumentationsabgleich verpflichtend vor RC.

### Sollte
- In-game Profiler UX Upgrade:
  - `/giga profile` liefert kompakte Ingame-Ansicht + detaillierte JSON-Ausgabe mit stabiler Feldstruktur.
  - Hotspot-Ansicht zeigt Top-N Verursacher (Plugin/System/Command/Event) nach CPU-Zeit und Aufrufhäufigkeit.
  - Trend-Ansicht zeigt Zeitfenster-Vergleich (z. B. letzte 5/15/60 Minuten) inkl. Delta-Indikatoren.
  - Klare Operator-Hinweise bei Budget-Überschreitungen (Warnung, Empfehlung, nächster Diagnoseschritt).
- Snapshot/Restore für Reload-Rollback Qualität:
  - Vor Reload wird ein konsistenter Runtime-Snapshot (Plugin-Status, relevante Registry-/Scheduler-Zustände) erstellt.
  - Bei Reload-Fehler erfolgt automatisches Restore auf den letzten stabilen Zustand ohne manuelle Eingriffe.
  - Rollback-Bericht enthält Ursache, betroffene Komponenten und Restore-Ergebnis (`success/partial/failed`).
  - Integrations- und Soak-Tests decken wiederholte Reload/Rollback-Zyklen mit deterministischem Endzustand ab.
- Erweiterte Host-API Kontrakte:
  - Additive Erweiterungen für `world/player/entity/inventory` mit klaren Nullability- und Fehler-Semantiken.
  - Mehr Kontextfelder in Responses/Events (z. B. world-id, dimension, source, tick-phase, correlation-id).
  - Einheitliche Contract-Tests für Host-Adapter + Runtime-Parity zwischen CLI, DSL und JSON-Diagnostik.
  - Doku-Updates mit Migrationshinweisen für neue optionale Felder.
- VS Code Addon DX Pass:
  - Neue Code Actions für häufige DSL-/Manifest-Probleme (fehlende Felder, ungültige Keys, schnelle Fix-Vorschläge).
  - Manifest-/DSL-Assistenz mit besserer Autocomplete-, Hover- und Validation-Abdeckung.
  - Snippet- und Template-Update für typische Plugin-Setups (commands/events/adapters/storage).
  - Konsistente Diagnosecodes zwischen Extension und Runtime-Diagnostik.

### Kann
- Visual Debug Views Preview:
  - Preview-UI für Timeline + Event/Task-Trace als lesbare Operator-Ansicht.
  - Export als JSON/CSV für externe Analyse und Incident-Nachbereitung.
  - Fokus auf niedrigen Runtime-Overhead und klaren Disable-Mechanismus.
- Operator Policy Packs:
  - Vordefinierte Presets `strict`, `balanced`, `perf`, `hardcore` mit dokumentierten Schwellenwerten.
  - Policy-Wechsel zur Laufzeit mit Audit-Event und klarer CLI/JSON-Sicht.
  - Möglichkeit, Presets als Ausgangspunkt für projektspezifische Policies zu überschreiben.
- Canary Rollouts:
  - Stufenweise Plugin-Aktivierung für Teilmengen/Segmente mit Telemetrievergleich gegen Baseline.
  - Automatischer Fallback bei Budget-/Fehler-Schwellenüberschreitung.
  - Rollout-Status und Rollback-Gründe in `doctor/profile` und Audit-Log sichtbar.

## Workstreams

## 1. Runtime & Isolation
- Capability Enforcement über alle Host-Zugriffe vereinheitlichen.
  - Jede sensitive Host-Operation hat einen expliziten Capability-Checkpoint.
  - Keine stillen Fallbacks: fehlende Berechtigung erzeugt deterministischen Fehlercode.
- Plugin-scope Limits für Dateizugriff/Netzwerk weiter härten.
  - Dateisystem: allowlist je Plugin + Pfadnormalisierung gegen Traversal.
  - Netzwerk: host/port/protocol constraints + verbindliche Timeout- und Rate-Limits.
- Crash Containment + Fault Budget Eskalation feiner abstufen.
  - Eskalationsstufen `warn -> throttle -> isolate` mit klaren Trigger-Schwellen.
  - Eskalationsentscheidungen als Audit-Trail und Diagnoseausgabe persistieren.
- Acceptance:
  - Bösartige Plugins können Core nicht destabilisieren.
  - Isolationsverstöße erzeugen klare `doctor/profile` Diagnosecodes.

## 2. Dependency Resolver v2
- Unterstütze `required`, `optional`, `softAfter`, `conflicts`.
  - Parser + Schema validieren deklarative Dependencies bereits beim Load.
  - Resolver berücksichtigt fehlende optionale Dependencies ohne harten Abbruch.
- Resolver-Output mit Ursachenketten und konkreten Fix-Vorschlägen.
  - Zyklus-/Konflikt-/Versionsfehler als strukturierte Ursachenkette.
  - Fix-Hinweise enthalten konkrete Plugin-IDs und empfohlene Reihenfolge/Änderung.
- CLI/Runtime Report parity für Resolver-Fehler.
  - Gleiches Fehlerbild in Console, JSON-Ausgabe und Runtime-Audit.
  - Reproduzierbare Testfälle für typische Resolver-Fehlerszenarien.
- Acceptance:
  - Deterministische Load Order.
  - Konfliktfälle reproduzierbar testabgedeckt.

## 3. Marketplace Pipeline
- Admin Submission Bereich mit strukturiertem Intake (Metadaten, Dateien, Bilder, Changelog).
  - Pflichtfelder, Upload-Limits und Statussicht direkt im Admin-Portal.
  - Nachvollziehbare Autoren-/Zeitstempel für jeden Submission-Schritt.
- Validation Pipeline (Schema, Version-Matrix, Dateiprüfungen, Signatur-Hooks vorbereiten).
  - Schemavalidierung für Manifest/Felder + Artefakt-Checks (Dateityp, Größe, Integrität).
  - Hook-Punkte für spätere Signatur-/Trust-Level-Erweiterung ohne API-Bruch.
- Publish-Freigabe mit klaren Stati (`draft`, `review`, `approved`, `published`).
  - Statuswechsel nur über erlaubte Transitionen, inklusive Audit-Historie.
  - Public Listing ausschließlich aus `published` und validierten Submissions.
- Acceptance:
  - Submission -> Validation -> Publish End-to-End testbar.
  - Kein unvalidiertes Paket im öffentlichen Marketplace.

## 4. Performance & Determinismus
- Cold-Start Profiling + Startup Bottleneck Reduktion.
  - Startup-Pipeline instrumentieren und Top-Bottlenecks pro Build ausweisen.
  - Maßnahmen priorisieren nach p95 Einfluss auf Startzeit.
- Tick-loop Jitter Limits und Plugin-Budget Enforcement schärfen.
  - Harte Budget-Regeln pro Plugin/System, inklusive klarer Eskalation bei Ausreißern.
  - Jitter-Metriken in Runtime und CI identisch berechnen.
- Reload-Zeit und Memory-Growth Budgets für Langläufer.
  - Reload-Budget mit Regressionsgrenzen gegen Baseline.
  - Soak-Memory-Tracking mit Leak-Verdacht-Detektor und automatischem Bericht.
- Acceptance:
  - Definierte p50/p95 Ziele für Tick/Reload/Startup werden gehalten.
  - Soak (>=6h) ohne unbounded Memory Growth.

## 5. API & DX
- `clockwork-api` und `clockwork-host-api` Contract Review (Nullability, Naming, Guarantees).
  - Öffentliche Vertragsflächen systematisch gegen v1.x-Kompatibilitätsregeln prüfen.
  - Alle neuen Felder/Methoden mit klaren Guarantees und Fehlersemantik dokumentieren.
- Additive DSL Verbesserungen für Commands/Events/Mutations.
  - Verbesserte Ergonomie ohne Breaking Changes (sinnvolle Defaults, klare Fehlermeldungen).
  - DSL-Diagnosen mit denselben Codes wie Runtime/CLI.
- VS Code Addon: zusätzliche Quick Fixes, bessere Manifest-/DSL-Assistenz.
  - Quick-Fixes und Validierung gezielt für häufige Authoring-Fehler.
  - Snippets/Hover/Autocomplete an v1.7-Verträge koppeln.
- Acceptance:
  - API Compatibility Report mandatory in CI.
  - Public Contract Tests decken alle neuen Interfaces ab.

## 6. Ops & Release Engineering
- Release Artifact Gate: Artefaktliste + Checksums + Validierung in CI.
  - RC/GA nur bei vollständiger Manifest- und Checksum-Konsistenz.
  - CI bricht bei fehlenden/zusätzlichen Artefakten deterministisch ab.
- Erweiterte Release Notes mit Migration Delta und Breaking Notices.
  - Release Notes enthalten verpflichtend: Delta, Migrationsschritte, Known Risks, Rollback-Hinweise.
  - Breaking Notices mit Impact-Klassifizierung und betroffenen Oberflächen.
- Diagnostics Export Stabilität (`doctor/profile/diag export`) für Automation.
  - Export-Schemas versionieren und kompatibel halten.
  - Beispiel-Automationspfade in Doku als Referenz bereitstellen.
- Acceptance:
  - Reproduzierbare Release-Bundles.
  - Keine offenen P0/P1 Bugs beim Release-Kandidaten.

## Milestones

## M1: Foundations
- Resolver v2 Kern + Runtime Isolation Basis.
- Marketplace Intake Model + Validation Skeleton.
- Exit:
  - Unit/Integration für Resolver Kern grün.
  - Isolations-Baselines in CI.
  - Grundlegender Intake-Flow in Admin-Portal funktional und testbar.

## M2: Hardening
- Performance Pass v2 + Reload Snapshot/Restore.
- Marketplace Validation + Review-Pipeline stabilisieren.
- Exit:
  - `test`, `integrationSmoke`, `soakTest` grün.
  - Performance-Budgets dokumentiert und eingehalten.
  - Rollback-Qualität unter wiederholten Reload-Szenarien nachweisbar stabil.

## M3: Polish
- API Freeze, Docs/Tutorials final, VS Code DX final.
- Release Notes + Migration Guides abschließen.
- Exit:
  - Vollständige Doku auf v1.7.
  - Keine offenen GA-Blocker.
  - CI-Compatibility-Report ohne ungenehmigte Breaks.

## M4: Release
- Final RC + Tag + Artefakt-Publikation.
- Post-release Monitoring und Hotfix-Plan.
- Exit:
  - Artefakte + Checksums publiziert.
  - Release Readiness Checkliste vollständig erfüllt.
  - Monitoring-Baseline für erste 7 Tage nach GA festgelegt.

## GA Exit-Kriterien (hart)
- `test` vollständig grün.
- `integrationSmoke` grün.
- `soakTest` grün (Langlauf ohne kritische Drift).
- API Compatibility Report ohne nicht-genehmigte Breaks.
- Security/Isolation Regeln aktiv und dokumentiert.
- Marketplace Submission/Review/Publish End-to-End verifiziert.
- Performance-Budgets (`startup`, `tick jitter`, `reload`) in CI eingehalten.
- Kein offener P0/P1-Bug zum Release-Zeitpunkt.

## Backlog nach v1.7 (Ausblick)
- Signed plugin manifests + Trust Levels (Vertrauenskette, Signaturprüfung, Policy-Integration).
- Cluster/proxy-aware plugin messaging (Routing, Delivery-Garantien, Backpressure).
- Visual profiler/export tooling GA (aus Preview in produktionsreife Operator-Workflows überführen).
- Advanced world/chunk task partitioning (bessere Parallelisierung ohne Determinismusverlust).
