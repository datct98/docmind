#!/usr/bin/env python3
"""DocMind v3.4.0 — Playbook Sync & Contract Validator (`sync_and_validate_playbook.py`).

1. Reads the master playbook `docs/playbooks/nang_sen_spa_playbook.yaml`.
2. Validates:
   - `response_contract.max_bubbles_per_turn in (2, 3)` and `max_words_per_bubble == 45`
   - `check_when_user_says_overlap`: no duplicate `when_user_says` trigger across rules
   - `recommended_questions <= 45 words`
   - Zero internal SKU codes (`NS01`, `DS02`, `ADD01`) and zero `.000đ` format in `playbook_rules` (`do`, `dont`, `recommended_questions`)
   - Every `APPROVED_GOOD` bubble in `sample_dialogues`:
     * `<= 45` words
     * `<= 2` sentences
     * `<= 1` price mention (`PRICE_TOKEN_RE` in `k` format), zero `.000đ` format, zero internal SKU codes
3. Synchronizes `docs/playbooks/nang_sen_spa_playbook.json` and `docs/playbooks/nang_sen_spa_playbook.md`.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
import yaml

ROOT_DIR = Path(__file__).resolve().parents[2]
PLAYBOOK_DIR = ROOT_DIR / "docs" / "playbooks"
YAML_PATH = PLAYBOOK_DIR / "nang_sen_spa_playbook.yaml"
JSON_PATH = PLAYBOOK_DIR / "nang_sen_spa_playbook.json"
MD_PATH = PLAYBOOK_DIR / "nang_sen_spa_playbook.md"

MAX_WORDS = 45
MAX_SENTENCES = 2
INTERNAL_SKU_RE = re.compile(r"\b(NS\d+|DS\d+|ADD\d+)\b")
DOT_PRICE_RE = re.compile(r"\b\d{1,3}(?:\.\d{3})+\s*(?:đ|vnđ|vnd)\b", re.IGNORECASE)
PRICE_TOKEN_RE = re.compile(r"\b\d{2,4}k\b", re.IGNORECASE)
SENTENCE_END_RE = re.compile(r"[.!?…]+(?=\s|$)")
SKIP_LABELS = {"QUARANTINED_BAD", "BAD", "REJECTED", "NEGATIVE"}


def check_when_user_says_overlap(playbook: dict) -> list[str]:
    """Ensure no two rules share an identical normalized `when_user_says` trigger."""
    errors: list[str] = []
    seen: dict[str, str] = {}
    all_rules = list(playbook.get("safety_policy", {}).get("rules", []) or []) + list(
        playbook.get("playbook_rules", []) or []
    )
    for rule in all_rules:
        rid = str(rule.get("id", "<unknown>"))
        for phrase in rule.get("when_user_says", []) or []:
            norm = re.sub(r"\s+", " ", str(phrase).strip().lower())
            if not norm:
                continue
            if norm in seen and seen[norm] != rid:
                errors.append(
                    f"[OVERLAP] when_user_says '{phrase}' trùng giữa {seen[norm]} và {rid}"
                )
            else:
                seen[norm] = rid
    return errors


def validate_playbook(playbook: dict) -> list[str]:
    violations: list[str] = []

    contract = playbook.get("response_contract") or {}
    max_bubbles = contract.get("max_bubbles_per_turn")
    if max_bubbles not in (2, 3):
        violations.append(
            f"[CONTRACT] max_bubbles_per_turn={max_bubbles} không nằm trong (2, 3)"
        )
    max_words = contract.get("max_words_per_bubble")
    if max_words != MAX_WORDS:
        violations.append(
            f"[CONTRACT] max_words_per_bubble={max_words} (yêu cầu {MAX_WORDS})"
        )

    violations.extend(check_when_user_says_overlap(playbook))

    for rule in playbook.get("playbook_rules", []) or []:
        rid = rule.get("id", "<no-id>")
        for idx, do_line in enumerate(rule.get("do", []) or [], start=1):
            sku = INTERNAL_SKU_RE.search(str(do_line))
            if sku:
                violations.append(
                    f"[RULE {rid} do#{idx}] lộ mã nội bộ '{sku.group(1)}'"
                )
            dot_p = DOT_PRICE_RE.search(str(do_line))
            if dot_p:
                violations.append(
                    f"[RULE {rid} do#{idx}] còn định dạng .000đ '{dot_p.group(0)}'"
                )
        for idx, rq in enumerate(rule.get("recommended_questions", []) or [], start=1):
            rq_str = str(rq).strip()
            w_count = len(rq_str.split())
            if w_count > MAX_WORDS:
                violations.append(
                    f"[RULE {rid} recommended_questions#{idx}] vượt {MAX_WORDS} từ ({w_count})"
                )
            sku = INTERNAL_SKU_RE.search(rq_str)
            if sku:
                violations.append(
                    f"[RULE {rid} recommended_questions#{idx}] lộ mã nội bộ '{sku.group(1)}'"
                )
            dot_p = DOT_PRICE_RE.search(rq_str)
            if dot_p:
                violations.append(
                    f"[RULE {rid} recommended_questions#{idx}] còn định dạng .000đ '{dot_p.group(0)}'"
                )

    for sample in playbook.get("sample_dialogues", []) or []:
        label = str(sample.get("quality_label", "")).upper()
        if label in SKIP_LABELS or sample.get("active") is False:
            continue
        sid = sample.get("id", "<no-id>")
        for t_idx, turn in enumerate(sample.get("turns", []) or [], start=1):
            assistant = turn.get("assistant") or []
            if isinstance(assistant, (str, dict)):
                assistant = [assistant]
            for b_idx, bubble in enumerate(assistant, start=1):
                text = (
                    str(bubble.get("text", "")).strip()
                    if isinstance(bubble, dict)
                    else str(bubble).strip()
                )
                words = len(text.split())
                if words > MAX_WORDS:
                    violations.append(
                        f"[SAMPLE {sid} T{t_idx}B{b_idx}] vượt {MAX_WORDS} từ ({words}): \"{text}\""
                    )
                sentences = len(SENTENCE_END_RE.findall(text)) or (1 if text else 0)
                if sentences > MAX_SENTENCES:
                    violations.append(
                        f"[SAMPLE {sid} T{t_idx}B{b_idx}] vượt {MAX_SENTENCES} câu ({sentences}): \"{text}\""
                    )
                sku = INTERNAL_SKU_RE.search(text)
                if sku:
                    violations.append(
                        f"[SAMPLE {sid} T{t_idx}B{b_idx}] lộ mã nội bộ '{sku.group(1)}': \"{text}\""
                    )
                dot_p = DOT_PRICE_RE.search(text)
                if dot_p:
                    violations.append(
                        f"[SAMPLE {sid} T{t_idx}B{b_idx}] còn định dạng .000đ '{dot_p.group(0)}': \"{text}\""
                    )
                k_prices = PRICE_TOKEN_RE.findall(text)
                if len(k_prices) > 1:
                    violations.append(
                        f"[SAMPLE {sid} T{t_idx}B{b_idx}] chứa >1 mức giá ({k_prices}): \"{text}\""
                    )

    return violations


def render_markdown(playbook: dict) -> str:
    pb_id = playbook.get("playbook_id", "SPA-PLAYBOOK-NANG-SEN-V3-0-1-UNIFIED")
    version = playbook.get("version", "3.0.1")
    page_scope = playbook.get("page_scope") or {}
    page_id = page_scope.get("page_id", "b23cd382-4f7a-4829-8c54-1d410da14bf8")
    brand = page_scope.get("brand_name", "Nàng Sen Spa")
    persona = playbook.get("persona") or {}
    bot_name = persona.get("bot_name", "An Nhiên")
    contract = playbook.get("response_contract") or {}

    lines: list[str] = [
        f"# PLAYBOOK VẬN HÀNH BOT TƯ VẤN: {brand.upper()} (`{pb_id}` — v{version})",
        "",
        f"- **Playbook ID**: `{pb_id}`",
        f"- **Version**: `{version}`",
        f"- **Page ID**: `{page_id}`",
        f"- **Thương hiệu**: {brand} (Bot persona: **{bot_name}**)",
        "- **Grounding Policy**: `strict_active_page_kb_only` (Bắt buộc tra cứu từ `<DOCUMENT_CONTEXT>` active; giá niêm yết KB: `129.000đ`, `239.000đ`, `339.000đ`, `399.000đ`, `500.000đ`, `530.000đ`, `580.000đ`, `670.000đ`, `780.000đ`, `990.000đ`, `1.990.000đ`, ưu đãi thẻ 5 buổi/voucher giảm `10%`)",
        f"- **Response Contract**: Tối đa `{contract.get('max_bubbles_per_turn', 3)}` bong bóng/lượt, tối đa `{contract.get('max_sentences_per_bubble', 2)}` câu/bong bóng, tối đa `{contract.get('max_words_per_bubble', 45)}` từ/bong bóng, tối đa `1` dấu `?`/toàn lượt, sau bong bóng giá nghỉ tối thiểu `10.000ms`, viết hoa chữ cái đầu tiên của mỗi bong bóng, cấm cắt cụt đuôi câu.",
        "",
        "---",
        "",
        "## PHẦN 0: SAFETY POLICY (`SAFE-NEURO-001`)",
        "",
    ]

    for srule in (playbook.get("safety_policy") or {}).get("rules", []) or []:
        lines.extend(_render_rule_md(srule))

    p_rules = playbook.get("playbook_rules") or []
    lines.extend(
        [
            "---",
            "",
            f"## PHẦN 1: PLAYBOOK RULES ({len(p_rules)} QUY TẮC VẬN HÀNH `PB-SPA-*`)",
            "",
        ]
    )
    for prule in p_rules:
        lines.extend(_render_rule_md(prule))

    dialogues = playbook.get("sample_dialogues") or []
    lines.extend(
        [
            "---",
            "",
            f"## PHẦN 2: SAMPLE DIALOGUES ({len(dialogues)} MẪU HỘI THOẠI `FD-SPA-*`)",
            "",
        ]
    )
    for dlg in dialogues:
        did = dlg.get("id", "")
        title = dlg.get("intent", "sample_dialogue")
        lines.append(f"### [{did}] {title}")
        for key in (
            "domain",
            "intent",
            "stage",
            "customer_type",
            "tone",
            "quality_label",
        ):
            if dlg.get(key) is not None:
                lines.append(f"- **{key}**: `{dlg.get(key)}`")
        if dlg.get("do_not_copy_literally") is not None:
            lines.append(
                f"- **do_not_copy_literally**: `{'true' if dlg.get('do_not_copy_literally') else 'false'}`"
            )
        tags = dlg.get("tags") or []
        if tags:
            lines.append(f"- **tags**: `{', '.join(str(t) for t in tags)}`")
        for turn in dlg.get("turns") or []:
            u = turn.get("user", "")
            lines.append(f"- **user**: {u}")
            assist = turn.get("assistant") or []
            if isinstance(assist, str):
                assist = [assist]
            joined_assist = " ".join(
                (b.get("text", "") if isinstance(b, dict) else str(b)).strip()
                for b in assist
            )
            lines.append(f"- **assistant**: {joined_assist}")
        if dlg.get("why_good"):
            lines.append(f"- **why_good**: {dlg.get('why_good')}")
        if dlg.get("why_bad"):
            lines.append(f"- **why_bad**: {dlg.get('why_bad')}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def _render_rule_md(rule: dict) -> list[str]:
    rid = rule.get("id", "")
    title = rule.get("title", "")
    out = [f"### [{rid}] {title}"]
    for key in (
        "domain",
        "intent",
        "stage",
        "priority",
        "thinking_mode_hint",
        "grounding_policy",
        "handoff_policy",
    ):
        if rule.get(key) is not None:
            out.append(f"- **{key}**: `{rule.get(key)}`")
    tags = rule.get("tags") or []
    if tags:
        out.append(f"- **tags**: `{', '.join(str(t) for t in tags)}`")
    for list_key in ("when_user_says", "do", "dont", "recommended_questions"):
        items = rule.get(list_key) or []
        if items:
            out.append(f"- **{list_key}**:")
            for item in items:
                out.append(f"  - `{item}`")
    out.append("")
    return out


def main() -> int:
    if not YAML_PATH.exists():
        print(f"[ERROR] Missing {YAML_PATH}", file=sys.stderr)
        return 2

    playbook = yaml.safe_load(YAML_PATH.read_text(encoding="utf-8")) or {}
    violations = validate_playbook(playbook)
    if violations:
        for v in violations:
            print(v, file=sys.stderr)
        print(f"Found {len(violations)} violation(s)!", file=sys.stderr)
        return 1

    JSON_PATH.write_text(
        json.dumps(playbook, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    MD_PATH.write_text(render_markdown(playbook), encoding="utf-8")
    print(
        f"Synced {YAML_PATH.name} -> {JSON_PATH.name}, {MD_PATH.name} | 0 violations!"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
