# Kế hoạch sửa Sales Core sau replay người dùng

1. Thu thập trace lỗi báo giá/phản đối và đối chiếu câu gửi thực tế — đã thực hiện.
2. Giữ core trung lập ngành; sửa chính sách chọn một bước có ích, tiết chế danh mục, lời mở đầu và cách giải quyết băn khoăn — đã triển khai, đã đọc replay; còn giới hạn chất lượng ghi trong báo cáo.
3. Tách xử lý báo giá và lời tiếp nối; ticket một lần, hủy khi tin mới/takeover, thời gian thinking nằm trong khoảng nghỉ — đã triển khai UI và webhook.
4. Sửa nhận diện VNĐ Unicode; thêm phục hồi bằng câu làm rõ có kiểm chứng thay vì mặc định bàn giao — đã triển khai.
5. Kiểm thử hợp đồng, expiry/replay/isolation, chen ngang, không nhân đôi tin khách, gửi Facebook giả lập, hồi quy Stage 1/2 — bản cuối 92/92 PASS (91 kỹ thuật + 1 live replay).
6. Replay model thật với ca người dùng và ngành thuê xe; đọc từng câu, lưu cả lần thất bại, không dùng PASS thay đánh giá chất lượng — hoàn tất 12 lượt ở bản cuối, 0 operational fallback; hạn chế lời văn/độ trễ được ghi riêng.
7. Build Docker cục bộ, kiểm tra UI và khoảng nghỉ, ghi giới hạn còn lại — hoàn tất; bản này được bàn giao bằng commit cục bộ, không push. API/DB xác minh 1 tin khách + 2 tin bot; UI có ảnh kiểm chứng và xử lý «Đắt» không bàn giao sai.

Đặc tả: `../specs/2026-11-07-sales-core-paced-recovery.md`.
