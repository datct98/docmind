# Sales Core 4 — biên bản nghiệm thu trải nghiệm Spa

Ngày 07/11/2026. Kiểm chứng trên Docker cục bộ và UI Test Chat Bot của Nàng Sen, model cấu hình sẵn, khách giả lập. Không phải hội thoại với khách thật hoặc phép đo tăng chuyển đổi. Baseline code trước thay đổi: `d7cb649bc2c3205723f18d971eece3b157282b75`.

## Kết luận hiện tại

Đã sửa các ranh giới quyết định quan trọng và có bản chạy UI. **Chưa nghiệm thu chất lượng thương mại hoặc tuyên bố trải nghiệm “Wao”.** Độ trễ còn cao, ngôn từ đôi lúc dài và nguồn dữ liệu doanh nghiệp có chỗ mâu thuẫn. Bộ test kỹ thuật xanh không thay thế các quan sát này.

Thiết kế được giữ thành [quyết định UX](2026-11-07-sales-core-v4-ux-design.md), với UX trước chốt sale, core dùng chung và pilot Spa trước.

## Những lỗi thực tế tìm được, không phải lỗi giả định

1. Một trường phụ `remove` thiếu trong memory proposal làm Jackson bác bỏ cả kế hoạch; bot mất hướng tư vấn dù transcript còn. Đã tách parser quyết định khỏi parser memory, mặc định không xóa fact.
2. TRUST_REPAIR có thể xin lỗi rồi hứa kiểm tra mà chưa trả lời câu hỏi. Đã phân biệt sửa quan hệ với công việc cần nguồn; khi khách đang đợi địa chỉ/giá/chính sách, vẫn phải tra và trả ngay.
3. LOCATION và RECOMMEND bị đánh đồng đề xuất cơ sở với chào bán sản phẩm. Đã sửa quyền theo đối tượng của lượt; writer nhận hợp đồng chỉ trả cơ sở ở lượt LOCATION.
4. Regex tiền tệ có thể nhận chữ “Đ” đầu “Đường” trong địa chỉ là đồng. Đã sửa ranh giới ký tự, kiểm tra cả địa chỉ và các cách viết VNĐ/đ/k.
5. Chỉ cần xuất hiện số tiền là hệ thống ép một bubble và cấm câu hỏi. Đã chuyển sang `ReplyMode` theo mục đích; DIRECT trả trọn việc hiện tại, PRICE_MOMENT là lựa chọn riêng.
6. Planner có hai tín hiệu lấy nguồn có thể mâu thuẫn. Stage hiện sở hữu đường lấy nguồn; cờ phụ không được xóa evidence khi đang tư vấn/tiếp nhận yêu cầu mua.
7. Lỗi kỹ thuật/định dạng/deadline từng tự chuyển thành handoff. Đã đổi sang phản hồi lỗi có thể thử lại, giữ quyền tư vấn ở bot.
8. Runtime prompt chứa tình huống đánh giá. Đã tách `runtimeView`, không đưa `evaluationScenarios` vào planner/writer/reviewer.

## Nghiệm thu bằng vai khách khó tính

Hành trình đầu là chuỗi tương tác thích ứng: hỏi giá → nêu nhu cầu vai gáy nhưng vẫn đủ chân tay → Long Biên → phàn nàn lỗi bot → nhờ chọn gói → chê đắt nhưng không đổi phạm vi → nói thật lo làm qua loa → chọn gói/giờ → từ chối số điện thoại và yêu cầu dừng.

Đây là **hành trình tìm lỗi xuyên qua các bản sửa trong ngày**, không phải một phép replay sạch của duy nhất bản cuối. Bằng chứng đầy đủ: `artifacts/sales-core-live/v4-spa-demanding-ui.txt`.

| Quan sát | Đánh giá của người nghiệm thu |
|---|---|
| Mở đầu chào ngắn, hỏi vùng muốn chăm sóc, chưa chọn ngẫu nhiên giá/chi nhánh | Cải thiện đúng hướng; hai tin có ý riêng |
| Dùng nhu cầu vai gáy + chân tay, hỏi quận sau khi biết Hà Nội | Giữ context, không bắt kể lại |
| Lượt hỏi Long Biên từng kết thúc bằng lỗi sau 41,8 giây | Đánh trượt, không tính là ca đạt; đã tái hiện và sửa hợp đồng LOCATION |
| Khách bực: xin lỗi ngắn, trả địa chỉ Long Biên trong tin riêng, không handoff | Đúng hành vi sửa lỗi; 29,9 giây vẫn chậm |
| Nhờ chọn gói: đề xuất đúng vùng/chi nhánh, nhưng còn nêu hai phương án và chi tiết quy trình dài | Chưa khéo; đã giảm ưu tiên thông tin phụ và tránh tự mở rộng lựa chọn |
| Chê đắt nhưng không đổi sang gội đầu: bot giữ phạm vi, chưa hỏi số điện thoại | Đúng phạm vi; còn nhắc lại nhiều chi tiết giá/quy trình |
| “Không thiếu tiền, sợ làm qua loa”: bot chuyển sang chất lượng, không chuyển sale vì chuyện tại chỗ trước | Hiểu thay đổi; còn ba bubble và lời mời ghi chú hơi sớm |
| Chọn gói + mai18h: bot ghi nhận đúng gói, cơ sở, vùng ưu tiên; hỏi tên/liên hệ | Không khám phá lại, không khẳng định đã giữ chỗ, không handoff |
| Chưa muốn đưa số điện thoại, muốn dừng: bot PAUSE, không hỏi tiếp | Tôn trọng nhịp khách; độ chờ còn cao |

Không xóa các lượt thất bại khỏi biên bản. Dãy thời gian 12,4 / 13,2 / 41,8 / 29,9 / 44,5 / 24,7 / 26,6 / 38,1 / 23,0 giây là số UI ghi ở hành trình tìm lỗi, không phải SLA hoặc benchmark bản cuối.

Một phiên mới hỏi chính xác Massage cơ bản tại Bích Câu và nói chỉ muốn biết giá đã phát hiện thêm lỗi: bot tự nêu gói nâng cao. Bằng chứng trước sửa: `v4-spa-price-scope-before.txt`. Đã điều chỉnh mục tiêu UX, tôn trọng phạm vi khách yêu cầu, cách xưng hô theo lời khách và review đối với upsell không được mời.

### Phiên sạch trên image cuối: hỏi giá cụ thể → muốn đặt → yêu cầu người thật

Bằng chứng: `artifacts/sales-core-live/v4-spa-final-ui.txt`; ảnh UI thật `artifacts/sales-core-live/v4-spa-price-ui.png`. Ba lượt thuộc cùng image cuối, không sửa code giữa các lượt.

- “Massage cơ bản ở Bích Câu bao nhiêu? Chị chỉ cần biết giá trước”: một bubble chào chị, trả đúng NS01/399.000VNĐ/60 phút, không thêm gói nâng cấp hoặc câu hỏi. UI 17,6 giây. Đạt phạm vi; vẫn có thể gọn hơn.
- “Mai 18h chị muốn đặt gói đó”: giữ đúng NS01/Bích Câu, hỏi tên/liên hệ, không khám phá lại hoặc tự handoff. UI 18,1 giây. **Chưa đạt hoàn toàn:** đổi cách xưng hô từ “chị” sang “mình”; hứa “sẽ xác nhận lại…sau khi kiểm tra” dù chưa có tác vụ lịch thực thi. Prompt và reviewer đã có ràng buộc nhưng vẫn lọt — không được coi đây là lỗi đã giải quyết chỉ bằng thêm chỉ dẫn.
- “Chị muốn nói chuyện với nhân viên”: UI chuyển trạng thái bàn giao, tóm tắt đúng gói/cơ sở/giờ và thông tin còn thiếu. UI 26,5 giây. Đạt ranh giới handoff, nhưng câu “nhân viên sẽ liên hệ” còn cam kết quá mức so với việc mới tạo yêu cầu, chưa có người nhận xác nhận.

Kết quả này đủ để thử pilot cục bộ, **chưa đủ để mở rộng tự động cho khách thật**. Ưu tiên tiếp theo là gắn lời hứa hành động với năng lực và biên nhận nghiệp vụ thực tế, đo tỷ lệ bỏ sót của reviewer bằng hội thoại giữ riêng; không thêm regex nhận dạng từng câu sai. Độ trễ và xưng hô cần được chấm riêng, không gộp vào tỷ lệ trả đúng giá.

### Phiên sạch thứ hai trên cùng API: khám phá → đề xuất → phản đối

Bằng chứng: `artifacts/sales-core-live/v4-spa-final-discovery-ui.txt`.

| Khách và kết quả | Đánh giá |
|---|---|
| “Giá”: chào rồi hỏi nhu cầu, chưa chọn giá/cơ sở; 6,4 giây | Đạt thứ tự thông tin, câu hỏi còn hơi dài |
| Toàn thân, kỹ vai gáy, Hà Nội: nhớ cả ba ý, chỉ hỏi quận; 8,1 giây | Đạt sử dụng context |
| Gần Bích Câu, chỉ một giờ, nhờ chọn: đề xuất NS01/60 phút/399k; 25,5 giây | Đúng lựa chọn, nhưng còn thêm lời mời gói phòng riêng dài hơn dù khách đã giới hạn thời gian; chưa đạt sự gọn và đúng trọng tâm |
| Chê giá, yêu cầu vẫn toàn thân: nêu chưa có phương án rẻ hơn cùng phạm vi, không handoff; 26,1 giây | Có giải thích đánh đổi, không giấu việc gội đầu thiếu toàn thân; tuy nhiên vẫn mời sang gội đầu sau khi khách muốn giữ toàn thân. Đây là đề xuất chưa khéo, không tính là “thấu hiểu cao” |

Ảnh UI hẹp còn cho thấy hàng nút “Chi tiết AI / Báo sai” gây cuộn ngang. Đã sửa flex wrap và giới hạn cột bubble theo phần rộng còn lại sau avatar; đây là thay đổi hiển thị frontend, không đổi lời thoại API ở hai phiên trên. Đã rebuild frontend, reload UI và thấy nút Báo sai xuống dòng, không còn cuộn ngang ở khung 319px; ảnh sau sửa: `artifacts/sales-core-live/v4-spa-responsive-ui.png`.

## Kiểm chứng kỹ thuật

- Bộ core có JDBC thực: 70 tests, 0 failures, 0 errors, 0 skips (`artifacts/sales-core-v4-release-tests-final.log`).
- Full suite ghi nhận 1.097 tests, 17 failures, 3 errors, 10 skips. Không báo toàn repo xanh. Tên 20 phương thức lỗi trùng báo cáo baseline lưu sẵn `artifacts/sales-core-full-verified.log`; đây là đối chiếu log lịch sử, không phải một lần build baseline sạch độc lập.
- Các kiểm chứng bao gồm: trì hoãn truy xuất đúng lúc; planning tắt vẫn đọc nguồn; lỗi nguồn không biến thành lỗi hiểu khách; nguồn khách không bị suy thành sự thật; memory có kiểm tra nguồn/review/CAS; giá có đơn vị; địa chỉ không bị nhận nhầm thành tiền; quyền handoff; PRICE_MOMENT; hủy phần tiếp nối khi phiên bị thay thế.
- Docker API và frontend build thành công, API/frontend/Postgres/document service healthy. UI hiển thị Sales Core 4.
- Replay độc lập một lượt hỏi Long Biên dùng model thật và snapshot đã trả đúng địa chỉ trong21,1giây (`v4-spa-branch-final.json`). Không gọi Facebook hay đặt lịch thật.

## Phần chưa được chứng minh hoặc chưa đạt

- Latency: các lượt có nguồn thường còn3 cuộc gọi tuần tự; sửa ngữ nghĩa có thể thành5 cuộc gọi. Chưa đạt mục tiêu phản hồi nhanh như một nhân viên trực tin nhắn. Không tăng deadline để che vấn đề.
- UX: độ gọn, sự tự nhiên và thời điểm đề nghị bước tiếp chưa ổn định đủ để tuyên bố thay thế sale hoàn toàn. Cần bộ hội thoại giữ riêng và người làm Spa đánh giá; không dùng chính các ví dụ đã sửa làm toàn bộ thước đo.
- Dữ liệu: bảng Long Biên và FAQ chung khác nhau về NS04/gội đầu; NS01/NS02 có cột phòng chung nhưng mô tả VIP/riêng. Đã hỏi chủ sản phẩm để xác nhận, chưa tự chỉnh dữ kiện. Model không được ghép phần hấp dẫn từ các nguồn mâu thuẫn.
- Retrieval: log runtime vẫn có `provider_model=stub dim=384`. Core hiện dùng thêm full-text và snapshot tài liệu, nên tìm được dữ kiện không chứng minh semantic embedding đang hoạt động tốt. Chưa thay embedding hoặc reindex dữ liệu doanh nghiệp trong đợt này.
- Nghiệp vụ: chưa có adapter lịch trống/giữ lịch. Bot chỉ tiếp nhận yêu cầu; chưa thể tự xác nhận giao dịch thật.
- Tiếp nối vẫn dùng ticket trong bộ nhớ một API instance. Chưa nghiệm thu nhiều replicas hoặc khả năng phục hồi ticket qua restart.

## Cách thử lại

Mở `http://localhost:3000`, chọn Nàng Sen → Test Chat Bot → Làm mới. Kiểm tra nhãn Sales Core 4. Thử lời nói tự nhiên, không cần dùng lại các câu ở biên bản. Đọc cả cuộc hội thoại và đo thời gian đến thông tin hữu ích đầu tiên.

Sau thay đổi Java/Vue: `docker compose up -d --build --no-deps api-java frontend`. `docker compose restart` không đưa code mới vào image. Hướng dẫn vận hành đầy đủ: [sales-core.md](../sales-core.md).
