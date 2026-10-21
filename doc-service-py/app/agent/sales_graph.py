"""Hot-Path LangGraph Engine (`sales_consultation_graph`).

Implements the 4-node State-Driven Sales Architecture:
1. `contextualize_memory_node`: Restores per-customer state (`thread_id`),
   locks `honorific` ("Anh"/"Chị") and `active_topic` across turns, and
   rewrites short follow-up/constraint messages (e.g., "nhưng tôi là nam")
   into self-contained queries anchored to the active treatment topic.
2. `intent_router_node`: Classifies the turn into 1 of 4 funnel states:
   `CONSULTING`, `OBJECTION_PRICE`, `CLOSING`, or `SUPPORT`.
3. `retrieve_few_shot_node`: Prunes off-topic service lines (preventing
   unrelated services like Laser Pico / Meso from polluting an Acne/Dark-spot
   thread) and selects 2-3 golden few-shot conversation samples.
4. `sales_responder_node`: Produces the stage-scoped prompt directive and
   `customer_psid`-locked tool scope.
"""

from __future__ import annotations

import re
from typing import Any, TypedDict

from app.agent.security import mask_pii

try:
    from langgraph.checkpoint.memory import MemorySaver
    from langgraph.graph import END, START, StateGraph

    _HAS_LANGGRAPH = True
except Exception:  # pragma: no cover - graceful fallback if langgraph not installed
    _HAS_LANGGRAPH = False
    END = "__end__"
    START = "__start__"
    MemorySaver = None  # type: ignore[assignment]
    StateGraph = None  # type: ignore[assignment]


class SalesConversationState(TypedDict, total=False):
    customer_psid: str
    page_id: str | None
    message: str
    masked_message: str
    pii_metadata: dict[str, Any]
    history: list[dict[str, str]]
    candidate_chunks: list[dict[str, Any]]
    candidate_rules: list[dict[str, Any]]
    # Derived memory & topic anchor state
    honorific: str
    active_topic: str
    topic_family: str
    topic_keywords: list[str]
    is_profile_update_turn: bool
    is_broad_price_turn: bool
    turn_index: int
    funnel_stage: str
    rewritten_query: str
    filtered_chunks: list[dict[str, Any]]
    selected_few_shots: list[dict[str, str]]
    stage_directive: str
    safe_tool_scope: dict[str, Any]


# In-process session memory store keyed by "{page_id}:{customer_psid}"
_SESSION_MEMORY: dict[str, dict[str, Any]] = {}

_MALE_RE = re.compile(
    r"(?i)(?:\b(?:tôi|mình|mìn|minh|mk|t|em|anh)\s+(?:là|la|làm)\s+(?:nam|con\s+trai|đàn\s+ông)\b|"
    r"\bnhưng\s+(?:tôi|mình|mìn|mk|t)\s+là\s+nam\b|"
    r"\bnam\s+giới\b|\bcho\s+nam\b|\bkhách\s+nam\b|\banh\s+muốn\b|\banh\s+bị\b|\banh\s+cần\b)"
)

_FEMALE_RE = re.compile(
    r"(?i)(?:\b(?:tôi|mình|chị|em)\s+là\s+(?:nữ|con\s+gái|phụ\s+nữ)\b|"
    r"\bchị\s+muốn\b|\bchị\s+bị\b|\bchị\s+cần\b|\bbầu\b|\bsau\s+sinh\b)"
)

_PROFILE_UPDATE_ONLY_RE = re.compile(
    r"(?i)^[\s,.\-!]*(?:nhưng\s+|mà\s+|à\s+)?(?:tôi|mình|mìn|minh|mk|t|anh|chị)?\s*"
    r"(?:là\s+nam(?:\s+giới)?|là\s+con\s+trai|là\s+đàn\s+ông|nam\s+nhé|nam\s+ạ|"
    r"nam\s+có\s+làm\s+được\s+không|nam\s+dùng\s+được\s+không|"
    r"da\s+(?:nhạy\s+cảm|dầu|khô|mỏng)|(?:\d{2})\s+tuổi)[\s?.!]*$"
)

# Topic families and their Vietnamese keywords
_TOPIC_FAMILIES: dict[str, dict[str, Any]] = {
    "ACNE_THAM": {
        "label": "Trị mụn & Mờ thâm chuyên sâu",
        "match_re": re.compile(
            r"(?i)\b(?:trị\s+mụn\s+lưng|trị\s+mụn\s+ẩn|trị\s+mụn\s+viêm|mụn\s+lưng|mụn\s+ẩn|"
            r"mụn\s+viêm|mụn\s+cằm|mụn\s+đầu\s+đen|mờ\s+thâm|trị\s+thâm|vết\s+thâm|thâm\s+mụn|"
            r"nặn\s+mụn|kháng\s+viêm|trị\s+mụn|mụn|thâm)\b"
        ),
        "keywords": ["Trị Mụn Chuẩn Y Khoa", "mụn", "mờ thâm", "kháng viêm"],
        "exclude_line_re": re.compile(
            r"(?i)(?:Trị\s+Nám\s+Laser\s+Pico|Căng\s+bóng\s+Meso\s+Collagen|"
            r"4\.500\.000|3\.200\.000|Laser\s+Pico|Meso\s+Collagen)"
        ),
    },
    "MELASMA_PICO": {
        "label": "Trị nám, tàn nhang & sắc tố Laser Pico",
        "match_re": re.compile(
            r"(?i)\b(?:nám|tàn\s+nhang|đồi\s+mồi|sạm\s+da|sắc\s+tố|laser\s+pico|pico)\b"
        ),
        "keywords": ["Trị Nám Laser Pico", "nám", "tàn nhang", "sắc tố"],
        "exclude_line_re": re.compile(
            r"(?i)(?:Căng\s+bóng\s+Meso\s+Collagen|3\.200\.000|Meso\s+Collagen)"
        ),
    },
    "MESO_REJUV": {
        "label": "Căng bóng & Trẻ hóa Meso Collagen",
        "match_re": re.compile(
            r"(?i)\b(?:căng\s+bóng|trẻ\s+hóa|meso|collagen|lão\s+hóa|nếp\s+nhăn|chảy\s+xệ)\b"
        ),
        "keywords": ["Căng bóng Meso Collagen", "trẻ hóa", "căng bóng", "collagen"],
        "exclude_line_re": re.compile(
            r"(?i)(?:Trị\s+Nám\s+Laser\s+Pico|4\.500\.000)"
        ),
    },
    "DETOX_OXY": {
        "label": "Thải độc & Làm sạch sâu Detox Oxy",
        "match_re": re.compile(r"(?i)\b(?:detox|oxy|thải\s+độc|làm\s+sạch\s+sâu)\b"),
        "keywords": ["Detox Oxy", "thải độc", "soi da AI 3D"],
        "exclude_line_re": None,
    },
}

_CLOSING_RE = re.compile(
    r"(?i)\b(?:đặt\s+lịch|giữ\s+suất|giữ\s+lịch|đăng\s+ký|chốt|lên\s+đơn|"
    r"thử\s+xem(?:\s+sao|\s+thế\s+nào|\s+nhé|\s+nha)?|sẽ\s+thử|mình\s+thử|t\s+thử|"
    r"trải\s+nghiệm\s+thử|làm\s+thử|khám\s+thử|soi\s+da\s+thử|qua\s+soi\s+da|qua\s+khám|"
    r"sáng\s+mai|chiều\s+nay|chiều\s+mai|khung\s+giờ|gọi\s+cho|zalo)\b"
)
_OBJECTION_PRICE_RE = re.compile(
    r"(?i)\b(?:đắt|mắc|cao\s+quá|hơi\s+cao|giảm\s+giá|bớt\s+không|khuyến\s+mãi|"
    r"ưu\s+đãi|combo|trả\s+góp|để\s+xem\s+thêm|để\s+nghĩ|suy\s+nghĩ\s+thêm|ngân\s+sách)\b"
)
_SUPPORT_RE = re.compile(
    r"(?i)\b(?:bảo\s+hành|đơn\s+hàng|khiếu\s+nại|địa\s+chỉ|ở\s+đâu|chi\s+nhánh|"
    r"mấy\s+giờ\s+mở\s+cửa|sau\s+khi\s+làm|sưng\s+đỏ|bong\s+tróc)\b"
)
_BROAD_PRICE_RE = re.compile(
    r"(?i)^[\s,.\-!]*(?:giá|báo\s+giá|bảng\s+giá|chi\s+phí|giá\s+sao|giá\s+thế\s+nào|"
    r"giá\s+như\s+thế\s+nào|bao\s+nhiêu\s+tiền)[\s?.!]*$"
)


# Golden Few-Shot repository organized by funnel stage / scenario
_GOLDEN_FEW_SHOTS: list[dict[str, str]] = [
    {
        "stage": "CONSULTING",
        "scenario": "PROFILE_UPDATE_MALE",
        "customer": "Mờ thâm và trị mụn lưng -> nhưng tôi là nam",
        "agent": (
            "Dạ phác đồ trị mụn lưng và mờ thâm chuẩn Y khoa bên em hoàn toàn phù hợp và rất hiệu quả với nền da dày, nhiều tuyến bã nhờn của Anh Nam giới ạ, lại có phòng trị liệu riêng tư kín đáo 100%.\n"
            "Để Bác sĩ Chuyên khoa I soi da AI 3D miễn phí và đo mức độ thâm mụn vùng lưng chính xác cho Anh, Anh tiện ghé cơ sở vào khung giờ Sáng hay Chiều hơn ạ?"
        ),
    },
    {
        "stage": "CONSULTING",
        "scenario": "BROAD_PRICE_FIRST_TURN",
        "customer": "Giá sao shop?",
        "agent": (
            "Dạ em chào Chị ạ! Các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em chỉ từ 650.000đ/buổi tùy tình trạng nền da (đã kèm Bác sĩ soi da AI 3D miễn phí 100%, cam kết trọn gói không phụ phí).\n"
            "Hiện tại Chị đang muốn ưu tiên xử lý dứt điểm mụn/thâm, làm mờ sạm nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?"
        ),
    },
    {
        "stage": "CONSULTING",
        "scenario": "SPECIFIC_SYMPTOM_ACNE",
        "customer": "Mình muốn mờ thâm và trị mụn lưng",
        "agent": (
            "Dạ với tình trạng mụn vùng lưng kèm vết thâm, bên em áp dụng Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000đ/buổi, 60 phút, tặng kèm 01 tuýp chấm mụn kháng viêm) giúp gom nhân êm, kháng khuẩn sâu và làm sáng đều vùng da thâm mà không lo sẹo.\n"
            "Hiện tại Chị muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung Sáng hay Chiều, hay cần em tư vấn kỹ hơn về quy trình các bước ạ?"
        ),
    },
    {
        "stage": "OBJECTION_PRICE",
        "scenario": "PRICE_HESITATION_OR_COMBO",
        "customer": "Giá hơi cao nhỉ, để mình suy nghĩ thêm",
        "agent": (
            "Dạ em hoàn toàn hiểu mình cần cân nhắc kỹ ạ! Khi đăng ký theo Combo Tiết Kiệm (3-4 buổi) mình được giảm ngay 10% kèm tặng 1 buổi Detox Oxy (650.000đ), hoặc Combo Toàn Diện (5-9 buổi) giảm 15% nên chi phí mỗi buổi cực kỳ tối ưu và cam kết 0 phát sinh phụ phí.\n"
            "Hay là em cứ giữ trước cho mình suất Bác sĩ soi da AI 3D miễn phí 100% (0đ ràng buộc, không làm cũng không sao) vào Sáng hoặc Chiều mai để mình biết chính xác nền da cần mấy buổi rồi mới quyết định nhé ạ?"
        ),
    },
    {
        "stage": "CLOSING",
        "scenario": "TRIAL_ACCEPTANCE_TO_BOOKING",
        "customer": "Ok thử xem sao / Ok t sẽ thử xem sao",
        "agent": (
            "Dạ tuyệt vời quá ạ! Em đăng ký giữ sẵn suất Bác sĩ Chuyên khoa I soi da AI 3D miễn phí 100% (kèm cam kết văn bản 0đ ràng buộc) để đánh giá chính xác nền da trước cho Chị nhé ạ.\n"
            "Để lễ tân xếp lịch đón tiếp chu đáo không phải chờ đợi, Chị tiện ghé cơ sở bên em vào khung giờ Sáng hay Chiều (hoặc cho em xin Số điện thoại) để em giữ lịch ưu tiên cho mình ạ?"
        ),
    },
    {
        "stage": "CLOSING",
        "scenario": "BOOKING_OR_LEAD",
        "customer": "Cho anh đặt lịch chiều mai nhé",
        "agent": (
            "Dạ em đã giữ sẵn suất ưu tiên Bác sĩ Chuyên khoa I soi da AI 3D miễn phí 100% cho Anh vào khung giờ Chiều mai rồi ạ.\n"
            "Anh cho em xin Số điện thoại hoặc khung giờ cụ thể (14h00 hay 16h00) để lễ tân gửi mã xác nhận lịch hẹn VIP không phải chờ đợi cho Anh nhé ạ?"
        ),
    },
    {
        "stage": "SUPPORT",
        "scenario": "POST_CARE_OR_WARRANTY",
        "customer": "Làm xong có bị sưng đỏ hay cần nghỉ dưỡng không?",
        "agent": (
            "Dạ phác đồ chuẩn Y khoa bên em thực hiện êm dịu, có cam kết bảo hành kết quả bằng văn bản và hoàn toàn không cần nghỉ dưỡng, làm xong mình sinh hoạt làm việc bình thường ngay ạ.\n"
            "Mình muốn bên em đặt lịch Bác sĩ kiểm tra nền da trực tiếp vào khung Sáng hay Chiều để yên tâm trải nghiệm trước 1 buổi ạ?"
        ),
    },
]


def _session_key(page_id: str | None, customer_psid: str | None) -> str:
    return f"{page_id or 'global'}:{customer_psid or 'default'}"


def _detect_topic_from_text(text: str) -> tuple[str | None, str | None, list[str]]:
    """Return (topic_family_code, readable_topic_label, keywords) if matched."""
    if not text:
        return None, None, []
    for code, spec in _TOPIC_FAMILIES.items():
        if spec["match_re"].search(text):
            # Capture user's own phrasing if specific (e.g. "mụn lưng", "mờ thâm")
            matched_terms = list(dict.fromkeys(m.group(0).lower() for m in spec["match_re"].finditer(text)))
            readable = f"{spec['label']} ({', '.join(matched_terms)})" if matched_terms else spec["label"]
            return code, readable, list(spec["keywords"]) + matched_terms
    return None, None, []


def contextualize_memory_node(state: SalesConversationState) -> SalesConversationState:
    """Node 1: Restore conversation memory, lock honorific & active_topic, rewrite query."""
    page_id = state.get("page_id")
    customer_psid = state.get("customer_psid") or "anonymous_customer"
    raw_message = (state.get("message") or "").strip()
    history = state.get("history") or []

    masked_msg, pii_meta = mask_pii(raw_message)

    key = _session_key(page_id, customer_psid)
    prior_mem = _SESSION_MEMORY.get(key, {})

    # Count prior assistant turns
    user_history_texts: list[str] = []
    assistant_turns = 0
    for h in history:
        role = (h.get("role") or "").lower()
        content = (h.get("content") or "").strip()
        if role == "user" and content:
            user_history_texts.append(content)
        elif role == "assistant" and content:
            assistant_turns += 1

    turn_index = assistant_turns + 1

    # 1. Resolve Honorific (sticky once male is stated in history or current message)
    all_user_text = " . ".join(user_history_texts + [raw_message])
    if _MALE_RE.search(raw_message) or _MALE_RE.search(all_user_text):
        honorific = "Anh"
    elif prior_mem.get("honorific") == "Anh" and turn_index > 1:
        honorific = "Anh"
    elif _FEMALE_RE.search(raw_message) or _FEMALE_RE.search(all_user_text):
        honorific = "Chị"
    else:
        honorific = state.get("honorific") or prior_mem.get("honorific") or "Chị"

    # 2. Resolve Active Topic Anchor (current message first, then reverse history, then session memory)
    topic_family, active_topic, topic_keywords = _detect_topic_from_text(raw_message)
    current_has_new_topic = topic_family is not None

    if not topic_family:
        for past_user_msg in reversed(user_history_texts):
            tf, at, kw = _detect_topic_from_text(past_user_msg)
            if tf:
                topic_family, active_topic, topic_keywords = tf, at, kw
                break

    if not topic_family and turn_index > 1:
        topic_family = prior_mem.get("topic_family") or ""
        active_topic = prior_mem.get("active_topic") or ""
        topic_keywords = prior_mem.get("topic_keywords") or []

    topic_family = topic_family or "GENERAL"
    active_topic = active_topic or "Tư vấn chăm sóc & điều trị da chuẩn Y khoa"
    topic_keywords = topic_keywords or []

    # 3. Detect if this turn is a profile/constraint update (e.g., "nhưng tôi là nam")
    is_profile_update_turn = bool(
        turn_index > 1
        and not current_has_new_topic
        and (_MALE_RE.search(raw_message) or _PROFILE_UPDATE_ONLY_RE.search(raw_message))
    )

    is_broad_price_turn = bool(
        turn_index == 1 and not current_has_new_topic and _BROAD_PRICE_RE.search(raw_message)
    )

    # 4. Contextual Query Rewriting: never send a bare "nhưng tôi là nam" to RAG!
    if is_profile_update_turn and topic_family != "GENERAL":
        kw_str = " ".join(topic_keywords[:4])
        rewritten_query = (
            f"{active_topic} {kw_str} phù hợp cho khách hàng {honorific} (Nam giới) — {raw_message}"
        ).strip()
    elif turn_index > 1 and not current_has_new_topic and topic_family != "GENERAL" and len(raw_message) <= 65:
        kw_str = " ".join(topic_keywords[:3])
        rewritten_query = f"{active_topic} ({kw_str}): {raw_message}"
    else:
        rewritten_query = raw_message

    # Persist into session memory when history continues or starts
    _SESSION_MEMORY[key] = {
        "honorific": honorific,
        "topic_family": topic_family,
        "active_topic": active_topic,
        "topic_keywords": topic_keywords,
        "last_turn_index": turn_index,
    }

    return {
        **state,
        "masked_message": masked_msg,
        "pii_metadata": pii_meta,
        "honorific": honorific,
        "active_topic": active_topic,
        "topic_family": topic_family,
        "topic_keywords": topic_keywords,
        "is_profile_update_turn": is_profile_update_turn,
        "is_broad_price_turn": is_broad_price_turn,
        "turn_index": turn_index,
        "rewritten_query": rewritten_query,
    }


def intent_router_node(state: SalesConversationState) -> SalesConversationState:
    """Node 2: Route customer into 1 of 4 sales funnel states (`CONSULTING`, `OBJECTION_PRICE`, `CLOSING`, `SUPPORT`)."""
    raw_message = state.get("message") or ""
    pii_meta = state.get("pii_metadata") or {}

    if pii_meta.get("phones") or _CLOSING_RE.search(raw_message):
        funnel_stage = "CLOSING"
    elif _OBJECTION_PRICE_RE.search(raw_message):
        funnel_stage = "OBJECTION_PRICE"
    elif _SUPPORT_RE.search(raw_message):
        funnel_stage = "SUPPORT"
    else:
        funnel_stage = "CONSULTING"

    return {
        **state,
        "funnel_stage": funnel_stage,
    }


def retrieve_few_shot_node(state: SalesConversationState) -> SalesConversationState:
    """Node 3: Filter off-topic service lines from chunks & pick 2-3 golden few-shot samples."""
    topic_family = state.get("topic_family") or "GENERAL"
    funnel_stage = state.get("funnel_stage") or "CONSULTING"
    is_profile_update = bool(state.get("is_profile_update_turn"))
    is_broad_price = bool(state.get("is_broad_price_turn"))
    honorific = state.get("honorific") or "Chị"
    raw_chunks = state.get("candidate_chunks") or []

    # 1. Topic-Scoped Chunk Filtering: strip lines about unrelated treatments
    spec = _TOPIC_FAMILIES.get(topic_family)
    exclude_re = spec["exclude_line_re"] if spec else None

    filtered_chunks: list[dict[str, Any]] = []
    for ch in raw_chunks:
        text = ch.get("text") or ""
        if exclude_re and text:
            kept_lines = [
                line
                for line in text.splitlines()
                if not exclude_re.search(line)
            ]
            cleaned_text = "\n".join(kept_lines).strip()
            if cleaned_text:
                filtered_chunks.append({**ch, "text": cleaned_text})
        else:
            filtered_chunks.append(ch)

    # 2. Select 2-3 Golden Few-Shot Samples matching the state & scenario
    selected_samples: list[dict[str, str]] = []
    for sample in _GOLDEN_FEW_SHOTS:
        if is_profile_update and sample["scenario"] == "PROFILE_UPDATE_MALE":
            selected_samples.insert(0, sample)
        elif is_broad_price and sample["scenario"] == "BROAD_PRICE_FIRST_TURN":
            selected_samples.insert(0, sample)
        elif sample["stage"] == funnel_stage:
            selected_samples.append(sample)

    # Deduplicate and cap at 2 samples to keep token count ultra-lean
    deduped: list[dict[str, str]] = []
    seen_scenarios: set[str] = set()
    for s in selected_samples:
        if s["scenario"] not in seen_scenarios:
            seen_scenarios.add(s["scenario"])
            # Adapt sample honorific dynamically to match resolved honorific
            adapted_agent = s["agent"]
            if honorific == "Anh":
                adapted_agent = re.sub(r"\bChị\b", "Anh", adapted_agent)
                adapted_agent = re.sub(r"\bchị\b", "anh", adapted_agent)
            deduped.append({
                "scenario": s["scenario"],
                "customer": s["customer"],
                "agent": adapted_agent,
            })
        if len(deduped) >= 2:
            break

    return {
        **state,
        "filtered_chunks": filtered_chunks,
        "selected_few_shots": deduped,
    }


def sales_responder_node(state: SalesConversationState) -> SalesConversationState:
    """Node 4: Assemble focused stage directive & customer_psid-locked tool scope."""
    customer_psid = state.get("customer_psid") or "anonymous_customer"
    honorific = state.get("honorific") or "Chị"
    active_topic = state.get("active_topic") or "Tư vấn chăm sóc & điều trị da"
    topic_family = state.get("topic_family") or "GENERAL"
    funnel_stage = state.get("funnel_stage") or "CONSULTING"
    turn_index = state.get("turn_index") or 1
    is_profile_update = bool(state.get("is_profile_update_turn"))
    selected_few_shots = state.get("selected_few_shots") or []

    lines: list[str] = [
        f"[LANGGRAPH STATE ENGINE — BƯỚC PHỄU: {funnel_stage} | LƯỢT CHAT: #{turn_index}]",
        f"- DANH XƯNG BẮT BUỘC 100%: Gọi khách là \"{honorific}\" (TUYỆT ĐỐI KHÔNG dùng danh xưng khác).",
        f"- CHỦ ĐỀ ĐANG NEO (ACTIVE TOPIC ANCHOR): {active_topic} (Mã nhóm: {topic_family}).",
    ]

    if turn_index >= 2:
        lines.append(
            "- QUY TẮC ĐA LƯỢT: Đây là lượt chat tiếp nối (Turn >= 2). CẤM TUYỆT ĐỐI chào lại "
            "(\"Dạ em chào...\") và CẤM giới thiệu lại danh mục dịch vụ tổng quan từ đầu!"
        )

    if is_profile_update:
        lines.append(
            f"- CHỈ THỊ ĐẶC BIỆT (CẬP NHẬT HỒ SƠ KHÁCH HÀNG): Khách vừa bổ sung thông tin cá nhân "
            f"(\"{state.get('message')}\") trong khi đang tìm hiểu về [{active_topic}]. "
            f"Hãy xác nhận ngay rằng phác đồ [{active_topic}] hoàn toàn phù hợp & hiệu quả cao với {honorific} "
            f"(có phòng trị liệu riêng tư kín đáo), giữ nguyên mạch tư vấn [{active_topic}] và "
            f"CẤM nhắc tới các dịch vụ ngoài lề (như Nám Laser Pico hay Meso Collagen)!"
        )
    elif funnel_stage == "OBJECTION_PRICE":
        lines.append(
            "- CHỈ THỊ BƯỚC OBJECTION_PRICE: Đồng cảm nhẹ nhàng, phân tích giá trị trọn gói 0 phụ phí "
            "hoặc quyền lợi giữ suất Bác sĩ soi da AI 3D miễn phí 100% (0đ ràng buộc) trước khi quyết định."
        )
    elif funnel_stage == "CLOSING":
        lines.append(
            f"- CHỈ THỊ BƯỚC CLOSING: Xác nhận giữ suất ưu tiên cho {honorific} và hỏi gọn khung giờ "
            "(Sáng hay Chiều) hoặc SĐT để gửi mã lịch hẹn VIP."
        )
    elif funnel_stage == "SUPPORT":
        lines.append(
            f"- CHỈ THỊ BƯỚC SUPPORT: Giải đáp trực diện thắc mắc kỹ thuật/bảo hành cho {honorific} "
            "bằng dữ liệu tài liệu nguồn, sau đó mời đặt lịch kiểm tra trực tiếp."
        )

    if selected_few_shots:
        lines.append("\n[VĂN MẪU THỰC CHIẾN CHUẨN MỰC CHO TRẠNG THÁI HIỆN TẠI]:")
        for idx, fs in enumerate(selected_few_shots, start=1):
            lines.append(f"  Mẫu #{idx} ({fs['scenario']}):")
            lines.append(f"    Khách: {fs['customer']}")
            lines.append(f"    Sale mẫu: {fs['agent']}")

    allowed_tools_by_stage = {
        "CONSULTING": ["lookup_topic_scoped_catalog", "check_free_ai_skin_scan_slot"],
        "OBJECTION_PRICE": ["calculate_combo_privilege", "reserve_zero_risk_scan_slot"],
        "CLOSING": ["book_vip_appointment_locked_psid", "capture_lead_phone_locked_psid"],
        "SUPPORT": ["lookup_order_or_warranty_locked_psid"],
    }

    safe_tool_scope = {
        "customer_psid": customer_psid,
        "psid_locked": True,
        "funnel_stage": funnel_stage,
        "allowed_tools": allowed_tools_by_stage.get(funnel_stage, ["lookup_topic_scoped_catalog"]),
    }

    return {
        **state,
        "stage_directive": "\n".join(lines),
        "safe_tool_scope": safe_tool_scope,
    }


def _build_compiled_sales_graph():
    """Compile the LangGraph StateGraph (or return None if langgraph unavailable)."""
    if not _HAS_LANGGRAPH or StateGraph is None:
        return None
    builder = StateGraph(SalesConversationState)
    builder.add_node("contextualize_memory_node", contextualize_memory_node)
    builder.add_node("intent_router_node", intent_router_node)
    builder.add_node("retrieve_few_shot_node", retrieve_few_shot_node)
    builder.add_node("sales_responder_node", sales_responder_node)

    builder.add_edge(START, "contextualize_memory_node")
    builder.add_edge("contextualize_memory_node", "intent_router_node")
    builder.add_edge("intent_router_node", "retrieve_few_shot_node")
    builder.add_edge("retrieve_few_shot_node", "sales_responder_node")
    builder.add_edge("sales_responder_node", END)

    checkpointer = MemorySaver() if MemorySaver is not None else None
    return builder.compile(checkpointer=checkpointer)


_COMPILED_SALES_GRAPH = _build_compiled_sales_graph()


def run_sales_consultation_graph(payload: dict[str, Any]) -> dict[str, Any]:
    """Execute the 4-node Hot-Path LangGraph for a customer turn."""
    initial_state: SalesConversationState = {
        "customer_psid": payload.get("customer_psid") or "anonymous_customer",
        "page_id": payload.get("page_id"),
        "message": payload.get("message") or "",
        "history": payload.get("history") or [],
        "candidate_chunks": payload.get("candidate_chunks") or [],
        "candidate_rules": payload.get("candidate_rules") or [],
        "honorific": payload.get("honorific") or "",
    }

    thread_id = _session_key(initial_state.get("page_id"), initial_state.get("customer_psid"))
    if _COMPILED_SALES_GRAPH is not None:
        result = _COMPILED_SALES_GRAPH.invoke(
            initial_state,
            config={"configurable": {"thread_id": thread_id}},
        )
    else:
        s1 = contextualize_memory_node(initial_state)
        s2 = intent_router_node(s1)
        s3 = retrieve_few_shot_node(s2)
        result = sales_responder_node(s3)

    return {
        "engine": "langgraph-state-engine" if _COMPILED_SALES_GRAPH is not None else "state-graph-fallback",
        "customer_psid": result.get("customer_psid"),
        "thread_id": thread_id,
        "honorific": result.get("honorific"),
        "active_topic": result.get("active_topic"),
        "topic_family": result.get("topic_family"),
        "topic_keywords": result.get("topic_keywords", []),
        "is_profile_update_turn": result.get("is_profile_update_turn", False),
        "is_broad_price_turn": result.get("is_broad_price_turn", False),
        "turn_index": result.get("turn_index", 1),
        "funnel_stage": result.get("funnel_stage", "CONSULTING"),
        "rewritten_query": result.get("rewritten_query", ""),
        "filtered_chunks": result.get("filtered_chunks", []),
        "selected_few_shots": result.get("selected_few_shots", []),
        "stage_directive": result.get("stage_directive", ""),
        "safe_tool_scope": result.get("safe_tool_scope", {}),
        "pii_metadata": result.get("pii_metadata", {}),
    }
