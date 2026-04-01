"""
post_analyzer.py — Sends DecisionResult history + metrics to GPT-4o for
post-experiment analysis. Produces recommendations for threshold and policy tuning.
"""

import json
from datetime import datetime
from pathlib import Path

from openai import OpenAI

_client = OpenAI()
_PROMPT_PATH = Path(__file__).parent / "prompts" / "post_analysis.txt"
_RESULTS_DIR = Path(__file__).parent / "logs"

# Current default policy values (used as placeholders in prompt)
_DEFAULT_POLICY = {
    "cooldown_seconds": 10,
    "sustained_windows": 3,
    "spike_sustained_windows": 2,
    "min_confidence": 0.6,
    "spike_threshold": 1.0,
    "gradual_threshold": 0.3,
    "error_threshold": 0.1,
}


def run_post_analysis(decision_log: list, metrics_snapshots: list,
                      policy: dict | None = None) -> dict:
    """
    Analyse decision history and metrics, return GPT-4o recommendations.
    """
    _RESULTS_DIR.mkdir(exist_ok=True)
    p = {**_DEFAULT_POLICY, **(policy or {})}

    prompt_template = _PROMPT_PATH.read_text(encoding="utf-8")
    prompt = (prompt_template
              .replace("{decision_log}", json.dumps(_compact_decisions(decision_log), indent=2, ensure_ascii=False))
              .replace("{metrics_timeseries}", json.dumps(_compact_metrics(metrics_snapshots), indent=2, ensure_ascii=False))
              .replace("{cooldown_seconds}", str(p["cooldown_seconds"]))
              .replace("{sustained_windows}", str(p["sustained_windows"]))
              .replace("{spike_sustained_windows}", str(p["spike_sustained_windows"]))
              .replace("{min_confidence}", str(p["min_confidence"]))
              .replace("{spike_threshold}", str(p["spike_threshold"]))
              .replace("{gradual_threshold}", str(p["gradual_threshold"]))
              .replace("{error_threshold}", str(p["error_threshold"])))

    print(f"  Sending {len(decision_log)} decisions + {len(metrics_snapshots)} snapshots to GPT-4o…")
    response = _client.chat.completions.create(
        model="gpt-4o",
        messages=[
            {"role": "system", "content": "You are a system performance analyst. Respond only with valid JSON."},
            {"role": "user", "content": prompt},
        ],
        temperature=0.2,
        response_format={"type": "json_object"},
    )

    raw = response.choices[0].message.content
    result = json.loads(raw)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = _RESULTS_DIR / f"post_analysis_{ts}.json"
    out_path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"  Post-analysis saved: {out_path}")

    _print_recommendations(result)
    return result


def _compact_decisions(decisions: list) -> list:
    return [
        {
            "timestamp": d.get("timestamp"),
            "state": d.get("state"),
            "algorithm": d.get("selectedAlgorithm"),
            "confidence": round(d.get("confidence", 0), 3),
            "reason": d.get("reason", "")[:200],
        }
        for d in decisions
    ]


def _compact_metrics(snapshots: list) -> list:
    return [
        {
            "timestamp": s.get("timestamp"),
            "rps": round(s.get("rps", 0), 2),
            "avg_latency_ms": round(s.get("avgLatencyMs", 0), 1),
            "p95_latency_ms": round(s.get("p95LatencyMs", 0), 1),
            "error_rate": round(s.get("errorRate", 0), 4),
        }
        for s in snapshots
    ]


def _print_recommendations(result: dict):
    print("\n  === Post-Analysis Recommendations ===")

    improvements = result.get("improvements", [])
    for imp in improvements[:3]:
        print(f"  [{imp.get('priority', '?')}] {imp.get('action', '')} → {imp.get('expected_effect', '')}")

    policy = result.get("policy_adjustments", {})
    for key, val in policy.items():
        if val.get("current") != val.get("recommended"):
            print(f"  Adjust {key}: {val['current']} → {val['recommended']} ({val.get('reason', '')})")
