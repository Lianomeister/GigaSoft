#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Tuple


PERF_PATTERN = re.compile(
    r"PERF_V2\s+metric=(?P<metric>[^\s]+)\s+p50=(?P<p50>[^\s]+)\s+p95=(?P<p95>[^\s]+)"
)


def parse_number(raw: str) -> float:
    return float(raw.replace(",", "."))


def collect_metrics(xml_files: List[Path]) -> Dict[str, Dict[str, float]]:
    observed: Dict[str, Dict[str, float]] = {}
    for xml_file in xml_files:
        text = xml_file.read_text(encoding="utf-8", errors="ignore")
        for match in PERF_PATTERN.finditer(text):
            metric = match.group("metric")
            p50 = parse_number(match.group("p50"))
            p95 = parse_number(match.group("p95"))
            current = observed.get(metric)
            if current is None:
                observed[metric] = {"p50": p50, "p95": p95, "source": str(xml_file)}
                continue
            # Keep worst value across duplicates.
            current["p50"] = max(current["p50"], p50)
            current["p95"] = max(current["p95"], p95)
    return observed


def evaluate(
    observed: Dict[str, Dict[str, float]],
    baseline: Dict[str, Dict[str, float]],
) -> Tuple[List[str], List[str]]:
    errors: List[str] = []
    lines: List[str] = []
    lines.append("| Metric | Observed p50 | Budget p50 | Observed p95 | Budget p95 | Status |")
    lines.append("|---|---:|---:|---:|---:|---|")

    for metric in sorted(baseline.keys()):
        budget = baseline[metric]
        obs = observed.get(metric)
        if obs is None:
            errors.append(f"Missing PERF_V2 metric: {metric}")
            lines.append(f"| `{metric}` | - | {budget['p50_max']:.3f} | - | {budget['p95_max']:.3f} | missing |")
            continue

        p50_ok = obs["p50"] <= budget["p50_max"]
        p95_ok = obs["p95"] <= budget["p95_max"]
        status = "ok" if p50_ok and p95_ok else "regression"
        if not p50_ok:
            errors.append(
                f"{metric} p50 regression: observed={obs['p50']:.3f} budget={budget['p50_max']:.3f}"
            )
        if not p95_ok:
            errors.append(
                f"{metric} p95 regression: observed={obs['p95']:.3f} budget={budget['p95_max']:.3f}"
            )

        lines.append(
            f"| `{metric}` | {obs['p50']:.3f} | {budget['p50_max']:.3f} | {obs['p95']:.3f} | {budget['p95_max']:.3f} | {status} |"
        )
    return errors, lines


def main() -> int:
    parser = argparse.ArgumentParser(description="Performance v2 regression gate")
    parser.add_argument("--baseline", required=True, help="Path to targets-v2.json")
    parser.add_argument(
        "--results-root",
        default=".",
        help="Repository root where module build/test-results are located",
    )
    parser.add_argument(
        "--report",
        default="build/reports/performance/perf-v2-report.md",
        help="Output markdown report path",
    )
    args = parser.parse_args()

    repo_root = Path(args.results_root).resolve()
    baseline_path = Path(args.baseline).resolve()
    report_path = Path(args.report).resolve()

    baseline_json = json.loads(baseline_path.read_text(encoding="utf-8"))
    baseline = baseline_json["metrics"]

    patterns = [
        "clockwork-runtime/build/test-results/performanceTest/TEST-*.xml",
        "clockwork-core/build/test-results/performanceTest/TEST-*.xml",
        "clockwork-standalone/build/test-results/performanceTest/TEST-*.xml",
    ]
    xml_files: List[Path] = []
    for pattern in patterns:
        xml_files.extend(sorted(repo_root.glob(pattern)))

    if not xml_files:
        print("No performanceTest XML files found.", file=sys.stderr)
        return 2

    observed = collect_metrics(xml_files)
    errors, table_lines = evaluate(observed, baseline)

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_lines = [
        "# Performance Gate v2 Report",
        "",
        f"- Baseline: `{baseline_path}`",
        f"- Results files: {len(xml_files)}",
        "",
    ]
    if errors:
        report_lines.append("## Result: FAILED")
    else:
        report_lines.append("## Result: PASSED")
    report_lines.append("")
    report_lines.extend(table_lines)
    report_lines.append("")
    if errors:
        report_lines.append("## Errors")
        report_lines.append("")
        for err in errors:
            report_lines.append(f"- {err}")
    report_path.write_text("\n".join(report_lines) + "\n", encoding="utf-8")

    if errors:
        print("Performance gate v2 failed:")
        for err in errors:
            print(f" - {err}")
        print(f"Report: {report_path}")
        return 1
    print("Performance gate v2 passed.")
    print(f"Report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
