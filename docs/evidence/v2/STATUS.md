# DocMind V2 — Trạng thái thực thi và nghiệm thu

**Chưa hoàn thành đợt cải cách. Release: BLOCKED.** Đã có sáu commit triển khai/audit cục bộ theo PR-0–PR-5. Chưa push, chưa tạo PR trên GitHub, chưa bật ứng viên mới trên UI đang chạy. Không đánh đồng build thành công, reviewer PASS hoặc một vài câu trả lời tốt với nghiệm thu sản phẩm.

## Trạng thái theo PR

| PR | Trạng thái | Commit | Bằng chứng và phần còn thiếu |
|---|---|---|---|
| [PR-0](pr0/README.md) | PARTIAL | `75f8c3c` | Đã audit baseline, 263 file runtime/config, nguồn trùng/xung đột, nhánh fallback và hội thoại lỗi thực tế. Baseline1097 test:17 failure/3 error. Binding Page và reachability toàn bộ helper cũ chưa được chứng minh. |
| [PR-1](pr1/README.md) | BLOCKED | `57f0c46` | Đã kiểm chứng 4 nguồn/114 bản ghi curated bằng compiler, provenance và test. Chưa xóa/import KB sống vì chưa xác minh Messenger Page ↔ knowledge Page. Embedding semantic chưa được chứng minh; dữ liệu chuẩn bị là FTS_ONLY. |
| [PR-2](pr2/README.md) | PARTIAL | `88bcb28` | Đã triển khai goal/open-loop/engagement có nguồn, recovery giữ bằng chứng, kiểm tra riêng lời đáp và memory. Replay26 lượt cải thiện fallback10→2 trong một phép so sánh; STOP/latency/UX chưa đạt toàn bộ. |
| [PR-3](pr3/README.md) | PARTIAL | `936ff77` | Có lựa chọn chiến lược gắn lời khách và nguồn, kiểm tra điều kiện dùng, trace thực tế. Replay10 lượt có0 fallback nhưng còn upsell không được hỏi, diễn đạt quá mức và chốt sớm. Không coi số strategy cards là chất lượng sales. |
| [PR-4](pr4/README.md) | PARTIAL | `b3581f0` | Đã chạy đủ30 ca owner/46 lượt, có5 fallback; giữ nhãn GIU/SUA và không tự nâng ca SUA thành mẫu tốt. Có mô phỏng thích ứng, counterfactual, báo cáo chống học vẹt và phiếu chấm người thật. Đã sửa truyền metadata nguồn, sửa có giới hạn và chọn nguồn chính sách. Một phép đối chiếu sau đó xấu hơn; chưa đạt gate về promo/STOP/receipt/giọng tư vấn. |
| [PR-5](pr5/README.md) | PARTIAL | `7d51ef0` | Cleanup commit độc lập: xóa template không có caller, bỏ2 hằng/2 điều kiện số lượng theo Page, hợp nhất6 đoạn rule lặp, thay gate slot mâu thuẫn bằng kiểm chứng context. Giữ fallback,46 migrations, outbound, consent và CAS. Reachability cũ và thống nhất nguồn KB sống vẫn còn nợ. Không gọi codebase là clean. |

SHA đầy đủ, danh sách file và checksum diff: [commit-index.json](commit-index.json). Diff thực từng commit nằm tại `artifacts/v2-handoff/pr0.diff` đến `pr5.diff`, hoặc xem `git show <SHA>`. Các số liệu trên là quan sát của lần chạy đã lưu, không phải cam kết thống kê.

## Kiểm chứng thực chạy

- **Đúng checkout PR-4 `b3581f0`:** `mvn -o -f api-java/pom.xml '-Dtest=Sales*Test' test` —102 test,0 failure/error,2 skip, exit0. Log `artifacts/v2-pr4/committed-unit.log`; checkout riêng tại `artifacts/v2-pr4/commit-tree`. Kết quả này không dựa vào cleanup chưa commit.
- **Cây mã tích hợp cuối:**126 test Core/metadata,0 failure/error,2 skip, exit0. Hai test bị skip là opt-in, không tính là PASS.10 test Python compiler/lab qua.
- **Full regression trên PostgreSQL riêng :55433:** baseline1097 test,17 failure/3 error/10 skip; ứng viên1127 test,17 failure/3 error/10 skip, cùng20 method lỗi, không có lỗi method mới được quan sát. **Cả hai exit1.** XML và aggregate Maven lệch163 test ở cả hai; đã giữ nguyên hạn chế báo cáo. [Đối chiếu đầy đủ](pr5/full-suite-release-check-comparison.json).
- **Memory với PostgreSQL thật:** test opt-in kiểm tra phục hồi sau restart, revision cạnh tranh và từ chối CAS cũ qua; chỉ dùng Page giả lập trong DB riêng. Frontend build và Docker build API/frontend qua. Build image không thay thế test.
- **Model thật:** giữ provider/model cấu hình hiện có. PRIMARY probe4 ca/7 lượt có1 fallback, chậm hơn lần ALTERNATE tương ứng; không có cơ sở để đổi writer. Đã lưu cả các lần timeout và thất bại.
- **Lần đối chiếu17 lượt:**6 fallback; trên16 lượt khớp baseline, fallback2→6, p50≈17.6→24.1 giây. Đây là regression quan sát được, không được loại khỏi báo cáo.
- **Sau sửa chọn chính sách,4 lượt mục tiêu:**0 fallback, p50≈16.8/p95≈26.7 giây. Trả đúng điều kiện thẻ dùng theo cơ sở và dữ liệu phản chứng747k/75 phút/phòng riêng/đã VAT. **Vẫn nói ưu đãi đang áp dụng khi thiếu ngày hiệu lực**; do đó không đạt gate dù reviewer PASS và test replay exit0.

Full transcript, source/strategy/raw model trace nằm trong các báo cáo PR và artifacts tương ứng. [Nhận xét từng nhóm hội thoại](pr4/quality-findings.md) là đánh giá của agent; phiếu chấm độc lập chưa được điền. Không có kết quả blind holdout hoặc bằng chứng tăng conversion.

Mô phỏng khách thuê xe thích ứng trên SHA `7d51ef0`:3 lượt đúng vai khách sau sửa prompt actor; vẫn1 fallback, lặp hỏi ngày và xử lý tạm nghỉ lạnh lùng. Đã xác minh sửa vai; không đánh đồng với tư vấn đạt chất lượng.

## UI và dữ liệu đang chạy

[runtime-state.json](pr5/runtime-state.json) ghi nhận API vẫn chạy image cũ, khác image vừa build. Không restart/promote ứng viên vì còn hard failure. Các cờ Goal-First/strategies mặc định vẫn false. **UI hiện tại không phải bản đại cải cách đã nghiệm thu.**

KB Nàng Sen sống chưa bị xóa/thay. UUID UI/allowlist `2850a8ef-ca4a-40eb-87fa-4f808640efee` khác UUID webhook mặc định `b23cd382-4f7a-4829-8c54-1d410da14bf8`; UUID mặc định không có trong DB đã audit. Numeric Messenger Page identity chưa được bind; Graph identity check không xác minh được. Còn thiếu **numeric Facebook Page ID và callback URL đúng** để đối chiếu. Không cần cấp lại quyền xóa KB đã cấp; cần xác định đúng tenant theo phụ lục05 mụcB: “Nếu chưa phân giải được Page đích, BLOCK destructive; không đoán bằng tên Nàng Sen”.

## Việc còn lại, phân biệt rõ trách nhiệm

1. **Dữ liệu/binding:** xác minh danh tính Page, sửa đường mapping/signature có test; sau đó mới thay KB và vô hiệu nguồn stale trong đúng scope, kiểm chứng retrieval/cache/reembedding. Thông tin owner thiếu chỉ chặn phần phụ thuộc binding.
2. **Engineering về sales:** xử lý promo chưa xác nhận nhưng vẫn bị nói thành đang áp dụng; STOP khi provider chậm/lỗi; receipt thật cho yêu cầu đặt/chuyển người; giảm catalog/upsell/CTA không cần thiết; đo lại toàn bộ biến thể sau sửa, không chỉ một ca đẹp. Binding đúng không tự giải quyết các lỗi này.
3. **Engineering về kiến trúc:** hoàn tất reachability helper cũ và nguồn cấu hình động, thống nhất nguồn sự thật sống, giải nợ full-suite có đối chiếu. Hiện vẫn có nhiều đường Core/fallback cần giữ cho đến khi thay thế được chứng minh.
4. **Nghiệm thu:** người đọc độc lập chấm toàn bộ hành trình, kiểm tra mâu thuẫn giữa reviewer và thực tế; owner chạy blind holdout riêng. Không đưa holdout vào dữ liệu phát triển. Chỉ sau khi cả gate sales và codebase đạt mới bật ứng viên cho UI/pilot.

Các worktree, DB test và artifact thất bại được giữ để tái hiện. Không có tin Facebook gửi khách thật hoặc chuyển tiếp tới người khác trong các replay offline.
