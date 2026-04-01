#!/usr/bin/env bash
# ============================================================
# compare.sh — Fixed algorithm vs Adaptive Load Balancer benchmark
#
# Usage:
#   ./compare.sh [scenario] [target_url]
#
# Examples:
#   ./compare.sh stable          # run stable-traffic with all algorithms
#   ./compare.sh spike           # run spike-traffic
#   ./compare.sh all             # run all 4 scenarios
#
# Requirements:
#   - k6 installed (brew install k6)
#   - ALB running at TARGET_URL (default: http://localhost:8081)
# ============================================================

set -euo pipefail

SCENARIO="${1:-stable}"
TARGET_URL="${2:-http://localhost:8081}"
ALB_ADMIN="${TARGET_URL}/alb"
RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"

ALGORITHMS=(ROUND_ROBIN WEIGHTED_ROUND_ROBIN LEAST_CONNECTIONS WEIGHTED_LEAST_CONNECTIONS IP_HASH RANDOM)

# ── Helpers ──────────────────────────────────────────────────────────────────

log() { echo "[$(date '+%H:%M:%S')] $*"; }

switch_algorithm() {
    local algo="$1"
    curl -s -X POST "${ALB_ADMIN}/algorithm?type=${algo}" > /dev/null
    log "Switched to ${algo}"
    sleep 1
}

run_scenario() {
    local scenario="$1"
    local label="$2"
    local out="${RESULTS_DIR}/${scenario}-${label}.json"
    local script="$(dirname "$0")/scenarios/${scenario}.js"

    if [[ ! -f "$script" ]]; then
        log "ERROR: scenario script not found: $script"
        exit 1
    fi

    log "Running ${scenario} [${label}] → ${out}"
    k6 run \
        --env TARGET_URL="${TARGET_URL}" \
        --summary-export="${out}" \
        --out "json=${out%.json}-raw.json" \
        "$script" 2>&1 | tee "${out%.json}.log"

    log "Done: ${scenario} [${label}]"
}

export_metrics() {
    local label="$1"
    curl -s -X POST "${ALB_ADMIN}/export" -o "${RESULTS_DIR}/metrics-${label}.json"
    log "ALB metrics exported: metrics-${label}.json"
}

run_all_fixed() {
    local scenario="$1"
    log "=== Fixed Algorithm Benchmarks: ${scenario} ==="
    for algo in "${ALGORITHMS[@]}"; do
        switch_algorithm "$algo"
        sleep 2
        run_scenario "$scenario" "fixed-${algo,,}"
        export_metrics "${scenario}-fixed-${algo,,}"
        sleep 3
    done
}

run_adaptive() {
    local scenario="$1"
    log "=== Adaptive Benchmark: ${scenario} ==="
    # Restore to initial algorithm — DecisionEngine will switch as needed
    switch_algorithm "ROUND_ROBIN"
    sleep 2
    run_scenario "$scenario" "adaptive"
    export_metrics "${scenario}-adaptive"
}

summarise() {
    local scenario="$1"
    log "=== Summary: ${scenario} ==="
    python3 - <<PYEOF
import json, glob, os

results_dir = "${RESULTS_DIR}"
scenario = "${scenario}"

rows = []
for path in sorted(glob.glob(f"{results_dir}/{scenario}-*.json")):
    label = os.path.basename(path).replace(f"{scenario}-", "").replace(".json", "")
    if label.endswith("-raw") or not os.path.isfile(path):
        continue
    try:
        with open(path) as f:
            data = json.load(f)
        metrics = data.get("metrics", {})
        dur = metrics.get("http_req_duration", {})
        err = metrics.get("error_rate", {})
        rps = metrics.get("http_reqs", {}).get("rate", 0)
        rows.append({
            "label": label,
            "rps": round(rps, 1),
            "avg_ms": round(dur.get("avg", 0), 1),
            "p95_ms": round(dur.get("p(95)", 0), 1),
            "error_rate": round(err.get("rate", 0) * 100, 2),
        })
    except Exception as e:
        print(f"  [skip] {label}: {e}")

if not rows:
    print("  No results found.")
else:
    hdr = f"{'Label':<35} {'RPS':>7} {'Avg(ms)':>9} {'p95(ms)':>9} {'Err%':>7}"
    sep = "-" * len(hdr)
    print(sep)
    print(hdr)
    print(sep)
    for r in rows:
        print(f"{r['label']:<35} {r['rps']:>7} {r['avg_ms']:>9} {r['p95_ms']:>9} {r['error_rate']:>7}%")
    print(sep)
PYEOF
}

# ── Main ──────────────────────────────────────────────────────────────────────

check_alb_running() {
    if ! curl -sf "${ALB_ADMIN}/status" > /dev/null; then
        log "ERROR: ALB not reachable at ${TARGET_URL}. Start it first."
        exit 1
    fi
    log "ALB is running at ${TARGET_URL}"
}

run_scenario_suite() {
    local scenario="$1"
    check_alb_running
    run_all_fixed "$scenario"
    run_adaptive "$scenario"
    summarise "$scenario"
}

case "$SCENARIO" in
    all)
        for s in stable spike gradual-increase hotspot; do
            run_scenario_suite "$s"
        done
        ;;
    stable|spike|gradual-increase|hotspot)
        run_scenario_suite "$SCENARIO"
        ;;
    *)
        echo "Usage: $0 [stable|spike|gradual-increase|hotspot|all] [target_url]"
        exit 1
        ;;
esac

log "Benchmark complete. Results in ${RESULTS_DIR}/"
