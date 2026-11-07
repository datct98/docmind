# Sales Core 3 — phản biện và lát triển khai

Phạm vi: phản hồi người dùng gửi ngày 2026-11-07; tiếp tục baseline `af1c236`. Đây là lát cải cách chạy được trên core hiện có, không tuyên bố đã hoàn thành mọi mốc của ADR.

## Chắt lọc bản góp ý

Giữ: modular monolith, phát triển chung core với spa và **thuê xe máy**, hỏi theo giá trị thông tin, đánh giá cả chốt quá sớm và bỏ lỡ cơ hội, điều chỉnh khi khách bác bỏ hướng tư vấn, quyền hạn theo khả năng nghiệp vụ thật.

Bổ sung/giới hạn:

- “Emotion first” là ưu tiên nhận biết và xử lý điều đang cản khách, không bắt mọi câu bắt đầu bằng “em hiểu”. Khách hỏi thẳng thì trả lời thẳng; khách bực vì bị hỏi lại thì sửa hành vi; chỉ suy cảm xúc dưới dạng giả thuyết.
- “Không hardcode” vẫn cần các bất biến bằng code về nguồn, quyền hạn, phiên bản và cô lập dữ liệu. Quyết định trò chuyện thích ứng, chuyên môn nằm trong pack; không dùng một funnel buộc khách đi qua mọi bước.
- Phản ứng của khách là bằng chứng quan sát, không chứng minh quan hệ nhân quả. Tiếp tục chat không có nghĩa chiến lược thành công. Không biến phản ứng trong một phiên thành learned rule áp cho mọi khách.
- Tên hành động đúng không chứng minh lời nói đúng. Kế hoạch phải đi cùng writer, reviewer và continuation; không để mỗi bước tự nâng mức sẵn sàng.
- Không ép hai bubble hay câu hỏi sau mọi báo giá. Khi chưa có bước tiếp có ích, trả lời rồi đợi khách là hành vi hợp lệ. Nếu có continuation thì vẫn giữ cơ chế khoảng nghỉ và hủy khi có tin mới.
- Trích dẫn đúng chữ chưa đủ để chứng minh diễn giải đúng. Cập nhật trí nhớ cần kiểm tra nguồn bằng code và kiểm tra nghĩa; reviewer là xác suất, không bảo chứng tuyệt đối.
- Hai kết quả kiểm tra phải tách hợp đồng: `replyReview` chấm câu gửi, `memoryReview` chấm cập nhật trí nhớ. Replay đã phát hiện lỗi trí nhớ bị chuyển nhầm sang writer, gây sửa không có tác dụng rồi timeout. Cập nhật trí nhớ bị bác bỏ không tự làm hỏng một câu trả lời đúng.
- Dữ liệu customer memory không chứa lời bot đã sinh, kết quả booking tưởng tượng hoặc cảm xúc suy đoán. Delivery ledger tiếp tục là nơi quản lý tình trạng gửi/nhận.
- Industry package là dữ liệu hướng dẫn có version; chưa phải một rule engine đầy đủ hay một bộ tool giao dịch. Không khai báo capability có thể đặt lịch khi chưa có adapter hoạt động.

## Pipeline đang triển khai

`Event/guard hiện có → khóa revision ngắn + ghi lời khách → nguồn đúng page + query dùng memory → lập kế hoạch và đề xuất cập nhật memory → viết lời → kiểm tra nguồn/quyền hạn/nghĩa/memory → CAS commit → delivery hiện có → continuation tùy nhu cầu với cùng kế hoạch`

- `SalesMemory` giữ facts có key, value, nguyên văn, sourceTurn; sửa/rút có dấu vết. Khi cập nhật thất bại, nguồn thô vẫn còn trong cửa sổ gần đây.
- `JdbcSalesMemoryStore` lưu PostgreSQL theo page, sender, conversation; transaction chỉ bao việc lấy revision/ghi nguồn, không giữ khóa khi đợi model. Kết quả revision cũ bị chặn ở commit.
- Trần hiện tại: 48 facts, 12 thay đổi gần nhất, tối đa 12 nguồn khách/12.000 ký tự. Không hứa nhớ vô hạn. Không tự xóa fact đang hiệu lực để vừa trần; báo vi phạm và giữ nguồn trước đó.
- Planner ghi rõ lợi ích cho khách, mục đích câu hỏi, bằng chứng sẵn sàng và trở ngại chưa giải quyết. Đây là kế hoạch tạm thời, không phải sự thật về tâm lý khách.
- `InteractionPlan` đi xuyên writer/reviewer/continuation. Nếu không cần hỏi và chưa có bằng chứng sẵn sàng, contract không cho tạo thêm continuation để thúc giao dịch.
- Reviewer dùng alternate đã cấu hình như baseline. Thử nghiệm chấm chéo bằng model khác bị loại vì đã diễn giải lời bot thành lựa chọn của khách và gây handoff sai. Không coi thêm model là tự động tăng chất lượng.
- Ngân sách evidence giữ chỗ cho đoạn truy xuất phù hợp trước catalog dự phòng, tránh bảng giá chiếm hết chỗ của chính sách/quy trình đang cần để giải đáp băn khoăn. Sau khi khách đã nêu rõ tiêu chí, planner có thể yêu cầu dữ kiện kinh doanh để giải quyết thay vì tiếp tục hỏi khám phá.
- `BEAUTY_SPA`, `VEHICLE_RENTAL`, `MOTORBIKE_RENTAL` có schemaVersion 2, customerFields, actionRequirements và evaluationScenarios. Pack cũ thiếu version vẫn đọc theo v1; version chưa hỗ trợ bị từ chối.

## Kiểm chứng cần có

| Năng lực | Kiểm chứng | Giới hạn |
|---|---|---|
| Nhớ/sửa lời | Sau hơn 40 lượt, serialization, sửa/rút ý và audit | Không chứng minh recall mọi thông tin ở quy mô production |
| Cô lập và concurrency | Hai store PostgreSQL, begin đồng thời, CAS chống kết quả cũ, reset | Delivery continuation vẫn dùng ticket trong một process |
| Nguồn | Quote giả, source tương lai, nguồn cũ ghi đè mới, semantic approval bắt buộc | Reviewer vẫn có thể chấm sai về nghĩa |
| Dùng memory để tra cứu | Query chứa fact đã nhớ, không giả thêm message vào transcript | Chất lượng retrieval cần benchmark riêng trên corpus lớn |
| Chiến lược | Replay spa/xe máy: đắt, giữ phạm vi/mẫu xe, so sánh, sửa ngày, sẵn sàng, dừng | Script cố định chưa thay thế người sales chấm hay hội thoại phân nhánh |
| Giao diện | Build, migration, API qua proxy và test UI thật | Không thử gửi tới khách Facebook thật |

## Khoảng trống còn lại

Chưa có booking/availability adapter thật; không tự cam kết thành công. Dữ kiện ngành, giá và policy vẫn cần chủ doanh nghiệp quản lý hiệu lực. Inbound dedup và delivery vẫn theo lớp hiện hữu; memory không biến REST thiếu event id thành exactly-once. Trạng thái chiến lược chưa là bộ đo outcome dài hạn. Chưa có tập held-out do sales chấm độc lập hoặc số liệu chuyển đổi thật. Chưa mở rộng pilot sang tất cả page.

Kết quả chạy, độ trễ, ca chưa đẹp và phiên bản bàn giao được ghi riêng trong báo cáo validation; không dùng số lượng unit test để tự phong EQ cao.
