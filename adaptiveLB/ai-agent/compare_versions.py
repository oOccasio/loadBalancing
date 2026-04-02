"""
compare_versions.py — Rule version performance comparison with fixed algorithm baselines.

Usage:
    python compare_versions.py
    python compare_versions.py --scenario stable
    python compare_versions.py --scenario stable,spike --cycles 5
"""

import argparse
import json
from pathlib import Path

import yaml

LOGS_DIR = Path(__file__).parent / "logs"
VERSIONS_DIR = Path(__file__).parent / "rule_versions"
RESULTS_DIR = Path(__file__).parent.parent / "benchmark" / "results"

FIXED_ALGOS = [
    "round_robin", "weighted_round_robin", "least_connections",
    "weighted_least_connections", "ip_hash", "random",
]


# ── Data loaders ──────────────────────────────────────────────────────────────

def _aggregate_snapshots(snapshots: list) -> dict:
    if not snapshots:
        return {}
    rps     = [s.get("rps", 0) for s in snapshots]
    avg_lat = [s.get("avgLatencyMs", 0) for s in snapshots]
    p95_lat = [s.get("p95LatencyMs", 0) for s in snapshots]
    err     = [s.get("errorRate", 0) for s in snapshots]
    return {
        "avg_rps":        round(sum(rps) / len(rps), 1),
        "avg_latency_ms": round(sum(avg_lat) / len(avg_lat), 1),
        "avg_p95_ms":     round(sum(p95_lat) / len(p95_lat), 1),
        "avg_error_pct":  round(sum(err) / len(err) * 100, 2),
    }


def _aggregate_k6_summary(data: dict) -> dict:
    """Parse k6 --summary-export JSON."""
    m = data.get("metrics", {})
    dur = m.get("http_req_duration", {})
    err = m.get("http_req_failed", m.get("error_rate", {}))
    rps = m.get("http_reqs", {}).get("rate", 0)
    return {
        "avg_rps":        round(rps, 1),
        "avg_latency_ms": round(dur.get("avg", 0), 1),
        "avg_p95_ms":     round(dur.get("p(95)", 0), 1),
        "avg_error_pct":  round(err.get("rate", err.get("value", 0)) * 100, 2),
    }


def load_fixed_baselines(scenario: str) -> dict[str, dict]:
    """Load fixed-algorithm results for a scenario from benchmark/results/."""
    baselines = {}
    for algo in FIXED_ALGOS:
        path = RESULTS_DIR / f"{scenario}-fixed-{algo}.json"
        if not path.exists():
            continue
        try:
            data = json.loads(path.read_text())
            # k6 summary export format
            if "metrics" in data:
                baselines[algo] = _aggregate_k6_summary(data)
            # ALB metrics snapshot list
            elif isinstance(data, list):
                baselines[algo] = _aggregate_snapshots(data)
        except Exception:
            pass
    return baselines


def load_adaptive_cycles(scenario: str, max_cycles: int) -> list[dict]:
    """Load per-cycle adaptive metrics from feedback loop logs."""
    rows = []
    for cycle in range(1, max_cycles + 1):
        path = LOGS_DIR / f"metrics_cycle{cycle}.json"
        if not path.exists():
            break
        try:
            snapshots = json.loads(path.read_text())
            agg = _aggregate_snapshots(snapshots)
            if not agg:
                continue

            version = cycle
            rules = _load_rules(version)
            decisions = _load_decisions_summary(cycle)

            rows.append({
                "label":    f"adaptive-v{version}",
                "version":  version,
                "rules":    rules,
                **agg,
                **decisions,
            })
        except Exception:
            pass
    return rows


def _load_rules(version: int) -> list[dict]:
    path = VERSIONS_DIR / f"v{version}.yml"
    if not path.exists():
        return []
    data = yaml.safe_load(path.read_text())
    return data.get("rules", []) if data else []


def _load_decisions_summary(cycle: int) -> dict:
    path = LOGS_DIR / f"decisions_cycle{cycle}.json"
    if not path.exists():
        return {}
    decisions = json.loads(path.read_text())
    if not decisions:
        return {}
    algos = [d.get("selectedAlgorithm") for d in decisions]
    switches = sum(1 for i in range(1, len(algos)) if algos[i] != algos[i - 1])
    return {
        "total_decisions": len(decisions),
        "algo_switches":   switches,
    }


# ── Printing ──────────────────────────────────────────────────────────────────

def _row(label, d, tag=""):
    return (f"{label:<30} {d.get('avg_rps',0):>7} {d.get('avg_latency_ms',0):>9} "
            f"{d.get('avg_p95_ms',0):>9} {d.get('avg_error_pct',0):>6}%  {tag}")


def print_comparison(scenarios: list[str], max_cycles: int):
    for scenario in scenarios:
        print(f"\n{'═'*75}")
        print(f"  Scenario: {scenario.upper()}")
        print(f"{'═'*75}")
        hdr = (f"{'Label':<30} {'RPS':>7} {'Avg(ms)':>9} {'p95(ms)':>9} "
               f"{'Err%':>7}  Notes")
        sep = "─" * 75
        print(hdr)
        print(sep)

        # Fixed baselines
        baselines = load_fixed_baselines(scenario)
        if baselines:
            for algo, d in sorted(baselines.items()):
                print(_row(f"fixed-{algo}", d))
            print(sep)
        else:
            print("  (No fixed baseline results found — run: ./compare.sh stable,spike)")
            print(sep)

        # Adaptive versions
        adaptive_rows = load_adaptive_cycles(scenario, max_cycles)
        if adaptive_rows:
            for r in adaptive_rows:
                tag = f"switches={r.get('algo_switches',0)} decisions={r.get('total_decisions',0)}"
                print(_row(r["label"], r, tag))
        else:
            print("  (No adaptive cycles found — run: python feedback_loop.py)")

        # Delta table
        if baselines and adaptive_rows:
            print(f"\n  Improvement vs best fixed algorithm:")
            # find best fixed by avg_latency_ms
            best_label, best = min(baselines.items(), key=lambda x: x[1].get("avg_latency_ms", 9999))
            print(f"  Baseline: fixed-{best_label} "
                  f"(avg={best['avg_latency_ms']}ms, p95={best['avg_p95_ms']}ms)\n")
            print(f"  {'Version':<15} {'ΔRPS':>8} {'ΔAvg(ms)':>10} {'Δp95(ms)':>10} {'ΔErr%':>8}")
            print("  " + "─" * 55)
            for r in adaptive_rows:
                sign = lambda v: f"+{v:.1f}" if v > 0 else f"{v:.1f}"
                print(f"  {r['label']:<15} "
                      f"{sign(r['avg_rps'] - best['avg_rps']):>8} "
                      f"{sign(r['avg_latency_ms'] - best['avg_latency_ms']):>10} "
                      f"{sign(r['avg_p95_ms'] - best['avg_p95_ms']):>10} "
                      f"{sign(r['avg_error_pct'] - best['avg_error_pct']):>8}%")

        # Rule mappings per version
        if adaptive_rows:
            print(f"\n  Rule mappings:")
            seen = set()
            for r in adaptive_rows:
                v = r["version"]
                if v in seen or not r["rules"]:
                    continue
                seen.add(v)
                print(f"    v{v}: " + "  ".join(
                    f"{rule.get('state','?')}→{rule.get('algorithm','?')}"
                    for rule in r["rules"]
                ))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Compare rule version performance")
    parser.add_argument("--scenario", default="stable",
                        help="Scenario(s) to compare, comma-separated (default: stable)")
    parser.add_argument("--cycles", type=int, default=20,
                        help="Max adaptive cycles to scan (default: 20)")
    args = parser.parse_args()

    scenarios = [s.strip() for s in args.scenario.split(",")]
    print_comparison(scenarios, args.cycles)
