"""5 Core Psychological Sales Skills Matrix (100% Domain-Agnostic, 2-3 Bubble 3-Beat Blueprints).

Implements:
1. `tactical_empathy_voss` (Chris Voss - Never Split the Difference)
2. `spin_micro_diagnostic` (Neil Rackham - High-Velocity SPIN Frame Control)
3. `behavioral_value_reframing` (Daniel Kahneman, Richard Thaler & Robert Cialdini)
4. `loss_aversion_urgency` (Prospect Theory 2.25x - Authentic Operational Scarcity & Priority Hold)
5. `assumptive_double_bind_close` (Ericksonian Double-Bind & Assumptive Close)
"""

from __future__ import annotations

from app.memory.models import (
    CustomerMemoryContext,
    PsychologicalSkillDirective,
    PurchaseStage,
)


def compile_audience_voice_cadence(domain_meta: dict | None, honorific: str) -> str:
    """Synthesizes the emotional cadence & tone profile from the Page's configured target_audience, industry, and tone."""
    meta = domain_meta or {}
    audience = str(meta.get("target_audience") or "").strip()
    industry = str(meta.get("industry") or "").strip()
    tone = str(meta.get("tone_style") or "").strip()
    combined = f"{audience} {industry} {tone}".lower()

    if any(k in combined for k in ("dưỡng sinh", "massage", "spa", "thư giãn", "chăm sóc", "văn phòng", "làm đẹp")):
        base_cadence = (
            f"Dịu dàng, tinh tế, ấm áp và giàu tính chữa lành (Soothing & Sensory Empathy) — "
            f"dùng từ ngữ gợi cảm giác xúc giác thực tế (nhẹ bẫng, ủ ấm, mở từng điểm bó cơ, thư thái sâu) "
            f"để {honorific} đọc lên cảm thấy được thấu hiểu và tò mò muốn trải nghiệm ngay."
        )
    elif any(k in combined for k in ("phòng khám", "y khoa", "bác sĩ", "da liễu", "nha khoa", "thẩm mỹ")):
        base_cadence = (
            f"Điềm đạm, ân cần, chuẩn mực Chuyên gia (Reassuring & Trustworthy) — "
            f"lắng nghe tinh tế, giải tỏa lo lắng nhẹ nhàng để {honorific} cảm thấy an tâm tuyệt đối."
        )
    elif any(k in combined for k in ("thời trang", "mỹ phẩm", "gen z", "trẻ", "phụ kiện")):
        base_cadence = (
            f"Thân thiện, cuốn hút, tinh tế và truyền cảm hứng (Warm & Inspiring) — "
            f"khơi gợi hình ảnh {honorific} tự tin, rạng rỡ khi trải nghiệm sản phẩm."
        )
    else:
        base_cadence = (
            f"Thân thiện, dịu dàng, khéo léo và giàu sự quan tâm chân thành — "
            f"tạo thiện cảm ấm áp ngay từ câu đầu tiên để giữ chân {honorific}."
        )

    details = []
    if audience:
        details.append(f"Tệp KH mục tiêu: [{audience}]")
    if tone:
        details.append(f"Giọng điệu Page: [{tone}]")
    if details:
        return f"{' | '.join(details)} -> {base_cadence}"
    return base_cadence


def select_psychological_skill(
    stage: PurchaseStage,
    intent: str,
    memory_ctx: CustomerMemoryContext,
    domain_meta: dict | None = None,
) -> tuple[PsychologicalSkillDirective, str, int]:
    """Selects the single highest-impact psychological skill and returns (directive, archetype, buying_temp)."""
    h = memory_ctx.honorific or "Anh/Chị"
    meta = domain_meta or {}
    is_first_turn = bool(meta.get("is_first_turn", False)) or (
        len(memory_ctx.active_facts) == 0 and memory_ctx.session.last_intent is None
    )
    audience_cadence = compile_audience_voice_cadence(meta, h)
    price_obj_count = next((o.count for o in memory_ctx.objections if o.type == "price"), 0)

    # 0. TURN-1 ZERO-PRICE IMPRESSION & CURIOSITY OPENER (Khách mới nhắn câu đầu tiên)
    if is_first_turn and stage not in ("READY_TO_BUY", "COMPLAINING"):
        bubble_plan = [
            f"Bong bóng 1 (Chạm Thiện Cảm & Gợi Hình Trải Nghiệm - TUYỆT ĐỐI 0 BÁO GIÁ): Mở đầu thân thiện đúng âm hưởng tệp khách hàng của Page, đồng cảm dịu dàng với cảm giác/nhu cầu của {h} và khéo léo gợi mở hình ảnh trải nghiệm đặc trưng nhất khiến {h} tò mò (TUYỆT ĐỐI CẤM nêu giá tiền, con số VNĐ hay mã gói ở lượt đầu tiên!).",
            f"Bong bóng 2 (Câu Hỏi Khơi Gợi Tò Mò - 0 Áp Lực): Đặt 1 câu hỏi thật nhẹ nhàng, tinh tế về cảm giác hoặc mong muốn hiện tại của {h} để {h} hào hứng chia sẻ tiếp (TUYỆT ĐỐI CẤM hỏi gạ chốt lịch Sáng/Chiều ở lượt mở đầu).",
        ]
        directive = PsychologicalSkillDirective(
            skill_code="spin_micro_diagnostic",
            skill_name="Nghệ thuật Mở Đầu Gây Ấn Tượng & Gợi Mở Tò Mò (Zero-Price First Touch & Sensory Hook)",
            academic_origin="Consultative Selling & Sensory Curiosity Hook (Neil Rackham SPIN + Robert Cialdini Mystery Hook)",
            trigger_reason="Lượt mở đầu cuộc trò chuyện (First Touch): Ưu tiên tạo thiện cảm sâu sắc, giữ chân khách và khơi gợi sự tò mò trước khi báo giá.",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Gợi mở tinh tế trải nghiệm thực tế và sự chăm chút tỉ mỉ của bên em mà không dùng bất kỳ con số giá tiền nào.",
            scarcity_or_close_hint="Khoan báo giá và khoan chốt lịch ở lượt 1; dùng câu hỏi chạm đúng cảm giác để khách muốn trả lời tiếp.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
            price_disclosure_gate="WITHHOLD_NUMBERS_HOOK_FIRST",
        )
        return directive, "FIRST_IMPRESSION_CURIOSITY", 48

    # 1. SKILL 1: tactical_empathy_voss (Khách nghi ngờ chất lượng, sợ rủi ro, sợ phát sinh chi phí)
    if stage == "SKEPTICAL" or intent == "QUALITY_OR_RISK_CONCERN":
        bubble_plan = [
            f"Bong bóng 1 (Labeling & Accusation Audit): Gọi tên chính xác nỗi băn khoăn thầm kín của {h} ngay câu đầu ('Dạ em rất hiểu tâm lý {h} khi tìm hiểu thường lo nhất là chất lượng thực tế hoặc chi phí phát sinh...') để làm dịu phòng vệ.",
            f"Bong bóng 2 (Risk Reversal & Subtle Flex): Đưa ra cơ chế bảo chứng 0 rủi ro có thật trong tài liệu của Page bằng giọng tự tin về chất lượng/tay nghề.",
            f"Bong bóng 3 (Chuyển hướng nhẹ nhàng): 1 câu hỏi lựa chọn ngắn gọn xoay quanh nhu cầu thực tế của {h}.",
        ]
        directive = PsychologicalSkillDirective(
            skill_code="tactical_empathy_voss",
            skill_name="Kỹ năng Giải tỏa Phòng vệ & Làm chủ Cảm xúc (Tactical Empathy - Chris Voss)",
            academic_origin="Chris Voss (FBI Negotiation - Labeling & Accusation Audit)",
            trigger_reason="Khách đang có tâm lý phòng vệ, nghi ngờ chất lượng/hiệu quả hoặc lo ngại rủi ro.",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Khẳng định tự tin chuẩn mực chất lượng thực tế và cam kết minh bạch 100% dựa trên dữ liệu Page.",
            scarcity_or_close_hint="Dùng cơ chế Bảo chứng rủi ro bằng 0 (Risk Reversal) để xóa rào cản tâm lý trước khi hỏi nhu cầu.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
        )
        return directive, "SKEPTICAL_CAUTIOUS", 55

    # 1.5. SKILL PRICE INQUIRY: SALES_ANTI_INFO_DUMPING_PACING (Khách hỏi giá chung / bảng giá)
    if intent == "PRICE_INQUIRY":
        bubble_plan = [
            f"Bong bóng 1 (Neo Khoảng Giá & Phễu 2 Tầng - SKILL 02/03): Nếu khách hỏi giá chung, chỉ tung khoảng giá 129k - 580k (trọn gói 0 Tip). Nếu khách đã nêu triệu chứng đau mỏi cổ vai gáy, neo 2 gói: Massage Ngâm Thảo Dược & Trị Liệu (530k - 80 phút) và Massage Trị Liệu Cơ Bản (399k - 60 phút).",
            f"Bong bóng 2 (SPIN Choice A/B): Đặt 1 câu hỏi A/B dưới 16 chữ giúp {h} chọn giữa ngâm thảo dược toàn diện (530k) hay ấn huyệt cơ bản (399k).",
        ]
        directive = PsychologicalSkillDirective(
            skill_code="SALES_ANTI_INFO_DUMPING_PACING",
            skill_name="[SKILL 02 & 03 - Neo Khoảng Giá & Phễu 2 Tầng] Báo Giá Kép 530k vs 399k & Minh Bạch 0 Tip",
            academic_origin="Robert Cialdini (Contrast Principle) & Price Framing",
            trigger_reason="Khách hỏi giá trực tiếp: Neo khoảng giá hoặc 2 tầng giá 530k vs 399k thay vì xả cả bảng giá.",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Khẳng định mức giá niêm yết trọn gói 100%, không phát sinh phụ phí ẩn và không nhận Tip.",
            scarcity_or_close_hint="Dùng câu hỏi A/B giữa 2 mốc giá để khách dễ dàng chọn hướng phù hợp.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
        )
        return directive, "PRICE_CONSCIOUS", 60

    # 1.8. SKILL 06: first_time_perk_lock (Khách hỏi ưu đãi / khuyến mãi: "thế có ưu đãi gì k")
    if intent == "PROMO_OR_DISCOUNT_INQUIRY":
        bubble_plan = [
            f"Bong bóng 1 (Giữ Nguyên Gói Hiện Tại + Tặng Đặc Quyền Buổi Lẻ Đầu Tiên - SKILL 06): Giữ nguyên gói đang tư vấn (VD: 239k hoặc 399k, TUYỆT ĐỐI KHÔNG tự ý tụt xuống 129k lấy ráy tai). Nêu Đặc quyền trải nghiệm buổi lẻ đầu tiên (miễn phí ngâm chân thảo dược ấm + trà dưỡng nhan + điểm tâm thực dưỡng, trọn gói 0 Tip) rồi giới thiệu thêm ưu đãi mua 5 buổi tặng Voucher giảm 10%.",
            f"Bong bóng 2 (Chốt Thử 1 Buổi Lẻ Trước): Đặt 1 câu hỏi gợi ý {h} đặt lịch trải nghiệm thử 1 buổi lẻ trước để kiểm chứng tay nghề KTV hay đăng ký gói 5 buổi tiết kiệm 10%.",
        ]
        directive = PsychologicalSkillDirective(
            skill_code="first_time_perk_lock",
            skill_name="[SKILL 06 - Khóa Gói & Tặng Đặc Quyền Trải Nghiệm] Xử Lý Hỏi Ưu Đãi & Chốt Thử 1 Buổi",
            academic_origin="Robert Cialdini (Reciprocity & Value Stacking) + Risk Reversal",
            trigger_reason="Khách chủ động hỏi ưu đãi/khuyến mãi ('thế có ưu đãi gì k'): Tín hiệu mua hàng tích cực, tuyệt đối không hạ cấp dịch vụ.",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Tặng kèm đặc quyền ngâm chân thảo dược, trà dưỡng nhan và điểm tâm thực dưỡng miễn phí ngay từ buổi lẻ đầu tiên.",
            scarcity_or_close_hint="Mời khách trải nghiệm thử 1 buổi lẻ để kiểm chứng tay nghề trước khi quyết định gói 5 buổi.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
        )
        return directive, "PROMO_HUNTER_WARM", 68

    # 2. SKILL 04 & 05: behavioral_value_reframing (Khách chê đắt lần 1 -> SKILL 04; Khách chê đắt lần 2 -> SKILL 05)
    if stage == "PRICE_SENSITIVE" or intent in ("PRICE_OBJECTION_OR_PROMO", "PRODUCT_COMPARISON"):
        if price_obj_count >= 2:
            bubble_plan = [
                f"Bong bóng 1 (Bẻ Hướng Sang Gói Nhập Môn 239k - SKILL 05): Thấu cảm tinh tế tâm lý {h} lần đầu muốn chọn gói gọn nhẹ nhất để kiểm chứng tay nghề trước, và bẻ lái sang gói Gội đầu dưỡng sinh & Massage cổ vai gáy (239k - 60 phút, vẫn ấn huyệt giải mỏi cổ vai gáy kèm chườm ngải cứu ấm, trọn gói 0 Tip). TUYỆT ĐỐI CẤM hạ xuống gói 129k Lấy ráy tai khi khách đau mỏi cổ vai gáy!",
                f"Bong bóng 2 (Zero-Pressure Micro-Trial): Đặt 1 câu hỏi nhẹ nhàng mời {h} trải nghiệm thử 1 buổi gói 239k này vào khung giờ Sáng hay Chiều.",
            ]
        else:
            bubble_plan = [
                f"Bong bóng 1 (Bảo Vệ Giá Trị & Chia Nhỏ Chi Phí - SKILL 04): Đồng cảm tự nhiên (CẤM xin lỗi vì giá, CẤM hỏi ngược 'thấy hơi đắt nhỉ?'), bảo vệ giá trị gói 399k (~6.5k/phút cho 60 phút trị liệu chuyên sâu + ngâm chân thảo dược + điểm tâm thực dưỡng + 0 Tip), đồng thời giữ mở lựa chọn gói 399k hoặc gợi mở nhẹ gói dưỡng sinh cổ vai gáy mềm hơn.",
                f"Bong bóng 2 (Give-to-Get Choice): Đặt 1 câu hỏi A/B dưới 16 chữ giúp {h} dễ dàng chọn trải nghiệm thử 1 buổi.",
            ]
        directive = PsychologicalSkillDirective(
            skill_code="behavioral_value_reframing",
            skill_name="[SKILL 04 & 05 - Bảo Vệ Giá Trị & Bẻ Hướng 239k] Kỹ năng Flex Giá Trị & Bẻ Lái Sang Gói Nhập Môn 239k",
            academic_origin="Daniel Kahneman, Richard Thaler & Robert Cialdini (Price Reframing & Down-sell Pivot)",
            trigger_reason=f"Khách đang cân nhắc/chê đắt (số lần phản kháng giá: {max(1, price_obj_count)}).",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Bảo vệ chuẩn mực chất lượng thực tế và cam kết trọn gói không nhận Tip; giữ sàn 239k cho đau mỏi cổ vai gáy.",
            scarcity_or_close_hint="Không bao giờ hạ xuống 129k (Lấy ráy tai) khi khách đang đau mỏi cổ vai gáy.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
        )
        return directive, "PRICE_CONSCIOUS", 64

    # 3. SKILL 07: loss_aversion_urgency (Khách xin suy nghĩ thêm: "uk để suy nghĩ đã" - Lùi 1 Bước Để Tiến 2 Bước)
    if stage == "HESITATING" or intent == "HESITATION_OR_DELAY":
        bubble_plan = [
            f"Bong bóng 1 (Drop the Rope - Tôn Trọng Tuyệt Đối & Neo Cảm Giác Ấm Áp - SKILL 07): Tôn trọng 100% quyết định của {h} ('Dạ vâng {h} cứ thong thả cân nhắc thêm nhé ạ!'), kèm lời nhắn ấm áp rằng bất cứ khi nào vùng cổ vai gáy mỏi nhức cần thả lỏng bằng thảo dược ấm thì nhắn em hỗ trợ giữ lịch ưu tiên ngay. TUYỆT ĐỐI KHÔNG ĐẶT CÂU HỎI (?) Ở CUỐI TIN NHẮN, KHÔNG XIN SĐT!",
        ]
        directive = PsychologicalSkillDirective(
            skill_code="loss_aversion_urgency",
            skill_name="[SKILL 07 - Lùi 1 Bước Để Tiến 2 Bước] Nghệ Thuật Buông Dây & Giữ Cửa Mở (Drop the Rope)",
            academic_origin="Sandler Selling (Drop the Rope) & Kahneman Prospect Theory",
            trigger_reason="Khách tạm hoãn quyết định ('uk để suy nghĩ đã', 'để xem thêm'): Buông áp lực 100% để giữ trọn thiện cảm.",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Để lại ấn tượng tinh tế, ấm áp và không chèo kéo.",
            scarcity_or_close_hint="TUYỆT ĐỐI CẤM đặt câu hỏi (?) hoặc gạ xin SĐT ở lượt khách xin suy nghĩ thêm.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
        )
        return directive, "HESITANT_CONSIDERER", 62

    # 4. SKILL 5: assumptive_double_bind_close (Khách có tín hiệu mua / đồng ý thử / hỏi lịch trống / hỏi ship)
    if stage == "READY_TO_BUY" or intent == "PURCHASE_OR_BOOKING_SIGNAL":
        bubble_plan = [
            f"Bong bóng 1 (Assumptive Affirmation + Subtle Flex): Khẳng định lựa chọn của {h} cực kỳ chuẩn bài và nêu ngắn gọn trải nghiệm/lợi ích tuyệt vời {h} sắp nhận được.",
            f"Bong bóng 2 (Ericksonian Double-Bind Close): Không hỏi 'có chốt không', mà đưa ra ngay 2 lựa chọn thực thi cụ thể (ví dụ: khung giờ Sáng hay Chiều / phương án nhận A hay B) mà chọn phương án nào cũng là hoàn tất bước chốt.",
        ]
        directive = PsychologicalSkillDirective(
            skill_code="assumptive_double_bind_close",
            skill_name="Kỹ năng Chốt đơn Mặc định & Lựa chọn Kép (Assumptive Double-Bind Close)",
            academic_origin="Milton Erickson (Double-Bind) & Assumptive Close",
            trigger_reason="Khách đã phát tín hiệu sẵn sàng trải nghiệm/mua hàng hoặc hỏi điều kiện thực thi.",
            bubble_plan=bubble_plan,
            subtle_flex_hint="Tự tin khẳng định gói/sản phẩm khách chọn chính là lựa chọn được yêu thích nhất.",
            scarcity_or_close_hint="Dùng câu hỏi lựa chọn kép A/B về thời gian/phương thức thực hiện để khóa đơn mượt mà.",
            prompt_directive=" | ".join(bubble_plan),
            audience_voice_cadence=audience_cadence,
        )
        return directive, "DECISIVE_READY_BUYER", 88

    # 5. SKILL 2 (Turn 2+ Tailored Discovery): spin_micro_diagnostic
    bubble_plan = [
        f"Bong bóng 1 (Đón bắt nhu cầu + Subtle Flex): Phản hồi trực diện, ngắn gọn câu hỏi hoặc vấn đề của {h} trong 1 câu giàu cảm xúc, lồng ghép khéo léo thế mạnh cốt lõi trong tài liệu.",
        f"Bong bóng 2 (Đo ni đóng giày Gói & Giá trị): Gợi ý đúng 1 gói phù hợp nhất kèm mức giá minh bạch từ tài liệu (không xả tràn lan cả bảng giá).",
        f"Bong bóng 3 (SPIN Micro-Diagnostic): Đặt 1 câu hỏi 'bác sĩ bắt mạch' về ngữ cảnh/mức độ/ưu tiên của {h} (phương án A hay B) để nắm quyền dẫn dắt (Frame Control).",
    ]
    directive = PsychologicalSkillDirective(
        skill_code="spin_micro_diagnostic",
        skill_name="Kỹ năng Bác sĩ Bắt mạch & Định vị Chuyên gia (SPIN Micro-Diagnostic)",
        academic_origin="Neil Rackham (SPIN Selling adapted for High-Velocity Chat)",
        trigger_reason="Khách đang ở giai đoạn khám phá nhu cầu và đã bước qua lượt chào mở đầu.",
        bubble_plan=bubble_plan,
        subtle_flex_hint="Đứng ở tâm thế Chuyên gia am hiểu sâu sắc, giải quyết gọn ghẽ nỗi lo của khách.",
        scarcity_or_close_hint="Giữ Frame Control bằng câu hỏi chẩn đoán 2 phương án cụ thể.",
        prompt_directive=" | ".join(bubble_plan),
        audience_voice_cadence=audience_cadence,
    )
    return directive, "EXPLORING_CONSULTATION", 55
