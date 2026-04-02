"""
analyze.py — Main entry point for the AI offline analysis pipeline.

Reads benchmark results JSON → calls OpenAI GPT-4o → generates updated
decision rules → saves new rule version to rule_versions/.

Usage:
    python analyze.py --benchmark benchmark/results/metrics-stable-adaptive.json
    python analyze.py --benchmark benchmark/results/  # all files in dir
    python analyze.py --decisions http://localhost:8081/alb/decisions  # live fetch
"""

import argparse
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

from pattern_classifier import classify_patterns
from post_analyzer import run_post_analysis
from rule_generator import generate_rules

load_dotenv()


def load_json(path: str) -> dict | list:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def fetch_decisions(url: str) -> list:
    import urllib.request
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read())


def collect_benchmark_files(path: str) -> list[Path]:
    p = Path(path)
    if p.is_file():
        return [p]
    return sorted(p.glob("*.json"))


def main():
    parser = argparse.ArgumentParser(description="ALB AI offline analysis")
    parser.add_argument("--benchmark", help="Path to benchmark result JSON (file or dir)")
    parser.add_argument("--decisions", help="Path or URL to DecisionResult log JSON")
    parser.add_argument("--metrics", help="Path to ALB metrics snapshot JSON")
    parser.add_argument("--skip-rules", action="store_true", help="Skip rule generation")
    parser.add_argument("--skip-post", action="store_true", help="Skip post-analysis")
    args = parser.parse_args()

    if not os.getenv("OPENAI_API_KEY"):
        print("ERROR: OPENAI_API_KEY environment variable not set.")
        print("  Set it in ai-agent/.env or export it.")
        sys.exit(1)

    # ── Collect inputs ────────────────────────────────────────────────────────

    benchmark_data = None
    if args.benchmark:
        files = collect_benchmark_files(args.benchmark)
        benchmark_data = {}
        for f in files:
            benchmark_data[f.stem] = load_json(str(f))
        print(f"Loaded {len(benchmark_data)} benchmark file(s).")

    decision_log = None
    if args.decisions:
        if args.decisions.startswith("http"):
            decision_log = fetch_decisions(args.decisions)
        else:
            decision_log = load_json(args.decisions)
        print(f"Loaded {len(decision_log)} decision log entries.")

    metrics_data = None
    if args.metrics:
        metrics_data = load_json(args.metrics)
        print(f"Loaded {len(metrics_data)} metric snapshot(s).")

    # ── Run analysis pipeline ─────────────────────────────────────────────────

    if benchmark_data and not args.skip_rules:
        print("\n[1/3] Generating decision rules from benchmark data…")
        generate_rules(benchmark_data)

    if metrics_data:
        print("\n[2/3] Classifying traffic patterns…")
        classify_patterns(metrics_data)

    if decision_log and metrics_data and not args.skip_post:
        print("\n[3/3] Running post-analysis…")
        run_post_analysis(decision_log, metrics_data)

    print("\nAnalysis complete.")


if __name__ == "__main__":
    main()
