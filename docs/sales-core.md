# Vận hành Sales Core

Core tư vấn mới ở `api-java/.../service/salescore`, tích hợp vào đường Fanpage hiện có. Cờ mặc định **tắt** để một lần build không tự chuyển tất cả Page sang engine đang pilot. Container đang chạy chưa được thay đổi.

Sau khi duyệt replay và chuẩn bị bản chạy thử, cấu hình:

```dotenv
SALES_CORE_ENABLED=true
SALES_CORE_PAGE_ALLOWLIST=<UUID Page chạy thử>
SALES_CORE_WRITER_CLIENT=ALTERNATE
```

`ALTERNATE` sử dụng `LLM_FALLBACK_*` hiện có; thiếu alternate thì dùng primary. `PRIMARY` giữ model chính hiện có. Replay đã quan sát nhiều lỗi JSON/customer-fact hơn với primary đang cấu hình; đây là kết quả của endpoint/model hiện tại, không phải kết luận chung về hãng model. Allowlist có thể chứa nhiều UUID cách nhau bằng dấu phẩy; để trống nghĩa là mọi Page khi bật cờ. Tắt `SALES_CORE_ENABLED` để quay lại ACI. Việc chạy lại/deploy ứng dụng chưa được thực hiện trong đợt này.

Cờ bật/tắt, allowlist và writer được đọc lúc khởi động: đổi các giá trị này cần khởi động lại ứng dụng. File JSON industry/Page được đọc theo từng lượt. Báo cáo kết quả và giới hạn ở `docs/analysis/2026-11-07-sales-core-validation.md`.

## Thêm Page / ngành

1. Tạo Page và nạp tài liệu như trước. Tài liệu phải thuộc đúng Page, READY, active và còn hiệu lực. Khi gửi API luôn có `page_id`.
2. Đặt `persona_config.industry_code` thành `BEAUTY_SPA`, `VEHICLE_RENTAL` hoặc mã ngành riêng. Core đọc mã được khai báo, không đoán ngành từ tên Page. Ngành chưa có pack dùng `GENERIC`.
3. Có thể đặt file `config/sales/pages/<PAGE_UUID>.json`; tham khảo `config/sales/examples`. File được đọc lại mỗi lượt. Trong Docker hiện tại thư mục config đã mount read-only vào `/app/config`.
4. Thêm ngành bằng `config/sales/industries/<CODE>.json` với `code`, `decisionCriteria`, `discoveryHints`, `boundaries` (mỗi trường sau là mảng chuỗi). Dùng các pack trong resources làm mẫu; không cần sửa core Java.

Giữ dữ liệu thật của mỗi doanh nghiệp ở tài liệu/catalog Page. Industry pack chỉ mô tả tiêu chí tư vấn và giới hạn chuyên môn, không chứa giá/ưu đãi/chính sách dùng chung cho mọi doanh nghiệp.

## Catalog có cấu trúc tùy chọn

`offers` là mảng `{id,name,price,scope,details,validFrom,validUntil}`. Giá là chuỗi **có tiền tệ, đơn vị và điều kiện**, ví dụ hình thức `… VND/ngày`; không đưa số giá ví dụ vào Page thật. `scope` ghi chi nhánh/phạm vi; `details` ghi bao gồm, không bao gồm, cọc/phụ thu hoặc điều kiện. Ngày dạng `YYYY-MM-DD`, bỏ trống trường nếu không giới hạn. `businessPolicies` chứa các câu chính sách đã xác minh.

Catalog bổ sung vào tri thức từ tài liệu. Khi thay bảng giá, phải tắt tài liệu/offer cũ để không tồn tại hai nguồn mâu thuẫn. Core không tự quyết định phiên bản nào mới hơn chỉ dựa trên lời model. File lỗi hoặc tham chiếu tài liệu Page khác khiến lượt chuyển sang yêu cầu nhân viên kiểm tra.

## Luồng và quyền hạn

Inbound guard → lịch sử đã lưu → dữ kiện đúng Page → quyết định + lời thoại → kiểm tra schema/nguồn/con số/phép tính/quyền hạn → review ngữ nghĩa độc lập → outbound guard/ledger hiện có. Tối đa một lần sửa, thường 2 cuộc gọi model, tối đa 4, cùng deadline mặc định 45 giây. Không đi qua planner phản đối giá và sanitizer ACI cũ. Review có thể sai; phải chấm replay và theo dõi tỷ lệ chuyển người thật, độ trễ khi pilot.

Nếu đã cấu hình `LLM_FALLBACK_*`, core dùng model dự phòng cho review và lượt sửa lỗi schema/ngữ nghĩa; lượt sinh đầu theo `SALES_CORE_WRITER_CLIENT` (mặc định PRIMARY). Nếu chỉ có một model thì cùng model đảm nhiệm cả hai. Không chọn provider theo ngành hoặc từ khóa khách. Log `SALES-CORE-MODEL` ghi rõ mục đích và model được chọn. Deadline vẫn áp dụng cho tổng các lượt gọi; client có thể retry HTTP bên trong một lần gọi.

Sai dữ kiện/phạm vi/quyền hạn là lỗi BLOCKING: không sửa được thì bàn giao. Câu đúng dữ kiện nhưng còn dài/lặp/hỏi chưa khéo là QUALITY: thử sửa một lần, nếu chưa đạt thì gửi kèm cảnh báo telemetry `QUALITY_REVIEW_WARNING`, không giả vờ đạt PASS và không chuyển nhân viên chỉ vì văn phong. Phân loại của reviewer vẫn cần đánh giá con người.

Core tư vấn, so sánh, làm rõ, mời bước tiếp và bàn giao; **không tự xác nhận giữ chỗ, thanh toán hay tồn xe**. Khi khách sẵn sàng, core thu thập yêu cầu cần thiết hoặc trả `needs_human_handoff` và tóm tắt cho inbox. Tích hợp hệ thống đặt lịch/tồn xe thực tế là adapter nghiệp vụ riêng; không có kết quả tool thì không được nói đã thực hiện.

Lịch sử đã lưu là trí nhớ có nguồn; nhận định cảm xúc/trở ngại là giả thuyết mỗi lượt, không tự ghi vào hồ sơ như sự thật. Cửa sổ tối đa 40 tin/14.000 ký tự; dữ kiện tối đa 18.000 ký tự, giữ nguyên từng đoạn. `contextTruncated` báo thiếu ngữ cảnh cho model. Với hội thoại rất dài cần bổ sung memory trích xuất có dẫn chứng và kiểm chứng riêng.

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
