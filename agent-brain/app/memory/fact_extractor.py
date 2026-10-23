"""Domain-Agnostic Customer Fact, Objection, Intent & Stage Extractor (Section 8)."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from app.memory.decay_policy import compute_expiry_for_fact
from app.memory.models import MemoryFact, ObjectionItem, PurchaseStage
from app.memory.normalizer import mask_sensitive_pii, normalize_vietnamese_message

# 1. Domain-agnostic entity & preference patterns
_SIZE_RE = re.compile(r"(?i)\b(?:size|cỡ|kích\s*cỡ)\s*([0-9]{2}|xxs|xs|s|m|l|xl|xxl|2xl|3xl)\b")
_COLOR_RE = re.compile(
    r"(?i)\b(?:màu|tone|tông)\s+(đen|trắng|đỏ|xanh(?:\s+rêu|\s+navy|\s+dương|\s+lá)?|vàng|hồng|nâu|be|kem|xám|ghi|tím)\b"
)
_SKU_OR_CODE_RE = re.compile(r"\b([A-Za-z]{2,5}[-_]?\d{2,5})\b")
_PRICE_TOKEN_RE = re.compile(r"(?i)\b(\d{1,3}(?:\.\d{3})+|\d{2,4}k)\b")
_BARE_PACKAGE_PRICE_RE = re.compile(
    r"(?i)(?:\b(?:gói|mức|giá|và|với|so\s+với)\s+)(\d{3})(?!\s*(?:phút|p\b|buổi|ngày|ml|g\b|kg|%|m\b))"
)
_LOCATION_RE = re.compile(
    r"(?i)\b(hà\s+nội|tp\.?\s*hcm|sài\s+gòn|quận\s+\d+|đà\s+nẵng|hải\s+phòng|cần\s+thơ)\b"
)
_MALE_RE = re.compile(
    r"(?i)\b(?:tôi\s+là\s+nam|mình\s+là\s+nam|anh\s+muốn|anh\s+cần|cho\s+anh\s+hỏi|khách\s+nam|nam\s+giới)\b"
)
_FEMALE_RE = re.compile(
    r"(?i)\b(?:tôi\s+là\s+nữ|mình\s+là\s+nữ|chị\s+muốn|chị\s+cần|cho\s+chị\s+hỏi|bầu|sau\s+sinh)\b"
)
_CALL_REQUEST_RE = re.compile(
    r"(?i)\b(?:gọi(?:\s+điện|\s+lại)?\s+cho|alo\s+cho|liên\s+hệ\s+số|tư\s+vấn\s+qua\s+điện\s+thoại)\b"
)
_TIME_SLOT_RE = re.compile(
    r"(?i)\b((?:sáng|chiều|tối|trưa)(?:\s+nay|\s+mai|\s+thứ\s+\w+|\s+cuối\s+tuần)?|\d{1,2}h(?:\d{2})?)\b"
)
_NEED_OR_SYMPTOM_RE = re.compile(
    r"(?i)\b(mỏi\s+vai\s+gáy|đau\s+cổ\s+vai\s+gáy|mỏi\s+lưng|mất\s+ngủ|đau\s+đầu|thư\s+giãn\s+toàn\s+thân|"
    r"mụn\s+lưng|mụn\s+ẩn|mờ\s+thâm|trị\s+nám|sẹo\s+rỗ|da\s+nhạy\s+cảm)\b"
)

# 2. Objection & Sentiment patterns
_PRICE_INQUIRY_RE = re.compile(
    r"(?i)\b(giá|báo\s+giá|bảng\s+giá|giá\s+sao|bao\s+nhiêu|chi\s+phí|nhiêu\s+tiền|xin\s+giá|giá\s+cả|tư\s+vấn\s+giá)\b"
)
_PRICE_OBJECTION_RE = re.compile(
    r"(?i)\b(đắt|hơi\s+đắt|đắt\s+vậy|đắt\s+thế|vẫn\s+đắt|giá\s+cao|hơi\s+cao|cao\s+thế|mắc|chát|"
    r"rẻ\s+hơn|mềm\s+hơn|vượt\s+ngân\s+sách|không\s+đủ\s+tiền|chưa\s+đủ\s+tiền)\b"
)
_PROMO_INQUIRY_RE = re.compile(
    r"(?i)\b(ưu\s+đãi|khuyến\s+mãi|giảm\s+giá|voucher|combo|chiết\s+khấu|tặng\s+kèm|quà\s+tặng|bớt\s+không)\b"
)
_SHIPPING_OBJECTION_RE = re.compile(
    r"(?i)\b(bao\s+giờ\s+mới\s+tới|ship\s+lâu|giao\s+lâu|phí\s+ship\s+cao|mấy\s+ngày\s+tới|ship\s+về)\b"
)
_SKEPTICAL_OR_TRUST_RE = re.compile(
    r"(?i)\b(sợ\s+không\s+giống|sợ\s+hớ|không\s+đứng\s+form|có\s+bảo\s+hành\s+không|có\s+cam\s+kết\s+không|"
    r"có\s+phụ\s+phí|có\s+nhận\s+tip|sợ\s+đau|có\s+hiệu\s+quả\s+không|hàng\s+chuẩn\s+không|kiểm\s+tra\s+hàng)\b"
)
_HESITATION_DELAY_RE = re.compile(
    r"(?i)\b(lương\s+về|để\s+hôm\s+nào|để\s+suy\s+nghĩ|suy\s+nghĩ\s+thêm|nghĩ\s+thêm|tối\s+về\s+nhắn|"
    r"hỏi\s+ý\s+kiến|để\s+xem\s+đã|chưa\s+vội|khi\s+khác)\b"
)
_COMPLAINT_OR_REFUND_RE = re.compile(
    r"(?i)\b(khiếu\s+nại|hoàn\s+tiền|đổi\s+trả|bực\s+mình|lừa\s+đảo|tệ\s+quá|hàng\s+lỗi|kích\s+ứng)\b"
)
_HUMAN_REQUEST_RE = re.compile(
    r"(?i)\b(gặp\s+nhân\s+viên|gặp\s+người\s+thật|gặp\s+quản\s+lý|chuyển\s+nhân\s+viên|nói\s+chuyện\s+với\s+người)\b"
)
_BOT_FAILURE_FEEDBACK_RE = re.compile(
    r"(?i)\b(không\s+phải\s+ý\s+đó|hiểu\s+sai\s+rồi|trả\s+lời\s+sai|hỏi\s+lặp\s+lại|không\s+hiểu\s+à)\b"
)
_READY_TO_BUY_SIGNAL_RE = re.compile(
    r"(?i)\b(chốt|lấy\s+giúp|đặt\s+lịch|giữ\s+lịch|giữ\s+chỗ|thử\s+trước|thử\s+1\s+buổi|muốn\s+thử|"
    r"còn\s+hàng\s+không|có\s+sẵn\s+không|ship\s+về|ok\s+đẹp\s+đấy|lấy\s+màu|lấy\s+gói)\b"
)
_COMPARISON_SIGNAL_RE = re.compile(
    r"(?i)\b(khác\s+gì\s+nhau|so\s+sánh|chênh\s+nhau|hay\s+là\s+gói|phân\s+vân\s+giữa)\b"
)


class ExtractedTurnSignals:
    def __init__(
        self,
        normalized_text: str,
        masked_text: str,
        pii_meta: dict[str, list[str]],
        intent: str,
        sentiment: str,
        purchase_stage: PurchaseStage,
        facts: list[MemoryFact],
        objections: list[tuple[str, str, float]],
        is_bot_misunderstanding: bool,
        is_explicit_human_request: bool,
        is_complaint_or_refund: bool,
    ) -> None:
        self.normalized_text = normalized_text
        self.masked_text = masked_text
        self.pii_meta = pii_meta
        self.intent = intent
        self.sentiment = sentiment
        self.purchase_stage = purchase_stage
        self.facts = facts
        self.objections = objections
        self.is_bot_misunderstanding = is_bot_misunderstanding
        self.is_explicit_human_request = is_explicit_human_request
        self.is_complaint_or_refund = is_complaint_or_refund


def extract_turn_signals(
    tenant_id: str,
    customer_id: str,
    raw_message: str,
    evidence_message_id: int | None = None,
    now: datetime | None = None,
) -> ExtractedTurnSignals:
    current_time = now or datetime.now(timezone.utc)
    normalized = normalize_vietnamese_message(raw_message)
    masked, pii_meta = mask_sensitive_pii(raw_message)
    ev_ids = [evidence_message_id] if evidence_message_id is not None else []

    facts: list[MemoryFact] = []
    objections: list[tuple[str, str, float]] = []

    def _add_fact(key: str, value: str, source: str = "explicit", conf: float = 0.95) -> None:
        facts.append(
            MemoryFact(
                tenant_id=tenant_id,
                customer_id=customer_id,
                fact_key=key,
                fact_value=value,
                source_type=source,  # type: ignore[arg-type]
                confidence=conf,
                evidence_message_ids=ev_ids,
                effective_from=current_time,
                effective_to=compute_expiry_for_fact(key, source, current_time),
                last_confirmed_at=current_time,
            )
        )

    # 1. Extract explicit preferences (size, color, SKU/code, location, honorific, contact)
    size_m = _SIZE_RE.search(normalized)
    if size_m:
        _add_fact("preferred_size", size_m.group(1).upper(), "explicit", 0.95)

    color_m = _COLOR_RE.search(normalized)
    if color_m:
        _add_fact("color_preference", color_m.group(1).lower(), "explicit", 0.96)
    else:
        # Handle "lấy màu trắng", "đổi sang trắng"
        alt_color = re.search(
            r"(?i)\b(?:lấy|chọn|đổi\s+sang|thích)\s+(?:màu\s+)?(đen|trắng|đỏ|xanh(?:\s+rêu|\s+navy)?|vàng|hồng|nâu|be|kem|xám)\b",
            normalized,
        )
        if alt_color:
            _add_fact("color_preference", alt_color.group(1).lower(), "explicit", 0.95)

    for sku_m in _SKU_OR_CODE_RE.finditer(raw_message):
        code = sku_m.group(1).upper()
        if not code.startswith(("VN", "TP")) and any(ch.isdigit() for ch in code):
            _add_fact("product_interest", code, "explicit", 0.92)

    loc_m = _LOCATION_RE.search(normalized)
    if loc_m:
        _add_fact("location_hint", loc_m.group(1).strip(), "explicit", 0.92)

    if _MALE_RE.search(normalized):
        _add_fact("honorific", "Anh", "explicit", 0.98)
    elif _FEMALE_RE.search(normalized):
        _add_fact("honorific", "Chị", "explicit", 0.98)

    if _CALL_REQUEST_RE.search(normalized) or pii_meta.get("phones"):
        _add_fact("preferred_contact", "phone", "explicit", 0.96)

    need_m = _NEED_OR_SYMPTOM_RE.search(normalized)
    if need_m:
        _add_fact("symptom_or_need", need_m.group(1).lower(), "explicit", 0.94)

    slot_m = _TIME_SLOT_RE.search(normalized)
    if slot_m:
        _add_fact("time_slot_preference", slot_m.group(1).strip(), "explicit", 0.90)

    # Extract price mentions / target budget
    prices: list[str] = [m.group(1) for m in _PRICE_TOKEN_RE.finditer(normalized)]
    for bm in _BARE_PACKAGE_PRICE_RE.finditer(normalized):
        prices.append(f"{bm.group(1)}k")
    if prices:
        _add_fact("price_anchor", ", ".join(dict.fromkeys(prices)), "explicit", 0.88)

    # 2. Extract objections & nuanced intent (Section 8.2 & 8.3: do not over-infer!)
    has_price_obj = bool(_PRICE_OBJECTION_RE.search(normalized))
    has_promo_inquiry = bool(_PROMO_INQUIRY_RE.search(normalized)) and not has_price_obj
    has_price_inquiry = bool(_PRICE_INQUIRY_RE.search(normalized)) and not has_price_obj and not has_promo_inquiry
    has_ship_obj = bool(_SHIPPING_OBJECTION_RE.search(normalized))
    has_skepticism = bool(_SKEPTICAL_OR_TRUST_RE.search(normalized))
    has_hesitation = bool(_HESITATION_DELAY_RE.search(normalized))
    has_complaint = bool(_COMPLAINT_OR_REFUND_RE.search(normalized))
    has_human_req = bool(_HUMAN_REQUEST_RE.search(normalized))
    has_bot_failure = bool(_BOT_FAILURE_FEEDBACK_RE.search(normalized))
    has_ready_buy = bool(_READY_TO_BUY_SIGNAL_RE.search(normalized))
    has_comparison = bool(_COMPARISON_SIGNAL_RE.search(normalized))

    if has_price_obj:
        objections.append(("price", raw_message.strip(), 0.92))
        _add_fact("objection_price", "Khách băn khoăn về giá hoặc hỏi ưu đãi/gói mềm hơn", "explicit", 0.90)

    if has_ship_obj:
        objections.append(("shipping_time", raw_message.strip(), 0.90))

    if has_skepticism:
        objections.append(("trust", raw_message.strip(), 0.88))

    if has_hesitation:
        objections.append(("timing", raw_message.strip(), 0.85))
        # Section 8.3: "để hôm nào lương về mình mua" -> inferred intent with temporary financial/timing delay, NOT confirmed purchase!
        if "lương" in normalized.lower():
            _add_fact(
                "purchase_intent",
                "có ý định mua nhưng tạm hoãn đến khi có lương (chưa chốt)",
                "inferred",
                0.75,
            )
        else:
            _add_fact(
                "purchase_intent",
                "đang cân nhắc/tạm hoãn quyết định, cần giữ quyền lợi khéo léo",
                "inferred",
                0.72,
            )
    elif has_ready_buy:
        _add_fact(
            "purchase_intent",
            "tín hiệu sẵn sàng trải nghiệm/mua hàng cao",
            "explicit",
            0.92,
        )

    if has_complaint:
        _add_fact("complaint_history", f"Khách phản ánh/khiếu nại: {masked}", "explicit", 0.99)

    # 3. Classify sentiment, intent, and purchase stage
    if has_complaint or has_bot_failure:
        sentiment = "negative"
    elif has_price_obj or has_skepticism:
        sentiment = "cautious"
    elif has_ready_buy:
        sentiment = "positive"
    else:
        sentiment = "neutral"

    if has_complaint:
        intent = "COMPLAINT_OR_REFUND"
        stage: PurchaseStage = "COMPLAINING"
    elif has_human_req or _CALL_REQUEST_RE.search(normalized):
        intent = "HUMAN_OR_CALL_REQUEST"
        stage = "READY_TO_BUY" if pii_meta.get("phones") else "COMPARING"
    elif has_hesitation:
        intent = "HESITATION_OR_DELAY"
        stage = "HESITATING"
    elif has_promo_inquiry:
        intent = "PROMO_OR_DISCOUNT_INQUIRY"
        stage = "COMPARING"
    elif has_price_obj:
        intent = "PRICE_OBJECTION_OR_PROMO"
        stage = "PRICE_SENSITIVE"
    elif has_price_inquiry:
        intent = "PRICE_INQUIRY"
        stage = "COMPARING"
    elif has_skepticism:
        intent = "QUALITY_OR_RISK_CONCERN"
        stage = "SKEPTICAL"
    elif has_comparison:
        intent = "PRODUCT_COMPARISON"
        stage = "COMPARING"
    elif has_ready_buy or pii_meta.get("phones"):
        intent = "PURCHASE_OR_BOOKING_SIGNAL"
        stage = "READY_TO_BUY"
    else:
        intent = "DISCOVERY_OR_CONSULTATION"
        stage = "CURIOUS"

    return ExtractedTurnSignals(
        normalized_text=normalized,
        masked_text=masked,
        pii_meta=pii_meta,
        intent=intent,
        sentiment=sentiment,
        purchase_stage=stage,
        facts=facts,
        objections=objections,
        is_bot_misunderstanding=has_bot_failure,
        is_explicit_human_request=has_human_req,
        is_complaint_or_refund=has_complaint,
    )
