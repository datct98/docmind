import json
import re
import sys
import time
import requests

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

BASE_URL = "http://localhost:8090"
PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee"

def send_msg(message, history, customer_id="eval_user_001"):
    url = f"{BASE_URL}/api/bot/fanpage/message"
    payload = {
        "page_id": PAGE_ID,
        "customer_id": customer_id,
        "message": message,
        "history": history
    }
    t0 = time.time()
    res = requests.post(url, json=payload, timeout=90)
    dur = time.time() - t0
    assert res.status_code == 200, f"Error {res.status_code}: {res.text}"
    data = res.json()
    return data, dur

def run_evaluation():
    results = {}
    history = []
    
    # -------------------------------------------------------------
    # TURN 1: Broad pricing query
    # -------------------------------------------------------------
    t1_msg = "Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?"
    print(f"=== TURN 1: {t1_msg} ===")
    d1, dur1 = send_msg(t1_msg, history)
    reply1 = d1.get("reply_text", "")
    bubbles1 = [b.get("text", "") for b in d1.get("reply_bubbles", [])]
    hooks1 = d1.get("triggered_hooks", [])
    stage1 = d1.get("sales_stage", "")
    handoff1 = d1.get("needs_human_handoff", False)
    
    print(f"Latency: {dur1:.2f}s | Stage: {stage1} | Handoff: {handoff1}")
    print(f"Hooks: {hooks1}")
    print(f"Bubbles ({len(bubbles1)}):")
    for i, b in enumerate(bubbles1, 1):
        print(f"  [{i}]: {b}")
    print(f"Full Reply: {reply1}\n")
    
    # Verification assertions for Turn 1
    has_starting_price = "650.000" in reply1
    has_3d_scan = "soi da" in reply1.lower() or "ai 3d" in reply1.lower()
    hook_active = "HOOK_ANTI_INFO_DUMPING_DISCOVERY" in hooks1
    no_lan_anh_1 = not bool(re.search(r"(?i)\blan\s+anh\b", reply1))
    
    print(f"Turn 1 Checks:")
    print(f"  - 650.000đ starting price present: {has_starting_price}")
    print(f"  - Free 3D AI skin analysis mentioned: {has_3d_scan}")
    print(f"  - AntiInfoDumpingHook active: {hook_active}")
    print(f"  - Zero Lan Anh: {no_lan_anh_1}")
    
    results["turn1"] = {
        "user": t1_msg,
        "reply": reply1,
        "bubbles": bubbles1,
        "hooks": hooks1,
        "stage": stage1,
        "latency_sec": dur1,
        "checks": {
            "has_starting_price_650k": has_starting_price,
            "has_3d_scan": has_3d_scan,
            "hook_anti_info_dumping": hook_active,
            "zero_lan_anh": no_lan_anh_1
        }
    }
    
    history.append({"role": "user", "content": t1_msg})
    history.append({"role": "assistant", "content": reply1})
    
    # -------------------------------------------------------------
    # TURN 2: 15-year acne + pitted scars
    # -------------------------------------------------------------
    t2_msg = "T bị mụn từ lúc dậy thì, đến nay cũng 15 năm thi thaorng viêm nhẹ và sưng kèm sẹo rỗ"
    print(f"\n=== TURN 2: {t2_msg} ===")
    d2, dur2 = send_msg(t2_msg, history)
    reply2 = d2.get("reply_text", "")
    bubbles2 = [b.get("text", "") for b in d2.get("reply_bubbles", [])]
    hooks2 = d2.get("triggered_hooks", [])
    stage2 = d2.get("sales_stage", "")
    
    print(f"Latency: {dur2:.2f}s | Stage: {stage2}")
    print(f"Hooks: {hooks2}")
    print(f"Bubbles ({len(bubbles2)}):")
    for i, b in enumerate(bubbles2, 1):
        print(f"  [{i}]: {b}")
    print(f"Full Reply: {reply2}\n")
    
    # Verification assertions for Turn 2
    has_1500k = "1.500.000" in reply2 or "1.500k" in reply2.lower() or "1tr5" in reply2.lower()
    no_repeated_greeting = not reply2.startswith("Dạ em chào") and not reply2.startswith("Em chào")
    mentions_scar_boundary = "sẹo rỗ" in reply2.lower()
    no_scar_cure_claim = "trị sạch sẹo rỗ" not in reply2.lower() and "khỏi hẳn sẹo rỗ" not in reply2.lower()
    
    print(f"Turn 2 Checks:")
    print(f"  - 1.500.000đ present: {has_1500k}")
    print(f"  - No repeated greeting: {no_repeated_greeting}")
    print(f"  - Mentions scar boundary: {mentions_scar_boundary}")
    print(f"  - No false scar cure claim: {no_scar_cure_claim}")
    
    results["turn2"] = {
        "user": t2_msg,
        "reply": reply2,
        "bubbles": bubbles2,
        "hooks": hooks2,
        "stage": stage2,
        "latency_sec": dur2,
        "checks": {
            "has_1500k": has_1500k,
            "no_repeated_greeting": no_repeated_greeting,
            "mentions_scar_boundary": mentions_scar_boundary,
            "no_scar_cure_claim": no_scar_cure_claim
        }
    }
    
    history.append({"role": "user", "content": t2_msg})
    history.append({"role": "assistant", "content": reply2})
    
    # -------------------------------------------------------------
    # TURN 3: Male identification & timeframe
    # -------------------------------------------------------------
    t3_msg = "Mìn là nam, không biết có thể dùng dịch vụ không? bao lâu thấy hiệu quẩ"
    print(f"\n=== TURN 3: {t3_msg} ===")
    d3, dur3 = send_msg(t3_msg, history)
    reply3 = d3.get("reply_text", "")
    bubbles3 = [b.get("text", "") for b in d3.get("reply_bubbles", [])]
    hooks3 = d3.get("triggered_hooks", [])
    stage3 = d3.get("sales_stage", "")
    profile3 = d3.get("consultation_profile", {})
    
    print(f"Latency: {dur3:.2f}s | Stage: {stage3} | Profile: {profile3}")
    print(f"Hooks: {hooks3}")
    print(f"Bubbles ({len(bubbles3)}):")
    for i, b in enumerate(bubbles3, 1):
        print(f"  [{i}]: {b}")
    print(f"Full Reply: {reply3}\n")
    
    has_anh = "Anh" in reply3
    zero_chi = not bool(re.search(r"(?i)\bchị\b", reply3))
    confirms_male = any(w in reply3.lower() for w in ["nam", "nam giới"])
    answers_timeframe = any(w in reply3.lower() for w in ["buổi", "tuần", "tháng", "hiệu quả", "lộ trình"])
    
    print(f"Turn 3 Checks:")
    print(f"  - Switched to 'Anh': {has_anh}")
    print(f"  - Zero 'Chị'/'chị': {zero_chi}")
    print(f"  - Male suitability confirmed: {confirms_male}")
    print(f"  - Timeframe / sessions answered: {answers_timeframe}")
    
    results["turn3"] = {
        "user": t3_msg,
        "reply": reply3,
        "bubbles": bubbles3,
        "hooks": hooks3,
        "stage": stage3,
        "profile": profile3,
        "latency_sec": dur3,
        "checks": {
            "has_anh": has_anh,
            "zero_chi": zero_chi,
            "confirms_male": confirms_male,
            "answers_timeframe": answers_timeframe
        }
    }
    
    history.append({"role": "user", "content": t3_msg})
    history.append({"role": "assistant", "content": reply3})
    
    # -------------------------------------------------------------
    # TURN 4: Phone lead capture
    # -------------------------------------------------------------
    t4_msg = "0968221644 tư vấn mình thêm"
    print(f"\n=== TURN 4: {t4_msg} ===")
    d4, dur4 = send_msg(t4_msg, history)
    reply4 = d4.get("reply_text", "")
    bubbles4 = [b.get("text", "") for b in d4.get("reply_bubbles", [])]
    handoff4 = d4.get("needs_human_handoff", False)
    reason4 = d4.get("handoff_reason", "")
    lead4 = d4.get("extracted_lead", {})
    
    print(f"Latency: {dur4:.2f}s | Handoff: {handoff4} | Reason: {reason4}")
    print(f"Lead: {lead4}")
    print(f"Bubbles ({len(bubbles4)}):")
    for i, b in enumerate(bubbles4, 1):
        print(f"  [{i}]: {b}")
    print(f"Full Reply: {reply4}\n")
    
    is_fast_path = dur4 < 3.0
    has_phone = "0968221644" in reason4 and lead4.get("phone_number") == "0968221644"
    addressed_anh_4 = "Anh" in reply4 and not bool(re.search(r"(?i)\bchị\b", reply4))
    no_pitch_repetition = "1.500.000" not in reply4 and "combo" not in reply4.lower()
    
    print(f"Turn 4 Checks:")
    print(f"  - Fast path (< 3s): {is_fast_path} ({dur4:.2f}s)")
    print(f"  - Handoff true: {handoff4}")
    print(f"  - Phone 0968221644 captured: {has_phone}")
    print(f"  - Addressed as 'Anh' (0 'Chị'): {addressed_anh_4}")
    print(f"  - Pitch silenced: {no_pitch_repetition}")
    
    results["turn4"] = {
        "user": t4_msg,
        "reply": reply4,
        "bubbles": bubbles4,
        "handoff": handoff4,
        "reason": reason4,
        "lead": lead4,
        "latency_sec": dur4,
        "checks": {
            "fast_path": is_fast_path,
            "handoff_true": handoff4,
            "phone_captured": has_phone,
            "addressed_anh": addressed_anh_4,
            "no_pitch_repetition": no_pitch_repetition
        }
    }
    
    # -------------------------------------------------------------
    # TURN 5: Buying Temperature & Cooling On Brush-Off
    # -------------------------------------------------------------
    print("\n=== EVALUATION: Buying Temperature & Psychological Takeaway ===")
    t5_msg = "ừ để chị xem thêm"
    t5_history = [
        {"role": "user", "content": "Cho mình hỏi về dịch vụ trị mụn"},
        {"role": "assistant", "content": "Dạ em chào Chị ạ! Liệu trình Trị Mụn Chuẩn Y Khoa 1.500.000 VNĐ ạ."},
        {"role": "user", "content": "Giá này có được giảm không em?"},
        {"role": "assistant", "content": "Dạ nếu Chị đăng ký Combo Tiết Kiệm (3-4 buổi) bên em giảm 10% và tặng 1 buổi Detox Oxy ạ."}
    ]
    d5, dur5 = send_msg(t5_msg, t5_history, customer_id="cooling_user_002")
    insight5 = d5.get("customer_insight", {})
    temp5 = insight5.get("buying_temperature")
    delta5 = insight5.get("temperature_delta")
    momentum5 = insight5.get("momentum")
    strategy5 = insight5.get("recommended_strategy")
    skills5 = insight5.get("active_psychological_skills", [])
    
    print(f"Brush-off User: '{t5_msg}'")
    print(f"Temperature: {temp5} | Delta: {delta5} | Momentum: {momentum5}")
    print(f"Strategy: {strategy5} | Skills: {skills5}")
    print(f"Reply: {d5.get('reply_text')}\n")
    
    cooling_verified = delta5 is not None and delta5 < 0
    momentum_verified = momentum5 == "COOLING_HALF_HEARTED"
    
    print(f"Buying Temp Checks:")
    print(f"  - Delta < 0: {cooling_verified} (delta={delta5})")
    print(f"  - Momentum COOLING_HALF_HEARTED: {momentum_verified}")
    print(f"  - Psychological skill active: {len(skills5) > 0}")
    
    results["cooling_check"] = {
        "user": t5_msg,
        "temperature": temp5,
        "delta": delta5,
        "momentum": momentum5,
        "strategy": strategy5,
        "skills": skills5,
        "reply": d5.get("reply_text"),
        "checks": {
            "cooling_verified": cooling_verified,
            "momentum_verified": momentum_verified
        }
    }
    
    # -------------------------------------------------------------
    # TURN 6: Proactive Re-engagement on Idle Time
    # -------------------------------------------------------------
    print("\n=== EVALUATION: Proactive Re-engagement on Idle Time (300s) ===")
    reengage_url = f"{BASE_URL}/api/bot/fanpage/reengage"
    reengage_payload = {
        "page_id": PAGE_ID,
        "customer_id": "idle_user_003",
        "idle_seconds": 300,
        "history": [
            {"role": "user", "content": "Tư vấn cho mình gói trị mụn"},
            {"role": "assistant", "content": "Dạ liệu trình trị mụn 1.500.000 VNĐ ạ. Chị đang gặp mụn viêm hay mụn ẩn ạ?"}
        ]
    }
    t0 = time.time()
    r_idle = requests.post(reengage_url, json=reengage_payload, timeout=90)
    dur_idle = time.time() - t0
    assert r_idle.status_code == 200, f"Error {r_idle.status_code}: {r_idle.text}"
    d_idle = r_idle.json()
    
    insight_idle = d_idle.get("customer_insight", {})
    temp_idle = insight_idle.get("buying_temperature")
    delta_idle = insight_idle.get("temperature_delta")
    momentum_idle = insight_idle.get("momentum")
    reengage_msg = d_idle.get("reply_text")
    
    print(f"Idle 300s Response Latency: {dur_idle:.2f}s")
    print(f"Temperature: {temp_idle} | Delta: {delta_idle} | Momentum: {momentum_idle}")
    print(f"Proactive Re-engagement Message: {reengage_msg}\n")
    
    idle_verified = delta_idle is not None and delta_idle < 0
    idle_msg_generated = reengage_msg is not None and len(reengage_msg) > 10
    
    print(f"Idle Checks:")
    print(f"  - Delta < 0: {idle_verified} (delta={delta_idle})")
    print(f"  - Momentum: {momentum_idle}")
    print(f"  - Proactive message generated: {idle_msg_generated}")
    
    results["idle_check"] = {
        "idle_seconds": 300,
        "temperature": temp_idle,
        "delta": delta_idle,
        "momentum": momentum_idle,
        "message": reengage_msg,
        "checks": {
            "idle_verified": idle_verified,
            "idle_msg_generated": idle_msg_generated
        }
    }
    
    # Save results to json for handoff
    with open("live_eval_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("\n>>> LIVE EVALUATION COMPLETED SUCCESSFULLY! <<<")

if __name__ == "__main__":
    run_evaluation()
