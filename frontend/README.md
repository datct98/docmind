# DocMind Frontend (Vue 3 + Vite)

Giao diện Web Frontend hiện đại cho hệ thống **DocMind (Document-grounded RAG)**, lấy cảm hứng từ trải nghiệm của NotebookLLM.

---

## 🌟 Các tính năng chính

1. **Upload tài liệu đa định dạng**:
   - Khu vực Kéo & thả (Drag and drop) hoặc chọn tệp từ máy.
   - Hỗ trợ các định dạng: `.pdf`, `.docx`, `.pptx`, `.xlsx`, `.md`, `.html`, `.txt` (tối đa 50MB).
   - Tự động bắt đầu ingest pipeline và nhận `document_id`, `job_id`.

2. **Theo dõi trạng thái tài liệu thời gian thực (Status Tracker)**:
   - **`PARSING` (Đang phân tích)**: Hiệu ứng pulsing indicator, tự động poll trạng thái từ backend mỗi 2 giây.
   - **`READY` (Sẵn sàng / Đã xử lý)**: Hiển thị số trang, các engine bóc tách (`pymupdf`, `docling`, `paddleocr`, v.v.).
   - **`FAILED` (Lỗi trích xuất)**: Báo đỏ trực quan.
   - Bộ lọc nhanh theo trạng thái (Tất cả / Sẵn sàng / Đang xử lý / Lỗi).
   - Checkbox cho phép chọn 1 hoặc nhiều tài liệu làm ngữ cảnh hỏi đáp (Context scope).

3. **Hỏi đáp RAG thông minh (Grounded Chat with Citations)**:
   - Trả lời bằng Markdown (hỗ trợ in đậm, danh sách, khối mã lệnh, trích dẫn).
   - **Thẻ Citation kiểm chứng nguồn gốc**: Hiển thị chính xác số trang (`Trang 1`, `Trang 2`), đường dẫn tiêu đề mục (`heading_path`), toạ độ khối (`bbox`), trích đoạn gốc (`snippet`) và nút sao chép trích đoạn.
   - Điều chỉnh tham số `Top-K` chunks linh hoạt (3, 5, 10, 20 chunks).
   - Các gợi ý câu hỏi bắt đầu (Starter prompts).

4. **Xóa tài liệu & Toàn bộ dữ liệu nhúng (Cascade Delete & Confirmation Modal)**:
   - Nút xóa trực tiếp trên từng thẻ tài liệu và bên trong modal chi tiết tài liệu.
   - Hộp thoại cảnh báo xác nhận rõ ràng trước khi xóa: giải thích chi tiết việc xóa vĩnh viễn tệp, blocks văn bản và toàn bộ **vector embeddings (384-dim pgvector)** + chỉ mục tìm kiếm full-text trong PostgreSQL database.
   - Tự động hủy polling và cập nhật giao diện ngay lập tức.

5. **Inspector xem chi tiết tài liệu (Document Details Modal)**:
   - Thanh tiến trình trực quan phân bổ engine bóc tách (Engine Breakdown: PyMuPDF / Docling / PaddleOCR).
   - Thông tin hash SHA-256, số trang, Document ID.

6. **Chế độ Demo trực quan (Standalone Demo Mode)**:
   - Tự động phát hiện trạng thái backend. Nếu backend `http://localhost:8090` chưa bật, ứng dụng tự động hiển thị dữ liệu mẫu để trải nghiệm giao diện ngay lập tức mà không gặp lỗi.
   - Có nút chuyển đổi linh hoạt giữa Live API và Demo Mode.

---

## 🚀 Khởi chạy ứng dụng

```bash
# 1. Đi tới thư mục frontend
cd docmind/frontend

# 2. Cài đặt dependencies (nếu chưa cài)
npm install

# 3. Chạy dev server
npm run dev
```

Server sẽ khởi chạy tại: **`http://localhost:3000/`**

---

## 🔗 Cấu hình Proxy kết nối Backend

Trong `vite.config.js`:
- Mọi request gọi `/api/**` được proxy trực tiếp sang Spring Boot Backend tại `http://localhost:8090`.
- Backend Spring Boot đã được mở CORS và bổ sung endpoint `GET /api/documents` để lấy danh sách toàn bộ file trong DB.
