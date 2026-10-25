#!/usr/bin/env python3
"""DocMind v3.3.4 GAP-04 — Sample Dialogue Contract Validator.

Validates every ACTIVE sample dialogue bubble in a playbook file (JSON, or YAML when PyYAML is
installed) against the runtime bubble contract, so few-shot examples can never teach the LLM to
break the contract that ReplyContractValidator enforces at runtime:

  * <= 45 words (Vietnamese syllables) per bubble
  * <= 2 sentences per bubble
  * no internal codes leaked (NS01, DS02, PB-*, FD-*, VR-*)
  * <= 1 price mention per bubble

Samples labelled as negative/quarantined (quality_label QUARANTINED_BAD / BAD / REJECTED, or
active == false) are skipped: they exist precisely to show what NOT to do.

Usage:
    python3 02_dev/scripts/validate_samples.py [playbook.json|playbook.yaml ...]
Exit code 0 = all active samples valid, 1 = at least one violation, 2 = usage / parse error.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

MAX_WORDS = 45
MAX_SENTENCES = 2
INTERNAL_CODE_RE = re.compile(r"\b(NS\d+|DS\d+|ADD\d+|PB-[A-Z0-9-]+|FD-[A-Z0-9-]+|VR-[A-Z0-9-]+)\b")
PRICE_RE = re.compile(r"\b\d{1,3}(?:[.,]\d{3})*\s*(?:k|đ|vnđ|vnd)\b", re.IGNORECASE)
SENTENCE_END_RE = re.compile(r"[.!?…]+(?=\s|$)")
SKIP_LABELS = {"QUARANTINED_BAD", "BAD", "REJECTED", "NEGATIVE"}
DEFAULT_PLAYBOOKS = [
    Path(__file__).resolve().parents[2] / "docs" / "playbooks" / "nang_sen_spa_playbook.json",
]


def bubble_violations(text: str) -> list[str]:
    """Return a list of human-readable contract violations for one bubble."""
    t = (text or "").strip()
    problems: list[str] = []
    words = len(t.split())
    if words > MAX_WORDS:
        problems.append(f"vượt {MAX_WORDS} âm tiết ({words})")
    sentences = len(SENTENCE_END_RE.findall(t)) or (1 if t else 0)
    if sentences > MAX_SENTENCES:
        problems.append(f"vượt {MAX_SENTENCES} câu ({sentences})")
    leak = INTERNAL_CODE_RE.search(t)
    if leak:
        problems.append(f"lộ mã nội bộ '{leak.group(1)}'")
    prices = PRICE_RE.findall(t)
    if len(prices) > 1:
        problems.append(f"báo nhiều hơn 1 mức giá trong 1 bubble: {prices}")
    return problems


def _bubble_text(bubble) -> str:
    if isinstance(bubble, dict):
        return str(bubble.get("text", ""))
    return str(bubble)


def iter_active_bubbles(playbook: dict):
    for sample in playbook.get("sample_dialogues") or []:
        label = str(sample.get("quality_label", "")).upper()
        if label in SKIP_LABELS or sample.get("active") is False:
            continue
        sid = sample.get("id", "<no-id>")
        for t_idx, turn in enumerate(sample.get("turns") or [], start=1):
            assistant = turn.get("assistant") or []
            if isinstance(assistant, (str, dict)):
                assistant = [assistant]
            for b_idx, bubble in enumerate(assistant, start=1):
                yield sid, t_idx, b_idx, _bubble_text(bubble)


def load_playbook(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8")
    if path.suffix.lower() in {".yaml", ".yml"}:
        try:
            import yaml  # type: ignore
        except ImportError as exc:  # pragma: no cover - depends on environment
            raise SystemExit(f"PyYAML chưa được cài, không đọc được {path} ({exc}). Dùng bản .json.") from exc
        return yaml.safe_load(raw) or {}
    return json.loads(raw)


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv] or DEFAULT_PLAYBOOKS
    total_bubbles = 0
    failures = 0
    for path in paths:
        if not path.exists():
            print(f"[ERROR] Không tìm thấy file: {path}", file=sys.stderr)
            return 2
        playbook = load_playbook(path)
        for sid, t_idx, b_idx, text in iter_active_bubbles(playbook):
            total_bubbles += 1
            problems = bubble_violations(text)
            if problems:
                failures += 1
                print(f"[FAIL] {path.name} {sid} turn {t_idx} bubble {b_idx}: {'; '.join(problems)}\n       \"{text}\"")
    status = "PASS" if failures == 0 else "FAIL"
    print(f"[{status}] {total_bubbles} active bubbles checked, {failures} violation(s).")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
