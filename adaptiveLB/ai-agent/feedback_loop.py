"""
feedback_loop.py — Automated ALB feedback loop.

Continuously:
  1. Fetches live metrics + decision log from running ALB
  2. When enough data has accumulated, triggers AI analysis
  3. AI generates new rules → saved to config/decision-rules.yml
  4. Calls /alb/rules/reload so the running app picks them up immediately
  5. Waits for next cycle

Usage:
    python feedback_loop.py
    python feedback_loop.py --interval 120 --min-decisions 30
    python feedback_loop.py --alb-url http://localhost:8081 --dry-run
"""

import argparse
import json
import time
import urllib.request
import urllib.error
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

ALB_DEFAULT = "http://localhost:8081"
RESULTS_DIR = Path(__file__).parent.parent / "benchmark" / "results"


# ── HTTP helpers ──────────────────────────────────────────────────────────────

def _get(url: str):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return json.loads(r.read())
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        raise RuntimeError(f"GET {url} failed: {e}")


def _post(url: str):
    try:
        req = urllib.request.Request(url, method="POST")
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        raise RuntimeError(f"POST {url} failed: {e}")


# ── Core loop ─────────────────────────────────────────────────────────────────

def run_loop(alb_url: str, interval_seconds: int, min_decisions: int, dry_run: bool):
    print(f"[FeedbackLoop] Starting — ALB: {alb_url}, interval: {interval_seconds}s, "
          f"min_decisions: {min_decisions}, dry_run: {dry_run}")

    cycle = 0

    while True:
        cycle += 1
        ts = datetime.now().strftime("%H:%M:%S")
        print(f"\n[{ts}] ── Cycle {cycle} ──────────────────────────────")

        # 1. Check ALB is alive
        try:
            status = _get(f"{alb_url}/alb/status")
            print(f"  ALB alive | algorithm={status['algorithm']} | "
                  f"servers={len(status['servers'])}")
        except RuntimeError as e:
            print(f"  ALB not reachable: {e}. Waiting…")
            time.sleep(interval_seconds)
            continue

        # 2. Fetch decision log
        try:
            decisions = _get(f"{alb_url}/alb/decisions?last=100")
            print(f"  Decision log: {len(decisions)} entries")
        except RuntimeError as e:
            print(f"  Could not fetch decisions: {e}")
            decisions = []

        if len(decisions) < min_decisions:
            print(f"  Not enough decisions yet ({len(decisions)}/{min_decisions}). "
                  f"Waiting {interval_seconds}s…")
            time.sleep(interval_seconds)
            continue

        # 3. Fetch latest metrics snapshots
        try:
            metrics = _get(f"{alb_url}/alb/metrics")
            print(f"  Metrics snapshots: {len(metrics)}")
        except RuntimeError as e:
            print(f"  Could not fetch metrics: {e}")
            metrics = []

        # 4. Export metrics to file (so analyze.py can read them)
        try:
            export_result = _post(f"{alb_url}/alb/export")
            exported_file = export_result.get("file", "")
            print(f"  Metrics exported → {exported_file}")
        except RuntimeError as e:
            print(f"  Export failed: {e}")
            exported_file = ""

        # 5. Collect benchmark result files
        benchmark_files = sorted(RESULTS_DIR.glob("*.json")) if RESULTS_DIR.exists() else []
        print(f"  Benchmark files found: {len(benchmark_files)}")

        if not benchmark_files and not decisions:
            print("  No data to analyze. Waiting…")
            time.sleep(interval_seconds)
            continue

        # 6. Save decision log to temp file for analyze.py
        decisions_file = Path(__file__).parent / "logs" / f"decisions_cycle{cycle}.json"
        decisions_file.parent.mkdir(exist_ok=True)
        decisions_file.write_text(json.dumps(decisions, indent=2, ensure_ascii=False))

        metrics_file = Path(__file__).parent / "logs" / f"metrics_cycle{cycle}.json"
        metrics_file.write_text(json.dumps(metrics, indent=2, ensure_ascii=False))

        # 7. Snapshot current performance (before applying new rules)
        baseline_perf = _aggregate_metrics(metrics)

        # 8. Run AI analysis
        if dry_run:
            print("  [DRY RUN] Would call analyze.py here.")
        else:
            print("  Running AI analysis…")
            _run_analysis(
                benchmark_dir=str(RESULTS_DIR) if benchmark_files else None,
                decisions_file=str(decisions_file),
                metrics_file=str(metrics_file),
            )

            # 9. Reload rules and wait one interval to observe new performance
            try:
                reload_result = _post(f"{alb_url}/alb/rules/reload")
                print(f"  Rules reloaded: {reload_result}")
            except RuntimeError as e:
                print(f"  Rule reload failed: {e}")
                continue

            # 10. Compare performance — rollback if worse
            print(f"  Waiting {min(interval_seconds, 30)}s to observe new rule performance…")
            time.sleep(min(interval_seconds, 30))

            try:
                new_metrics = _get(f"{alb_url}/alb/metrics")
                new_perf = _aggregate_metrics(new_metrics)
                _evaluate_and_maybe_rollback(alb_url, baseline_perf, new_perf)
            except RuntimeError as e:
                print(f"  Could not evaluate new performance: {e}")

        print(f"  Cycle {cycle} done. Next in {interval_seconds}s.")
        time.sleep(interval_seconds)


def _aggregate_metrics(snapshots: list) -> dict:
    """Average key metrics across snapshots. Returns {} if no data."""
    valid = [s for s in snapshots if s.get("rps", 0) > 0]
    if not valid:
        return {}
    return {
        "avg_rps":   sum(s["rps"] for s in valid) / len(valid),
        "avg_lat":   sum(s["avgLatencyMs"] for s in valid) / len(valid),
        "p95_lat":   sum(s["p95LatencyMs"] for s in valid) / len(valid),
        "error_rate": sum(s["errorRate"] for s in valid) / len(valid),
    }


def _evaluate_and_maybe_rollback(alb_url: str, before: dict, after: dict):
    """Roll back to previous rule version if new rules are clearly worse."""
    if not before or not after:
        print("  Perf comparison skipped (no traffic data).")
        return

    p95_worse = after["p95_lat"] > before["p95_lat"] * 1.15   # 15% 이상 악화
    rps_worse = after["avg_rps"] < before["avg_rps"] * 0.85   # 15% 이상 감소
    err_worse = after["error_rate"] > before["error_rate"] + 0.02  # error 2%p 이상 증가

    print(f"  Before: p95={before['p95_lat']:.1f}ms  rps={before['avg_rps']:.1f}  err={before['error_rate']:.3f}")
    print(f"  After:  p95={after['p95_lat']:.1f}ms  rps={after['avg_rps']:.1f}  err={after['error_rate']:.3f}")

    if p95_worse or rps_worse or err_worse:
        reasons = []
        if p95_worse: reasons.append(f"p95 +{(after['p95_lat']/before['p95_lat']-1)*100:.0f}%")
        if rps_worse: reasons.append(f"RPS -{(1-after['avg_rps']/before['avg_rps'])*100:.0f}%")
        if err_worse: reasons.append(f"error +{(after['error_rate']-before['error_rate'])*100:.1f}%p")
        print(f"  ⚠ Performance degraded ({', '.join(reasons)}) — rolling back to previous version.")
        _rollback_rules(alb_url)
    else:
        print(f"  ✓ New rules performing well. Keeping.")


def _rollback_rules(alb_url: str):
    """Restore the second-to-last rule version."""
    versions_dir = Path(__file__).parent / "rule_versions"
    config_path = Path(__file__).parent.parent / "config" / "decision-rules.yml"
    versions = sorted(versions_dir.glob("v*.yml"),
                      key=lambda p: int(p.stem[1:]))
    if len(versions) < 2:
        print("  No previous version to roll back to.")
        return
    prev = versions[-2]
    config_path.write_text(prev.read_text())
    try:
        _post(f"{alb_url}/alb/rules/reload")
        print(f"  Rolled back to {prev.name}")
    except RuntimeError as e:
        print(f"  Rollback reload failed: {e}")


def _run_analysis(benchmark_dir, decisions_file, metrics_file):
    import subprocess, sys, os

    cmd = [sys.executable, "analyze.py"]
    if benchmark_dir:
        cmd += ["--benchmark", benchmark_dir]
    if decisions_file:
        cmd += ["--decisions", decisions_file]
    if metrics_file:
        cmd += ["--metrics", metrics_file]

    env = {**os.environ}

    result = subprocess.run(
        cmd,
        cwd=Path(__file__).parent,
        env=env,
        capture_output=False,
    )

    if result.returncode != 0:
        print(f"  [WARN] analyze.py exited with code {result.returncode}")


# ── CLI ───────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ALB automated feedback loop")
    parser.add_argument("--alb-url", default=ALB_DEFAULT, help="ALB base URL")
    parser.add_argument("--interval", type=int, default=120,
                        help="Seconds between analysis cycles (default: 120)")
    parser.add_argument("--min-decisions", type=int, default=20,
                        help="Minimum decision log entries before triggering analysis (default: 20)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Fetch data but skip AI call and rule update")
    args = parser.parse_args()

    run_loop(
        alb_url=args.alb_url,
        interval_seconds=args.interval,
        min_decisions=args.min_decisions,
        dry_run=args.dry_run,
    )
