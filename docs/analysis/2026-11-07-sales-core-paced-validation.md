# Sales Core 2 — tư vấn có nhịp, kiểm chứng và giới hạn

## Phạm vi thay đổi

Core tư vấn inbound dùng chung các ngành. Planner chọn bước hữu ích từ hội thoại trước khi writer thấy catalog; industry pack cung cấp tiêu chí chuyên môn, còn tri thức/giá/chính sách thuộc từng Page. Không có nhánh Java theo spa, thuê xe, tên gói hoặc câu khách «Đắt» trong package salescore.

Đã tách ba việc trước đây bị trộn vào một prompt: trả lời có dữ kiện kinh doanh, trao đổi để hiểu khách, và lời tiếp nối sau giá. Câu chữ do model sinh, không ghép mẫu đồng cảm/chốt sale. Các kiểm tra schema, nguồn, số học, quyền hạn và vận chuyển vẫn là quy tắc kỹ thuật. Thông báo vận hành khi provider/ngữ cảnh không khả dụng vẫn có nội dung dự phòng; không tuyên bố mọi chuỗi trong ứng dụng đều do AI viết.

Planner chỉ đưa giả thuyết và chọn mức ngữ cảnh. Nếu bản nháp với ngữ cảnh gọn không đạt, bước sửa có thể đọc lại nguồn gốc đầy đủ; quyết định của planner không được biến thành giới hạn khiến hệ thống không thể sửa lỗi thiếu nguồn. Lỗi định dạng bong bóng và lỗi ngữ nghĩa được gom khi có thể để cùng một lượt sửa xử lý cả hai.

## Hai bong bóng sau báo giá

Model đầu chỉ tạo một bong bóng giá và mục đích tiếp nối. UI gọi tiếp sau khi hiển thị giá; webhook chỉ gọi tiếp khi Graph chấp nhận bong bóng đầu. Khi đó mới sinh lời tiếp nối với reasoning được bật. Đầu ra bước này chỉ gồm chiến lược và một bong bóng; phần hiểu khách đã kiểm chứng được giữ nguyên, không suy thêm dữ kiện khi khách chưa nhắn.

Khoảng nghỉ mục tiêu tối thiểu 10 giây gồm thời gian model và review; phần còn thiếu được chờ ở kênh gửi. Nếu model mất hơn 10 giây, khoảng nghỉ dài hơn. Đây không phải cam kết luôn đúng 10 giây. Nếu lời tiếp nối không kiểm chứng được thì bỏ phần tùy chọn đó, không thay báo giá đã gửi bằng handoff giả.

Ticket gắn Page/sender/conversation, dùng một lần, hạn 120 giây, giới hạn 512 sender. Tin khách mới, takeover, opt-out và outbound fence có thể chặn phần còn chờ. Callback không được ghi như tin khách mới. Hiện ticket nằm trong một API instance; restart bỏ phần chờ, nhiều replica cần sticky routing hoặc kho ticket chung.

## Các mốc kiểm tra

- Mốc tích hợp trước chỉnh prompt cuối: **92/92 test PASS**, gồm core, gateway, hủy/expiry/replay, lưu inbox, gửi Graph giả, provider HTTP boundary, Stage 1/2 và outbound ledger. Log `artifacts/sales-core-paced-release-tests.log`. Không gọi đây là kết quả toàn bộ repository.
- Build frontend: PASS (`artifacts/sales-core-paced-frontend.log`). Docker API/frontend đã build thành công; API và UI chạy cục bộ tại 8090/3000.
- UI đã hiển thị hai bong bóng và hook `SALES_CORE_PACED_V2`; không gửi tin cho khách Facebook thật trong kiểm thử.
- REST qua proxy UI đã xác minh một giá, lời tiếp nối và callback trùng bị bỏ. SELECT PostgreSQL của sender thử `fb-customer-demo-sales-paced-bd84e67f7541` cho đúng 1 CUSTOMER + 2 AI_BOT. Lần đo này trước rút gọn schema tiếp nối: 17,151 giây cho giá, 33,610 giây cho lời tiếp nối; giữ riêng tại `artifacts/sales-core-paced-api-before-compact.json`.

### Bản cuối — 07/11/2026, 08:11 (+07)

- **92 test, 0 failure, 0 error, 0 skip** ở mã cuối: `artifacts/sales-core-paced-final-verified.log`. Gồm 91 test kỹ thuật/hồi quy và một test live chạy 12 lượt spa + thuê xe bằng catalog giả lập. Đây là tập kiểm thử liên quan, không phải toàn bộ repository.
- Replay lưu riêng tại `artifacts/sales-core-live/paced-final-12-turns.json`: 12/12 lượt không rơi vào thông báo dự phòng do lỗi, 7 lời tiếp nối được sinh thật. Có một bàn giao có chủ đích ở lượt thuê xe cuối vì nguồn không có thủ tục/giấy tờ; không xác nhận đã giữ xe. Các lượt «Đắt» không tự chuyển nhân viên.
- Thời gian phase đầu trong replay: nhỏ nhất 11,733 giây, trung vị 20,153 giây, lớn nhất 40,683 giây. Phase tiếp nối từ 5,207 đến 26,113 giây, chưa cộng phần chờ bù. Mẫu nhỏ và có repair, không đại diện p95 vận hành.
- REST qua proxy UI trên image cuối: giá **15,906 giây**; sinh/kiểm chứng tiếp nối **8,916 giây**; delay còn lại **1,193 giây**. Callback lặp trả rỗng. PostgreSQL xác nhận sender thử `fb-customer-demo-sales-paced-b2957dbe3b7e` có đúng **1 CUSTOMER + 2 AI_BOT**. Chi tiết: `artifacts/sales-core-paced-api-smoke.json`.
- Image API đang chạy khớp image vừa build: `sha256:9166abfec2435739704b4356a04c6a1277ec6e1283ac18464a51ea70169cfdcc`. API, frontend và PostgreSQL đều healthy sau recreate.
- UI đã reload và mở hội thoại mới: «Cho mình hỏi giá massage toàn thân ở Hà Nội» → một gói 399.000VNĐ rồi một lời tiếp nối; «Đắt» → hỏi làm rõ băn khoăn, không bàn giao. Badge ghi Sales Core; thời gian phase đầu hiển thị 13,3 giây và 13,2 giây. Ảnh: `artifacts/sales-core-paced-ui.png`.

Đọc thủ công transcript cho thấy cải thiện về hợp đồng và không bàn giao sai vì một từ «Đắt», nhưng còn lặp giá ở spa, có câu hỏi hai ý, câu chữ đôi lúc thiếu tự nhiên, và thuê xe vẫn đưa hơi nhiều điều kiện cùng lúc. Replay dùng chuỗi câu khách cố định; khi câu hỏi của bot thay đổi, câu khách tiếp theo có thể không trả lời đúng câu vừa hỏi. Không dùng bộ này để khẳng định EQ/chốt sale đã đạt chuẩn. Các lần lỗi trước sửa vẫn giữ riêng dưới `artifacts/sales-core-live/`; không gộp các phiên bản vào một tỷ lệ thành công.

## Điều chưa thể suy ra từ PASS

Review ngữ nghĩa vẫn có thể bỏ sót lặp câu, lời mời hơi sớm hoặc lời văn thiếu tinh tế. Provider có lúc vượt deadline 45 giây; không dùng kết quả đẹp nhất để đại diện độ ổn định. Mốc full suite trước đây có các failure/error baseline, được ghi trong báo cáo nền `2026-11-07-sales-core-validation.md`; không tuyên bố full suite xanh.

Core không có kết quả tồn xe, đặt lịch hay thanh toán thật. Khi khách sẵn sàng, nó có thể ghi nhận nhu cầu và yêu cầu nhân viên xử lý; không được nói đã giữ chỗ/thu tiền. Hiệu quả chốt sale, EQ và mức hài lòng cần pilot có người chấm trên hội thoại chưa dùng để chỉnh prompt, không được suy từ số skill hoặc cờ PASS.

Hướng dẫn chạy và thêm ngành: `../sales-core.md`. Chỉ bật pilot Page Nàng Sen tại môi trường này; không push hoặc mở rộng allowlist trong đợt sửa.
