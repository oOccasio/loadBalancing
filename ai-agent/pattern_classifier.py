"""
pattern_classifier.py — Sends ALB metric snapshots to GPT-4o for pattern
classification and validation of the PatternAnalyzer's decisions.
"""

import json
from datetime import datetime
from pathlib import Path

from openai import OpenAI

_client = OpenAI()
_PROMPT_PATH = Path(__file__).parent / "prompts" / "pattern_analysis.txt"
_RESULTS_DIR = Path(__file__).parent / "logs"


def classify_patterns(metrics_snapshots: list) -> dict:
    """
    Send metric time-series to GPT-4o for pattern classification.

    Returns the classification result as a dict.
    """
    _RESULTS_DIR.mkdir(exist_ok=True)
    prompt_template = _PROMPT_PATH.read_text(encoding="utf-8")

    # Summarise snapshots for prompt (avoid token overflow)
    summary = _summarise_snapshots(metrics_snapshots)
    prompt = prompt_template.replace("{metrics_timeseries}", json.dumps(summary, indent=2, ensure_ascii=False))

    print(f"  Sending {len(metrics_snapshots)} snapshots to GPT-4o for classification…")
    response = _client.chat.completions.create(
        model="gpt-4o",
        messages=[
            {"role": "system", "content": "You are a traffic pattern analysis expert. Respond only with valid JSON."},
            {"role": "user", "content": prompt},
        ],
        temperature=0.2,
        response_format={"type": "json_object"},
    )

    raw = response.choices[0].message.content
    result = json.loads(raw)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = _RESULTS_DIR / f"pattern_classification_{ts}.json"
    out_path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"  Pattern classification saved: {out_path}")

    # Print summary to console
    if "summary" in result:
        print(f"  Summary: {result['summary']}")

    return result


def _summarise_snapshots(snapshots: list) -> list:
    """Compact snapshot representation for the prompt."""
    result = []
    prev_rps = None
    for s in snapshots:
        rps = s.get("rps", 0)
        change = ((rps - prev_rps) / prev_rps) if (prev_rps and prev_rps > 0) else 0
        result.append({
            "timestamp": s.get("timestamp"),
            "rps": round(rps, 2),
            "rps_change_rate": round(change, 3),
            "avg_latency_ms": round(s.get("avgLatencyMs", 0), 1),
            "p95_latency_ms": round(s.get("p95LatencyMs", 0), 1),
            "error_rate": round(s.get("errorRate", 0), 4),
            "server_count": len(s.get("serverMetrics", {})),
        })
        prev_rps = rps
    return result
