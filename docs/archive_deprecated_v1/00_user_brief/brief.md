> ⚠️ DEPRECATED ARCHITECTURE (DO NOT USE)
> Tài liệu này thuộc kiến trúc V1/C1-C3 cũ (Stage cứng / Regex tiền-LLM / Template cứng) và đã bị CÁCH LY. Nguồn chân lý duy nhất hiện hành là `ACI_V2_SOURCE_OF_TRUTH.md`.

# 00_USER_BRIEF — DocMind Fanpage AI Sales & Emotional Consultation Overhaul

## 1. Bối cảnh & Yêu cầu gốc của User
1. **Tích hợp & Vận hành quy trình `QUY TRÌNH ANTIGRAVITY MULTI-AGENT TEAM v1.0`**:
   - Thiết lập đầy đủ hệ thống thư mục bàn giao độc lập ngữ cảnh (`00_user_brief/`, `01_po_techlead/`, `02_dev/`, `03_qa/`, `04_product_uat/`, `99_pipeline/`).
   - Mỗi Agent vận hành theo đúng vai trò chuyên biệt:
     - **Agent 1 (PO + Techlead)**: Phân tích sâu yêu cầu kinh doanh & kỹ thuật, định nghĩa hợp đồng nghiệm thu máy đọc được (`acceptance_contract.yaml`), kiến trúc, chiến lược kiểm thử và sổ đăng ký rủi ro.
     - **Agent 2 (Senior Dev)**: Thực thi giải pháp kỹ thuật triệt để, chạy đầy đủ Unit Test & Happy-Path Verification, đóng gói artifact bàn giao.
     - **Agent 3 (Black-Box QA / Empirical Challenger)**: Kiểm thử hộp đen độc lập (tuyệt đối không đọc mã nguồn Dev), chạy bộ kiểm thử tất định (Deterministic Policy), ngẫu nhiên có seed (Random Persona), đối kháng (Adversarial) và chấm điểm cảm xúc/chốt sale (Emotional & Sales Conversion Judge).
     - **Agent 4 (Product PO UAT)**: Nghiệm thu dưới góc nhìn kinh doanh & trải nghiệm khách hàng thực tế, xuất gói kịch bản cho User tự kiểm chứng (`user_test_package.md`).
2. **Chẩn đoán & Đại tu toàn diện chất lượng hội thoại của Chatbot Tư vấn Bán hàng DocMind**:
   - **Vấn đề thực tế của User**: Dù đã qua nhiều bản vá nhỏ lẻ, khi chat thử với AI hiện tại, cảm xúc của khách hàng bị **"tụt mood, chỉ muốn out ngay"** vì câu trả lời còn mang tính rập khuôn, cơ học, cắt ngang mạch cảm xúc hoặc hỏi chốt lịch cứng nhắc khi khách chưa sẵn sàng.
   - **Mục tiêu cốt lõi**:
     - Hội thoại phải **giàu cảm xúc (emotionally rich)**, chạm đúng nỗi đau thể chất/tinh thần bằng ngôn từ gợi hình ảnh và xúc giác ấm áp ("thảo dược tươi nấu ấm", "nhẹ bẫng cả đôi vai", "thả lỏng từng bó cơ căng cứng").
     - Giữ nhịp thở hội thoại tự nhiên trên Messenger (**tối đa 3 bong bóng chat `<= 3` bubbles** theo cấu trúc 3 nhịp cảm xúc: *Nhịp 1: Chạm cảm xúc & đồng cảm sâu -> Nhịp 2: Giá trị trải nghiệm thực tế & minh bạch -> Nhịp 3: Câu hỏi gợi mở nhẹ nhàng không áp lực*).
     - **Lượt đầu tiên (Turn 1)**: Phản hồi nhanh, tạo ấn tượng cảm xúc sâu sắc ngay từ giây đầu tiên mà không xả bảng giá khô khan.
     - **Lượt sau báo giá (Post-Price Deliberation)**: Dành thời gian suy nghĩ sâu (~10 giây trên LLM thực tế) để phân tích tâm lý khách hàng sau khi nghe giá (chê đắt, hỏi ưu đãi, lưỡng lự "để suy nghĩ thêm", hoặc đồng ý thử), nâng niu cảm xúc để khách **không bao giờ bị tụt mood** và dẫn dắt **chốt sale (booking/SĐT)** tự nhiên, thuyết phục.

## 2. Ràng buộc kỹ thuật & Môi trường thực thi
- **Workspace**: `/Users/chutiendat/Documents/Project/docmind`
- **Page kiểm thử thực tế**: Nàng Sen Spa (`page_id = b23cd382-4f7a-4829-8c54-1d410da14bf8`)
- **Dịch vụ đang chạy**:
  - `docmind-api` (Spring Boot Java 21): `http://localhost:8090`
  - `docmind-agent-brain` (FastAPI Python): `http://localhost:8095`
  - `docmind-doc-service` (FastAPI Python): `http://localhost:8000`
  - `docmind-postgres` (PostgreSQL + pgvector)
  - `docmind-web` (React + Vite): `http://localhost:5173`
- **Bảo toàn toàn bộ Unit Test & E2E Test hiện hữu**: Không làm suy yếu, xóa bỏ hay skip bất kỳ bài kiểm thử nào đã có của hệ thống.

## 3. Cập nhật Bổ sung (Model `qwen2.5:14b-instruct-q5_K_M` + Hệ thống Nạp Playbook & Hội thoại Mẫu `.yaml / .md / .json` Auto-ACTIVE)
1. **Cấu hình Model & Tham số Suy luận Chuẩn theo Tác vụ (Ollama & API Key Providers)**:
   - **Model khuyến nghị**: `qwen2.5:14b-instruct-q5_K_M` kèm cơ chế tự động fallback runtime sang `qwen2.5:14b` / `qwen2.5:7b` nếu model Q5_K_M chưa pull xong hoặc thiếu VRAM, kèm file cấu hình `docs/ollama/Modelfile.qwen2.5-14b-sales-q5`.
   - **Tham số cho Bot Tư vấn Bán hàng Fanpage (`FanpageBotService`)**: `num_ctx = 4096`, `temperature = 0.2`, `top_p = 0.9`, `repeat_penalty = 1.08`, `num_predict = 600`.
   - **Tham số cho Hỏi đáp Tri thức Nội bộ (`ChatService`)**: `temperature = 0.1`, `top_p = 0.85`, `num_predict = 900`, `repeat_penalty = 1.05`.
   - **Tham số cho Trích xuất Quy tắc / Memory / QA Judge (`MetaLearningService`, `HarnessOptimizerService`)**: `temperature = 0.0`, `top_p = 0.8`.
2. **Hệ thống Upload Playbook (`PB-*`) & Sample Dialogues (`FD-*`) theo từng Page**:
   - Hỗ trợ đầy đủ 3 định dạng: **YAML (`.yaml`/`.yml`)**, **Markdown (`.md`/`.markdown`)**, **JSON (`.json`)** qua cả 2 cổng: dán nội dung (`POST /api/rules/upload-playbook`) và tải file trực tiếp (`POST /api/rules/upload-playbook-file`).
   - **Kích hoạt `ACTIVE` ngay lập tức (Bỏ qua bước duyệt thủ công)**: Ngay khi tải lên, hệ thống tự động quét kiểm định an toàn (Schema, PII SĐT/Email, cam kết chữa khỏi vĩnh viễn 100%, Prompt Injection). Các mục hợp lệ chuyển thẳng sang `active = true`, `review_status = 'APPROVED'`; các mẫu âm tính/độc hại (`FD-SPA-BAD-001`, `FD-SPA-BAD-002`) tự động bị chặn (`active = false`, `review_status = 'REJECTED'`).
   - **Bỏ hoàn toàn tra giá qua API ngoài**: Toàn bộ giá dịch vụ, thời lượng, combo và ưu đãi được neo trực tiếp từ Tài liệu Tri thức (`<DOCUMENT_CONTEXT>`) của Page; tuyệt đối không bật handoff sai khi khách hỏi giá hoặc chê đắt.
3. **Rà soát, Tinh chỉnh & Nạp Bộ Sample Playbook Nàng Sen Spa (`v1.1`)**:
   - Đánh giá chi tiết bản mẫu `SPA SALES PLAYBOOK & SAMPLE DIALOGUES v1.0`, bổ sung các mục còn thiếu và chuẩn hóa thành `docs/playbooks/nang_sen_spa_playbook.{yaml,md,json}` (gồm `11` quy tắc `PB-SPA-*` và `11` hội thoại mẫu `FD-SPA-*` trong đó có `2` mẫu kiểm thử âm tính `FD-SPA-BAD-001`, `FD-SPA-BAD-002`).

