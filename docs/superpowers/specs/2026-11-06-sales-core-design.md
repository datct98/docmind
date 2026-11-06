# Sales Core: thiết kế thay luồng tư vấn

Mục tiêu: dùng cùng một bộ máy tư vấn cho nhiều Page/ngành; model quyết định bước tiếp theo dựa trên lời khách và dữ kiện. Không dùng số lần phản đối giá, từ khóa ngành hay kịch bản cố định để điều khiển lời thoại.

## Ranh giới

1. `FanpageBotService` giữ quản lý inbound, opt-out, tiếp quản, hydrate lịch sử và chốt chặn outbound.
2. `salescore` nhận lịch sử, tri thức và cấu hình Page; không gọi planner/sanitizer ACI cũ. Một lượt sinh quyết định + lời thoại; chỉ sửa lại khi vi phạm hợp đồng dữ liệu/quyền hạn, tối đa một lần trong deadline chung.
3. Industry pack JSON chứa tiêu chí lựa chọn, câu hỏi có ích và giới hạn chuyên môn. Page giữ giọng nói và dữ liệu riêng. Thêm ngành bằng file, không thêm nhánh Java.
4. Bảng giá/chính sách tiếp tục từ tài liệu READY, đang hiệu lực, đúng Page. Có thể khai báo catalog có cấu trúc trong cấu hình Page để tránh phụ thuộc bảng PDF. Không sao chép giá test vào dữ liệu thật.
5. Lịch sử tin nhắn đã lưu là trí nhớ có thẩm quyền. Model trích dẫn lời khách khi ghi nhận nhu cầu; giả thuyết tách riêng và không nâng thành sự thật. Chưa thêm một kho memory thứ hai dễ lệch trạng thái.
6. Core có quyền tư vấn, đề xuất bước tiếp, thu thập yêu cầu và yêu cầu bàn giao. Không có quyền tự xác nhận tồn kho/đặt chỗ/thanh toán. Chốt giao dịch cần kết quả từ hệ thống nghiệp vụ hoặc nhân viên; adapter thực thi riêng là bước mở rộng có hợp đồng rõ.

## Hành vi cần đạt

- Giải quyết ý hiện tại, không hỏi lại thông tin đã biết; phản hồi cụ thể thay cho lặp câu đồng cảm.
- Mức giá thấp hơn phải chỉ rõ phần nhu cầu bị giảm. Không biến gói khác phạm vi thành thay thế tương đương.
- Phản đối chưa đủ để khẳng định nguyên nhân. Khách nói ngắn không đồng nghĩa hết nhu cầu.
- Sau một cách tư vấn không hiệu quả, thay cách tiếp cận có lý do: làm rõ tiêu chí, so sánh, thừa nhận không phù hợp hoặc bàn giao. Không ép mỗi lượt có câu hỏi/chốt.
- Khi khách sẵn sàng, giúp hoàn tất bước thực tế trong quyền hạn; không tiếp tục vòng khám phá.

## Kiểm chứng

Điều chỉnh từ replay thực tế: thêm một lượt model kiểm tra ngữ nghĩa độc lập cho bản nháp và bản sửa. Chỉ sửa tối đa một lần, mọi cuộc gọi chia sẻ deadline 45 giây. Không dùng nhãn tự nhận của lượt sinh để chứng minh chất lượng. Giá tổng tính bằng phép toán có toán hạng trích nguồn; Java xác minh phép tính, reviewer kiểm tra đơn vị/ngữ cảnh. Hai thay đổi này dựa trên lỗi đã quan sát trong replay Spa và thuê xe.

Sau khi kiểm tra hợp đồng provider: policy và JSON dữ liệu được tách thành system/user đúng cơ chế của client hiện có. Khi đã cấu hình model dự phòng, dùng nó cho review/lượt sửa để không lặp lỗi của cùng model. Đây là chính sách khả năng/độ tin cậy, không định tuyến theo ngành hay câu khách.

Điều chỉnh sau replay: writer có lựa chọn PRIMARY/ALTERNATE qua cấu hình. Chứng cứ hợp lệ được chia sẻ giữa các bubble cùng lượt; không bắt lặp citation chỉ vì nhắc cùng giá lần nữa. Phép tính sau có thể tham chiếu kết quả đã kiểm chứng trước đó, không được tham chiếu vòng/tương lai. Phản hồi đúng dữ kiện nhưng còn lỗi diễn đạt được thử sửa một lần và gắn QUALITY_REVIEW_WARNING nếu vẫn chưa đạt; sai dữ kiện/quyền hạn luôn chặn. Đây không phải bằng chứng mọi phản hồi đã có chất lượng bán hàng tốt.

Test hợp đồng và integration phải chứng minh đường runtime mới được gọi, Page isolation, bảo toàn câu hỏi ngân sách hợp lệ, lỗi JSON/giá/nguồn/cam kết không lọt, deadline, opt-out/outbound fence. Spa và thuê xe dùng cùng code, dữ liệu fixture riêng. Chạy lại test cũ; lỗi baseline phải phân biệt với regression. Replay model thật nếu provider khả dụng; báo rõ số lượt, prompt, latency, điểm yếu; test xanh không đồng nghĩa EQ/conversion đã tốt.

## Triển khai và rollback

Core bật bằng `sales.core.enabled` (mặc định false), có allowlist UUID Page để pilot từng Page; bản build mới mới có hiệu lực. `false` quay về ACI cũ. Không deploy/push trong công việc này. Các file cấu hình mẫu không tự gắn vào Page thật. Không đổi embedding hoặc reindex ngầm; truy xuất bảng giá độc lập vector stub, các đoạn hỗ trợ vẫn dùng retrieval hiện có.
