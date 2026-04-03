"""
rule_diff.py — Compare two rule version YAML files and generate a human-readable
diff document explaining what changed and why.

Usage:
    python rule_diff.py v1 v2                  # compare versions
    python rule_diff.py v1 v2 --benchmark <f>  # include benchmark context
"""

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Optional

import yaml
from openai import OpenAI

_client = OpenAI()
_PROMPT_PATH = Path(__file__).parent / "prompts" / "rule_diff.txt"
_VERSIONS_DIR = Path(__file__).parent / "rule_versions"
_DOCS_DIR = Path(__file__).parent.parent / "docs"


def load_version(version: str) -> dict:
    path = _VERSIONS_DIR / f"{version}.yml"
    if not path.exists():
        raise FileNotFoundError(f"Rule version not found: {path}")
    with open(path) as f:
        return yaml.safe_load(f)


def diff_versions(old_ver: str, new_ver: str, benchmark_data: Optional[dict] = None) -> str:
    """
    Compare two rule versions with GPT-4o and produce a markdown diff document.
    Returns the path to the generated diff file.
    """
    old_rules = load_version(old_ver)
    new_rules = load_version(new_ver)

    # Build simple text comparison without GPT if prompt template missing
    if not _PROMPT_PATH.exists():
        return _simple_diff(old_ver, new_ver, old_rules, new_rules)

    prompt_template = _PROMPT_PATH.read_text(encoding="utf-8")
    prompt = (prompt_template
              .replace("{old_version}", old_ver)
              .replace("{new_version}", new_ver)
              .replace("{old_rules}", yaml.dump(old_rules, allow_unicode=True))
              .replace("{new_rules}", yaml.dump(new_rules, allow_unicode=True))
              .replace("{benchmark_comparison}",
                       json.dumps(benchmark_data, indent=2, ensure_ascii=False)
                       if benchmark_data else "N/A"))

    print(f"  Calling OpenAI API to analyse diff {old_ver}→{new_ver}…")
    response = _client.chat.completions.create(
        model="gpt-4o",
        messages=[
            {"role": "system", "content": "You are a system strategy analyst. Respond only with valid JSON."},
            {"role": "user", "content": prompt},
        ],
        temperature=0.2,
        response_format={"type": "json_object"},
    )

    diff_json = json.loads(response.choices[0].message.content)
    return _write_diff_doc(old_ver, new_ver, diff_json)


def _write_diff_doc(old_ver: str, new_ver: str, diff_json: dict) -> str:
    _DOCS_DIR.mkdir(exist_ok=True)
    path = _DOCS_DIR / f"diff_{old_ver}_{new_ver}.md"

    changes = diff_json.get("changes", [])
    summary = diff_json.get("summary", "")

    lines = [
        f"## Rule Changes: {old_ver} → {new_ver}",
        f"_Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_",
        "",
        "| State | Old Algorithm | New Algorithm | Reason | Expected Improvement |",
        "|-------|--------------|--------------|--------|---------------------|",
    ]
    for c in changes:
        lines.append(
            f"| {c.get('state','')} "
            f"| {c.get('old_algorithm','')} "
            f"| {c.get('new_algorithm','')} "
            f"| {c.get('reason','')} "
            f"| {c.get('expected_improvement','')} |"
        )
    lines += ["", f"## Summary", "", summary, ""]

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"  Diff document saved: {path}")
    return str(path)


def _simple_diff(old_ver: str, new_ver: str, old: dict, new: dict) -> str:
    """Fallback: generate diff without GPT."""
    _DOCS_DIR.mkdir(exist_ok=True)
    path = _DOCS_DIR / f"diff_{old_ver}_{new_ver}.md"

    old_map = {r["state"]: r["algorithm"] for r in (old.get("rules") or [])}
    new_map = {r["state"]: r["algorithm"] for r in (new.get("rules") or [])}

    lines = [
        f"## Rule Changes: {old_ver} → {new_ver}",
        f"_Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_",
        "",
        "| State | Old Algorithm | New Algorithm |",
        "|-------|--------------|--------------|",
    ]
    all_states = sorted(set(old_map) | set(new_map))
    for state in all_states:
        oa = old_map.get(state, "—")
        na = new_map.get(state, "—")
        marker = " ← changed" if oa != na else ""
        lines.append(f"| {state} | {oa} | {na}{marker} |")

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"  Simple diff saved: {path}")
    return str(path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("old_version")
    parser.add_argument("new_version")
    parser.add_argument("--benchmark", help="Path to benchmark comparison JSON")
    args = parser.parse_args()

    benchmark = None
    if args.benchmark:
        with open(args.benchmark) as f:
            benchmark = json.load(f)

    result = diff_versions(args.old_version, args.new_version, benchmark)
    print(f"Diff written to: {result}")
