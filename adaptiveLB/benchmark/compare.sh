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
    local script_map
    case "$scenario" in
        stable)           script_map="stable-traffic" ;;
        spike)            script_map="spike-traffic" ;;
        gradual-increase) script_map="gradual-increase" ;;
        hotspot)          script_map="hotspot-traffic" ;;
        *)                script_map="$scenario" ;;
    esac
    local script="$(dirname "$0")/scenarios/${script_map}.js"

    if [[ ! -f "$script" ]]; then
        log "ERROR: scenario script not found: $script"
        exit 1
    fi

    log "Running ${scenario} [${label}] → ${out}"
    k6 run \
        --env TARGET_URL="${TARGET_URL}" \
        --env LABEL="${label}" \
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
        algo_lower=$(echo "$algo" | tr '[:upper:]' '[:lower:]')
        run_scenario "$scenario" "fixed-${algo_lower}"
        export_metrics "${scenario}-fixed-${algo_lower}"
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


VALID="stable spike gradual-increase hotspot"

check_alb_running

# comma-separated list or keywords
if [[ "$SCENARIO" == "all" ]]; then
    SCENARIOS_TO_RUN="stable spike gradual-increase hotspot"
elif [[ "$SCENARIO" == *","* ]]; then
    SCENARIOS_TO_RUN="${SCENARIO//,/ }"
elif echo "$VALID" | grep -qw "$SCENARIO"; then
    SCENARIOS_TO_RUN="$SCENARIO"
else
    echo "Usage: $0 [stable|spike|gradual-increase|hotspot|all|stable,spike|...] [target_url]"
    exit 1
fi

for s in $SCENARIOS_TO_RUN; do
    if ! echo "$VALID" | grep -qw "$s"; then
        log "Unknown scenario: $s — skipping"
        continue
    fi
    run_all_fixed "$s"
    run_adaptive "$s"
    summarise "$s"
done

log "Benchmark complete. Results in ${RESULTS_DIR}/"
