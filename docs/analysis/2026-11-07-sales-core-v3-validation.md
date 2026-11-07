# Sales Core 3 — kiểm chứng bản pilot ngày 2026-11-07

Phạm vi: tiếp tục baseline `af1c236`, nhánh `fix/post-cp3-quality-remediation`. Đây là bản pilot để kiểm tra trải nghiệm, chưa phải nghiệm thu toàn bộ ADR hay chứng minh tăng chuyển đổi.

## Đã thay đổi

- Bộ nhớ PostgreSQL có nguồn từ lời khách, sửa/rút có dấu vết, revision và CAS; tách Page/sender/conversation. Không coi lời bot hay cảm xúc suy đoán là dữ kiện khách.
- Kế hoạch lượt trả lời nêu lợi ích, mục đích câu hỏi, tín hiệu sẵn sàng và băn khoăn chưa giải quyết; cùng kế hoạch đi qua writer và continuation. Báo giá có thể dừng, không bắt buộc hỏi/chốt tiếp.
- Giữ chỗ cho ngữ cảnh truy xuất liên quan trong ngân sách evidence, để catalog không đẩy mất chính sách/quy trình cần cho tư vấn.
- Tách `replyReview` và `memoryReview`: loại cập nhật trí nhớ chưa đủ căn cứ mà không ép writer sửa một lỗi ngoài quyền của nó.
- Industry pack v2 cho spa, thuê xe và thuê xe máy; dữ kiện chuyên ngành ở pack/Page, không thêm nhánh Java theo từng câu “đắt”, từng vùng cơ thể hoặc từng mẫu xe.
- UI phân biệt phản hồi mới bằng nhãn **Sales Core 3**.

## Kết quả kỹ thuật trên source cuối

`artifacts/sales-core-v3-release-check.log`: **109 tests, 0 failures, 0 errors, 0 skipped**, hoàn tất 10:10:53 UTC+7. Gồm 108 test kỹ thuật và một test live chứa 4 lượt hội thoại. Không gọi đây là 109 hội thoại hoặc 109 test EQ.

Các nhóm đã chạy: memory/review, JDBC thực, planner, core/gateway, continuation/delivery/inbox, provider boundary, foundation/composition, durable sent ledger, outbound reliability, pending cancellation. Test JDBC dùng Page giả riêng, hai store và transaction thật, có dọn đúng dữ liệu test. Kiểm tra scope chéo, sửa lời, nguồn giả/cũ, reset/concurrency, >40 lượt, context budget đều nằm trong bộ này.

Full repository **chưa xanh**: log lịch sử `artifacts/sales-core-full-verified.log` có 1040 tests, 17 failures, 3 errors, 8 skipped; hoàn tất 00:13:41 cùng ngày. Đây không phải lượt chạy lại toàn repo trên source V3 cuối. Không lấy bộ kiểm thử chọn lọc thay cho tuyên bố toàn repository đạt.

## Replay model thật

Provider hiện hữu qua gateway đã cấu hình; writer/reviewer dùng alternate, writer reasoning tắt. Catalog trong các replay dưới đây là **giả lập**, không phải bảng giá production Nàng Sen. Trace chứa prompt và raw response trong thư mục artifacts cục bộ.

### Bộ cuối: chất lượng dịch vụ → chọn gói

File: `artifacts/sales-core-live/v3-release-quality.json`.

| Lượt khách | Hành vi quan sát | Thời gian core |
|---|---|---:|
| Hỏi giá, đủ cổ vai gáy và tay chân, Hà Nội | Nêu gói toàn thân đúng phạm vi và giá từ catalog test; dừng sau giá | 12,5s |
| Không lo ngân sách, sợ làm qua loa | Giải thích cách trao đổi vùng tập trung với kỹ thuật viên từ policy; không hạ gói | 15,1s |
| Vai gáy từng bị lướt qua | Dùng đúng vùng vừa nêu, không bắt chọn ưu tiên lại | 17,3s |
| Chọn gói toàn thân, mai 18h | Hỏi phần còn thiếu để nhân viên kiểm tra lịch; không xác nhận đã đặt | 17,9s |

4/4 lượt không fallback; 0 lần sửa lời. Một cập nhật trí nhớ bị semantic review bác bỏ, câu trả lời vẫn được gửi và nguồn khách còn giữ. Lượt sau có thể sử dụng nguồn đó. Câu ở lượt 2/3 vẫn hơi lặp cách giải thích; không coi đây là hoàn hảo về ngôn từ.

### Bộ hai ngành trong quá trình phát triển

File: `artifacts/sales-core-live/v3-fast-two-industry.json`, 8 lượt spa/xe máy, không fallback. Có chê đắt, giữ nguyên phạm vi/mẫu Vision, so sánh đối thủ, sửa ngày, tín hiệu thuê và dừng. Core 11,5–44,7s/lượt. Bộ này chạy trước thay đổi cuối về tách review và phân bổ evidence; giữ riêng, không trình bày như benchmark cùng source cuối.

### Những thử nghiệm không đạt đã giữ bằng chứng

- `v3-reasoning-rejected.json`: bật reasoning writer gây 2 timeout trong 8 lượt; không giữ cấu hình này.
- `v3-cross-review-rejected.json`: chấm bằng model khác đã nhầm lời bot thành lựa chọn khách và gây handoff sai; đã bỏ thay đổi routing. Lượt kiểm thử thử nghiệm này còn có lỗi class test do thay đổi file khi đang chạy; không tính là bằng chứng hồi quy sạch.
- `v3-memory-review-rejected.json`: lỗi cập nhật memory bị đưa vào yêu cầu sửa câu trả lời; timeout 45s ở 1/4 lượt. Đây là nguyên nhân dẫn tới hợp đồng hai kết quả review riêng, có test chống hồi quy và replay lại thành công.

Đây là development/replay cùng các ca đã dùng để sửa; **không phải held-out**, chưa có người sales chấm ẩn danh hay kết quả chuyển đổi thực. Các thời gian trên không phải p95 production.

## Runtime local

Docker build API + frontend thành công; recreate hai service, giữ dependency đang chạy. API, frontend, doc-service-py và PostgreSQL healthy. Flyway `46|t` đã xác minh. Image API được inspect:

`sha256:cda37fdc67544fd3b0f728083985d054f7ef696e1e48ffa52da1b1c5c3806073`

Chỉ Page pilot Nàng Sen nằm trong allowlist hiện hữu. Không gửi hội thoại test tới khách Facebook thật, không tạo booking hoặc mở pilot toàn bộ Page.

### Kiểm tra UI trên dữ liệu Page

Đã reload frontend, mở Nàng Sen → Test Chat → Làm mới và gửi khách giả lập. Header hiện “AI đang trực”, phản hồi hiện “Sales Core 3”.

- Hỏi gói đủ cổ vai gáy và tay chân tại Hà Nội: bot nêu NS01 60 phút, 399.000VNĐ, Bích Câu và phạm vi full body; UI 18,4s, không ép câu chốt tiếp ở lượt đó.
- Nói không lo ngân sách, từng bị làm lướt vai gáy: bot giữ gói/phạm vi, không hạ sang gội đầu. Tuy nhiên có lặp giá và continuation hỏi phân bổ thời gian khá sớm, kèm cách nói “để em dặn kỹ thuật viên” trước khi khách chọn. **Chưa đạt trọn vẹn về quyết định/giọng nói**; không che điểm này bằng PASS của reviewer. UI hiển thị 10,6s cho phase cuối, không phải toàn bộ thời gian của hai phase.
- Chọn NS01/Bích Câu/mai 18h, nhắc giữ đủ vùng: bot ghi nhận đúng các chi tiết, yêu cầu số điện thoại và tạo handoff nội bộ để kiểm tra lịch; không tuyên bố đặt lịch thành công. UI 30,7s. Handoff này thuộc simulator, chưa chứng minh nhân viên ngoài hệ thống đã nhận/xử lý.

Bằng chứng cục bộ: `artifacts/sales-core-v3-ui.txt`, `artifacts/sales-core-v3-ui.png`. Khung chat được giữ mở để người dùng xem hoặc bấm Làm mới thử phiên khác.

Kết quả live synthetic tốt hơn không chứng minh chất lượng tương đương trên corpus thật. Ca UI này được giữ làm lỗi cần chấm tiếp với tập độc lập; chưa tự thêm luật tình huống vào runtime.

## Cách test và chạy lại

Mở `http://localhost:3000` → **Nàng sen → Test Chat → Làm mới**. Kiểm tra nhãn **Sales Core 3** ở phản hồi. Thử tự đổi cách nói, chen ngang và sửa lựa chọn; không cần gõ đúng câu trong bộ replay.

Một hành trình nên thử: hỏi giá toàn thân → nói rõ lo chất lượng thay vì ngân sách → bổ sung trải nghiệm cũ → chọn gói/ngày giờ → sửa giờ → nói dừng. Đánh giá bot có dùng thông tin mới, giữ nhu cầu, ngừng hỏi thừa và chỉ tiến giao dịch khi có đồng ý hay không.

Sau sửa Java/Vue, khi dependencies đang chạy:

```powershell
docker compose up -d --build --no-deps api-java frontend
```

`docker compose restart` không build source mới. Tắt pilot bằng `SALES_CORE_ENABLED=false` rồi recreate API nếu cần quay về luồng cũ; giữ bảng memory, không xóa dữ liệu để rollback.

## Những phần chưa thể tuyên bố hoàn tất

Chưa có tool kiểm tra lịch/xe trống hoặc tạo giao dịch thật; chưa có state/outcome chiến lược dài hạn; continuation ticket vẫn trong một process. Bộ nhớ hữu hạn và trích xuất xác suất, chưa kiểm chứng mọi trường hợp. Retrieval còn dùng service hiện hữu, chưa hoàn tất benchmark embedding/corpus. Độ trễ trên gateway hiện tại và chất lượng diễn đạt cần tiếp tục đo qua pilot. Mở rộng nhiều Page/replica hoặc khẳng định “EQ cao/chốt sale tốt” cần thêm bằng chứng ngoài các test ở đây.
