# Vận hành Sales Core

Core tư vấn mới ở `api-java/.../service/salescore`, tích hợp vào đường Fanpage hiện có. Cờ mặc định **tắt** để một lần build không tự chuyển tất cả Page sang engine đang pilot. Môi trường Docker cục bộ đã bật pilot cho Page Nàng Sen; các Page khác vẫn dùng engine hiện tại.

Sau khi duyệt replay và chuẩn bị bản chạy thử, cấu hình:

```dotenv
SALES_CORE_ENABLED=true
SALES_CORE_PAGE_ALLOWLIST=<UUID Page chạy thử>
SALES_CORE_WRITER_CLIENT=ALTERNATE
SALES_CORE_PLANNING_ENABLED=true
SALES_CORE_WRITER_REASONING=false
SALES_CORE_QUOTE_PAUSE_MS=10000
```

`ALTERNATE` sử dụng `LLM_FALLBACK_*` hiện có; thiếu alternate thì dùng primary. `PRIMARY` giữ model chính hiện có. Replay đã quan sát nhiều lỗi JSON/customer-fact hơn với primary đang cấu hình; đây là kết quả của endpoint/model hiện tại, không phải kết luận chung về hãng model. Allowlist có thể chứa nhiều UUID cách nhau bằng dấu phẩy; để trống nghĩa là mọi Page khi bật cờ. Tắt `SALES_CORE_ENABLED` để quay lại ACI.

Cờ bật/tắt, allowlist và writer được đọc lúc khởi động: đổi các giá trị này cần khởi động lại ứng dụng. File JSON industry/Page được đọc theo từng lượt. Báo cáo nền ở `docs/analysis/2026-11-07-sales-core-validation.md`; đợt sửa nhịp tư vấn ở `docs/analysis/2026-11-07-sales-core-paced-validation.md`.

## Thêm Page / ngành

1. Tạo Page và nạp tài liệu như trước. Tài liệu phải thuộc đúng Page, READY, active và còn hiệu lực. Khi gửi API luôn có `page_id`.
2. Đặt `persona_config.industry_code` thành `BEAUTY_SPA`, `MOTORBIKE_RENTAL` (thuê xe máy), `VEHICLE_RENTAL` hoặc mã ngành riêng. Core đọc mã được khai báo, không đoán ngành từ tên Page. Không khai báo thì dùng `GENERIC`; khai báo mã không tồn tại là lỗi cấu hình, không âm thầm chuyển ngành.
3. Có thể đặt file `config/sales/pages/<PAGE_UUID>.json`; tham khảo `config/sales/examples`. File được đọc lại mỗi lượt. Trong Docker hiện tại thư mục config đã mount read-only vào `/app/config`.
4. Thêm ngành bằng `config/sales/industries/<CODE>.json` với `code`, `schemaVersion: 2`, `decisionCriteria`, `discoveryHints`, `boundaries`, `customerFields`, `actionRequirements` (mảng chuỗi), `recommendationRequirements` (mảng `{key,description}`). `evaluationScenarios` là metadata kiểm thử tùy chọn, không đưa vào prompt runtime. Dùng pack resources làm mẫu; không cần sửa core Java. Pack v1 vẫn đọc được. Industry pack không cấp quyền gọi công cụ nghiệp vụ.

Giữ dữ liệu thật của mỗi doanh nghiệp ở tài liệu/catalog Page. Industry pack chỉ mô tả tiêu chí tư vấn và giới hạn chuyên môn, không chứa giá/ưu đãi/chính sách dùng chung cho mọi doanh nghiệp.

## Catalog có cấu trúc tùy chọn

`offers` là mảng `{id,name,price,scope,details,validFrom,validUntil}`. Giá là chuỗi **có tiền tệ, đơn vị và điều kiện**, ví dụ hình thức `… VND/ngày`; không đưa số giá ví dụ vào Page thật. `scope` ghi chi nhánh/phạm vi; `details` ghi bao gồm, không bao gồm, cọc/phụ thu hoặc điều kiện. Ngày dạng `YYYY-MM-DD`, bỏ trống trường nếu không giới hạn. `businessPolicies` chứa các câu chính sách đã xác minh.

Catalog bổ sung vào tri thức từ tài liệu. Khi thay bảng giá, phải tắt tài liệu/offer cũ để không tồn tại hai nguồn mâu thuẫn. Core không tự quyết định phiên bản nào mới hơn chỉ dựa trên lời model. File lỗi hoặc tham chiếu tài liệu Page khác chặn lượt bằng phản hồi lỗi có thể thử lại; không tự chuyển quyền điều khiển sang sale.

## Luồng và quyền hạn

Sales Core 4: inbound guard → lịch sử/memory → planner chọn việc khách cần → truy xuất dữ kiện đúng Page nếu cần → lời thoại → kiểm tra schema/nguồn/con số/phép tính/quyền hạn → review ngữ nghĩa → outbound guard/ledger. Planner không thấy catalog. Lượt khám phá/sửa cách trò chuyện/kết thúc có thể sinh 1–4 bubble theo ý nghĩa trong một cuộc gọi, không retrieval, không tự lưu memory proposal chưa được review. Lượt có dữ kiện thường 3 cuộc gọi; cùng deadline 45 giây, tối đa một sửa và một recovery. Không dùng sanitizer ACI để viết lại lời thoại. Review không chứng minh chất lượng UX; phải đọc cả hội thoại.

`ReplyMode.DIRECT` mặc định: trả trọn việc khách hỏi, chia bubble theo ý. Xuất hiện tiền không tự kích hoạt quy trình giá. Với `split_price_bubbles=true` và planner chọn `PRICE_MOMENT`, gửi một bubble giá đã kiểm chứng trước. Tiếp nối chỉ được phép khi COMMITMENT có mục đích hỏi hữu ích và bằng chứng lời khách; thiếu plan không cấp quyền. UI callback sau hiển thị, webhook callback sau Facebook chấp nhận giá; lúc đó mới sinh phần tiếp nối. `SALES_CORE_QUOTE_PAUSE_MS=10000` gồm thời gian tạo/kiểm chứng; không có nghĩa khách đã đọc. Ticket một lần, TTL120 giây, tối đa512 sender. Khách chen ngang/human takeover/opt-out hủy gửi. Restart bỏ phần chờ. Chưa hỗ trợ nhiều API replicas nếu không có kho ticket dùng chung/sticky routing.

Prompt riêng cho trả lời có dữ kiện, trao đổi làm rõ và lời tiếp nối. Lời tiếp nối chỉ sinh chiến lược + một bong bóng, giữ nguyên phần hiểu khách đã kiểm chứng vì chưa có tin khách mới. Nếu không kiểm chứng được phần tiếp nối tùy chọn thì không gửi nó. Stage quyết định nguồn: LOCATION/CONSULTATION/COMMITMENT/ESCALATION nạp evidence; DISCOVERY/TRUST_REPAIR/CLOSE giữ nhiệm vụ trò chuyện không có dữ kiện kinh doanh mới. Cờ `needsBusinessEvidence` không được ghi đè ranh giới này.

Sau sửa Java hoặc Vue, chạy tại thư mục gốc: `docker compose up -d --build --no-deps api-java frontend` (khi các dependency đang chạy). `docker compose restart` không build mã mới. Khi khởi động toàn bộ lần đầu dùng `docker compose up -d --build`. Industry/Page JSON ngoài image được đọc theo lượt; cấu hình môi trường cần recreate container.

Nếu đã cấu hình `LLM_FALLBACK_*`, lượt sinh đầu theo `SALES_CORE_WRITER_CLIENT` (mặc định PRIMARY); review và lượt sửa dùng alternate như baseline. Cùng model có thể đảm nhiệm cả sinh và chấm; reviewer là kiểm tra xác suất, không bảo chứng độc lập. Thử nghiệm chấm bằng model khác bị loại sau replay gây handoff sai. Không chọn provider theo ngành hoặc từ khóa khách. Log `SALES-CORE-MODEL` ghi rõ mục đích và model được chọn. Deadline vẫn áp dụng cho tổng các lượt gọi; client có thể retry HTTP bên trong một lần gọi.

Sai dữ kiện/phạm vi/quyền hạn là BLOCKING: thử sửa, rồi recovery hạn chế; vẫn lỗi thì báo trục trặc có thể thử lại, không tự handoff. QUALITY: thử sửa một lần; nếu vẫn chưa đạt, có thể gửi với telemetry `QUALITY_REVIEW_WARNING`, không giả vờ PASS. `replyReview` và `memoryReview` tách kết quả; JSON memory proposal lỗi cũng không làm mất kế hoạch hợp lệ. Phân loại reviewer vẫn cần người đánh giá.

Core tư vấn, so sánh, làm rõ và tiếp nhận yêu cầu; **không tự xác nhận giữ chỗ, thanh toán hay tồn xe**. Đặt lịch bình thường, chê đắt, lưỡng lự hoặc chất vấn cách bot tư vấn vẫn do bot xử lý. `needs_human_handoff` chỉ cho yêu cầu người thật, khiếu nại sản phẩm/dịch vụ đã nhận tại doanh nghiệp này, hoàn/trả hoặc tranh chấp có lời khách làm bằng chứng. Adapter lịch/tồn xe là năng lực chưa có; không có kết quả tool thì không được nói đã thực hiện.

Sales Core 3 bổ sung `sales_customer_memory` qua Flyway V46: facts kèm nguyên văn/sourceTurn, dấu vết sửa/rút và các nguồn khách gần đây, scope theo Page + sender + conversation. Planner đề xuất updates; chỉ lưu sau kiểm tra nguồn và reviewer phê duyệt rõ về nghĩa. Không lưu lời bot sinh thành lời đã gửi hoặc đoán cảm xúc thành sự thật. Revision/CAS chặn lượt cũ ghi đè mới. DB lỗi được phản ánh bằng lỗi có thể thử lại và telemetry, không giả đã nhớ hoặc tự handoff. Query retrieval cũng dùng facts đã nhớ. Cửa sổ hội thoại vẫn tối đa 40 tin/14.000 ký tự; memory tối đa 48 facts, 12 thay đổi gần nhất và 12 nguồn khách/12.000 ký tự. Dữ kiện kinh doanh tối đa 18.000 ký tự, giữ nguyên từng đoạn. Không bảo đảm nhớ vô hạn; việc trích xuất vẫn cần đánh giá.

Thiết kế UX hiện tại: `docs/analysis/2026-11-07-sales-core-v4-ux-design.md`. UI hiện nhãn **Sales Core 4**; nghiệm thu ưu tiên Spa. Các báo cáo V3 là lịch sử, không đại diện runtime hiện tại.

## Kiểm chứng và giới hạn

- `mvn -o '-Dtest=SalesCoreTest,SalesCoreGatewayTest,SalesProviderBoundaryTest' test`: hợp đồng, public service và request HTTP provider với các phụ thuộc giả tại biên; không phải kiểm thử Facebook đầu-cuối.
- `mvn -o test`: toàn bộ test offline (một số test hiện có cần DB hoặc có điều kiện skip).
- `mvn -o '-Dtest=SalesKnowledgeSnapshotTest' '-Dsales.pageSnapshot=true' test`: kiểm tra file SELECT snapshot `artifacts/sales-core-live/page-knowledge.json` qua bộ dựng ngữ cảnh thật; không ghi DB. Xuất `page-input.json` để xem chính xác phần tri thức được đưa vào model.
- `mvn -o '-Dtest=SalesCoreLiveReplayTest' '-Dsales.live=true' '-Dsales.writer=ALTERNATE' test`: opt-in, dùng provider cấu hình cục bộ, gửi khách/catalog **giả lập**. Không gọi Facebook, DB hay ghi Page thật. Kết quả ở `artifacts/sales-core-live/replay.json` gồm prompt, raw output, câu gửi cuối, quyết định, lỗi và telemetry.
- Thêm `'-Dsales.snapshotInput=../artifacts/sales-core-live/page-input.json'` vào lệnh replay để thử 6 lượt với ngữ cảnh Nàng Sen xuất từ snapshot. Đây là model thật + tri thức snapshot, không phải cuộc hội thoại với khách thật. Sao lưu `replay.json` trước lần chạy kế tiếp.

Replay ưu tiên `.env` ở gốc repo giống bản Docker (file được mount vào `/app/.env`), không ưu tiên `api-java/.env`. Các phép chạy với provider khác phải báo riêng, không gộp thành benchmark cùng model. Full suite cũ có test tích hợp DB với session thử nghiệm; chạy trên DB kiểm thử trước khi phát hành.

Kiểm tra nguồn/con số không chứng minh được mọi quan hệ ngữ nghĩa (ví dụ giá đúng nhưng gán sai đối tượng). Review độc lập giảm rủi ro này nhưng vẫn có thể chấm sai. Cần đọc replay và chấm theo nhu cầu, phạm vi, lặp ý, cách hỏi, độ tự nhiên và bước tiếp. Không lấy `PASS`, số skill, hay lời model tự nhận thấu hiểu làm chỉ số chất lượng. UI hiển thị Sales Core và thời gian thật; token chưa tổng hợp được qua client chung được ghi là chưa có thống kê, không hiển thị 0 như số đo.

Đo pilot theo tỷ lệ trả lời đúng câu hỏi, giữ đúng nhu cầu, đưa lựa chọn phù hợp, hoàn thành bước khách đồng ý, bàn giao đúng và latency. Chốt bán chỉ tính khi hệ thống nghiệp vụ xác nhận; chưa có dữ liệu pilot để khẳng định tăng chuyển đổi.

Luồng mới không gọi `recordConversionSuccess` chỉ vì phát hiện số điện thoại. Phản hồi/skill/rule của ACI cũ không tự chèn vào prompt mới; dùng ca báo sai làm dữ liệu đánh giá, rồi sửa có kiểm chứng ở core/industry pack/tri thức đúng nơi. Không tiếp tục vòng tự thêm luật tình huống vào prompt sau từng câu sai.
