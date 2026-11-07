# Sales Core: tư vấn có nhịp và phục hồi lỗi

Mục tiêu: sửa phản hồi xả nhiều gói, lặp câu hỏi và chuyển nhân viên khi khách chê giá; core dùng chung ngành, câu từ do model sinh theo dữ kiện và lịch sử.

## Quyết định

- Thêm một brief quyết định ngắn từ hội thoại trước khi writer thấy catalog. Planner không có bảng giá: chọn việc cần giúp và có cần dữ kiện kinh doanh ở lượt này hay không. Nếu chỉ cần làm rõ/lắng nghe, writer nhận ngữ cảnh hội thoại, không nhận danh mục để tránh thói quen thay câu hỏi bằng một gói giá khác. Brief là giả thuyết, không trở thành sự thật về khách. Brief lỗi thì giữ nguyên ngữ cảnh đầy đủ và ghi trace; không dùng fallback nội dung cứng. Bước này dùng chung mọi ngành, nằm trong deadline lượt.

- Chọn một việc giúp khách quyết định trong lượt hiện tại. Mở đầu thân thiện; giới thiệu ngắn, khám phá theo điều còn thiếu, đề xuất có lý do, mời bước tiếp theo đúng mức sẵn sàng. Đây là tiêu chí đánh giá, không phải chuỗi trạng thái bắt buộc hay script. Planner/writer phase đầu mặc định không bật reasoning ẩn để giảm độ trễ/tràn token; có thể bật bằng cấu hình. Lịch sử/tin khách nằm sau catalog để giữ trọng tâm hội thoại. Reviewer không bật reasoning; phase tiếp nối luôn yêu cầu reasoning thấp.
- Phase tiếp nối có prompt riêng: phần giá đã gửi bất biến, mục đích tiếp nối, lịch sử và các trích đoạn đã kiểm chứng. Không đưa toàn bộ chỉ dẫn mở đầu/báo giá hoặc danh mục vào bước này để tránh model trả lời lại câu khách từ đầu. Câu chữ vẫn do model sinh, không ghép câu mẫu.
- Lượt trao đổi không cần dữ kiện kinh doanh và lượt phục hồi cũng có prompt riêng, tránh chỉ dẫn báo giá xung đột với nhiệm vụ làm rõ. Dữ kiện khách giữ schema object có trích dẫn, không phải mảng chuỗi. Bước tiếp nối dùng schema nhỏ hơn và tái sử dụng phần hiểu khách đã kiểm chứng vì chưa có tin khách mới.
- Với kênh hỗ trợ chia lượt, model gửi một bubble giá kèm mục đích tiếp nối (`followUp.aim`). Chưa có câu tiếp nối được viết sẵn. Sau khi UI hiển thị hoặc Facebook chấp nhận bubble giá, gọi model lần nữa với phần đã gửi bất biến và bật reasoning ở adapter. Thời gian sinh và kiểm tra câu sau được tính trong khoảng nghỉ cấu hình 10 giây; nếu model chậm hơn, gửi khi sẵn sàng.
- Giữ protocol hiện có `GENERATE_PRICE_ANCHOR_BUBBLE`, nhưng core dùng ticket riêng có hạn 120 giây, giới hạn 512 sender, chỉ dùng một lần, khớp page/sender/conversation. Không tin đồng hồ hay nội dung do client gửi trong callback. Khách nhắn mới làm vô hiệu ticket; kiểm tra lại sau model và trước gửi. Khởi động lại sẽ bỏ continuation đang chờ, không gửi nội dung cũ.
- Không ghi callback như tin khách mới, không thay đổi đồng ý nhận tin/booking. Guard takeover, opt-out, sender epoch và ledger vẫn kiểm soát gửi.
- Tối đa hai lần sinh thông thường và một lần phục hồi trong cùng deadline. Phục hồi thu hẹp về câu làm rõ theo ngữ cảnh, không thêm dữ kiện kinh doanh; vẫn kiểm tra contract và semantic. Lỗi factual không được bỏ qua. Lỗi provider hoặc hết deadline vẫn có phản hồi vận hành; thất bại phần tiếp nối không thay thế giá đã gửi bằng handoff giả.
- Reviewer chỉ nhận nguồn được trích (nguyên nguồn, giữ điều kiện), lịch sử và phạm vi phase; giảm catalog thừa khi kiểm tra. PASS là tín hiệu kỹ thuật, không chứng minh EQ hay tỷ lệ chốt.
- Đặc thù wellness và giới hạn lời khuyên sức khỏe nằm trong BEAUTY_SPA; core không có nhánh nhận diện spa/thuê xe hay câu khách 'Đắt'.

## Kiểm chứng yêu cầu

| Yêu cầu | Bằng chứng cần có |
| --- | --- |
| Lắng nghe, EQ, ngôn từ | Đọc replay thật ca người dùng, đối chiếu chi tiết khách, không gán ngân sách/cảm xúc, không lặp sáo ngữ |
| Mở đầu và tiết chế thông tin | Giá chung có mốc hữu ích, không xả cả danh mục; không buộc khám phá lại điều đã nói |
| Chốt phù hợp | Replay ngành thuê xe đi đến yêu cầu xử lý thật, không bịa tồn xe/đặt thành công |
| Nghỉ sau báo giá | Test không gọi model2 trước callback, reasoning bật, phần chờ còn lại; UI và Facebook test cùng protocol |
| Không hardcode ngành/tình huống | Chung core/schema/validator; profile ngành là dữ liệu; replay nhiều ngành |
| Vận hành | Hủy khi khách chen ngang; takeover/opt-out; token sai/hết hạn/dùng lại; không ghi trùng tin khách |

Tham khảo thiết kế: [Anthropic — Building effective agents](https://www.anthropic.com/research/building-effective-agents), phần prompt chaining và evaluator-optimizer: dùng bước nhỏ có tiêu chí đo và phản hồi kiểm tra, chỉ tăng độ phức tạp khi có lợi ích quan sát được. Áp dụng ở đây là quyết định thiết kế của dự án, không phải bảo đảm chất lượng từ nguồn.
