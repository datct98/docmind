> ⚠️ **GHI CHÚ KIẾN TRÚC HIỆN HÀNH:** Luồng nhận thức hội thoại chính của `docmind-api` hiện chạy trên **`LLM_COGNITIVE_V2` (Single-Pass ACI)** trong Java. Xem [`ACI_V2_SOURCE_OF_TRUTH.md`](../ACI_V2_SOURCE_OF_TRUTH.md); không sử dụng khuôn mẫu cố định 3 nhịp / 2-3 bong bóng cũ bên dưới cho `LLM_COGNITIVE_V2`.

# Agent Brain (`agent-brain`)

**Bộ não Độc lập & Tái sử dụng (Cognitive Memory, Customer Insight & Psychological Strategy Engine)** dành cho các hệ thống AI Agent Bán hàng & Chăm sóc Khách hàng đa kênh (Messenger, Zalo, Web Chat, CRM, E-Commerce).

## Kiến trúc Cốt lõi

1. **Tách bạch `Customer Memory` và `RAG Knowledge Base`:**
   - Không lưu trữ tài liệu sản phẩm tĩnh; không dùng Memory cũ để tự báo giá hay tồn kho.
   - Tập trung 100% vào **Hiểu Khách Hàng (Customer Insight)**, **Nhớ Mạch Hội Thoại (Session & Episodic Memory)**, **Sự Thật Có Kiểm Chứng (`customer_memory_facts` kèm `confidence`, `source_type`, `superseded_by`)**, **Phát Hiện Rào Cản (`objections`)**, và **Quyết Định Chiến Thuật (`PolicyEngine` + `SkillMatrix`)**.
2. **Mô hình `Retain -> Recall -> Reflect` Hai Tốc Độ (Dual-Speed):**
   - **Hot Path (`POST /v1/brain/recall-and-advise` < 10ms):** Trả về ngay `customer_memory_block` (nén theo token budget), `purchase_stage`, `buyer_archetype`, `active_skill` (1 trong 5 Kỹ năng Tâm lý Chốt Sale kèm Khuôn mẫu 3 nhịp 2–3 bong bóng), và `policy_decision` (`REPLY | CLARIFY | HANDOFF`).
   - **Async/Cold Path (`POST /v1/brain/retain` & `POST /v1/brain/reflect`):** Bóc tách `facts` mới, giải quyết xung đột theo nguyên tắc `Current Explicit Message > Verified Fact > Recent Summary > Inferred Old Fact`, áp dụng `DecayPolicy` (TTL), và tổng hợp phân tích `Reflect` cho nhân viên Sale.
3. **5 Kỹ Năng Tâm Lý Chốt Sale Cốt Lõi (Domain-Agnostic 100%):**
   - `spin_micro_diagnostic` (Neil Rackham — Chẩn đoán vi mô & Chiếm quyền dẫn dắt Frame Control)
   - `tactical_empathy_voss` (Chris Voss — Thấu cảm chiến thuật, Labeling, Accusation Audit & Risk Reversal)
   - `behavioral_value_reframing` (Kahneman, Thaler & Cialdini — Đảo chiều giá trị, Chia nhỏ chi phí & Give-to-Get)
   - `loss_aversion_urgency` (Prospect Theory 2.25x — Khan hiếm vận hành tinh tế & Giữ suất ưu tiên không áp lực)
   - `assumptive_double_bind_close` (Ericksonian Double-Bind — Mặc định chốt đơn bằng lựa chọn kép A/B)
