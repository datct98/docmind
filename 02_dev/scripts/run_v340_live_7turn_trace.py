#!/usr/bin/env python3
"""
DocMind v3.3.4 + v3.4.0 Live 7-Turn Conversation Trace Runner
Runs the mandatory 7-turn trace on Nàng Sen Spa (page_id = b23cd382-4f7a-4829-8c54-1d410da14bf8)
against a clean database and saves test_pages/nang_sen_spa/NANG_SEN_SPA_FULL_TRACE_MANIFEST.json.
"""
import json
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "docs" / "test_pages" / "nang_sen_spa" / "NANG_SEN_SPA_FULL_TRACE_MANIFEST.json"
PAGE_ID = "b23cd382-4f7a-4829-8c54-1d410da14bf8"
CONVERSATION_ID = "v340-live-7turn-trace"
SENDER_ID = "u-v340-live-7turns"
API_URL = "http://127.0.0.1:8090/api/bot/fanpage/message"
RESET_URL = "http://127.0.0.1:8090/api/bot/fanpage/reset"

TURNS = [
    "Giá",
    "tôi bị đau lưng mỏi vai",
    "thi thoảng bị",
    "chi phí gói 60 phút",
    "đắt",
    "đau lưng chứ đau lòng đâu mà nhẹ nhõm",
    "tụt hứng tghooi rẹp",
]


def post_json(url: str, payload: dict) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=240) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    # Reset session in API memory
    try:
        post_json(RESET_URL, {"page_id": PAGE_ID, "conversation_id": CONVERSATION_ID, "sender_id": SENDER_ID})
    except Exception:
        pass

    history = []
    trace_turns = []

    for idx, msg in enumerate(TURNS, start=1):
        t0 = time.time()
        payload = {
            "page_id": PAGE_ID,
            "sender_id": SENDER_ID,
            "conversation_id": CONVERSATION_ID,
            "customer_name": "Khách Thực Nghiệm v3.4.0",
            "message": msg,
            "history": history,
            "reset_session": idx == 1,
        }
        resp = post_json(API_URL, payload)
        elapsed_ms = int((time.time() - t0) * 1000)

        bubbles = resp.get("reply_bubbles") or resp.get("replyBubbles") or []
        bot_text = "\n\n".join(b.get("text", "") for b in bubbles if b.get("text"))
        telemetry = resp.get("turn_telemetry") or resp.get("turnTelemetry") or {}

        print(f"=== TURN {idx} ({elapsed_ms}ms) ===")
        print(f"USER: {msg}")
        print(
            f"TELEMETRY: action={telemetry.get('primary_action') or telemetry.get('primaryAction')} "
            f"| intent={telemetry.get('detected_intent') or telemetry.get('detectedIntent')} "
            f"| thinkingMode={telemetry.get('thinking_mode') or telemetry.get('thinkingMode')} "
            f"| thinkEnabled={telemetry.get('think_enabled') if 'think_enabled' in telemetry else telemetry.get('thinkEnabled')} "
            f"| maxOutputTokens={telemetry.get('max_output_tokens') or telemetry.get('maxOutputTokens')} "
            f"| thinkingChars={telemetry.get('thinking_chars') if 'thinking_chars' in telemetry else telemetry.get('thinkingChars')} "
            f"| source={telemetry.get('generation_source') or telemetry.get('generationSource')} "
            f"| validation={telemetry.get('validation_outcome') or telemetry.get('validationOutcome')}"
        )
        for b_idx, b in enumerate(bubbles, start=1):
            print(f"  B{b_idx} (delay={b.get('typing_delay_ms')}ms): {b.get('text')}")

        history.append({"role": "user", "content": msg})
        if bot_text:
            history.append({"role": "assistant", "content": bot_text})

        trace_turns.append(
            {
                "turn_index": idx,
                "user_message": msg,
                "elapsed_wall_ms": elapsed_ms,
                "reply_bubbles": bubbles,
                "sales_stage": resp.get("sales_stage"),
                "needs_human_handoff": resp.get("needs_human_handoff"),
                "applied_rules": resp.get("applied_rules"),
                "applied_skills": resp.get("applied_skills"),
                "turn_telemetry": telemetry,
            }
        )

    manifest = {
        "meta": {
            "version": "3.4.0-AUTHENTIC-EMOTIONAL-ENGINE",
            "playbook_version": "3.4.0",
            "page_id": PAGE_ID,
            "page_name": "Nàng Sen Spa",
            "conversation_id": CONVERSATION_ID,
            "sender_id": SENDER_ID,
            "generated_at_epoch": int(time.time()),
            "ollama_config": {
                "num_ctx": 8192,
                "num_predict": 2048,
                "temperature": 0.3,
                "model": "qwen3:14b",
            },
        },
        "live_7_turn_trace": trace_turns,
    }
    MANIFEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_PATH.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nSaved full trace manifest to {MANIFEST_PATH}")


if __name__ == "__main__":
    main()
