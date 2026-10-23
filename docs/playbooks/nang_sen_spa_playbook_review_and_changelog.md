# ĐÁNH GIÁ & HIỆU ĐÍNH BỘ PLAYBOOK MẪU SPA (`SPA SALES PLAYBOOK & SAMPLE DIALOGUES v1.2`)

## 1. Tổng quan đánh giá bản gốc `SPA SALES PLAYBOOK & SAMPLE DIALOGUES v1.0`

Qua rà soát chi tiết bản thảo `SPA SALES PLAYBOOK & SAMPLE DIALOGUES v1.0` đối chiếu với dữ liệu thực tế trong cơ sở dữ liệu PostgreSQL (`document_chunks`) của **Nàng Sen — Dưỡng Sinh & Trị Liệu Phương Đông** (`page_id = b23cd382-4f7a-4829-8c54-1d410da14bf8`) và kiến trúc RAG của DocMind, chúng tôi ghi nhận các điểm mạnh và các điểm cần bổ sung / điều chỉnh như sau:

### 1.1. Điểm mạnh của bản gốc
- **Phân tách rõ 2 tầng tri thức**:
  1. **Playbook Rules (`PB-SPA-*`)**: Quy tắc hành xử theo từng intent / stage (`greeting_or_vague_need`, `symptom_discovery`, `service_consultation`, `safety_and_contraindication`, `pricing_and_promotion`, `booking_collection`, `combo_or_membership_upsell`, `complaint_or_medical_risk`, `prompt_injection_or_out_of_scope`).
  2. **Sample Dialogues (`FD-SPA-*` & `FD-SPA-BAD-*`)**: Hội thoại mẫu nhiều lượt có `why_good`, `do_not_copy_literally: true`, kèm 2 mẫu độc hại (`FD-SPA-BAD-001`, `FD-SPA-BAD-002`) để kiểm thử bộ lọc bảo mật / PII / cam kết y khoa sai sự thật.
- **Tư duy tư vấn có chiều sâu**: Hỏi triệu chứng trước khi báo dịch vụ, lọc chống chỉ định (thai kỳ, huyết áp, bệnh lý cột sống cấp) trước khi chốt lịch, upsell tinh tế đúng lúc.

### 1.2. Các điểm đã hiệu chỉnh & bổ sung để khớp 100% thực tế KB Nàng Sen Spa & kiến trúc DocMind
1. **Bỏ phụ thuộc API tra giá ngoài — Grounding 100% từ Knowledge Base (`<DOCUMENT_CONTEXT>`)**:
   - Bản gốc ghi `grounding_policy: must_use_price_api_or_kb` và chứa các placeholder `[PRICE_SINGLE]`, `[PROMO_IF_ANY]`.
   - **Điều chỉnh**: Chuyển thành `grounding_policy: must_use_kb_document_context` và đồng bộ **100% tên dịch vụ, thời lượng, đơn giá và chính sách ưu đãi thật** đang lưu trong bảng `document_chunks` của Nàng Sen Spa:
     - **Lấy ráy tai thư giãn** (30 phút): `129.000đ` (giá gốc `190.000đ`).
     - **Gội đầu dưỡng sinh** (60 phút): ưu đãi trải nghiệm lần đầu `239.000đ`, giá niêm yết `260.000đ` (giá gốc `370.000đ`).
     - **Massage trị liệu cơ bản — NS01** (70 phút): ưu đãi trải nghiệm lần đầu `399.000đ`, giá niêm yết `570.000đ`.
     - **Massage ngâm thảo dược — NS02** (80 phút): `530.000đ` (giá gốc `760.000đ`).
     - **Massage phục hồi chuyên sâu VIP — NS03** (90–100 phút): `580.000đ` (giá gốc `840.000đ`).
     - **Combo Massage phục hồi + Dưỡng sinh** (120 phút): `670.000đ` (giá gốc `960.000đ`).
     - **Gói Silver VIP — Gội tại phòng riêng** (120 phút): `780.000đ` (giá gốc `1.110.000đ`).
     - **Chính sách ưu đãi liệu trình**: Mua từ **5 buổi hoặc 5 voucher trở lên giảm thêm 10%** (loại bỏ hoàn toàn các con số giả định `450.000đ`, `620.000đ`, `220.000đ`, `820.000đ`, `giảm 12%`, `giảm 20%` không có trong KB).
2. **Không Handoff sai khi khách hỏi giá hoặc chê đắt (`PB-SPA-PRICE-001`)**:
   - Bổ sung quy tắc rõ ràng trong `PB-SPA-PRICE-001`: Khi bảng giá đã có trong `<DOCUMENT_CONTEXT>`, bot chủ động báo giá minh bạch và gợi ý gói trải nghiệm hợp lý (`129.000đ`, `239.000đ`, `399.000đ`) hoặc ưu đãi mua từ 5 buổi giảm 10%; **tuyệt đối không bật `need_human_handoff`** chỉ vì khách hỏi giá hay so sánh giá.
3. **Kích hoạt ngay khi Upload (`ACTIVE` / `APPROVED`), tự động chặn mẫu độc hại (`REJECTED`)**:
   - Bỏ bước chờ duyệt thủ công (`PENDING`) khi nạp Playbook từ file `.yaml`, `.md`, `.json`.
   - Các mục hợp lệ (`PB-SPA-*` và `FD-SPA-*`) được kích hoạt ngay (`active = true`, `review_status = APPROVED`).
   - Hai mẫu kiểm thử âm tính (`FD-SPA-BAD-001` chứa SĐT thật `0988123456` + cam kết *"chữa khỏi vĩnh viễn 100%"*, và `FD-SPA-BAD-002` chứa prompt injection *"ignore previous instructions"* + bịa giá `0đ`) tự động bị bộ lọc an toàn gắn cờ `active = false`, `review_status = REJECTED` kèm lý do chi tiết.
4. **Chuẩn hóa đồng nhất trên cả 3 định dạng (`YAML`, `Markdown`, `JSON`)**:
   - Cung cấp đầy đủ 3 định dạng đồng bộ tuyệt đối tại:
     - `docs/playbooks/nang_sen_spa_playbook.yaml`
     - `docs/playbooks/nang_sen_spa_playbook.md`
     - `docs/playbooks/nang_sen_spa_playbook.json`
