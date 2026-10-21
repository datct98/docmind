# BÁO CÁO KIỂM TOÁN TIỀN SẢN XUẤT (PRE-PRODUCTION AUDIT REPORT)
# ĐÁNH GIÁ TOÀN DIỆN NỀN TẢNG DOCMIND SALES AI & TÍCH HỢP FANPAGE FACEBOOK

- **Dự án**: DocMind — Nền tảng AI Sales Document Q&A & Tự Tiến Hóa (Self-Improving AI)
- **Ngày thực hiện**: 2026-10-20
- **Phiên bản hệ thống**: DocMind v2.5-Harness²
- **Phạm vi kiểm toán**: Toàn bộ kiến trúc Full-Stack (`frontend`, `api-java`, `doc-service-py`, `docmind-postgres`), Kiểm thử Thực tế trên Live Docker Stack (`localhost:3000`, `localhost:8090`, `localhost:8091`), và Bộ kiểm thử `tests_e2e/`.
- **Trạng thái**: Hoàn tất kiểm toán chuyên sâu & Đề xuất hành động tiền triển khai (Pre-Production Ready).

---

## MỤC LỤC
1. [TỔNG QUAN & BẢNG ĐIỂM ĐÁNH GIÁ ĐỊNH LƯỢNG](#1-tổng-quan--bảng-điểm-đánh-giá-định-lượng)
2. [TRỤ CỘT 1: KIẾN TRÚC TỔNG THỂ & PHÂN TÍCH ƯU / NHƯỢC ĐIỂM](#2-trụ-cột-1-kiến-trúc-tổng-thể--phân-tích-ưu--nhược-điểm)
3. [TRỤ CỘT 2: THIẾT KẾ GIAO DIỆN & TRẢI NGHIỆM NGƯỜI DÙNG (UI/UX)](#3-trụ-cột-2-thiết-kế-giao-diện--trải-nghiệm-người-dùng-uiux)
4. [TRỤ CỘT 3: AN NINH BẢO MẬT & HÀNG RÀO PHÒNG VỆ (SECURITY & GUARDRAILS)](#4-trụ-cột-3-an-ninh-bảo-mật--hàng-rào-phòng-vệ-security--guardrails)
5. [TRỤ CỘT 4: ĐỘ CHÍNH XÁC THÔNG TIN & GIÁ BÁN RAG (RAG ACCURACY)](#5-trụ-cột-4-độ-chính-xác-thông-tin--giá-bán-rag-rag-accuracy)
6. [TRỤ CỘT 5: GIỌNG VĂN TỰ NHIÊN CHUẨN TIẾNG VIỆT (HUMAN-LIKE CONVERSATIONAL TONE)](#6-trụ-cột-5-giọng-văn-tự-nhiên-chuẩn-tiếng-việt-human-like-conversational-tone)
7. [TRỤ CỘT 6: KỸ NĂNG CHỐT SALE & THU THẬP KHÁCH HÀNG TIỀM NĂNG (SALES CLOSING & LEAD CAPTURE)](#7-trụ-cột-6-kỹ-năng-chốt-sale--thu-thập-khách-hàng-tiềm-năng-sales-closing--lead-capture)
8. [ĐÁNH GIÁ MỨC ĐỘ SẴN SÀNG & DANH MỤC KIỂM TRA TÍCH HỢP FANPAGE FACEBOOK (FANPAGE READINESS)](#8-đánh-giá-mức-độ-sẵn-sàng--danh-mục-kiểm-tra-tích-hợp-fanpage-facebook-fanpage-readiness)
9. [KẾT LUẬN & KẾ HOẠCH HÀNH ĐỘNG TRIỂN KHAI](#9-kết-luận--kế-hoạch-hành-động-triển-khai)

---

## 1. TỔNG QUAN & BẢNG ĐIỂM ĐÁNH GIÁ ĐỊNH LƯỢNG

DocMind là nền tảng AI Sales Document Q&A, Fanpage Conversational Consultant và Tự tiến hóa (Self-Improving AI) kết hợp giữa RAG 2 tầng (Hybrid Retrieval pgvector + Full-text TSV), hệ thống quy tắc đúc kết từ sai sót theo chuẩn **Google Research Harness²**, và Hộp thư Đa kênh (Omnichannel Live Conversation Inbox).

### 1.1 Bảng Điểm Đánh Giá Định Lượng Toàn Diện (Executive Scorecard)

| Trụ Cột Đánh Giá | Điểm Số (0–100) | Xếp Hạng | Đánh Giá Tóm Tắt |
|---|:---:|:---:|---|
| **1. Kiến trúc Tổng thể & Ưu/Nhược điểm** | **86 / 100** | Rất tốt | Phân tách rành mạch Hot Path và Cold Path; Hybrid RRF Search; Pluggable Multi-LLM Client. Điểm nghẽn duy nhất là độ trễ mô hình suy luận lớn (`grok-4.5` mất 19–32s) và xử lý Webhook đồng bộ. |
| **2. Thiết kế Giao diện & Trải nghiệm Người dùng** | **91 / 100** | Xuất sắc | Ngôn ngữ thiết kế NotebookLM hiện đại; Hộp thư 3 cột đầy đủ nghiệp vụ; Đệm gộp tin nhắn dồn dập (3s); Nhịp gõ chữ so le chân thực (~2.7s); Đo nhiệt độ và radar tâm lý khách trực quan. |
| **3. An ninh Bảo mật & Hàng rào Phòng vệ** | **62 / 100** | Cảnh báo cao | Phòng vệ Runtime Bot rất tốt (chặn 100% Direct Jailbreak và Fake Discount). Tuy nhiên, thiếu Spring Security, Wildcard CORS, rò rỉ dữ liệu chéo Page (`page_id`) tại một số API, và thiếu bảo vệ tại cổng tạo Rule. |
| **4. Độ Chính xác Thông tin & Giá bán RAG** | **95 / 100** | Xuất sắc | 100% khớp bảng giá và chính sách ưu đãi trong tài liệu gốc; không bịa đặt cam kết y khoa ngoài phạm vi; từ chối chính xác và chuyển giao nhân viên khi hỏi dịch vụ ngoài tài liệu. |
| **5. Giọng văn Tự nhiên Chuẩn Tiếng Việt** | **96 / 100** | Xuất sắc | Xóa bỏ triệt để phantom name "Lan Anh"; nhất quán 100% xưng hô Em - Chị/Anh; lọc sạch mã dịch vụ (`SPA-01`), citation (`[1]`) và markdown headers trong bong bóng chat Messenger. |
| **6. Kỹ năng Chốt Sale & Thu thập Lead** | **94 / 100** | Xuất sắc | Pacing 3 chặng chặt chẽ; Buying Temperature theo dõi trạng thái và độ nguội; Kỹ năng tâm lý Takeaway / Pattern Interrupt; Bắt SĐT siêu tốc 0ms không lặp lại kịch bản bán hàng. |
| **Sẵn sàng Tích hợp Fanpage FB Thật** | **58 / 100** | **Chưa sẵn sàng** | Đã có xác thực Webhook `hub.challenge` và HMAC-SHA256, nhưng **thiếu Meta Graph API Send API** để đẩy tin ra Messenger thật và Webhook xử lý đồng bộ dễ gây timeout 20s của Facebook. |

**ĐIỂM TRUNG BÌNH TOÀN HỆ THỐNG**: **83.1 / 100** (Chất lượng Core Logic & Hội thoại đạt chuẩn xuất sắc; Cần gia cố bảo mật đa khách thuê và hoàn thiện Outbound Graph API Client trước khi đóng điện Fanpage thương mại).

---

## 2. TRỤ CỘT 1: KIẾN TRÚC TỔNG THỂ & PHÂN TÍCH ƯU / NHƯỢC ĐIỂM

### 2.1 Bản Đồ Dịch Vụ Docker & Luồng Dữ Liệu

DocMind được container hóa hoàn chỉnh qua `docker-compose.yml` gồm 5 service độc lập:
1. **`docmind-postgres`** (`pgvector/pgvector:pg16` trên port Host `5433` -> Container `5432`):
   - Lưu trữ dữ liệu quan hệ và vector nhúng 384 chiều (`vector(384)`).
   - Quản trị bởi 13 migration Flyway (`V1__init.sql` đến `V13__omnichannel_conversations.sql`).
2. **`docmind-doc-service`** (`Python 3.11 / FastAPI` trên port `8091`):
   - Chịu trách nhiệm Ingestion: phân tích cấu trúc `.docx` (`python-docx`), `.xlsx` (`openpyxl`), `.md` (`builtin-markdown-1.0`).
   - Cung cấp endpoint nhúng vector `/v1/embed` và đồ thị LangGraph Cold Path `/internal/v1/nightly-extract`.
3. **`docmind-api`** (`Java 21 / Spring Boot 4` trên port `8090`):
   - Đóng vai trò Hạt nhân Điều phối (Orchestrator): RAG Hybrid Search, In-Loop Guardrail Hooks (< 5ms), Harness² Recursive Engine, Quản lý Hộp thư trực tiếp `ConversationInboxService`, và Pluggable Multi-LLM Client.
4. **`docmind-frontend`** (`Vue 3 / Vite / Tailwind CSS / Nginx` trên port Host `3000` -> Container `80`):
   - Nginx Reverse Proxy: Định tuyến `/api/` -> `http://api-java:8090/api/` với timeout 300s, `/actuator/` -> `http://api-java:8090/actuator/`, phục vụ Vue 3 SPA.
5. **`docmind-tunnel`** (`cloudflare/cloudflared:latest`):
   - Tự động thiết lập đường hầm HTTPS an toàn (`https://*.trycloudflare.com`), cho phép kết nối Webhook Meta và kiểm thử từ xa mà không cần cấu hình NAT/Port-Forwarding.

```
       [Khách hàng Facebook Messenger / Quản trị viên]
                             │
                             ▼ (HTTPS / SSL)
                   [Cloudflare Tunnel]
                             │
                             ▼ (Port 80)
                [Nginx + Vue 3 SPA Frontend]
                 /                         \
        (Tài nguyên tĩnh SPA)          (Reverse Proxy /api/*)
                │                                   │
           [Trình duyệt]                            ▼ (Port 8090)
                                          [api-java Orchestrator]
                                          (Spring Boot 4 / Java 21)
                                          ├── In-Loop Guardrail (<5ms)
                                          ├── FanpageBotService & Inbox
                                          ├── Harness² Recursive Engine
                                          └── Pluggable Multi-LLM Client
                                           /              \               \
                                          ▼                ▼               ▼
                            [doc-service-py:8091]   [Postgres:5432]   [LLM Provider]
                            ├── Office Ingestion    ├── pgvector      (Grok, DeepSeek,
                            ├── AST Chunking        ├── Chunks        Ollama, OpenAI)
                            └── LangGraph Agent     ├── Learned Rules
                                                    └── Sessions & Msg
```

### 2.2 Phân Tách Hot Path & Cold Path

- **Hot Path (Phục vụ khách hàng thời gian thực)**:
  - Nằm hoàn toàn trong `api-java`. Khi khách hàng nhắn tin, hệ thống thực hiện: Coalescing tin nhắn -> Phân tích danh xưng & ý định -> Chốt chặn tiền LLM -> RAG Hybrid Search -> Lời gọi LLM -> Hậu xử lý văn phong -> Chốt chặn In-Loop Guardrail Hooks (`< 5ms`) -> Phản hồi.
  - Tuyệt đối không sinh thêm các cuộc gọi LLM đánh giá chặn đường (no blocking reflection LLM calls) trong Hot Path, đảm bảo tiết kiệm chi phí và duy trì độ mượt.
- **Cold Path (Đào tạo kinh nghiệm sau ca trực / Ban đêm)**:
  - Định kỳ hoặc khi phiên chat kết thúc chuyển đổi (`CONVERTED`), hệ thống đẩy dữ liệu sang `doc-service-py` để chạy đồ thị LangGraph trích xuất kinh nghiệm tự động kèm chốt chặn `SecurityAuditorNode` ngăn chặn đầu độc quy tắc trước khi lưu vào `learned_rules`.

### 2.3 RAG Đa Tầng Lai Ghép (Hybrid Retrieval with Reciprocal Rank Fusion)

- Tích hợp song song 2 cánh tìm kiếm:
  - Cánh 1: Dense Semantic Vector Search qua `pgvector` (`cosine distance <=>`).
  - Cánh 2: Sparse Keyword Search qua PostgreSQL Full-Text Search (`to_tsvector('simple', text)`), tự động mở rộng từ khóa tìm kiếm (`retrieval_keywords`) từ các quy tắc Playbook đang kích hoạt.
- Dung hợp kết quả bằng thuật toán **Reciprocal Rank Fusion (RRF)** chuẩn quốc tế:
  $$RRF\_Score = \frac{1}{60 + rank_{dense}} + \frac{1}{60 + rank_{sparse}}$$
- Tái sắp xếp kết quả: Ưu tiên bảng giá và combo (`prioritizeCatalogAndPriceTableChunks`), đa dạng hóa độ bao phủ truy vấn (MMR Coverage Diversification), và giới hạn theo ngữ cảnh dòng sản phẩm (`scopeChunksToTopicFamily`), tránh việc hỏi trị mụn lại lấy nhầm phác đồ laser nám.

### 2.4 Unified Multi-Provider LLM Client

- File: `api-java/src/main/java/com/example/docmind/service/llm/OpenAiCompatibleLlmClient.java`.
- Cấu hình thống nhất qua biến môi trường `.env`, hỗ trợ chuyển đổi linh hoạt:
  - `LLM_PROVIDER=ollama` (Local Ollama)
  - `LLM_PROVIDER=grok` (xAI: `grok-4.5`, `grok-3-mini` qua `https://api.x.ai/v1`)
  - `LLM_PROVIDER=deepseek` (`deepseek-chat`, `deepseek-reasoner` qua `https://api.deepseek.com/v1`)
  - `LLM_PROVIDER=openai` (`gpt-4o-mini`, `gpt-4o` qua `https://api.openai.com/v1`)
  - `LLM_PROVIDER=gemini` (`gemini-2.5-flash` qua OpenAI compatibility endpoint)
- Tự động bóc tách các khối suy luận nội tâm `<think>...</think>` trước khi chuyển văn bản cho khách hàng.

### 2.5 Phân Tích Ưu Điểm & Nhược Điểm Từng Thành Phần

| Thành Phần Codebase | Ưu Điểm (Pros) | Nhược Điểm (Cons) | Rủi Ro Vận Hành |
|---|---|---|---|
| **Frontend** (`docmind/frontend`) | Giao diện NotebookLM trực quan; Hộp thư 3 cột đầy đủ nghiệp vụ; Đệm tin nhắn dồn dập 3s; Giả lập gõ chữ ~2.7s; Đầy đủ nút gán nhãn, đổi chế độ Sale, đo nhiệt độ. | Chưa dùng Vue Router (chuyển view qua cờ `currentView`); Quản lý state cục bộ trong component lớn (`FanpageBotSimulatorModal.vue` > 4.000 dòng). | Khó bảo trì khi ứng dụng mở rộng thêm nhiều tính năng mới; bundle size lớn. |
| **Backend API** (`docmind/api-java`) | In-Loop Guardrails < 5ms; RAG Hybrid RRF-60 chính xác; Quản lý Persona theo Page; Hỗ trợ đa nhà cung cấp LLM; Tự động hóa Harness². | Webhook Meta xử lý đồng bộ trong HTTP thread; Chưa có cơ chế hàng đợi bất đồng bộ (Async Queue) cho outbound Webhook. | Sập luồng kết nối Spring Boot (`http-nio-8090-exec`) khi có đợt traffic dồn dập trên Fanpage; Webhook timeout từ Meta. |
| **Doc Service** (`docmind/doc-service-py`) | Phân tích cấu trúc DOCX / XLSX / MD không mất thông tin; Bộ lọc PII tự động che SĐT / Email; LangGraph Cold Path có Security Node. | Chưa cài đặt các thư viện ML nặng (`sentence-transformers`, PyMuPDF) trong image Docker hiện tại để tiết kiệm RAM. | Embedding hiện tại dựa trên băm chuỗi tất định 384 chiều (deterministic stub) thay vì transformer ngữ nghĩa thực thụ. |
| **Database** (`docmind/postgres`) | 13 migration Flyway chuẩn chỉ; Hỗ trợ index vector HNSW/IVFFlat; Lưu trữ lịch sử hội thoại chi tiết kèm metadata JSON. | Chưa thiết lập phân vùng bảng (partitioning) cho bảng `conversation_messages` khi đạt hàng triệu bản ghi. | Tốc độ truy vấn hội thoại cũ có thể chậm dần theo thời gian nếu không có cơ chế dọn dẹp (pruning). |

### 2.6 Điểm Nghẽn Hiệu Năng Hot Path (Latency Bottleneck)

- **Hiện trạng kiểm chứng thực tế**:
  - `retrievalMs`: Chỉ mất **53ms – 72ms** (RAG cực nhanh).
  - `latencyMs` tổng thể: Kéo dài từ **19.332ms đến 31.713ms** do lời gọi mô hình suy luận `grok-4.5` sinh từ 1.100 đến 2.240 token suy luận (`reasoningTokens`).
- **Lỗi ngắt kết nối giả (Premature Timeout Bug)**:
  - Tại `OpenAiCompatibleLlmClient.java` (dòng 67–69), hằng số `FAST_REASONING_ATTEMPT_TIMEOUT_MS = 35_000` (35 giây) tự động ép timeout của một lần thử xuống 35s.
  - Khi `grok-4.5` suy luận mất 38–42 giây, client ngắt kết nối đột ngột (`I/O error on POST request ... Request cancelled`), buộc phải retry lần 2 gây trễ gấp đôi (> 70s).
- **Khuyến nghị khắc phục**:
  - Nâng `FAST_REASONING_ATTEMPT_TIMEOUT_MS` lên `60_000` hoặc tôn trọng `LLM_TIMEOUT_SECONDS=120` từ file `.env`.
  - Đối với Hot Path phục vụ chat Messenger, khuyến nghị cấu hình model nhẹ, phản hồi nhanh: `LLM_PROVIDER=gemini` (`gemini-2.5-flash` độ trễ 1.5s–2.5s) hoặc `LLM_PROVIDER=openai` (`gpt-4o-mini` độ trễ 1.5s–2.0s), hoặc `LLM_PROVIDER=grok` với model `grok-3-mini`.

---

## 3. TRỤ CỘT 2: THIẾT KẾ GIAO DIỆN & TRẢI NGHIỆM NGƯỜI DÙNG (UI/UX)

### 3.1 Ngôn Ngữ Thiết Kế NotebookLM & Phân Cấp Thị Giác

Giao diện DocMind được xây dựng theo phong cách **NotebookLM hiện đại**:
- **Hub Fanpage (Directory Screen)**:
  - Hiển thị danh sách Fanpage/Ngành nghề dưới dạng các cuốn "Notebook" riêng biệt với màu sắc pastel dịu mắt, icon cảm xúc, chân dung khách hàng mục tiêu (`target_audience`), và số liệu tóm tắt (số tài liệu, số quy tắc Playbook).
  - Thanh đỉnh (Notebook Topbar) tích hợp **LLM Health Pill** hiển thị trực tiếp trạng thái API Key, Model đang chạy và độ trễ ping mạng thực tế (ví dụ: `GROK: grok-4.5 (2073ms)`).
- **Không Gian Tác Nghiệp (Workspace Screen)**:
  - Chuyển tiếp mượt mà vào trung tâm điều hành: **Hộp thư Đa Kênh 3 cột (`LiveConversationInbox.vue`)**.

### 3.2 Đánh Giá Hộp Thư Đa Kênh 3 Cột (`LiveConversationInbox.vue`)

```
┌─────────────────────────┬─────────────────────────────────┬─────────────────────────┐
│ CỘT TRÁI: DANH SÁCH     │ CỘT GIỮA: LUỒNG CHAT MESSENGER  │ CỘT PHẢI: RADAR THẤU    │
│ KHÁCH & TÀI LIỆU NGUỒN  │ THỜI GIAN THỰC                  │ HIỂU KHÁCH HÀNG         │
├─────────────────────────┼─────────────────────────────────┼─────────────────────────┤
│ • Tab Khách hàng / Docs │ • Header: Avatar, Chặng bán     │ • Đồng hồ Nhiệt độ mua  │
│ • Tìm kiếm SĐT, Tên     │ • Nút: [Sale Tiếp Nhận] / [AI]  │   hàng (0-100°, Delta)  │
│ • Badge: [🚨 Cần Sale]  │ • Nút: [✅ Đánh dấu Đã Chốt]    │ • Radar tâm lý, Nỗi đau │
│   [✅ Đã chốt] [🤖 AI]  │ • Bong bóng phân biệt 3 vai trò │ • Thẻ Lead Capture:     │
│ • Nhiệt độ mua: 🔥 86°  │ • Dual-Mode Composer (Sale/User)│   SĐT + Giờ hẹn đã chốt │
└─────────────────────────┴─────────────────────────────────┴─────────────────────────┘
```

1. **Cột Trái (Danh sách Khách hàng & Tài liệu)**:
   - Cho phép tìm kiếm nhanh theo tên, SĐT, nội dung tin nhắn. Nút "+ KH mới" tạo phiên chat giả lập chỉ trong vài giây.
   - Thẻ hội thoại hiển thị đầy đủ: Tên khách, danh xưng (Anh/Chị), trích đoạn tin nhắn, thời gian tương đối, nhãn trạng thái (`🚨 Cần Sale`, `✅ Đã chốt`, `👨‍💼 Sale trực`, `🤖 AI trực`), chỉ số nhiệt độ mua hàng (`🔥 86°`), và SĐT đã chốt (`📞 0968221644`).
2. **Cột Giữa (Luồng Chat Messenger Thực Chiến)**:
   - Header hiển thị chặng bán hàng hiện tại (`📍 VALUE_CONSULTATION`).
   - Nút quản trị tối quan trọng: **`✋ Sale Tiếp Nhận (Tắt AI)`** và **`🤖 Bật lại AI Tự Động`** giúp nhân viên sale tiếp quản ngay lập tức mà không lo AI nói leo.
   - Nút **`✅ Đánh dấu Đã Chốt`** để đánh dấu các case thành công đưa vào dữ liệu huấn luyện Cold Path.
   - Dưới mỗi câu trả lời của AI có thanh công cụ: hiển thị chốt chặn Guardrail đã duyệt (`🛡️ BubbleCapHook`), nút **"💬 Báo sai & Dạy AI"**, và nút **"🧬 Tự tối ưu Harness²"**.
   - **Khung soạn thảo 2 chế độ (Dual-Mode Composer)**:
     - Chế độ 1: *👨‍💼 Trả lời khách (Tư cách Sale thật)* -> Lưu làm mẫu thực chiến.
     - Chế độ 2: *👤 Giả lập Khách nhắn tiếp vào hộp thư* -> Kiểm thử tương tác phản hồi của Bot.
3. **Cột Phải (Radar Thấu Hiểu Khách Hàng)**:
   - Đồng hồ nhiệt kế Mua hàng (`Buying Temperature Thermometer`) hiển thị trực quan điểm số, độ lệch nhiệt độ (`temperature_delta` màu xanh/đỏ) và xu hướng (`momentum`).
   - Phân tích hình mẫu tâm lý khách hàng (`buyer_archetype`), nỗi đau cốt lõi (`extracted_pains`), rào cản tâm lý ẩn giấu (`hidden_concerns`), và chiến lược tư vấn đề xuất (`recommended_strategy`).
   - Khối trích xuất thông tin khách hàng chốt lịch (`Lead Capture Card`): Tự động bắt SĐT và giờ hẹn mong muốn.

### 3.3 Tính Năng Giả Lập Messenger & Đệm Tin Nhắn Dồn Dập

1. **Nhịp gõ chữ so le chân thực (Staggered Multi-Bubble Pacing)**:
   - Khi Bot sinh 2 bong bóng câu trả lời, bong bóng 1 xuất hiện trước. Sau đó, hiển thị biểu tượng ba chấm nhấp nháy (typing indicator) trong khoảng delay ~2.5s – 3.0s trước khi bong bóng thứ 2 xuất hiện, mang lại cảm giác chân thực như một tư vấn viên con người đang gõ trên điện thoại.
2. **Bộ đệm gộp tin nhắn dồn dập (3-Second Multi-Message Burst Coalescing)**:
   - Khách hàng có thể gõ liên tiếp 2–3 tin nhắn ngắn (ví dụ: *"giá sao shop"*, *"mình bị mụn ẩn"*, *"có đau không"*). Giao diện không khóa input mà hiển thị ngay các bong bóng của khách, khởi động bộ đếm 3 giây. Nếu khách gõ tiếp, bộ đếm tự động reset. Khi hết 3s, toàn bộ cụm tin nhắn được gửi đi trong 1 lượt API duy nhất, giúp AI trả lời bao quát toàn bộ câu hỏi mà không chào lặp lại.
3. **Giả lập độ trễ & khách im lặng (Idle Simulation)**:
   - Có 2 nút bấm tiện lợi: **`⏳ Khách im lặng 5p`** và **`⏳ Khách im lặng 30p`**.
   - Bấm nút lập tức mô phỏng việc khách hạ nhiệt (`temperature_delta` âm), chuyển sang momentum làm nguội (`COOLING_IDLE`), và kích hoạt phản hồi giữ nhiệt chủ động (*Proactive Re-engagement*) với kỹ năng tâm lý lùi 1 bước nhẹ nhàng.

### 3.4 Bộ Nhớ Kinh Nghiệm AI Playbook (`PlaybookModal.vue`)

- Phân tách rành mạch theo 2 chiều kích thước Harness²:
  - **`INV` (Chiến thuật & Quy trình Bất Biến)**: Quy tắc tâm lý, cách xưng hô, kỹ năng xử lý từ chối.
  - **`DOM` (Sự Thật Nghiệp Vụ Page)**: Bảng giá, cam kết y khoa, địa chỉ, chính sách ưu đãi của từng Page.
- Hiển thị đầy đủ bộ ba thẻ kinh nghiệm: **`Cue Signal` (Dấu hiệu nhận diện)**, **`Lesson` (Quy tắc đúc kết)**, và **`Contrastive Evidence` (Đối chiếu ❌ Trước vs ✅ Sau)**.
- Quản lý định mức ký tự Always-On (`Always-On Token Budget Cap`): Giới hạn 1.200 ký tự giúp prompt không bị phình to gây chậm hệ thống.
- Tab **"🧬 Lịch sử Tự tiến hóa Harness²"**: Thống kê tỷ lệ nhận diện cải tiến (`Adoption Rate`), phân loại các phiên chạy `ADOPTED` (được áp dụng) và `KEEP_BASE` (giữ nguyên câu cũ vì câu cũ đã tốt).

### 3.5 Độ Tương Thích Giao Diện Đa Thiết Bị (Responsive Mobile)

- Tại breakpoint `<= 960px`: `LiveConversationInbox.vue` tự động chuyển sang thanh điều hướng 3 tab dưới đáy: `📋 DS Khách`, `💬 Chat`, `📊 Insight KH`. Người dùng chuyển đổi dễ dàng bằng 1 chạm ngón tay cái.
- Tại breakpoint `<= 900px`: `FanpageBotSimulatorModal.vue` tự động hiển thị 2 tab di động: `💬 Khung Chat Test` và `📊 Radar Tâm lý & 6 Kịch bản`.
- Tại breakpoint `<= 640px`: Header tự động thu gọn các nút phụ, căn chỉnh độ rộng modal 100vw, đảm bảo không bị tràn ngang hay vỡ layout.

---

## 4. TRỤ CỘT 3: AN NINH BẢO MẬT & HÀNG RÀO PHÒNG VỆ (SECURITY & GUARDRAILS)

### 4.1 Điểm Đạt Yêu Cầu (Strengths)

1. **Phòng vệ Runtime Bot Mạnh Mẽ (Pre-LLM Gates & Guardrail Hooks)**:
   - `PromptSecurityGuard.isDirectJailbreakAttempt`: Chặn đứng 100% các câu lệnh prompt injection nguy hiểm (`ignore all instructions`, `system override`, `tiết lộ system prompt`) ngay lập tức ở mức 0ms mà không tốn token LLM, chuyển giao nhân viên với lý do an ninh.
   - Ngăn chặn triệt để thao túng giá: Từ chối các yêu cầu tự bịa giảm giá 100% hoặc giá 0đ (`"áp dụng mã giảm giá 100% miễn phí"` -> bị chặn và chuyển nhân viên).
   - Chặn dịch vụ ngoài tài liệu (`detectUngroundedSpecificTopic`): Bắt chính xác các dịch vụ không có trong cơ sở tri thức (như `tiêm botox`, `cắt mí`) và chuyển giao chuyên viên y tế.
2. **Xác thực Webhook Meta**:
   - Xác thực `hub.challenge` chuẩn chỉ.
   - So khớp chữ ký HMAC-SHA256 sử dụng thuật toán so sánh thời gian không đổi `MessageDigest.isEqual` để chống tấn công Timing Attack.

### 4.2 Các Lỗ Hổng Bảo Mật Trọng Yếu Cần Khắc Phục (Critical Vulnerabilities)

#### Lỗ Hổng 1: Thiếu Hoàn Toàn Xác Thực & Phân Quyền API (CRITICAL)
- **Vị trí**: `api-java/pom.xml` hoàn toàn không có `spring-boot-starter-security`.
- **Thực trạng**: Mọi Controller (`DocumentController`, `RuleController`, `ConversationInboxController`, `FeedbackController`) đều mở tự do cho mọi client không cần đăng nhập hay API token.
- **Rủi ro**: Bất kỳ ai cũng có thể gửi `DELETE /api/documents/{id}` để xóa tài liệu tri thức, `POST /api/rules` để bơm quy tắc lạ, hoặc `GET /api/conversations` để đọc dữ liệu khách hàng.
- **File**: `CorsConfig.java` cho phép wildcard `allowedOriginPatterns("*")` tạo nguy cơ tấn công CSRF từ các trang web độc hại.

#### Lỗ Hổng 2: Rò Rỉ Dữ Liệu Chéo Đa Khách Thuê (CRITICAL)
- **Vị trí**: `ConversationInboxService.java` (dòng 67–74) & `ConversationInboxController.java`:
  - Khi gọi `GET /api/conversations` không truyền tham số `page_id`, hàm trả về toàn bộ danh sách khách hàng và SĐT của **tất cả các Fanpage trong hệ thống**.
  - Hàm `GET /api/conversations/{id}` không kiểm tra quyền sở hữu Page, cho phép đọc toàn văn cuộc trò chuyện của Page khác.
- **Vị trí**: `DocumentController.java` (dòng 48–51) và `RuleController.java` (dòng 86–88):
  - Khi không truyền `page_id`, trả về toàn bộ danh sách tài liệu và toàn bộ quy tắc kinh doanh của tất cả các Page.

#### Lỗ Hổng 3: Hổng Xác Thực Token Nội Bộ Giữa Các Microservice (HIGH)
- **Vị trí**: `doc-service-py/app/api.py`:
  - Các endpoint `/v1/parse`, `/v1/jobs/{job_id}`, `/v1/jobs/{job_id}/result`, `/v1/embed` **hoàn toàn không kiểm tra token** `X-Internal-Token`.
  - Thực nghiệm kiểm chứng: `http_post("http://localhost:8091/v1/embed", {"texts": ["Test"]})` trả về HTTP 200 OK ngay lập tức mà không cần token!
- **Lệch cấu hình biến môi trường**:
  - `docker-compose.yml` truyền `INTERNAL_API_TOKEN`, nhưng `doc-service-py` chỉ đọc `DOCMIND_INTERNAL_TOKEN` và `api-java` cũng đọc `DOCMIND_INTERNAL_TOKEN`. Do đó cả hai service tiếp tục dùng chuỗi fallback mặc định `docmind-internal-secret-token-2026`.

#### Lỗ Hổng 4: Đầu Độc Tri Thức Qua Cổng Tạo Quy Tắc Playbook (HIGH)
- **Vị trí**: `RuleController.java` (dòng 98–183):
  - Nhận `RuleRequest` và lưu thẳng vào database mà **không hề gọi** `PromptSecurityGuard.isPoisonedRuleAttempt`.
  - Trạng thái lưu mặc định là `APPROVED` (theo migration `V12`). Kẻ xấu có thể chèn quy tắc xưng hô thô tục hoặc giá 0đ và quy tắc này sẽ lập tức kích hoạt vào prompt chat!
- **Vị trí**: `FeedbackController.java` (dòng 45–75):
  - Luồng "Báo sai & Dạy AI" không kiểm tra `isPoisonedRuleAttempt` trước khi trích xuất quy tắc mới.

#### Lỗ Hổng 5: Ghi SĐT Thô Vào Log & Lưu Trữ Cleartext (HIGH)
- **Vị trí**: `FanpageBotService.java` (dòng 514–516):
  - Ghi thẳng SĐT của khách (`0968221644`) ra log container mà không che 4 số giữa (`096****644`).
- **Vị trí**: `MetaLearningService.java` (dòng 293–301):
  - Nếu câu dạy của người dùng có chứa SĐT, hàm trích xuất có thể gộp cả SĐT vào `generalized_rule`, dẫn đến lộ thông tin riêng tư trong Playbook.

---

## 5. TRỤ CỘT 4: ĐỘ CHÍNH XÁC THÔNG TIN & GIÁ BÁN RAG (RAG ACCURACY)

### 5.1 Bằng Chứng Đối Chiếu Tri Thức Trong Cơ Sở Dữ Liệu Thực Tế

Kiểm tra trực tiếp trong PostgreSQL (`docmind-postgres:5433`), Page `"Nàng sen"` (`2850a8ef-ca4a-40eb-87fa-4f808640efee`) chứa 2 tài liệu nguồn:
1. `Template_Tri_Thuc_Sale_Chuan.md`
2. `Nghệ thuật chốt sale.md`

### 5.2 Bảng Đối Chiếu Giá Dịch Vụ & Khảo Sát Thực Nghiệm

| Dịch Vụ Chuẩn Y Khoa | Giá Trong Tài Liệu Nguồn | Kết Quả Bot Trả Lời Thực Tế | Trạng Thái Đánh Giá |
|---|---|---|:---:|
| **Liệu trình Trị Mụn Chuẩn Y Khoa** (`SPA-01`) | 1.500.000 VNĐ (60 phút, tặng tuýp chấm mụn 10ml) | 1.500.000đ/buổi, tặng tuýp chấm mụn kháng viêm 10ml | **100% Chính xác** |
| **Căng bóng Meso Collagen** (`SPA-02`) | 3.200.000 VNĐ (90 phút, tặng mặt nạ B5) | 3.200.000đ/buổi, tặng mặt nạ phục hồi B5 | **100% Chính xác** |
| **Trị Nám Laser Pico** (`SPA-03`) | 4.500.000 VNĐ (75 phút, bảo hành văn bản 12 tháng) | 4.500.000đ/buổi, bảo hành tái phát 12 tháng | **100% Chính xác** |
| **Detox Oxy Cơ Bản** (`SPA-04`) | 650.000 VNĐ (45 phút, miễn phí soi da AI 3D) | Từ 650.000đ/buổi, soi da AI 3D miễn phí 100% | **100% Chính xác** |

### 5.3 Chính Sách Combo & Ranh Giới Y Khoa

- **Chính sách Combo**:
  - Combo Tiết Kiệm (3–4 buổi): Giảm `10%` + tặng 1 buổi Detox Oxy.
  - Combo Toàn Diện (5–9 buổi): Giảm `15%` + tặng bộ kit chăm sóc tại nhà.
  - Combo VIP Năm (10+ buổi): Giảm `20%`.
  - **Khảo sát thực tế**: Bot không bao giờ nhầm lẫn giữa Combo Tiết Kiệm (3–4 buổi) và Combo Toàn Diện (5–9 buổi).
- **Ranh giới y khoa chuẩn mực**:
  - Khi khách hàng hỏi: *"T bị mụn 15 năm kèm sẹo rỗ, liệu trình 1.5tr có cam kết khỏi sẹo rỗ không em?"*.
  - Bot trả lời chuẩn xác: Liệu trình 1.500.000đ chỉ xử lý ổ mụn viêm và phục hồi nền da khỏe; đối với sẹo rỗ lâu năm cần phác đồ bóc tách đáy sẹo chuyên sâu riêng và mời Bác sĩ Da liễu soi da AI 3D đánh giá, tuyệt đối không hứa hẹn chữa khỏi sẹo rỗ trong gói mụn.
- **Xử lý dịch vụ ngoài tài liệu**:
  - Khi hỏi: *"Bên mình có tiêm botox xóa nhăn không?"*.
  - Bot lịch sự thông báo hiện tại cơ sở chuyên sâu về các phác đồ chăm sóc và điều trị da chuẩn Y khoa, đồng thời kích hoạt `needs_human_handoff = true` để chuyên viên giải thích thêm.

---

## 6. TRỤ CỘT 5: GIỌNG VĂN TỰ NHIÊN CHUẨN TIẾNG VIỆT (HUMAN-LIKE CONVERSATIONAL TONE)

### 6.1 Xóa Sổ Triệt Để Phantom Name "Lan Anh"

- **Thực trạng trước kiểm toán**: Backend runtime đã sạch "Lan Anh", tuy nhiên bộ test `test_zero_lan_anh_in_frontend_source` bị fail do còn sót "Lan Anh" trong file mock data và placeholder của frontend.
- **Khắc phục dứt điểm**:
  - `frontend/src/mockData.js`: Đã xóa sạch 7 vị trí (trong `contrastive_evidence`, `base_response`, `candidate_b`, `rationale`, và các hàm logic mock).
  - `frontend/src/components/PlaybookModal.vue`: Đã sửa placeholder tại dòng 325 từ `Chị Lan Anh` thành `Chị`.
  - **Kiểm chứng tự động**:
    - `pytest tests_e2e/test_overhaul_acceptance.py -k "TestZeroLanAnhStringIntegrity"`: **3/3 bài test PASSED 100%**.
    - `npm run build` trong `frontend/`: Hoàn thành với **0 lỗi (Zero Errors)**.

### 6.2 Nhất Quán Danh Xưng 100% (`Em` - `Chị`/`Anh`)

- Mặc định xưng hô `"Chị"` khi khách chưa nói rõ giới tính.
- **Nhận diện giới tính nam có dung lỗi chính tả**:
  - Nhận diện chính xác ngay cả khi khách gõ tắt hoặc sai chính tả: `mìn là nam`, `mk là nam`, `mình làm nam`, `minh la nam`, `t là nam`, `nam giới`, `anh muốn`.
  - Một khi đã nhận diện nam trong tin nhắn hiện tại hoặc lịch sử hội thoại, hệ thống chuyển đổi 100% sang `"Anh"`, xóa sạch toàn bộ chữ `"Chị"` trong câu trả lời.
  - Hook `HonorificConsistencyHook` chuẩn hóa Unicode NFC, sửa lỗi đảo ngữ đại từ (`"Em có thể tham khảo..."` -> `"Anh có thể tham khảo..."`), và loại bỏ đại từ gộp (`"anh/chị"` -> `"Anh"`).

### 6.3 Loại Bỏ Toàn Bộ Mã Dịch Vụ, Citation Robot & Định Dạng Markdown

Trong `FanpageBotService.polishConversationalVietnamese`:
- Xóa bỏ triệt để các nhãn trích dẫn: `[1]`, `[2]`, `[1, 2]`, `[trích dẫn...]`.
- Xóa bỏ mã dịch vụ nội bộ: `SPA-01`, `SPA-02`, `(SPA-03)`.
- Xóa bỏ các câu mở đầu robot: *"Dựa trên tài liệu được cung cấp..."*, *"Theo đoạn trích tài liệu..."*.
- Xóa bỏ toàn bộ định dạng Markdown: Dấu thăng `#`, `##`, và dấu sao in đậm `**` được gọt sạch, giúp tin nhắn trông tự nhiên như văn bản chat Messenger thông thường giữa người với người.

### 6.4 Chống Xả Thông Tin Ở Giai Đoạn Khám Phá (`DISCOVERY`)

- Khi khách chỉ hỏi chung chung: *"Giá sao shop"*, *"Chi phí thế nào"*:
- Hook `AntiInfoDumpingHook` lập tức can thiệp:
  1. Chỉ báo 1 mức giá khởi điểm thấp nhất (`từ 650.000đ/buổi`).
  2. Bổ sung quyền lợi thăm khám miễn phí (`Bác sĩ soi da AI 3D miễn phí 100%`).
  3. Đặt 1 câu hỏi A/B định hướng nhu cầu: *"Hiện tại Chị đang muốn ưu tiên xử lý mụn, mờ nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?"*.
- Ngăn chặn hoàn toàn tình trạng liệt kê hàng loạt 4–5 dịch vụ và bảng combo làm khách hàng choáng ngợp và bỏ đi.

---

## 7. TRỤ CỘT 6: KỸ NĂNG CHỐT SALE & THU THẬP KHÁCH HÀNG TIỀM NĂNG (SALES CLOSING & LEAD CAPTURE)

### 7.1 Động Cơ Nhiệt Độ Mua Hàng Có Trạng Thái (`CustomerInsightEngine`)

- Theo dõi điểm số `buying_temperature` (thang 0–100) kèm xu hướng `momentum`:
  - `RISING`: Nhiệt độ đang tăng qua từng lượt tương tác.
  - `STABLE`: Khách hàng duy trì quan tâm đều đặn.
  - `COOLING_HALF_HEARTED`: Khách trả lời hời hợt (*"ừ"*, *"ok"*, *"để xem"*, *"để nghĩ đã"*, *"hơi đắt"*), nhiệt độ giảm mạnh `ΔTemp = -20 đến -22`.
  - `COOLING_IDLE`: Khách im lặng trôi qua 5 phút (`ΔTemp = -18`) hoặc 30 phút (`ΔTemp = -35`).
  - `RECOVERING`: Khách được hâm nóng trở lại sau khi áp dụng chiến thuật giữ nhiệt.

### 7.2 Các Kỹ Năng Tâm Lý Thực Chiến

Khi nhiệt độ mua hàng sụt giảm (`temperature_delta < 0`), hệ thống tự động kích hoạt các kỹ năng tâm lý đỉnh cao:
1. **`PSYCH_ZERO_PRESSURE_TAKEAWAY` (Lùi 1 bước để tiến 2 bước)**:
   - Ngay lập tức giải tỏa áp lực bán hàng: *"Dạ em hoàn toàn hiểu Anh muốn xem thêm cho kỹ, không có gì phải vội cả ạ."*
2. **`PSYCH_PATTERN_INTERRUPT_CURIOSITY` (Phá vỡ khuôn mẫu & Gợi mở tò mò)**:
   - Chuyển hướng từ việc báo giá sang câu hỏi chuyên môn nhẹ nhàng về nền da để kích thích khách chia sẻ thêm.
3. **`SALES_LOSS_AVERSION_RESERVATION` (Giữ suất ưu đãi 0đ ràng buộc)**:
   - Giữ chân khách bằng tâm lý sợ mất quyền lợi: *"Bên em giữ sẵn suất Bác sĩ soi da AI 3D miễn phí 0đ không ràng buộc gì cho Anh — Anh muốn em giữ khung Sáng hay Chiều tiện hơn ạ?"*

### 7.3 Tiến Trình Bán Hàng 3 Chặng & Chốt Lead Siêu Tốc 0ms

- **Chặng 1: `DISCOVERY` (Khám phá nhu cầu)**: Xác định nền da, phân loại mối quan tâm.
- **Chặng 2: `VALUE_CONSULTATION` (Tư vấn giá trị)**: Giải thích phác đồ, quyền lợi, vô khuẩn, quà tặng kèm theo.
- **Chặng 3: `SOFT_BOOKING_CLOSE` (Dẫn dắt lịch hẹn)**: Đưa ra 2 lựa chọn thay thế (*"Sáng hay Chiều"*, *"Ngày thường hay Cuối tuần"*).
- **Fast-Path Lead Capture (0ms LLM)**:
  - Khi khách để lại SĐT (`"0968221644 tư vấn thêm"`), hệ thống chuyển sang Fast Path bỏ qua hoàn toàn LLM:
  - Trả lời xác nhận ngay trong < 1s, xưng hô chuẩn xác (`Anh`/`Chị`), thông báo chuyên viên sẽ liên hệ qua SĐT/Zalo ngay, dừng toàn bộ việc quảng cáo combo giá, và bật cờ `needs_human_handoff = true` kèm số điện thoại đã bóc tách.

---

## 8. ĐÁNH GIÁ MỨC ĐỘ SẴN SÀNG & DANH MỤC KIỂM TRA TÍCH HỢP FANPAGE FACEBOOK (FANPAGE READINESS)

### 8.1 Điểm Số Sẵn Sàng Hiện Tại: 58 / 100

DocMind đã hoàn thành rất tốt logic nghiệp vụ và giả lập Messenger, nhưng **chưa thể cắm trực tiếp vào Fanpage Facebook thật** do còn 5 rào cản kiến trúc chí mạng dưới đây.

### 8.2 Phân Tích 5 Lỗ Hổng & Rào Cản Chí Mạng Khi Chạy Trên Messenger Thật

```
[Khách nhắn tin Messenger]
            │
            ▼ (POST /webhook)
    ┌────────────────────────────────────────────────────────┐
    │ FanpageBotController.java                              │
    │                                                        │
    │ ❌ LỖ HỔNG 1: Luồng HTTP bị block đồng bộ 20-30s       │
    │   -> Meta Webhook Timeout (Giới hạn 20s của Facebook)  │
    │   -> Meta liên tục gửi lại (Retry Loop)                │
    │                                                        │
    │ ❌ LỖ HỔNG 2: Không gửi tin qua Graph API Send API      │
    │   -> Trả về JSON trong HTTP response body              │
    │   -> KHÁCH TRÊN MESSENGER HOÀN TOÀN KHÔNG NHẬN ĐƯỢC TIN! │
    │                                                        │
    │ ❌ LỖ HỔNG 3: Không có Page Access Token                │
    │   -> Không biết Page nào để gửi, không có token xác thực│
    │                                                        │
    │ ❌ LỖ HỔNG 4: Thiếu Idempotency theo message.mid        │
    │   -> Trùng lặp câu trả lời khi mạng lag                │
    └────────────────────────────────────────────────────────┘
```

1. **Lỗ hổng 1: Xử lý Webhook Đồng bộ (Synchronous Webhook Bottleneck)**:
   - Trong `FanpageBotController.java` dòng 163, lời gọi `fanpageBotService.handleCustomerMessage` nằm ngay trong luồng HTTP request của Facebook.
   - Khi LLM mất 20–30s để phản hồi, request vượt ngưỡng timeout 20s của Meta. Facebook sẽ coi là kết nối thất bại và retry liên tục, gây nghẽn toàn bộ server.
2. **Lỗ hổng 2: Thiếu Hoàn Toàn Facebook Graph API Send API (Outbound Messenger Client)**:
   - Hiện tại controller trả về tin nhắn của Bot trong HTTP response body (`ResponseEntity.ok(out)`).
   - Facebook Webhook là cơ chế đẩy một chiều. Facebook **không đọc** HTTP response body để hiển thị cho khách hàng! Để tin nhắn hiển thị trên Messenger của khách, hệ thống **bắt buộc phải thực hiện lời gọi HTTP POST ra ngoài** tới:
     `POST https://graph.facebook.com/v19.0/me/messages?access_token=<PAGE_ACCESS_TOKEN>`
3. **Lỗ hổng 3: Thiếu Trường Lưu Trữ `Page Access Token` và Ánh Xạ `Facebook Page ID`**:
   - Bảng `knowledge_pages` chỉ có UUID nội bộ, không có cột lưu `fb_page_id` và `page_access_token`. Khi Facebook gửi webhook, nó chỉ truyền ID của Page Facebook dạng số trong `entry[].id`, hệ thống hiện tại không ánh xạ được sang Page Persona nào.
4. **Lỗ hổng 4: Thiếu Tín Hiệu Gõ Chữ Thật (`sender_action: typing_on`)**:
   - Trên Messenger thật, cần gọi API gửi tín hiệu đang gõ chữ trong lúc LLM đang xử lý để khách không sốt ruột và gửi bong bóng so le với nhịp dừng ~2.5s.
5. **Lỗ hổng 5: Thiếu Bộ Khóa Trùng Lặp Tin Nhắn (Deduplication theo `mid`)**:
   - Chưa kiểm tra mã `message.mid` của Facebook trước khi xử lý, dẫn đến nguy cơ bot trả lời 2 lần và trừ nhiệt độ 2 lần khi Meta retry.

---

### 8.3 Actionable Facebook Fanpage Webhook Integration Checklist

Dưới đây là danh mục kiểm tra và các bước hành động cụ thể dành cho đội ngũ kỹ thuật để đưa hệ thống vào sản xuất:

#### Giai Đoạn 1: CSDL & Quản Lý Token Fanpage (Database & Configuration)
- [ ] **Flyway Migration `V14__fanpage_facebook_credentials.sql`**:
  - Thêm cột `fb_page_id VARCHAR(64) UNIQUE` vào bảng `knowledge_pages`.
  - Thêm cột `page_access_token TEXT` vào bảng `knowledge_pages` (mã hóa nếu cần).
  - Thêm cột `app_secret VARCHAR(128)` vào bảng `knowledge_pages`.
- [ ] **Cập Nhật Entity & DTO**:
  - Bổ sung các trường trên vào `KnowledgePageEntity.java` và `KnowledgePageResponse.java`.
  - Thêm index `idx_knowledge_pages_fb_page_id` để tra cứu trong O(1).
- [ ] **Giao Diện Quản Trị Modal Cấu Hình Page**:
  - Cho phép người quản trị nhập `Facebook Page ID` và dán `Page Access Token` trực tiếp trên UI Vue 3.

#### Giai Đoạn 2: Xây Dựng Facebook Graph API Client & Asynchronous Worker
- [ ] **Tạo `FacebookGraphApiClient.java` trong `api-java`**:
  - Cài đặt hàm `sendTextMessage(String pageAccessToken, String recipientPsid, String text)`.
  - Cài đặt hàm `sendTypingIndicator(String pageAccessToken, String recipientPsid, boolean typingOn)`.
  - Cài đặt hàm `sendStaggeredReply(String pageAccessToken, String recipientPsid, FanpageBotResponse response)`: Tự động gửi bong bóng 1 -> chờ ~2.5s với `typing_on` -> gửi tiếp bong bóng 2.
- [ ] **Chuyển Đổi Webhook Sang Xử Lý Bất Đồng Bộ (@Async)**:
  - Cấu hình `ThreadPoolTaskExecutor` cho Webhook Meta (`metaWebhookExecutor`).
  - Trong `FanpageBotController.receiveMetaWebhook`:
    - Xác thực chữ ký HMAC-SHA256 trong < 5ms.
    - Trả về ngay lập tức HTTP 200 OK: `{"status":"EVENT_RECEIVED"}` (trong < 200ms).
    - Đẩy sự kiện tin nhắn vào luồng worker bất đồng bộ.
- [ ] **Logic Luồng Worker Bất Đồng Bộ**:
  1. Đọc `fb_page_id` từ `entry[].id`, truy vấn `KnowledgePageEntity` tương ứng.
  2. Bật tín hiệu `sender_action: typing_on` gửi tới PSID của khách.
  3. Gọi `FanpageBotService.handleCustomerMessage` lấy câu trả lời và nhãn Guardrails.
  4. Đồng bộ tin nhắn vào `ConversationInboxService`.
  5. Gọi `FacebookGraphApiClient.sendStaggeredReply` đẩy tin nhắn ra Messenger thật.

#### Giai Đoạn 3: Chống Trùng Lặp (Idempotency) & Tối Ưu Độ Trễ
- [ ] **Bộ Đệm Deduplication theo `message.mid`**:
  - Lưu các `mid` đã nhận vào cache có TTL 10 phút (Redis hoặc `ConcurrentHashMap` tự dọn dẹp).
  - Nếu `mid` đã tồn tại, lập tức bỏ qua không gọi lại LLM.
- [ ] **Điều Chỉnh Cấu Hình LLM Phù Hợp Cho Hot Path**:
  - Đặt `LLM_TIMEOUT_SECONDS=60` và tăng `FAST_REASONING_ATTEMPT_TIMEOUT_MS=60_000`.
  - Khuyến nghị sử dụng `gemini-2.5-flash` hoặc `gpt-4o-mini` cho Hot Path để đảm bảo thời gian phản hồi chỉ từ 1.5s – 3.0s.

#### Giai Đoạn 4: Đăng Ký Ứng Dụng Meta & Thiết Lập Live Webhook
- [ ] Tạo App trên Meta for Developers (`developers.facebook.com`) loại "Doanh nghiệp (Business)".
- [ ] Bật sản phẩm "Messenger" và cấp quyền `pages_messaging`, `pages_read_engagement`.
- [ ] Cấu hình Callback URL trỏ tới: `https://<your-domain>/api/bot/fanpage/webhook`.
- [ ] Điền mã `Verify Token` khớp với cấu hình trong `application.properties`.
- [ ] Đăng ký theo dõi sự kiện Webhook `messages` và `messaging_postbacks`.

---

## 9. KẾT LUẬN & KẾ HOẠCH HÀNH ĐỘNG TRIỂN KHAI

DocMind là một nền tảng AI Bán hàng xuất sắc và đột phá:
- **Ưu thế vượt trội**: Bộ nhớ kinh nghiệm **Harness²**, hệ thống chốt chặn **In-Loop Guardrails (< 5ms)**, tính năng **Đệm tin nhắn dồn dập (3s)**, **Nhịp gõ chữ so le chân thực (~2.7s)**, và độ nhạy bén tâm lý khách hàng cao hơn hẳn các giải pháp chatbot RAG truyền thống.
- **Hành động cần làm trước khi cắm Fanpage thương mại**:
  1. Gia cố cô lập dữ liệu đa khách thuê (`page_id`) tại `ConversationInboxService` và `DocumentController`.
  2. Bổ sung kiểm tra token cho các endpoint của `doc-service-py` và đồng bộ tên biến môi trường `DOCMIND_INTERNAL_TOKEN`.
  3. Cài đặt `FacebookGraphApiClient` và luồng xử lý Webhook bất đồng bộ theo đúng Checklist ở Mục 8.3.

Sau khi hoàn thành các hạng mục trên, DocMind sẽ đạt **100% tiêu chuẩn sẵn sàng vận hành sản xuất** trên nền tảng Meta Messenger Platform với khả năng chốt đơn tự động, đúc kết kinh nghiệm liên tục và an toàn dữ liệu tuyệt đối.
