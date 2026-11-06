# Đánh giá DocMind: năng lực tư vấn bán hàng và vòng lặp cải tiến

Ngày: 06/11/2026. Baseline local: `290b1e1`, nhánh `fix/post-cp3-quality-remediation`.

Đây là đánh giá cấp hệ thống và sản phẩm, có kiểm chứng những đường thực thi liên quan đến lỗi đang gặp. Không phải chứng nhận đã kiểm toán mọi dòng code, mọi tenant hoặc triển khai Messenger thực tế.

## Kết luận

DocMind có nền tảng kỹ thuật đáng giữ: nhập tài liệu, catalog theo cơ sở, quản lý hội thoại, kiểm soát đặt lịch, quyền tiếp quản, kiểm tra dữ kiện, thử nghiệm và logging. Nhưng chưa có bằng chứng đủ mạnh rằng tổ hợp hiện tại tạo ra nhân viên bán hàng biết thích ứng. Phiên người dùng cung cấp chứng minh rõ thất bại về giữ đúng nhu cầu, xử lý phương án bị từ chối và giúp khách tiến tới một quyết định phù hợp.

Vòng lặp đang diễn ra: một câu trả lời kém → bổ sung luật/prompt/sanitizer → test chứng minh luật mới chạy → báo cáo hoàn thành giai đoạn → người dùng gặp lại vấn đề trong hội thoại. Đơn vị được cải thiện thường là thành phần kỹ thuật, trong khi đơn vị cần nghiệm thu là toàn bộ quyết định tư vấn và nội dung khách nhận được.

Không có căn cứ để quy toàn bộ lỗi cho Java, Qwen, RAG hay good examples. Cần tách từng giả thuyết và đo tác động. Xóa toàn bộ guardrail, chuyển ngôn ngữ lập trình hay bổ sung một tầng agent mới đều chưa được bằng chứng hiện tại biện minh.

## Phạm vi và kiểm chứng

- Đọc nội dung hiển thị của [hội thoại chia sẻ](https://chatgpt.com/share/6aedf8d5-e3d8-83ec-b335-ee61707fe659), log và transcript người dùng gửi trực tiếp. Các tệp đính kèm cũ không đọc được chỉ từ nội dung hiển thị của trang chia sẻ.
- Đối chiếu nguồn local, đường ACI V2, prompt thực tế trên đĩa, parser, referee, sanitizer, memory, retrieval và test liên quan. Dùng CodeGraph trước khi tra mã nguồn.
- Xác nhận HEAD và diff commit Stage 2: 7 file, 683 dòng thêm, 322 dòng xóa; không thay system prompt hay prompt builder trong commit này.
- Chạy `mvn -o '-Dtest=Stage1FoundationRemediationTest,Stage2ResponseCompositionAndNoHardcodeTest' test`: **24 test, 0 failure, 0 error, 0 skipped**. Đây là kiểm chứng kỹ thuật của hai suite, không phải điểm chất lượng bán hàng.
- Kiểm tra Docker: các container API, parser, PostgreSQL và frontend đang chạy; API/parser/DB healthy. Chưa xác minh image digest khớp SHA local.
- Đọc API `/api/skills` của Nàng Sen: ba skill liên quan đang active, `good_example` hiện là các nguyên tắc, không phải các câu thoại Java cũ được nêu trong đánh giá trước.
- Thử API embedding trực tiếp nhận 401 vì thiếu internal token; không vượt xác thực. Kết luận provider `stub` dựa trên log phiên người dùng và đối chiếu implementation.
- Đọc dữ liệu ablation Stage 1 được báo cáo dẫn tới. Bản tối giản vẫn có các phản hồi dài, lặp lời đồng cảm và suy đoán về dịch vụ đối thủ. Do đó giảm cấu trúc chưa tự chứng minh tăng chất lượng sale.
- Chạy một component probe trên code đã biên dịch, với lịch sử dựng lại từ tình huống người dùng. Probe không gọi LLM, không dùng snapshot bộ nhớ DB, không gửi Facebook. Nó chứng minh hành vi của planner/sanitizer trên đầu vào công khai, không tái dựng chính xác raw output của phiên live.

Bằng chứng probe: [kết quả](/D:/Project/digital-spirituality/docmind/artifacts/audit-2026-11-06/component-probe.txt), [mã chạy lại](/D:/Project/digital-spirituality/docmind/artifacts/audit-2026-11-06/AuditConversationProbe.java).

## Phát hiện có bằng chứng

### 1. Policy hiện hành còn đóng khung cách giao tiếp

[System prompt, dòng 8](/D:/Project/digital-spirituality/docmind/config/aci_v2_system_prompt.md:8) quy định hỏi giá chung thành hai bubble: chào + giá + gói đại diện, rồi hỏi nhu cầu. [Dòng 48](/D:/Project/digital-spirituality/docmind/config/aci_v2_system_prompt.md:48) vẫn yêu cầu `bubbles` là mảng chuỗi, trong khi test Stage 2 đưa JSON object có `purpose` vào parser. Parser hỗ trợ một schema không đồng nghĩa model được yêu cầu sinh schema đó.

Quy định này giải thích vì sao cấu trúc mở đầu không đổi sau một commit chủ yếu sửa DTO/hậu xử lý. Tuy nhiên không thể suy từ đó rằng chỉ thay hai bubble thành ba bubble sẽ nâng chất lượng sale. Khách hỏi giá cần được đáp ứng nhanh; lời chào dài hơn hoặc nhiều cảm xúc hơn có thể làm khách khó chịu hơn.

### 2. Hậu xử lý vẫn có thể thay đổi quyết định giao tiếp

[Orchestrator, dòng 5986](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java:5986) còn gọi `enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace`, truyền planner Java đã dựng trước LLM.

[Planner, dòng 6481](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/AdaptiveConversationIntelligence.java:6481) chọn `RESPECTFUL_DEESCALATION` khi đã có phản đối giá trước đó, lượt hiện tại lại phản đối và không khớp nhánh hỏi tại sao/phương án khác.

[Sanitizer, dòng 8095](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOutputRefereeAndSanitizer.java:8095) xóa câu hỏi trong nhánh này nhưng giữ câu giới thiệu gói thông thường.

Probe thực tế cho kết quả:

1. `planner_action=RESPECTFUL_DEESCALATION`.
2. Câu lặp lại gói dưỡng sinh 239k được giữ nguyên.
3. Câu hỏi làm rõ ngân sách sau khi giải thích khác biệt phạm vi bị xóa.

Đây là một cơ chế có thể vừa để lọt lời bán hàng không phù hợp, vừa cắt mất bước làm rõ hữu ích. Không có raw output của phiên người dùng nên chưa khẳng định chính câu hỏi nào đã bị cắt ở lượt T11. Cũng không khẳng định mọi lần hỏi ngân sách đều tốt: cần xét khách đã cung cấp ngân sách hay yêu cầu dừng chưa.

Prompt builder còn rút `actionExecutionDirective` xuống 180 ký tự. Trong probe, chỉ thị thực tế còn đến phần “KHÔNG ép chốt”, mất các vế sau của bản đầy đủ. System prompt vẫn có quy tắc riêng, nên đây là mất thông tin ở một đoạn chỉ dẫn, không phải toàn bộ policy biến mất.

### 3. Telemetry hiện có thể khiến người đọc tin quá mức

[Orchestrator, dòng 8083](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java:8083) gán lý do mặc định `FORMAT_OR_PRONOUN_NORMALIZATION` và tên component `BubbleTextSanitizer` khi có diff nhưng chưa có lý do được phân loại. Vì vậy nhãn này không chứng minh thay đổi chỉ là định dạng/đại từ.

[Dòng 6102](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java:6102) lấy `selectedSpeechAct` từ reflection. [Dòng 6126](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java:6126) thường gán `actualSentSpeechAct = generatedSpeechAct`. Các nhãn bằng nhau không phải ba lần kiểm tra độc lập trên nội dung cuối.

`refereeDecision=PASS` tại đây có nghĩa không phát hiện vi phạm thuộc bộ kiểm tra đó. Nó không chứng minh khách được lắng nghe, lời khuyên phù hợp hoặc tỷ lệ chốt tăng. `DELIVERY_REQUESTED`/`PENDING` trong log simulator cũng không phải bằng chứng khách Facebook đã nhận tin.

### 4. Phản biện một nguyên nhân được khẳng định trong cuộc hội thoại cũ

Đoạn `tacticalFocus = "BẮT BUỘC giới thiệu phương án tiết kiệm trong [NHÓM 2]..."` thật sự có tại [prompt builder dòng 2377](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentCatalogAndPromptBuilder.java:2377), bên trong `appendCleanAgentBrainInsightBlock` của đường `buildCleanAgentPrompt` cũ.

Đường hiện hành đi từ [FanpageBotService dòng 1397](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/FanpageBotService.java:1397) tới `executeAciCognitiveTurn`, rồi [dòng 5288 của orchestrator](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java:5288) gọi `buildAciSinglePassPrompt`. Không thấy đường này gọi helper legacy sinh `tacticalFocus` đó.

Ngoài ra [referee ACI V2 dòng 5447](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOutputRefereeAndSanitizer.java:5447) vô hiệu các kiểm tra `PREMATURE_DOWNSELL_ON_FIRST_OBJECTION`/`MISSING_ECONOMICAL_PACKAGE...`, và tiếp tục lọc các vi phạm đó ở dòng 5487. Câu repair cũ tồn tại không đủ chứng minh nó gây mâu thuẫn trong phiên ACI V2 đã gửi.

Vì vậy hai đoạn legacy là nợ kỹ thuật cần quản lý, nhưng không nên gọi là căn nguyên đã xác minh của phiên này. Hướng điều tra phải dựa vào prompt được gửi và đường gọi thực, không chỉ tìm câu đáng ngờ trong repository.

### 5. Embedding đang ở chế độ stub trong log phiên lỗi

Log ghi `provider_model=stub`, `dim=384`. [Python _StubProvider](/D:/Project/digital-spirituality/docmind/doc-service-py/app/embeddings.py:105) dùng hash token và một số cụm từ khóa thủ công. Đây không phải embedding ngữ nghĩa học từ model. [Stub Java](/D:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/embedding/StubEmbeddingClient.java:8) cũng nói rõ không dành cho chất lượng ngữ nghĩa thật.

Điều này hạn chế độ tin cậy của RAG trên diễn đạt mới, tiếng lóng, câu ngắn theo ngữ cảnh. Nhưng không được quy việc lặp lại gói 239k hoàn toàn cho embedding: phiên này vẫn có catalog và các phương án được báo giá. Cần đo retrieval riêng bằng query có ngữ cảnh và bằng chứng đúng, đồng thời sửa policy/output riêng.

Khi thay embedding cần kiểm tra provider dùng lúc ingest lẫn query và tạo lại index/embeddings đồng bộ; cùng số chiều 384 không có nghĩa hai không gian vector tương thích.

### 6. Chất lượng hội thoại thất bại ở quyết định, không chỉ từ ngữ

| Dữ kiện khách | Bot đã làm | Điều cần cải thiện |
|---|---|---|
| Hỏi giá | Đưa nhiều mức của hai thành phố | Đáp ứng giá rõ ràng, thu hẹp thông tin cần thiết; không lấy số bubble làm KPI |
| Nêu cổ vai gáy, chân tay | Hỏi lại toàn thân hay cổ vai gáy | Ghi nhận nhiều vùng, chỉ hỏi thêm nếu câu hỏi làm đổi đề xuất |
| Xác nhận tất cả | Giới thiệu gói và hỏi muốn trải nghiệm | Giải thích phạm vi gói khớp nhu cầu; chọn bước tiếp theo theo mức sẵn sàng |
| Chê đắt | Gắn VALUE_PERCEPTION_GAP rồi nhắc tiện ích | Phân biệt ngân sách, so sánh, chưa hiểu giá trị và mặc cả; không coi một từ là bằng chứng chắc chắn |
| Chọn HN, vẫn muốn toàn thân | Đề nghị gói đầu/cổ vai gáy 239k | Công khai phần đánh đổi; rẻ hơn chưa có nghĩa đáp ứng nhu cầu |
| Chê đắt lần hai | Lặp lại cùng phương án | Ghi nhận đề xuất chưa hiệu quả; làm rõ một điều còn thiếu hoặc tạm dừng theo ngữ cảnh |

Giữ lời khách “cổ vai gáy chân tay” làm bằng chứng không tự nó là lỗi nhớ. Điểm cần có là sự liên kết nhất quán giữa lời đó, câu “tất cả”, phạm vi đề xuất hiện tại và các phương án đã bị phản đối. Một trường hypothesis đổi tên chưa chứng minh toàn bộ bộ nhớ đã sai hoặc đã đúng.

## Điều chỉnh cách thiết kế sản phẩm

“Biết thấu hiểu” nên được định nghĩa qua hành vi: không hỏi lại điều đã biết, không đổi sang dịch vụ thiếu phạm vi mà giấu đánh đổi, không áp đặt lý do khách chưa mua, không lặp đề xuất đã thất bại, biết hỏi đúng một thông tin và biết ngừng khi khách muốn ngừng.

“Nghệ thuật chốt” nên là đưa ra bước cam kết nhỏ đúng lúc: xác nhận gói phù hợp, giải quyết rào cản còn lại, đề nghị kiểm tra lịch/đơn hàng khi khách có tín hiệu sẵn sàng. Không đồng nhất việc xin số điện thoại hay hỏi lịch với conversion. Chỉ xác nhận giao dịch khi công cụ/người có thẩm quyền đã xác nhận.

Tri thức cần hai loại dữ liệu được duyệt:

- Sự thật giao dịch: tên dịch vụ, phạm vi bao gồm/không bao gồm, giá theo cơ sở, thời lượng, điều kiện ưu đãi, hiệu lực, nguồn, trạng thái khả dụng.
- Hiểu biết bán hàng: khách nào phù hợp, trade-off của lựa chọn rẻ hơn, khác biệt có thể chứng minh, điều không được hứa, phản đối thường gặp và các đoạn tư vấn thực tế được người phụ trách đánh giá tốt.

Không có dữ liệu khác biệt thật thì model không thể tạo “giá trị thuyết phục” một cách trung thực chỉ từ tên gói và giá. Không nên bù bằng cảm giác thư giãn hoặc chất lượng nhân viên do model tự tưởng tượng.

Luồng đề xuất: lịch sử + trạng thái đáng tin + catalog phù hợp → một nơi quyết định phản hồi → kiểm tra sự thật/quyền giao dịch → gửi → lưu chính nội dung đã gửi và phản ứng tiếp theo. Có thể tách quyết định và diễn đạt về mặt cấu trúc mà chưa cần thêm một lần gọi model. Mọi lần sửa lời có ý nghĩa phải có diff và lý do cụ thể.

Good examples không tự thân là xấu. Ví dụ đa dạng, được nhân viên sale duyệt có thể hữu ích. Cần kiểm tra có/không có ví dụ trên tập chưa dùng để sửa prompt, thay vì tuyệt đối hóa “no hardcode”. Quy tắc cứng về giá/quyền đặt lịch/opt-out vẫn cần giữ.

## Kế hoạch thoát vòng lặp

### Bước 1 — Đóng băng baseline và cách nghiệm thu

Chọn một ngành, một Page, một mục tiêu kinh doanh hẹp trước. Lưu SHA/image, prompt hash, dữ liệu catalog/skill version và bộ hội thoại lỗi. Dùng 20–30 hội thoại thật đã bỏ thông tin cá nhân để bắt đầu; phân tách tập phát triển và tập giữ kín. Đây là cỡ thử nghiệm ban đầu, không phải ngưỡng đủ chứng minh hiệu quả thương mại.

Mỗi ca phải mô tả: điều khách muốn, điều đã biết, điều chưa biết, bước tư vấn hợp lý và lỗi không được có. Không yêu cầu đúng một câu mẫu hoặc bắt tất cả khách qua cùng funnel.

### Bước 2 — Chạy so sánh để phân biệt nguyên nhân

| Cấu hình | Mục đích |
|---|---|
| A: Pipeline hiện tại, cố định model và dữ liệu | Baseline |
| B: Cùng model, cùng dữ kiện/lịch sử, policy thống nhất ngắn hơn, không cắt ngữ nghĩa ngoài các kiểm tra an toàn/giao dịch trong thử nghiệm offline | Đo tác động của chỉ dẫn và hậu xử lý |
| C: Cùng cấu hình B, một model đối chứng nếu có quyền sử dụng | Đo tác động của model |

Không đổi đồng thời model, dữ liệu, nhiệt độ, tiêu chí chấm và pipeline rồi quy công cho một yếu tố. Replay một lượt dùng cùng lịch sử cho A/B; thử hội thoại tự sinh nhiều lượt phải tách riêng vì các nhánh lịch sử sẽ khác nhau. Chạy lặp các ca quan trọng để thấy độ biến thiên.

Ablation Stage 1 đã có một phần dữ liệu, nên tận dụng nó làm điểm xuất phát. Không coi số token giảm là bằng chứng hội thoại tốt hơn. Không gọi lại model trả phí trong đợt đánh giá này.

### Bước 3 — Sửa đúng lớp được thử nghiệm chỉ ra

- Hợp nhất policy xử lý phản đối, làm rõ ngân sách, đổi phạm vi và tạm dừng.
- Bỏ cắt câu theo số lần phản đối trong thử nghiệm được kiểm soát; kiểm chứng câu cuối trước gửi. Nếu vi phạm cứng, sửa có giới hạn hoặc handoff, không âm thầm xóa một phần ý.
- Đồng bộ schema prompt/parser/runtime trước khi nghiệm thu `purpose`.
- Ghi raw output, từng phép biến đổi và final payload với trace ID riêng; không dùng nhãn suy đoán mặc định như tên component thực sự gây thay đổi.
- Đo RAG riêng, sửa stub/provider và tái lập embeddings nhất quán trước khi tuyên bố truy xuất ngữ nghĩa đạt yêu cầu.

### Bước 4 — Nghiệm thu bằng bốn lớp độc lập

1. **Kỹ thuật:** schema, giá/cơ sở, idempotency, bộ nhớ, timeout, quyền giao dịch. Unit/integration test giữ vai trò ở đây.
2. **Hội thoại:** người đánh giá chọn A/B khi ẩn tên cấu hình, xét đúng nhu cầu, câu hỏi có ích, phạm vi đề xuất, tránh lặp và phản ứng đúng với từ chối. Người dùng và một người sale có kinh nghiệm nên cùng hiệu chỉnh rubric. LLM judge chỉ hỗ trợ sau khi được đối chiếu với người thật.
3. **Kinh doanh:** pilot có giám sát, đo cuộc tư vấn đủ điều kiện → yêu cầu kiểm tra lịch → lịch xác nhận → khách đến/mua. Theo dõi thêm mất khách, phàn nàn và handoff. So sánh trên lead có đặc điểm tương đương; không suy conversion từ khách mô phỏng gật đầu.
4. **Kênh Facebook:** webhook, quyền tiếp quản, gửi nhận/delivery thật, duplicate và tin đến dồn. Không thay thế lớp này bằng REST simulator.

Chỉ thêm một thành phần khi thí nghiệm cho thấy nó cải thiện một lỗi xác định mà không làm xấu các ca còn lại. Một người chịu trách nhiệm phát hành policy; reviewer kiểm tra bằng chứng, không tự công nhận EQ chỉ vì test xanh. Chưa mở rộng đồng thời sang nhiều ngành hoặc nhiều tầng agent trước khi một lát cắt nhỏ đạt chất lượng.

## Minh họa thay đổi quyết định

Các mức 399k/239k dưới đây lấy từ transcript người dùng, không phải báo giá mới đã được xác minh độc lập.

Khi khách cần cả cổ vai gáy và chân tay ở Hà Nội: đề xuất gói toàn thân 399k và giải thích nó bao phủ nhu cầu. Khi nói đến 239k, phải nói rõ gói đó chỉ đầu/cổ vai gáy và không tương đương toàn thân.

Nếu khách vẫn chê đắt nhưng chưa nêu ngân sách và chưa muốn dừng, một hướng có thể là: “Mình muốn giữ chi phí khoảng bao nhiêu cho một buổi, để em xem có lựa chọn đủ phần chân tay không ạ?” Nếu khách đã nêu ngân sách thì dùng dữ kiện đó, không hỏi lại. Nếu catalog không có gói phù hợp, nói rõ giới hạn và đưa lựa chọn tiếp tục kiểm tra với nhân viên hoặc để khách cân nhắc, tùy nhu cầu.

Nếu khách nói “thôi, không cần nữa”, dừng tư vấn bán hàng. Không biến mọi lần “đắt” thứ hai thành từ chối giao tiếp, cũng không biến mọi lần hỏi giá thành tín hiệu sẵn sàng đặt lịch.

Đợt đánh giá này chỉ thêm tài liệu và probe trong workspace. Không sửa logic sản phẩm, không đổi cấu hình, không push/deploy, không gửi tin cho khách.
