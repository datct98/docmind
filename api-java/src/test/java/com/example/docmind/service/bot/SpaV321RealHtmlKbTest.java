package com.example.docmind.service.bot;

import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.SampleDialogueDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpaV321RealHtmlKbTest {

    private static final UUID NANG_SEN_PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID HTML_DOC_ID = UUID.fromString("c9481efb-f3a9-4613-a2e0-fe50e9b99b39");

    private static final String ACTIVE_PROMPT_COMPACT_V321 = """
            BẠN LÀ: nhân viên chăm sóc của Nàng Sen Spa (tự nhiên, ấm áp, tinh tế). Xưng em - gọi mình.
            LUẬT CỐT LÕI v3.2.1:
            1. Chỉ báo gói, thời lượng, giá, quyền lợi có thật trong <DOCUMENT_CONTEXT> (mặc định Hà Nội trừ khi hỏi Sài Gòn/Q1).
            2. Khách nêu đau mỏi: đồng cảm tự nhiên (CẤM nói Dạ em ghi nhận mình đang...), hỏi tối đa 1 câu làm rõ tình trạng.
            3. Báo giá bằng 1 định dạng gọn duy nhất (399k, 239k, 530k), CẤM viết kép 399k (399.000đ), CẤM lặp gói ngắn ... gói ngắn.
            4. Chê đắt lần 1: giữ gói đang tư vấn, nêu giá trị và mời thử 1 buổi. Chê đắt lần 2 hoặc xin gói rẻ hơn: hạ 1 bậc xuống gói thấp hơn trong <DOCUMENT_CONTEXT>.
            5. Hỏi Có gói khác k: gợi ý 1-2 gói khác chưa nhắc. Bức xúc (Dở à, Lặp à): xin lỗi ngắn gọn, giải quyết thẳng nhu cầu.
            6. Tối đa 3 bong bóng, <=2 câu/bong bóng, <=45 từ/bong bóng, <=1 câu hỏi. Sau bong bóng có giá, delay >= 10000ms.""".strip();

    private PromptBuilder.PagePersona nangSenPersona;
    private List<ReciprocalRankFusion.FusedChunk> realHtmlChunks;

    @BeforeEach
    void setUp() {
        PagePersonaConfig cfg = new PagePersonaConfig(
                "BEAUTY_SPA",
                "nhân viên chăm sóc",
                "em",
                "mình",
                "Dịu dàng, ấm áp, tinh tế, thấu cảm tình trạng khách trước rồi mới gợi ý gói",
                "NONE",
                List.of("chốt đơn", "cam kết khỏi hẳn", "chữa khỏi", "điều trị dứt điểm"),
                List.of("massage", "cổ vai gáy", "vai gáy", "mỏi", "đau", "nhức", "tê", "căng", "ngâm thảo dược", "ngâm chân", "vệ sinh tai", "ráy tai", "đá nóng", "thư giãn", "gội đầu", "dưỡng sinh", "chân", "gối", "lưng", "toàn thân"),
                "spa-llm-native-guarded-v3.2.1-final",
                ACTIVE_PROMPT_COMPACT_V321,
                List.of(
                        "Dạ chào mình, em là nhân viên chăm sóc của Nàng Sen Spa ạ.",
                        "Bên em chuyên massage trị liệu và gội đầu dưỡng sinh thư giãn nhẹ nhàng, chỉn chu ạ."
                ),
                10000,
                "Mình thích lực nhẹ thư giãn hay lực vừa để thả lỏng vùng này cho dễ chịu ạ?",
                "Mình bị lâu chưa hay mới gần đây, và thường căng sau ngồi lâu hay vận động nhiều ạ?",
                "Dạ em hiểu mình đang khó chịu ạ. Mình gặp tình trạng này mới gần đây hay đã kéo dài ạ?",
                null,
                List.of("đau", "mỏi", "nhức", "nhói", "tê", "căng cứng", "căng cổ", "đau cơ", "đau lưng", "mỏi gối", "đau chân", "đau đầu", "mất ngủ", "khó ngủ"),
                List.of("cổ", "vai", "gáy", "cổ vai gáy", "vai gáy", "lưng", "thắt lưng", "eo", "chân", "bắp chân", "bàn chân", "gối", "đầu gối", "khớp gối", "tay", "đầu", "tai", "toàn thân", "body"),
                java.util.Map.of(),
                java.util.Map.of()
        );
        nangSenPersona = new PromptBuilder.PagePersona("Nàng Sen Spa", "BEAUTY_SPA", "Khách hàng spa", "Ấm áp", cfg);

        // Exact parsed tables from 01_giá_dịch_vụ.html (c9481efb-f3a9-4613-a2e0-fe50e9b99b39)
        String hanoiMassageTableChunk = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp | Quy trình chi tiết |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu | Phòng cộng đồng có rèm riêng | KTV tiêu chuẩn | • Tắm xông thảo dược giúp làm ấm cơ thể và thư giãn trước khi trị liệu ; • Massage trị liệu full body 60 phút giúp giảm căng cơ, mỏi vai gáy, lưng ; • KTV tiêu chuẩn tay nghề ổn định, thao tác đúng huyệt ; • Buffet thực dưỡng miễn phí sau liệu trình (trà thảo mộc, đồ ăn nhẹ tốt cho sức khỏe) ; • Không gian phòng cộng đồng yên tĩnh, có rèm riêng tư | Khách thư giãn nhanh, dân văn phòng | • Thay đồ & chuẩn bị trị liệu• Massage trị liệu full body cơ bản• Kết hợp bấm huyệt và thả lỏng cơ thể• Khách có thể yêu cầu tập trung vùng mỏi như cổ vai gáy hoặc lưng |
                | NS02 | Massage ngâm thảo dược | 530.000VNĐ | 90 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 75p massage | Phòng cộng đồng có rèm | KTV tiêu chuẩn | • Kết hợp ngâm bồn thảo dược + tẩy tế bào chết + xông hơi + massage trị liệu 75 phút giúp thư giãn sâu hơn gói cơ bản. ; • Thảo dược ấm giúp giãn cơ, kích thích tuần hoàn máu và đào thải độc tố trước khi massage. ; • Tẩy tế bào chết giúp làm sạch da, thông thoáng lỗ chân lông và hấp thụ dưỡng chất tốt hơn. ; • Thời lượng 90 phút đủ để cơ thể thả lỏng hoàn toàn, giảm đau mỏi cổ – vai – gáy – lưng rõ rệt. ; • Không gian phòng cộng đồng yên tĩnh, có rèm riêng tư đảm bảo sự thoải mái. ; • Thưởng thức buffet thực dưỡng miễn phí sau liệu trình giúp bổ sung năng lượng nhẹ nhàng. | Khách mỏi cơ, lạnh người | • Tắm tráng làm sạch cơ thể• Ngâm bồn thảo dược 10 phút giúp thư giãn cơ• Xông hơi 5 phút hỗ trợ tuần hoàn• Massage trị liệu full body 75 phút• Tập trung giãn cơ và giảm mỏi theo nhu cầu của khách |
                | NS03 | Massage phục hồi | 580.000VNĐ | 100 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 85p massage phục hồi | Phòng VIP riêng | KTV phục hồi | • Phòng VIP riêng tư mang lại trải nghiệm thư giãn tuyệt đối. ; • Kết hợp ngâm thảo dược + tẩy tế bào chết + xông hơi + massage phục hồi chuyên sâu. ; • Giúp giãn cơ, đào thải độc tố, làm sạch da và tái tạo năng lượng. ; • KTV phục hồi với kỹ thuật bấm huyệt chuyên sâu. ; • Buffet thực dưỡng cao cấp sau liệu trình. ; - • Đây là gói được khách hàng lựa chọn nhiều nhất tại Nàng Sen. | Đau mỏi vai gáy, stress nặng | • Tắm tráng• Ngâm bồn thảo dược 10 phút• Xông hơi 5 phút• Massage phục hồi full body 85 phút• Kết hợp bấm huyệt, chườm nóng và phục hồi cơ thể• Kỹ thuật viên sẽ tập trung kỹ hơn vào các vùng cơ đang căng cứng theo tình trạng khách |
                | NS04 | Massage phục hồi + dưỡng sinh | 670.000VNĐ | 120 phút | 90p ngâm bồn xông hơi massage + 30p gội đầu dưỡng sinh | VIP + khu gội chung | KTV phục hồi | Phòng VIP riêng tư kết hợp khu gội đầu dưỡng sinh riêng biệt, mang đến trải nghiệm thư giãn trọn vẹn. ; Trọn bộ chăm sóc cơ thể và da đầu trong cùng một liệu trình, không cần di chuyển nhiều. ; Massage phục hồi chuyên sâu kết hợp gội đầu dưỡng sinh, giúp giảm đau mỏi, thư giãn thần kinh, giảm căng thẳng và hỗ trợ ngủ ngon hơn. ; Gội đầu dưỡng sinh với các thao tác day ấn huyệt vùng đầu, cổ, vai gáy, kích thích tuần hoàn máu và giải tỏa áp lực sau ngày dài làm việc. ; KTV phục hồi tay nghề cao thực hiện xuyên suốt toàn bộ liệu trình, đảm bảo trải nghiệm đồng nhất. Thưởng thức buffet thực dưỡng sau khi kết thúc liệu trình, bổ sung năng lượng và hoàn thiện trải nghiệm. | Mất ngủ nhẹ, cần điều trị chuyên sâu | • Tắm tráng• Ngâm bồn thảo dược 10 phút• Xông hơi 5 phút• Massage trị liệu full body 105 phút• Kết hợp chăm sóc đầu – cổ – vai – gáy• Gội dưỡng sinh tại khu vực dưỡng sinh chung• Có thể điều chỉnh lực và tập trung theo từng vùng cơ thể |
                | NS05 | Silver – Massage phục hồi chuyên sâu | 780.000VNĐ | 120 phút | Tương tự gói 670k + gội đầu dưỡng sinh tại phòng VIP | Phòng VIP riêng khép kín | KTV cao cấp | Không cần di chuyển giữa các bước hoàn toàn riêng tư | Khách thích riêng tư | • Tắm tráng• Ngâm bồn thảo dược• Xông hơi• Massage trị liệu full body chuyên sâu• Tập trung xử lý các vùng đau mỏi theo nhu cầu khách• Gội dưỡng sinh tại phòng VIP riêng• Hạn chế tối đa việc di chuyển giữa các bước |
                | NS06 | Gold – Massage trị liệu phục hồi | 990.000VNĐ | ~150 phút | 10p ngâm bồn + tắm tráng + 70p massage + 105p trị liệu + 30p gội đầu tại phòng + nước ép trái cây + tẩy da chết | Phòng VIP riêng | Nhân viên ưu tú | Trị liệu chuyên sâu + phục hồi cao cấp | Doanh nhân, khách stress nặng | • Tắm tráng làm ấm cơ thể• Ngâm bồn thảo dược 10 phút• Massage trị liệu full body 70 phút• Trị liệu phục hồi chuyên sâu 105 phút• Tập trung các vùng cơ đang căng cứng hoặc đau mỏi kéo dài• Gội dưỡng sinh tại phòng riêng 30 phút• Tẩy da chết toàn thân• Nước ép trái cây phục hồi sau liệu trình |
                | NS07 | Diamond – Massage trị liệu chuyên sâu VIP | 1.990.000VNĐ | ~180 phút | 10p ngâm bồn + tắm tráng + 70p massage + 105p trị liệu + spa da mặt 30p + gội đầu 30p + điểm tâm VIP + nước ép + tẩy da chết | VIP riêng tư hoàn toàn | KTV/Giáo viên trị liệu | Chăm sóc toàn diện cao cấp nhất | VIP, phục hồi chuyên sâu | • Tắm tráng làm ấm cơ thể• Ngâm bồn thảo dược 10 phút• Massage trị liệu full body chuyên sâu 70 phút• Trị liệu phục hồi cơ thể 105 phút• Điều chỉnh kỹ thuật và lực theo tình trạng từng khách• Spa chăm sóc da mặt 30 phút• Gội dưỡng sinh 30 phút• Tẩy da chết toàn thân• Điểm tâm VIP & nước ép phục hồi• Không gian VIP riêng tư khép kín |
                | ADD01 | Tẩy da chết cơ bản | 100.000VNĐ | Theo liệu trình | Tẩy da chết toàn thân | Theo phòng dịch vụ | KTV | Làm sạch & mềm da | Khách muốn chăm sóc da thêm | • Tẩy tế bào chết toàn thân• Làm sạch lớp da sần cũ• Hỗ trợ da mềm và sạch thoáng hơn sau liệu trình |
                """;

        String hanoiHairWashTableChunk = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | DS01 | Lấy ráy tai cơ bản | 129.000VNĐ | 30 phút | Lấy ráy tai cơ bản + ăn nhẹ & trái cây theo mùa | Khu dưỡng sinh chung | KTV dưỡng sinh | • Làm sạch tai nhẹ nhàng – an toàn • Kết hợp thư giãn vùng tai – đầu • Kèm ăn nhẹ & trái cây theo mùa | Khách muốn làm sạch tai, thư giãn nhanh |
                | DS02 | Dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Rửa mặt + massage mặt + gội thảo dược (2 lần) + chườm thảo dược cổ vai + ủ tóc + sấy dưỡng + ăn nhẹ | Khu dưỡng sinh chung | KTV dưỡng sinh | • Kết hợp chăm sóc da mặt & gội thảo dược • Giúp thư giãn đầu – cổ – vai nhẹ nhàng • Phù hợp khách thư giãn định kỳ | Khách cần thư giãn nhanh, dân văn phòng |
                | DS03 | Dưỡng sinh thư giãn | 339.000VNĐ | 80 phút | Ngâm chân thảo dược + massage cổ vai gáy + tẩy trang rửa mặt + massage mặt + đắp mặt nạ + gội thảo dược (2 lần) + ủ tóc + sấy dưỡng + ăn nhẹ | Khu dưỡng sinh chung | KTV dưỡng sinh | • Kết hợp ngâm chân thảo dược giúp ấm cơ thể • Có massage cổ vai gáy giảm mỏi • Chăm sóc da mặt + gội dưỡng sinh trọn gói | Khách mỏi vai gáy nhẹ, muốn thư giãn sâu hơn |
                | DS04 | Dưỡng sinh chuyên sâu | 500.000VNĐ | 90 phút | Ngâm chân + tẩy tế bào chết da đầu + massage cổ vai gáy – lưng – tay + chăm sóc da mặt chuyên sâu + gội thảo dược + sấy dưỡng + ăn nhẹ | Khu dưỡng sinh | KTV dưỡng sinh tay nghề cao | • Tẩy tế bào chết da đầu giúp sạch sâu • Massage mở rộng vùng cổ – vai – lưng – tay • Chăm sóc da mặt chuyên sâu kết hợp thư giãn | Khách stress, đau mỏi cổ vai gáy nhiều |
                | DS05 | Dưỡng sinh chăm sóc chuyên sâu | 780.000VNĐ | 120 phút | Ngâm chân thảo dược + lấy ráy tai Hoàng Cung + tẩy tế bào chết da đầu + chăm sóc da mặt chuyên sâu + massage cổ vai gáy lưng tay + chườm thảo dược mắt – bụng – chân + gội thảo dược + sấy dưỡng + ăn nhẹ | Khu dưỡng sinh | KTV cao cấp | • Kết hợp ráy tai Hoàng Cung thư giãn sâu • Chườm thảo dược ấm toàn diện (mắt – bụng – chân) • Liệu trình dài 120 phút giúp phục hồi năng lượng | Khách cần thư giãn sâu, mất ngủ, stress |
                | DS06 | Dưỡng sinh VIP toàn diện | 990.000VNĐ | 135 phút | Tắm tráng + xông hơi + tẩy da chết body + ủ dưỡng toàn thân + massage body tinh dầu thảo dược + chăm sóc da mặt + gội dưỡng sinh + sấy dưỡng + ăn nhẹ | Phòng dịch vụ kết hợp dưỡng sinh | KTV cao cấp | • Kết hợp chăm sóc body + da mặt + gội dưỡng sinh trong 1 gói • Có xông hơi, tẩy da chết và ủ dưỡng toàn thân • Trải nghiệm chăm sóc toàn diện từ đầu đến chân | Khách muốn chăm sóc toàn diện cao cấp |
                """;

        String saigonMassageTableChunk = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp | Quy trình chi tiết |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 570.000VNĐ | 70 phút | Full body massage trị liệu | Phòng cộng đồng có rèm riêng | KTV tiêu chuẩn | • Tắm xông thảo dược giúp làm ấm cơ thể và thư giãn trước khi trị liệu ; • Massage trị liệu full body 70 phút giúp giảm căng cơ, mỏi vai gáy, lưng ; • Buffet thực dưỡng miễn phí sau liệu trình | Khách thư giãn nhanh, dân văn phòng | • Thay đồ & chuẩn bị trị liệu• Massage trị liệu full body cơ bản |
                | NS02 | Massage ngâm thảo dược | 750.000VNĐ | 90 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 75p massage | Phòng cộng đồng có rèm | KTV tiêu chuẩn | • Kết hợp ngâm bồn thảo dược + tẩy tế bào chết + xông hơi + massage trị liệu 75 phút ; • Thưởng thức buffet thực dưỡng miễn phí sau liệu trình | Khách mỏi cơ, lạnh người | • Tắm tráng làm sạch cơ thể• Ngâm bồn thảo dược 10 phút• Xông hơi 5 phút• Massage trị liệu full body 75 phút |
                | NS03 | Massage phục hồi | 870.000VNĐ | 110 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 95p massage phục hồi | Phòng VIP riêng | KTV phục hồi | • Phòng VIP riêng tư ; • Buffet thực dưỡng cao cấp sau liệu trình | Đau mỏi vai gáy, stress nặng | • Tắm tráng• Ngâm bồn thảo dược 10 phút• Xông hơi 5 phút• Massage phục hồi full body |
                | NS04 | Massage phục hồi + dưỡng sinh | 1.150.000VNĐ | 130 phút | 90p ngâm bồn xông hơi massage + 40p gội đầu dưỡng sinh | VIP + khu gội chung | KTV phục hồi | Phòng VIP riêng tư kết hợp khu gội đầu dưỡng sinh | Mất ngủ nhẹ, cần điều trị chuyên sâu | • Tắm tráng• Ngâm bồn thảo dược 10 phút• Xông hơi 5 phút• Massage trị liệu full body |
                | NS05 | Silver – Massage phục hồi chuyên sâu | 1.500.000VNĐ | 150 phút | Tương tự gói NS04 + gội đầu dưỡng sinh tại phòng VIP | Phòng VIP riêng khép kín | KTV cao cấp | Không cần di chuyển giữa các bước hoàn toàn riêng tư | Khách thích riêng tư | • Tắm tráng• Ngâm bồn thảo dược• Xông hơi• Massage trị liệu full body chuyên sâu |
                | NS06 | Gold – Massage trị liệu phục hồi | 1.990.000VNĐ | ~180 phút | 10p ngâm bồn + tắm tráng + 70p massage + 105p trị liệu + 30p gội đầu tại phòng + nước ép trái cây + tẩy da chết | Phòng VIP riêng | Nhân viên ưu tú | Trị liệu chuyên sâu + phục hồi cao cấp | Doanh nhân, khách stress nặng | • Tắm tráng làm ấm cơ thể• Ngâm bồn thảo dược 10 phút• Massage trị liệu full body |
                | ADD01 | Tẩy da chết cơ bản | 100.000VNĐ | Theo liệu trình | Tẩy da chết toàn thân | Theo phòng dịch vụ | KTV | Làm sạch & mềm da | Khách muốn chăm sóc da thêm | • Tẩy tế bào chết toàn thân |
                """;

        String realFaqChunk = """
                | Câu hỏi | Trả lời |
                | --- | --- |
                | Sau khi làm dịch vụ có được ăn nhẹ không? | Có. Tất cả khách hàng sau liệu trình đều được phục vụ đồ ăn nhẹ và trà thảo mộc miễn phí để bổ sung năng lượng và thư giãn thêm trước khi ra về. |
                | Các gói VIP có kèm đồ ăn hoặc nước uống riêng không? | Các gói cao cấp như Gold và Diamond có thêm nước ép trái cây hoặc điểm tâm phục hồi sau liệu trình. |
                | Đồ ăn nhẹ tại spa có tính phí không? | Không. Đây là phần chăm sóc đi kèm sau dịch vụ dành cho khách hàng. |
                | Có phát sinh chi phí hoặc tiền tip bắt buộc không? | Không. Giá dịch vụ đã trọn gói, spa không thu phụ phí và không bắt buộc tiền tip. |
                | Gói VIP khác gì thật sự? | Dạ các gói VIP sẽ riêng tư hơn, hạn chế di chuyển và giúp cơ thể giữ được trạng thái phục hồi xuyên suốt hơn ạ |
                | Giờ nào vắng? | Dạ các khung giờ sáng hoặc sau 20h thường sẽ yên tĩnh và dễ chịu hơn một chút ạ |
                | Có cần cọc không? | Dạ bên em hiện chỉ hỗ trợ đặt cọc trước với khách đoàn từ 5 người trở lên hoặc các khung giờ quá tải đặc biệt ạ |
                """;

        String realBuffetMenuChunk = """
                [FAQ > Thực đơn Buffet thực dưỡng & Điểm tâm ăn nhẹ sau liệu trình]
                Sau khi làm dịch vụ có được ăn nhẹ không?: Có. Tất cả khách hàng sau liệu trình đều được phục vụ đồ ăn nhẹ và trà thảo mộc miễn phí để bổ sung năng lượng và thư giãn thêm trước khi ra về.
                Các gói VIP có kèm đồ ăn hoặc nước uống riêng không?: Các gói cao cấp như Gold và Diamond có thêm nước ép trái cây hoặc điểm tâm phục hồi sau liệu trình.
                Đồ ăn nhẹ tại spa có tính phí không?: Không. Đây là phần chăm sóc đi kèm sau dịch vụ dành cho khách hàng.

                Thực đơn Buffet thực dưỡng / Đồ ăn nhẹ miễn phí sau liệu trình gồm các món:
                | STT | Món ăn nhẹ / Thực đơn Buffet thực dưỡng sau liệu trình |
                | --- | --- |
                | 1 | Bánh mì bò sốt vang (banh my bo sot vang) |
                | 2 | Trứng ngải cứu (trung ngại cuu) |
                | 3 | Chè đậu đen (che dau den) |
                | 4 | Bún dọc mùng (bun doc mung) |
                | 5 | Cháo sườn (Chao sươn) |
                | 6 | Hoa quả theo mùa (hoa quả theo mùa) |
                """;

        realHtmlChunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("c9481efb-0001-4613-a2e0-fe50e9b99b39"),
                                HTML_DOC_ID,
                                List.of("MASSAGE", "Cơ sở Nàng Sen Bích Câu — Địa chỉ: 6 Bích Câu – Đống Đa – Hà Nội"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                hanoiMassageTableChunk,
                                0.96
                        ),
                        0.96
                ),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("c9481efb-0002-4613-a2e0-fe50e9b99b39"),
                                HTML_DOC_ID,
                                List.of("GỘI ĐẦU DƯỠNG SINH", "Khu vực Hà Nội"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                hanoiHairWashTableChunk,
                                0.94
                        ),
                        0.94
                ),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("c9481efb-0003-4613-a2e0-fe50e9b99b39"),
                                HTML_DOC_ID,
                                List.of("MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM) — Địa chỉ: 74 Nguyễn Công Trứ, Quận 1, TP Hồ Chí Minh"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                saigonMassageTableChunk,
                                0.92
                        ),
                        0.92
                ),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("1de606ea-490a-44d8-8dd6-07472d69230a"),
                                HTML_DOC_ID,
                                List.of("FAQ"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                realFaqChunk,
                                0.91
                        ),
                        0.91
                ),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("ca64440e-acc2-4afa-b29d-fa9bdf3231fd"),
                                HTML_DOC_ID,
                                List.of("FAQ", "Thực đơn Buffet thực dưỡng & Điểm tâm ăn nhẹ sau liệu trình"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                realBuffetMenuChunk,
                                0.90
                        ),
                        0.90
                )
        );
    }

    @Test
    @DisplayName("BT-SPA-001-PROMPT-BUDGET: Always-On <= 1200, Active Prompt Compact <= 900, Turn 1 'Giá' never loads PRICE_OBJECTION/TRIAL/CORRECTION, Few-Shot <= 1 & <= 450 chars")
    void promptBudgetGovernanceEnforcesHardCapAndIntentScoping() {
        LearnedRuleDto safeNeuro = LearnedRuleDto.fromEntity(com.example.docmind.entity.LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .pageId(NANG_SEN_PAGE_ID)
                .ruleCode("SAFE-NEURO-001")
                .dimension(com.example.docmind.entity.HarnessDimension.INV)
                .category("POLICY")
                .triggerCondition("Khi khách nói/hỏi (safety_and_contraindication): mang thai / có bầu | cao huyết áp chưa ổn định / bệnh tim mạch | đau tai / ù tai / chảy dịch tai / viêm tai | mới phẫu thuật / vết thương hở / bỏng rát / sưng tấy | tê tay lan / tê chân / chóng mặt dữ dội / thoát vị đĩa đệm cấp")
                .rootCause("Đảm bảo an toàn y khoa")
                .generalizedRule("[SAFE-NEURO-001 - High-risk health, pregnancy, or neurological red flag -> immediate handoff] NÊN LÀM: Dừng ngay việc tư vấn bán gói hoặc báo giá.; Gửi đúng 1 bong bóng thể hiện sự quan tâm an toàn và thông báo chuyển chuyên viên/quản lý hỗ trợ trực tiếp.; Đặt should_handoff = true, max_questions = 0.. TRÁNH: Không gợi ý gói 60/90 phút khi khách có cờ đỏ y khoa/thần kinh.; Không hỏi thêm câu hỏi khám bệnh.; Không báo giá..")
                .retrievalKeywords(new String[]{"safety", "neurological"})
                .active(true)
                .alwaysOn(true)
                .confidenceScore(0.98)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        LearnedRuleDto pbPriceObj1 = LearnedRuleDto.fromEntity(com.example.docmind.entity.LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .pageId(NANG_SEN_PAGE_ID)
                .ruleCode("PB-SPA-PRICE-OBJECTION-001")
                .dimension(com.example.docmind.entity.HarnessDimension.DOM)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi khách nói/hỏi (price_objection_first): Sao cao vậy shop? | Hơi đắt nhỉ")
                .rootCause("Khách chê đắt lần 1")
                .generalizedRule("[PB-SPA-PRICE-OBJECTION-001] Đồng cảm nhẹ nhàng + khẳng định chất lượng gói đang giữ từ KB + mời trải nghiệm thử 1 buổi 🥰")
                .retrievalKeywords(new String[]{"objection", "đắt"})
                .active(true)
                .alwaysOn(false)
                .confidenceScore(0.95)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        LearnedRuleDto pbPriceObj2 = LearnedRuleDto.fromEntity(com.example.docmind.entity.LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .pageId(NANG_SEN_PAGE_ID)
                .ruleCode("PB-SPA-PRICE-OBJECTION-002")
                .dimension(com.example.docmind.entity.HarnessDimension.DOM)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi khách nói/hỏi (price_objection_repeat): Mà hơi đắt | Có gói nào mềm hơn không?")
                .rootCause("Khách chê đắt lần 2")
                .generalizedRule("[PB-SPA-PRICE-OBJECTION-002] Hạ đúng 1 bậc xuống gói có giá thấp hơn trong KB.")
                .retrievalKeywords(new String[]{"downsell", "rẻ hơn"})
                .active(true)
                .alwaysOn(false)
                .confidenceScore(0.95)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        LearnedRuleDto pbTrialAccept = LearnedRuleDto.fromEntity(com.example.docmind.entity.LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .pageId(NANG_SEN_PAGE_ID)
                .ruleCode("PB-SPA-TRIAL-ACCEPT-001")
                .dimension(com.example.docmind.entity.HarnessDimension.DOM)
                .category("PLAYBOOK_CLOSING")
                .triggerCondition("Khi khách nói/hỏi: Thế cho mình thử 1 buổi xem sao")
                .rootCause("Khách đồng ý trải nghiệm thử")
                .generalizedRule("[PB-SPA-TRIAL-ACCEPT-001] Xác nhận gói giữ chỗ và xin khung giờ + SĐT.")
                .retrievalKeywords(new String[]{"thử xem sao", "đặt lịch"})
                .active(true)
                .alwaysOn(false)
                .confidenceScore(0.95)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        LearnedRuleDto pbCorrection = LearnedRuleDto.fromEntity(com.example.docmind.entity.LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .pageId(NANG_SEN_PAGE_ID)
                .ruleCode("PB-SPA-CORRECTION-001")
                .dimension(com.example.docmind.entity.HarnessDimension.DOM)
                .category("PLAYBOOK_EMPATHY")
                .triggerCondition("Khi khách nói/hỏi: Mình hỏi chân đau mỏi gối chứ có hỏi cổ vai gáy đâu")
                .rootCause("Khách chỉnh lại nhu cầu")
                .generalizedRule("[PB-SPA-CORRECTION-001] Nhận lỗi ngắn gọn và chuyển đúng dịch vụ.")
                .retrievalKeywords(new String[]{"correction"})
                .active(true)
                .alwaysOn(false)
                .confidenceScore(0.95)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        List<LearnedRuleDto> allRules = List.of(safeNeuro, pbPriceObj1, pbPriceObj2, pbTrialAccept, pbCorrection);
        PromptBudgetGuard.BudgetCheckResult budget = PromptBudgetGuard.validateAlwaysOnRules(allRules, ACTIVE_PROMPT_COMPACT_V321);

        assertThat(budget.withinBudget()).isTrue();
        assertThat(budget.alwaysOnRuleChars()).isLessThanOrEqualTo(PromptBudgetGuard.HARD_LIMIT_ALWAYS_ON_CHARS);
        assertThat(budget.alwaysOnRuleChars()).isLessThanOrEqualTo(PromptBudgetGuard.TARGET_CORE_CHARS);
        assertThat(budget.corePromptChars()).isLessThanOrEqualTo(PromptBudgetGuard.TARGET_CORE_CHARS);
        assertThat(budget.globalCriticalRules()).extracting(LearnedRuleDto::ruleCode).containsExactly("SAFE-NEURO-001");

        // Turn 1 "Giá": PB-SPA-PRICE-OBJECTION-001/002, PB-SPA-TRIAL-ACCEPT-001, PB-SPA-CORRECTION-001 must NEVER be loaded
        ConversationState turn1State = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-bt-spa-001", List.of(),
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        List<LearnedRuleDto> turn1Rules = PromptBudgetGuard.selectScopedContextualRule(allRules, "PRICE_INQUIRY", turn1State, "Giá");
        assertThat(turn1Rules).isEmpty();

        // Even if intent were PRICE_OBJECTION_FIRST, if state.priceDiscussed() == false, PRICE_OBJECTION must NOT load
        List<LearnedRuleDto> unquotedObjRules = PromptBudgetGuard.selectScopedContextualRule(allRules, "PRICE_OBJECTION_FIRST", turn1State, "Đắt nhỉ");
        assertThat(unquotedObjRules).isEmpty();

        // When price has been discussed, Turn 4 "đắt nhỉ" loads exactly 1 rule: PB-SPA-PRICE-OBJECTION-001
        ConversationState quotedState = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-bt-spa-001-quoted",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Giá"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói massage cơ bản 60 phút bên em có giá 399k ạ.")
                ),
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        List<LearnedRuleDto> turn4Rules = PromptBudgetGuard.selectScopedContextualRule(allRules, "PRICE_OBJECTION_FIRST", quotedState, "đắt nhỉ");
        assertThat(turn4Rules).hasSize(1);
        assertThat(turn4Rules.get(0).ruleCode()).isEqualTo("PB-SPA-PRICE-OBJECTION-001");

        // Few-shot sample dialogues must filter quality_label == APPROVED_GOOD AND active == true, max 1 dialogue <= 450 chars
        SampleDialogueDto goodDialogue = new SampleDialogueDto(
                UUID.randomUUID(), "FD-GOOD-001", NANG_SEN_PAGE_ID.toString(), "spa_wellness",
                "PRICE_INQUIRY", "DISCOVERY", "NEW", "WARM", null,
                "Giá", "Dạ bên em có massage trị liệu và gội đầu dưỡng sinh ạ.", "Good",
                true, "price", true, "APPROVED", "APPROVED_GOOD", null, null, "YAML",
                Instant.now(), Instant.now()
        );
        SampleDialogueDto quarantinedDialogue = new SampleDialogueDto(
                UUID.randomUUID(), "FD-BAD-001", NANG_SEN_PAGE_ID.toString(), "spa_wellness",
                "PRICE_INQUIRY", "DISCOVERY", "NEW", "WARM", null,
                "Giá", "Gói ngắn 60 phút 280k", "Bad",
                true, "price", true, "APPROVED", "QUARANTINED_BAD", "Deprecated", null, "YAML",
                Instant.now(), Instant.now()
        );
        assertThat(FanpageBotPromptBuilder.formatSampleDialogueCard(quarantinedDialogue, "mình")).isEmpty();
        assertThat(FanpageBotPromptBuilder.formatSampleDialogueCard(goodDialogue, "mình"))
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(FanpageBotPromptBuilder.SAMPLE_DIALOGUES_MAX_CHARS);
    }

    @Test
    @DisplayName("BT-SPA-002-END-TO-END-7-TURNS-WITH-REAL-HTML-KB: Exact 7-turn user transcript against 01_giá_dịch_vụ.html")
    void sevenTurnEndToEndConversationWithRealHtmlKb() {
        List<String> userTurns = List.of(
                "Giá",
                "Toi bbi đau mỏi cổ , nhức vai",
                "thi thoảng",
                "đắt nhỉ",
                "mà hơi đắt",
                "Có gói khác k",
                "Dở à"
        );

        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        Set<String> seenBubbleTexts = new LinkedHashSet<>();
        List<String> turnReplies = new ArrayList<>();
        List<List<FanpageBotResponse.ReplyBubble>> turnBubbles = new ArrayList<>();

        for (int i = 0; i < userTurns.size(); i++) {
            String msg = userTurns.get(i);
            ConversationState state = ConversationState.hydrate(
                    NANG_SEN_PAGE_ID,
                    "user-bt-spa-002-exact",
                    history,
                    nangSenPersona.resolvedConfig().safeDomainKeywords(),
                    realHtmlChunks
            );
            PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                    msg,
                    state,
                    realHtmlChunks,
                    nangSenPersona.resolvedConfig().safeDomainKeywords()
            );
            List<FanpageBotResponse.ReplyBubble> rawBubbles = PrimaryActionRouter.executeActionBubbles(
                    decision,
                    msg,
                    state,
                    "mình",
                    nangSenPersona,
                    realHtmlChunks,
                    null
            );
            ReplyContractValidator.ValidationResult valRes = ReplyContractValidator.validateAndEnforce(
                    rawBubbles,
                    state,
                    msg,
                    decision.effectiveResponseAction(),
                    decision.openingRequired(),
                    10000
            );

            String combined = valRes.combinedText();
            turnReplies.add(combined);
            turnBubbles.add(valRes.validatedBubbles());

            // Global assertions on every turn
            assertThat(combined)
                    .as("Turn %d (%s) must never contain fake deprecated prices 200k/280k/350k/480k or banned phrases", i + 1, msg)
                    .doesNotContain("200k", "280k", "350k", "480k", "200.000", "280.000", "350.000", "480.000")
                    .doesNotContainIgnoringCase("An Nhiên")
                    .doesNotContainIgnoringCase("Dạ em ghi nhận mình đang")
                    .doesNotContainIgnoringCase("Nếu chưa đúng ý")
                    .doesNotContainIgnoringCase("gói ngắn")
                    .doesNotContainIgnoringCase("chính_sách_thẻ");

            // Single compact price format (no "399k (399.000đ)")
            assertThat(combined).doesNotContainPattern("(?iU)\\b\\d{2,4}k\\s*\\(\\s*\\d{1,3}(?:\\.\\d{3})+");

            // Zero duplicate bubbles across the entire 7-turn conversation
            for (FanpageBotResponse.ReplyBubble b : valRes.validatedBubbles()) {
                String normBubble = b.text().strip().toLowerCase(Locale.ROOT);
                assertThat(seenBubbleTexts.add(normBubble))
                        .as("Turn %d (%s) repeated a previous bubble verbatim: %s", i + 1, msg, b.text())
                        .isTrue();
            }

            ConversationState.updateAfterSend(
                    NANG_SEN_PAGE_ID,
                    "user-bt-spa-002-exact",
                    state,
                    msg,
                    decision,
                    valRes.validatedBubbles(),
                    false
            );

            history.add(new FanpageBotRequest.ConversationTurn("user", msg));
            for (FanpageBotResponse.ReplyBubble b : valRes.validatedBubbles()) {
                history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text()));
            }
        }

        // Turn 1 ("Giá"): Warm greeting + asks service category without dumping price list
        assertThat(turnReplies.get(0)).contains("Nàng Sen Spa").doesNotContain("399k", "239k");

        // Turn 2 ("Toi bbi đau mỏi cổ , nhức vai"): Natural empathy acknowledging both needs + asks 1 condition question
        assertThat(turnReplies.get(1)).contains("đau mỏi cổ").contains("nhức vai").contains("?");

        // Turn 3 ("thi thoảng"): Recommends real HTML package (399k Massage cơ bản 60 phút) in single price format with >= 10000ms delay
        assertThat(turnReplies.get(2)).contains("399k").contains("60 phút");
        assertThat(turnBubbles.get(2).get(turnBubbles.get(2).size() - 1).typingDelayMs()).isGreaterThanOrEqualTo(10000);

        // Turn 4 ("đắt nhỉ"): First price objection -> holds package (399k), highlights real KB value (tắm xông / buffet / không nhận Tip), invites 1-session trial 🥰
        assertThat(turnReplies.get(3)).contains("trải nghiệm thử 1 buổi").contains("🥰");

        // Turn 5 ("mà hơi đắt"): Second price objection -> downsells 1 step to real lower package in 01_giá_dịch_vụ.html (239k Gói dưỡng sinh cơ bản 60 phút) with >= 10000ms delay
        assertThat(turnReplies.get(4).toLowerCase(Locale.ROOT))
                .contains("239k")
                .contains("dưỡng sinh cơ bản")
                .doesNotContain("massage cơ bản 60 phút bên em có giá 239k");
        assertThat(turnBubbles.get(4).get(turnBubbles.get(4).size() - 1).typingDelayMs()).isGreaterThanOrEqualTo(10000);

        // Turn 6 ("Có gói khác k"): Offers 1-2 unmentioned packages from real HTML catalog (e.g. 339k Dưỡng sinh thư giãn 80 phút / 530k Massage ngâm thảo dược 90 phút)
        assertThat(turnReplies.get(5)).containsAnyOf("339k", "530k", "500k", "580k");
        assertThat(turnReplies.get(5)).doesNotContain("399k", "239k");

        // Turn 7 ("Dở à"): Recognizes frustration and apologizes sincerely without repeating previous bubbles
        assertThat(turnReplies.get(6).toLowerCase(Locale.ROOT)).contains("xin lỗi");
    }

    @Test
    @DisplayName("BT-SPA-003-LEG-KNEE-MATCHING-WITH-REAL-HTML-KB: Exact 3-turn transcript ('Giá' -> 'Tôi bị chân đau, mỏi gối' -> 'thi thoảng') matches NS01..NS03 and never DS01..DS06 or Handoff")
    void legAndKneeSymptomMatchesFullBodyMassageWithRealHtmlKb() {
        List<String> userTurns = List.of(
                "Giá",
                "Tôi bị chân đau, mỏi gối",
                "thi thoảng"
        );
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        List<String> turnReplies = new ArrayList<>();

        for (int i = 0; i < userTurns.size(); i++) {
            String msg = userTurns.get(i);
            ConversationState state = ConversationState.hydrate(
                    NANG_SEN_PAGE_ID, "user-bt-spa-003-exact", history,
                    nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
            );
            PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.route(
                    msg, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
            );
            assertThat(dec.effectiveResponseAction())
                    .as("Turn %d (%s) must never trigger hard handoff", i + 1, msg)
                    .isNotIn(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT, PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY);

            List<FanpageBotResponse.ReplyBubble> rawBubbles = PrimaryActionRouter.executeActionBubbles(
                    dec, msg, state, "mình", nangSenPersona, realHtmlChunks, null
            );
            ReplyContractValidator.ValidationResult valRes = ReplyContractValidator.validateAndEnforce(
                    rawBubbles, state, msg, dec.effectiveResponseAction(), dec.openingRequired(), 10000
            );
            turnReplies.add(valRes.combinedText());

            ConversationState.updateAfterSend(
                    NANG_SEN_PAGE_ID, "user-bt-spa-003-exact", state, msg, dec, valRes.validatedBubbles(), false
            );
            history.add(new FanpageBotRequest.ConversationTurn("user", msg));
            valRes.validatedBubbles().forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));
        }

        // Turn 2 ("Tôi bị chân đau, mỏi gối"): Empathy + asks 1 condition question about leg/knee
        assertThat(turnReplies.get(1).toLowerCase(Locale.ROOT))
                .containsAnyOf("đau chân", "mỏi gối")
                .doesNotContain("dạ em ghi nhận mình đang")
                .contains("?");

        // Turn 3 ("thi thoảng"): Recommends full-body massage NS01 (399k) or NS02 (530k), never DS01..DS06 (gội đầu / lấy ráy tai)
        assertThat(turnReplies.get(2).toLowerCase(Locale.ROOT))
                .contains("massage")
                .doesNotContain("gội đầu")
                .doesNotContain("lấy ráy tai");
        assertThat(turnReplies.get(2)).containsAnyOf("399k", "530k", "580k");
    }

    @Test
    @DisplayName("BT-SPA-004-SAIGON-REGION-AND-VALIDATOR-PRICE-GROUNDING: Saigon region switch uses Quận 1 prices (570k/750k) and validator blocks ungrounded prices")
    void saigonRegionSelectionAndValidatorCatalogPriceGrounding() {
        List<FanpageBotRequest.ConversationTurn> sgHistory = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình ở Quận 1 Sài Gòn, bị đau mỏi vai gáy thường xuyên")
        );
        ConversationState sgState = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-bt-spa-004-sg", sgHistory,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(sgState.preferredRegion()).isEqualTo("SG");
        assertThat(sgState.availablePackageCatalog()).isNotEmpty();

        PrimaryActionRouter.RoutingDecision sgDec = PrimaryActionRouter.route(
                "bị lâu rồi, báo giá giúp mình", sgState, realHtmlChunks,
                nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        List<FanpageBotResponse.ReplyBubble> sgBubbles = PrimaryActionRouter.executeActionBubbles(
                sgDec, "bị lâu rồi, báo giá giúp mình", sgState, "mình", nangSenPersona, realHtmlChunks, null
        );
        String sgReply = String.join("\n", sgBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(sgReply).containsAnyOf("570k", "750k", "870k").doesNotContain("399k", "530k");

        // Validator must flag ungrounded price (e.g. 450k not in catalog)
        ReplyContractValidator.ValidationResult ungroundedCheck = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ gói massage bên em có giá 450k ạ.", 10000)),
                sgState,
                "báo giá",
                PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE,
                false,
                10000
        );
        assertThat(ungroundedCheck.errors()).contains("UNGROUNDED_CATALOG_PRICE");
    }

    @Test
    @DisplayName("BT-SPA-005-LIVE-B317-REPLAY-AND-SCENARIOS-A-B-D-E: Exact replay of session b317a239 + Scenarios A, B, D, E")
    void liveSessionB317ReplayAndScenariosA_B_D_E() {
        String senderId = "user-b317a239-live-replay";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Turn 1..4 of session b317a239-7912-45e8-bc4f-b4b04bbec797
        List<String> setupTurns = List.of("Giá", "Tôi bị đau đầu", "Hôm qua", "Cổ vai gáy");
        List<List<FanpageBotResponse.ReplyBubble>> setupReplies = new ArrayList<>();

        for (String msg : setupTurns) {
            ConversationState state = ConversationState.hydrate(
                    NANG_SEN_PAGE_ID, senderId, history,
                    nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
            );
            PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.route(
                    msg, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
            );
            List<FanpageBotResponse.ReplyBubble> raw = PrimaryActionRouter.executeActionBubbles(
                    dec, msg, state, "mình", nangSenPersona, realHtmlChunks, null
            );
            ReplyContractValidator.ValidationResult val = ReplyContractValidator.validateAndEnforce(
                    raw, state, msg, dec.effectiveResponseAction(), dec.openingRequired(), 10000
            );
            setupReplies.add(val.validatedBubbles());
            ConversationState.updateAfterSend(
                    NANG_SEN_PAGE_ID, senderId, state, msg, dec, val.validatedBubbles(), false
            );
            history.add(new FanpageBotRequest.ConversationTurn("user", msg));
            val.validatedBubbles().forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));
        }

        // Verify Requirement 5 on Turn 4 ("Cổ vai gáy"): natural grounded emotional anchoring, no mechanical concatenation, no medical cure claims
        List<FanpageBotResponse.ReplyBubble> turn4Bubbles = setupReplies.get(3);
        String turn4Combined = String.join("\n", turn4Bubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(turn4Combined)
                .contains("399k")
                .doesNotContain("rất dễ chịu ạ.")
                .doesNotContainIgnoringCase("chữa khỏi")
                .doesNotContainIgnoringCase("hết đau đầu")
                .doesNotContainIgnoringCase("điều trị dứt điểm");

        // Scenario A (Turn 5): "Thực dưỡng là j" -> resolves reference to previously mentioned buffet benefit & answers definition question directly
        String msgA = "Thực dưỡng là j";
        ConversationState stateA = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision decA = PrimaryActionRouter.route(
                msgA, stateA, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decA.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        assertThat(decA.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        assertThat(decA.correspondingMicroSkillCode()).isEqualTo("spa_service_detail_faq_qa");

        List<FanpageBotResponse.ReplyBubble> rawA = PrimaryActionRouter.executeActionBubbles(
                decA, msgA, stateA, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult valA = ReplyContractValidator.validateAndEnforce(
                rawA, stateA, msgA, decA.effectiveResponseAction(), decA.openingRequired(), 10000
        );
        assertThat(valA.validatedBubbles()).hasSize(1);
        assertThat(valA.combinedText().toLowerCase(Locale.ROOT))
                .contains("buffet thực dưỡng")
                .contains("ăn nhẹ")
                .contains("trà thảo mộc")
                .contains("miễn phí")
                .doesNotContain("mình đang quan tâm mảng dịch vụ nào")
                .doesNotContain("giữ lịch")
                .doesNotContain("khung giờ");

        // Scenario B (Turn 6): "K tôi thấy có buffet thực dưỡng, nó là j" after an unhelpful/ambiguous bot reply
        List<FanpageBotRequest.ConversationTurn> brokenHistory = new ArrayList<>(history);
        String unrelatedAmbiguousReply = "Dạ mình đang quan tâm mảng dịch vụ nào hoặc cần giải đáp điểm nào để em tư vấn đúng ý mình nhất ạ?";
        brokenHistory.add(new FanpageBotRequest.ConversationTurn("user", msgA));
        brokenHistory.add(new FanpageBotRequest.ConversationTurn("assistant", unrelatedAmbiguousReply));

        String msgB = "K tôi thấy có buffet thực dưỡng, nó là j";
        ConversationState stateB = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId + "-broken", brokenHistory,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(stateB.hasUnresolvedCustomerRequest()).isTrue();
        assertThat(stateB.latestUnresolvedRequest().referencedEntity()).contains("buffet thực dưỡng");

        PrimaryActionRouter.RoutingDecision decB = PrimaryActionRouter.route(
                msgB, stateB, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decB.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);

        List<FanpageBotResponse.ReplyBubble> rawB = PrimaryActionRouter.executeActionBubbles(
                decB, msgB, stateB, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult valB = ReplyContractValidator.validateAndEnforce(
                rawB, stateB, msgB, decB.effectiveResponseAction(), decB.openingRequired(), 10000
        );
        assertThat(valB.combinedText())
                .isNotEqualToIgnoringCase(unrelatedAmbiguousReply)
                .doesNotContain("mình đang quan tâm mảng dịch vụ nào");
        assertThat(valB.combinedText().toLowerCase(Locale.ROOT))
                .contains("buffet thực dưỡng")
                .contains("ăn nhẹ")
                .contains("miễn phí");

        // Scenario E (Turn 7): "Dở à" after two unanswered detail questions -> acknowledges specific failure + answers outstanding question directly without upselling/booking CTA
        brokenHistory.add(new FanpageBotRequest.ConversationTurn("user", msgB));
        brokenHistory.add(new FanpageBotRequest.ConversationTurn("assistant", unrelatedAmbiguousReply));

        String msgE = "Dở à";
        ConversationState stateE = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId + "-broken-e", brokenHistory,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(stateE.hasUnresolvedCustomerRequest()).isTrue();
        assertThat(stateE.latestUnresolvedRequest().repeatOrChallengeCount()).isGreaterThanOrEqualTo(1);

        PrimaryActionRouter.RoutingDecision decE = PrimaryActionRouter.route(
                msgE, stateE, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decE.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        assertThat(decE.detectedIntent()).isEqualTo("FRUSTRATION_RECOVERY_ANSWER_UNRESOLVED_FAQ");

        List<FanpageBotResponse.ReplyBubble> rawE = PrimaryActionRouter.executeActionBubbles(
                decE, msgE, stateE, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult valE = ReplyContractValidator.validateAndEnforce(
                rawE, stateE, msgE, decE.effectiveResponseAction(), decE.openingRequired(), 10000
        );
        assertThat(valE.validatedBubbles()).hasSize(1);
        assertThat(valE.combinedText().toLowerCase(Locale.ROOT))
                .contains("xin lỗi")
                .contains("buffet thực dưỡng")
                .contains("ăn nhẹ")
                .doesNotContain("530k")
                .doesNotContain("239k")
                .doesNotContain("khung giờ")
                .doesNotContain("giữ lịch");

        // Scenario D: "Buffet có món gì?" -> returns only menu items supported by 01_giá_dịch_vụ.html
        String msgD = "Buffet có món gì?";
        PrimaryActionRouter.RoutingDecision decD = PrimaryActionRouter.route(
                msgD, stateA, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decD.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<FanpageBotResponse.ReplyBubble> rawD = PrimaryActionRouter.executeActionBubbles(
                decD, msgD, stateA, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult valD = ReplyContractValidator.validateAndEnforce(
                rawD, stateA, msgD, decD.effectiveResponseAction(), decD.openingRequired(), 10000
        );
        assertThat(valD.combinedText().toLowerCase(Locale.ROOT))
                .contains("bánh mì bò sốt vang")
                .contains("trứng ngải cứu")
                .contains("chè đậu đen")
                .contains("bún dọc mùng")
                .contains("cháo sườn")
                .contains("hoa quả theo mùa")
                .doesNotContain("cháo gà hầm hạt sen");
    }

    @Test
    @DisplayName("BT-SPA-006-SCENARIO-C-REFUSAL-RESPECTED: 'Không cần nữa' / 'Thôi để sau' respects refusal even after bot mentioned buffet thực dưỡng")
    void scenarioC_RefusalRespectedEvenAfterBenefitMention() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá massage cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói massage cơ bản 60 phút bên em có giá 399k ạ."),
                new FanpageBotRequest.ConversationTurn("assistant", "Gói này gồm full body massage trị liệu và có kèm buffet thực dưỡng miễn phí sau liệu trình ạ.")
        );
        ConversationState state = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-c", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );

        // 1. Explicit opt-out refusal "Không cần nữa" -> EXPLICIT_OPT_OUT (never SERVICE_DETAIL_AND_FAQ_QA)
        String msgC1 = "Không cần nữa";
        PrimaryActionRouter.RoutingDecision decC1 = PrimaryActionRouter.route(
                msgC1, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decC1.detectedIntent()).isEqualTo("EXPLICIT_OPT_OUT");
        assertThat(decC1.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY);
        assertThat(decC1.effectiveResponseAction()).isNotEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);

        List<FanpageBotResponse.ReplyBubble> bubblesC1 = PrimaryActionRouter.executeActionBubbles(
                decC1, msgC1, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String textC1 = String.join("\n", bubblesC1.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(textC1.toLowerCase(Locale.ROOT))
                .contains("xin phép dừng")
                .doesNotContain("399k")
                .doesNotContain("buffet thực dưỡng");

        // 2. Strong rejection "Thôi không làm nữa" -> STRONG_REJECTION (never SERVICE_DETAIL_AND_FAQ_QA)
        String msgC2 = "Thôi không làm nữa";
        PrimaryActionRouter.RoutingDecision decC2 = PrimaryActionRouter.route(
                msgC2, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decC2.detectedIntent()).isEqualTo("STRONG_REJECTION");
        assertThat(decC2.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY);
        assertThat(decC2.effectiveResponseAction()).isNotEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);

        List<FanpageBotResponse.ReplyBubble> bubblesC2 = PrimaryActionRouter.executeActionBubbles(
                decC2, msgC2, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String textC2 = String.join("\n", bubblesC2.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(textC2.toLowerCase(Locale.ROOT))
                .contains("cảm ơn")
                .doesNotContain("399k")
                .doesNotContain("buffet thực dưỡng");
    }

    @Test
    @DisplayName("BT-SPA-007-SCENARIO-F-COMPREHENSION-FAILURE: 'Chả hiểu j' after explanation simplifies once, then escalates to human support if still unclear")
    void scenarioF_ComprehensionFailureClarifiesOnceThenOffersHumanSupport() {
        String senderId = "user-scenario-f";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>(List.of(
                new FanpageBotRequest.ConversationTurn("user", "Thực dưỡng là j"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ buffet thực dưỡng tại Nàng Sen là phần điểm tâm ăn nhẹ và trà thảo mộc phục vụ miễn phí cho khách sau khi kết thúc liệu trình ạ.")
        ));

        // 1st "Chả hiểu j" -> simplifies explanation clearly (SERVICE_DETAIL_AND_FAQ_QA), no immediate handoff
        String msgF1 = "Chả hiểu j";
        ConversationState stateF1 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision decF1 = PrimaryActionRouter.route(
                msgF1, stateF1, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decF1.detectedIntent()).isEqualTo("COMPREHENSION_RECOVERY_SIMPLIFY_FAQ");
        assertThat(decF1.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);

        List<FanpageBotResponse.ReplyBubble> bubblesF1 = PrimaryActionRouter.executeActionBubbles(
                decF1, msgF1, stateF1, "mình", nangSenPersona, realHtmlChunks, null
        );
        String replyF1 = String.join("\n", bubblesF1.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(replyF1.toLowerCase(Locale.ROOT))
                .contains("giải thích ngắn gọn")
                .contains("bánh mì bò sốt vang")
                .contains("miễn phí");

        ConversationState.updateAfterSend(
                NANG_SEN_PAGE_ID, senderId, stateF1, msgF1, decF1, bubblesF1, false
        );
        history.add(new FanpageBotRequest.ConversationTurn("user", msgF1));
        bubblesF1.forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));

        // 2nd consecutive "Vẫn chả hiểu j" -> escalates to human support (HARD_HANDOFF_COMPLAINT)
        String msgF2 = "Vẫn chả hiểu j";
        ConversationState stateF2 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision decF2 = PrimaryActionRouter.route(
                msgF2, stateF2, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decF2.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
    }

    @Test
    @DisplayName("BT-SPA-008-SCENARIO-G-PACKAGE-DETAILS: Room type, duration, and included steps preserve active package and tenant catalog")
    void scenarioG_PackageBenefitDurationRoomAndTechniqueQuestions() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi vai gáy thi thoảng, báo giá giúp mình"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ với tình trạng mỏi vai gáy thi thoảng, gói massage cơ bản 60 phút bên em có giá 399k ạ.")
        );
        ConversationState state = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-g", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );

        // Room type question
        String roomQ = "Gói cơ bản là phòng riêng hay phòng chung?";
        PrimaryActionRouter.RoutingDecision roomDec = PrimaryActionRouter.route(
                roomQ, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(roomDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<FanpageBotResponse.ReplyBubble> roomBubbles = PrimaryActionRouter.executeActionBubbles(
                roomDec, roomQ, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String roomReply = String.join("\n", roomBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(roomReply.toLowerCase(Locale.ROOT))
                .contains("massage cơ bản")
                .contains("phòng cộng đồng có rèm riêng");

        // Included benefits & steps question
        String stepsQ = "Gói này gồm những gì?";
        PrimaryActionRouter.RoutingDecision stepsDec = PrimaryActionRouter.route(
                stepsQ, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(stepsDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<FanpageBotResponse.ReplyBubble> stepsBubbles = PrimaryActionRouter.executeActionBubbles(
                stepsDec, stepsQ, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String stepsReply = String.join("\n", stepsBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(stepsReply.toLowerCase(Locale.ROOT))
                .contains("massage cơ bản")
                .contains("60 phút")
                .contains("full body massage trị liệu");
    }

    @Test
    @DisplayName("BT-SPA-009-SCENARIO-H-FRUSTRATION-WITHOUT-UNRESOLVED-QUESTION: Responds to current concern without fabricating an FAQ or pitching a package")
    void scenarioH_FrustrationWithoutUnresolvedQuestionAddressesCurrentConcern() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chào mình, em là nhân viên chăm sóc của Nàng Sen Spa ạ. Mình đang quan tâm massage trị liệu hay gội đầu dưỡng sinh ạ?")
        );
        ConversationState state = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-h", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(state.hasUnresolvedCustomerRequest()).isFalse();

        String msgH = "Dở à";
        PrimaryActionRouter.RoutingDecision decH = PrimaryActionRouter.route(
                msgH, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decH.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY);
        assertThat(decH.detectedIntent()).isEqualTo("FRUSTRATION_CONCERN_ACKNOWLEDGEMENT");

        List<FanpageBotResponse.ReplyBubble> bubblesH = PrimaryActionRouter.executeActionBubbles(
                decH, msgH, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String replyH = String.join("\n", bubblesH.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(replyH.toLowerCase(Locale.ROOT))
                .contains("xin lỗi")
                .doesNotContain("399k")
                .doesNotContain("530k")
                .doesNotContain("buffet thực dưỡng");
    }

    @Test
    @DisplayName("BT-SPA-010-SCENARIO-I-WEBHOOK-IDEMPOTENCY: Repeating the same webhook event does not increment frustration/objection counters twice")
    void scenarioI_DuplicateWebhookEventIdempotencyPreventsRepeatedStateIncrements() {
        String senderId = "user-scenario-i-idempotent";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chào mình, mình đang quan tâm massage trị liệu hay gội đầu dưỡng sinh ạ?")
        );
        String msg = "Dở à";

        // 1st delivery of "Dở à"
        ConversationState state1 = ConversationState.hydrateForTurn(
                NANG_SEN_PAGE_ID, senderId, msg, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision dec1 = PrimaryActionRouter.route(
                msg, state1, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        List<FanpageBotResponse.ReplyBubble> bubbles1 = PrimaryActionRouter.executeActionBubbles(
                dec1, msg, state1, "mình", nangSenPersona, realHtmlChunks, null
        );
        ConversationState post1 = ConversationState.updateAfterSend(
                NANG_SEN_PAGE_ID, senderId, state1, msg, dec1, bubbles1, false
        );
        assertThat(post1.frustrationCount()).isEqualTo(1);

        // 2nd duplicate delivery of the exact same webhook event ("Dở à" with identical history)
        ConversationState state2 = ConversationState.hydrateForTurn(
                NANG_SEN_PAGE_ID, senderId, msg, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(state2.frustrationCount())
                .as("Duplicate delivery must restore pre-turn state so frustrationCount is not incremented twice")
                .isEqualTo(0);
        PrimaryActionRouter.RoutingDecision dec2 = PrimaryActionRouter.route(
                msg, state2, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(dec2.primaryAction()).isEqualTo(dec1.primaryAction());
    }

    @Test
    @DisplayName("BT-SPA-011-SCENARIO-J-MISSING-OR-CONFLICTING-DATA: Never invents facts, prices, free benefits, or medical cures when KB data is missing or conflicting")
    void scenarioJ_MissingOrConflictingFaqAndCatalogDataNeverInventsFacts() {
        ConversationState baseState = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-j", List.of(),
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );

        // 1. Missing FAQ evidence -> routes to SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED instead of inventing details
        String missingQ = "Phòng xông đá muối Himalaya tia hồng ngoại bên mình là gì?";
        PrimaryActionRouter.RoutingDecision missingDec = PrimaryActionRouter.route(
                missingQ, baseState, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(missingDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED);
        assertThat(missingDec.detectedIntent()).isEqualTo("UNSUPPORTED_DETAIL_OR_FAQ_QUESTION");

        // 2. Conflicting catalog price evidence for the same package code & region -> routes to SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED
        String conflictingTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian |
                | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage | Phòng cộng đồng |
                | NS01 | Massage cơ bản | 499.000VNĐ | 60 phút | Full body massage | Phòng cộng đồng |
                """;
        List<ReciprocalRankFusion.FusedChunk> conflictingChunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.randomUUID(), HTML_DOC_ID,
                                List.of("MASSAGE", "Cơ sở Hà Nội"), List.of(), 1, new double[]{0, 0, 10, 10},
                                conflictingTable, 0.95
                        ),
                        0.95
                )
        );
        ConversationState conflictState = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-j-conflict", List.of(),
                nangSenPersona.resolvedConfig().safeDomainKeywords(), conflictingChunks
        );
        String detailOnConflictPkg = "Gói massage cơ bản gồm những gì?";
        PrimaryActionRouter.RoutingDecision conflictDec = PrimaryActionRouter.route(
                detailOnConflictPkg, conflictState, conflictingChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(conflictDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED);

        // 3. Empty post-retrieval fusedChunks (List.of()) on FAQ question must NOT bypass missing-evidence guard
        PrimaryActionRouter.RoutingDecision preRetrievalFaqDec = PrimaryActionRouter.route(
                "Buffet thực dưỡng là gì?", baseState, null, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(preRetrievalFaqDec.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);

        PrimaryActionRouter.RoutingDecision emptyPostRetrievalFaqDec = PrimaryActionRouter.route(
                "Buffet thực dưỡng là gì?", baseState, List.of(), nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(emptyPostRetrievalFaqDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED);
        assertThat(emptyPostRetrievalFaqDec.detectedIntent()).isEqualTo("UNSUPPORTED_DETAIL_OR_FAQ_QUESTION");

        // 4. Empty post-retrieval fusedChunks (List.of()) on frustrated turn with unresolved FAQ -> HARD_HANDOFF_COMPLAINT (UNRESOLVED_FAQ_HANDOFF)
        List<FanpageBotRequest.ConversationTurn> unresolvedHistory = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Thực dưỡng là j"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình đang quan tâm mảng dịch vụ nào ạ?")
        );
        ConversationState unresolvedState = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-j-empty-frustrated", unresolvedHistory,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), List.of()
        );
        PrimaryActionRouter.RoutingDecision emptyFrustratedDec = PrimaryActionRouter.route(
                "Dở à", unresolvedState, List.of(), nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(emptyFrustratedDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
        assertThat(emptyFrustratedDec.detectedIntent()).isEqualTo("UNRESOLVED_FAQ_HANDOFF");
    }

    @Test
    @DisplayName("BT-SPA-012-POLICY-GENERAL-COMPOUND-FAQ-AND-STATE-PRESERVATION: Policy FAQs, general FAQs, compound multi-entity FAQs, and hydrate state preservation")
    void policyGeneralCompoundFaqAndStatePreservationAcrossFaqTurns() {
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>(List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi vai gáy 2 tuần rồi, báo giá giúp mình"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ với tình trạng mỏi vai gáy kéo dài 2 tuần của mình, em gợi ý gói massage trị liệu cổ vai gáy chuyên sâu 90 phút có giá 530k theo bảng giá hiện tại ạ.")
        ));
        ConversationState state = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-012", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        int recCountBeforeFaq = state.recommendationCount();
        String heldPkgBeforeFaq = state.heldPackage();

        // 1. Policy FAQ: Tip / Extra Fees -> answers from FAQ table row, never returns unrelated basic package summary
        String tipQ = "Đi làm dịch vụ có cần tip không?";
        PrimaryActionRouter.RoutingDecision tipDec = PrimaryActionRouter.route(
                tipQ, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(tipDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<FanpageBotResponse.ReplyBubble> tipBubbles = PrimaryActionRouter.executeActionBubbles(
                tipDec, tipQ, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String tipReply = String.join("\n", tipBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(tipReply.toLowerCase(Locale.ROOT))
                .contains("trọn gói")
                .contains("tip")
                .doesNotContain("full body massage trị liệu");

        // 2. General FAQ: VIP package difference
        String vipQ = "Gói VIP khác gì thật sự?";
        PrimaryActionRouter.RoutingDecision vipDec = PrimaryActionRouter.route(
                vipQ, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(vipDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<FanpageBotResponse.ReplyBubble> vipBubbles = PrimaryActionRouter.executeActionBubbles(
                vipDec, vipQ, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        String vipReply = String.join("\n", vipBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(vipReply.toLowerCase(Locale.ROOT))
                .contains("riêng tư hơn")
                .contains("hạn chế di chuyển")
                .doesNotContain("dạ dạ");

        // 3. Compound Multi-Entity FAQ: Menu items + Room type in one turn
        String compoundQ = "Buffet có món gì và gói cơ bản là phòng riêng hay phòng chung?";
        PrimaryActionRouter.RoutingDecision compoundDec = PrimaryActionRouter.route(
                compoundQ, state, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(compoundDec.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<FanpageBotResponse.ReplyBubble> compoundBubbles = PrimaryActionRouter.executeActionBubbles(
                compoundDec, compoundQ, state, "mình", nangSenPersona, realHtmlChunks, null
        );
        assertThat(compoundBubbles).hasSize(2);
        String compoundReply = String.join("\n", compoundBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(compoundReply.toLowerCase(Locale.ROOT))
                .contains("bánh mì bò sốt vang")
                .contains("phòng cộng đồng có rèm riêng");

        // 4. Hydrate state preservation after FAQ turns: recommendationCount and heldPackage remain unchanged
        history.add(new FanpageBotRequest.ConversationTurn("user", "Gói cơ bản là phòng riêng hay phòng chung?"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ với gói massage cơ bản (60 phút), không gian phục vụ là phòng cộng đồng có rèm riêng tư đảm bảo yên tĩnh và kín đáo cho mình ạ."));
        ConversationState stateAfterFaq = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-scenario-012-after", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(stateAfterFaq.recommendationCount()).isEqualTo(recCountBeforeFaq);
        assertThat(stateAfterFaq.heldPackage()).isEqualTo(heldPkgBeforeFaq);
        assertThat(stateAfterFaq.lastBotAction()).isEqualTo("SERVICE_DETAIL_AND_FAQ_QA");
    }

    @Test
    @DisplayName("BT-SPA-013-V324-SCENARIO-A-THREE-STAGE-RETENTION-FLOW: Full 5-turn flow (Early Price -> 339k Quote + Anchor -> Stage 1 Hold & Trial -> Stage 2 Hesitation Clarify + 239k Downsell -> Stage 3 Graceful Exit)")
    void scenarioA_V324ThreeStageRetentionAndEmotionalAnchorFlow() {
        String senderId = "user-v324-scenario-a";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Turn 1: "Giá gội đầu dưỡng sinh bao nhiêu?" -> asks 1 clarifying question, 0 price quoted, FAST_NO_THINK
        String msg1 = "Giá gội đầu dưỡng sinh bao nhiêu?";
        ConversationState state1 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision dec1 = PrimaryActionRouter.route(
                msg1, state1, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        ThinkingModePolicy.ThinkingDecision think1 = ThinkingModePolicy.decide(
                msg1, state1, dec1, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(think1.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        List<FanpageBotResponse.ReplyBubble> bubbles1 = PrimaryActionRouter.executeActionBubbles(
                dec1, msg1, state1, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult val1 = ReplyContractValidator.validateAndEnforce(
                bubbles1, state1, msg1, dec1.effectiveResponseAction(), dec1.openingRequired(), 10000
        );
        assertThat(val1.combinedText()).doesNotContain("339k", "239k").contains("?");
        ConversationState.updateAfterSend(NANG_SEN_PAGE_ID, senderId, state1, msg1, dec1, val1.validatedBubbles(), false);
        history.add(new FanpageBotRequest.ConversationTurn("user", msg1));
        val1.validatedBubbles().forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));

        // Turn 2: "Tôi hay đau đầu căng thẳng, muốn gói 80 phút" -> Bubble 1 quotes 339k (DS03 Dưỡng sinh thư giãn 80 phút), Bubble 2 anchors emotion & value without repeating price or pushing hard booking close
        String msg2 = "Tôi hay đau đầu căng thẳng, muốn gói 80 phút";
        ConversationState state2 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision dec2 = PrimaryActionRouter.route(
                msg2, state2, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        ThinkingModePolicy.ThinkingDecision think2 = ThinkingModePolicy.decide(
                msg2, state2, dec2, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(think2.thinkingMode()).isEqualTo(ThinkingMode.PRICE_ANCHOR_THINK);
        List<FanpageBotResponse.ReplyBubble> bubbles2 = PrimaryActionRouter.executeActionBubbles(
                dec2, msg2, state2, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult val2 = ReplyContractValidator.validateAndEnforce(
                bubbles2, state2, msg2, dec2.effectiveResponseAction(), dec2.openingRequired(), 10000, ThinkingMode.PRICE_ANCHOR_THINK, 0L
        );
        assertThat(val2.validatedBubbles()).hasSize(2);
        assertThat(val2.validatedBubbles().get(0).text()).contains("339k").contains("80 phút");
        assertThat(val2.validatedBubbles().get(1).text())
                .doesNotContain("339k")
                .doesNotContainIgnoringCase("hôm nay hay ngày mai")
                .doesNotContainIgnoringCase("giữ lịch");
        assertThat(val2.validatedBubbles().get(1).typingDelayMs()).isGreaterThanOrEqualTo(10000);
        ConversationState post2 = ConversationState.updateAfterSend(
                NANG_SEN_PAGE_ID, senderId, state2, msg2, dec2, val2.validatedBubbles(), false
        );
        assertThat(post2.lastPriceOffered()).isNotNull();
        history.add(new FanpageBotRequest.ConversationTurn("user", msg2));
        val2.validatedBubbles().forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));

        // Turn 3 (Stage 1 / Nhịp 1): "339k hơi cao nhỉ" -> holds 339k, empathizes, reinforces KB value, invites 1-session trial with 🥰, no auto-downsell
        String msg3 = "339k hơi cao nhỉ";
        ConversationState state3 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision dec3 = PrimaryActionRouter.route(
                msg3, state3, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(dec3.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME);
        ThinkingModePolicy.ThinkingDecision think3 = ThinkingModePolicy.decide(
                msg3, state3, dec3, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(think3.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        List<FanpageBotResponse.ReplyBubble> bubbles3 = PrimaryActionRouter.executeActionBubbles(
                dec3, msg3, state3, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult val3 = ReplyContractValidator.validateAndEnforce(
                bubbles3, state3, msg3, dec3.effectiveResponseAction(), dec3.openingRequired(), 10000, ThinkingMode.OBJECTION_REFRAME_THINK, 0L
        );
        assertThat(val3.combinedText())
                .contains("trải nghiệm thử 1 buổi")
                .contains("🥰")
                .doesNotContain("239k")
                .doesNotContain("129k");
        ConversationState post3 = ConversationState.updateAfterSend(
                NANG_SEN_PAGE_ID, senderId, state3, msg3, dec3, val3.validatedBubbles(), false
        );
        assertThat(post3.downsellOffered()).isFalse();
        assertThat(post3.retentionAttemptCount()).isEqualTo(0);
        history.add(new FanpageBotRequest.ConversationTurn("user", msg3));
        val3.validatedBubbles().forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));

        // Turn 4 (Stage 2 / Nhịp 2): "Để tôi cân nhắc thêm" -> respects consideration + asks 1 gentle clarifying question in Bubble 1, then introduces verified 239k package in Bubble 2
        String msg4 = "Để tôi cân nhắc thêm";
        ConversationState state4 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision dec4 = PrimaryActionRouter.route(
                msg4, state4, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(dec4.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT);
        ThinkingModePolicy.ThinkingDecision think4 = ThinkingModePolicy.decide(
                msg4, state4, dec4, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(think4.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        List<FanpageBotResponse.ReplyBubble> bubbles4 = PrimaryActionRouter.executeActionBubbles(
                dec4, msg4, state4, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult val4 = ReplyContractValidator.validateAndEnforce(
                bubbles4, state4, msg4, dec4.effectiveResponseAction(), dec4.openingRequired(), 10000, ThinkingMode.OBJECTION_REFRAME_THINK, 0L
        );
        assertThat(val4.validatedBubbles()).hasSize(2);
        assertThat(val4.validatedBubbles().get(0).text().toLowerCase(Locale.ROOT))
                .contains("cân nhắc")
                .contains("?");
        assertThat(val4.validatedBubbles().get(1).text().toLowerCase(Locale.ROOT))
                .contains("239k")
                .contains("dưỡng sinh cơ bản")
                .contains("60 phút")
                .contains("rửa mặt")
                .contains("gội thảo dược")
                .doesNotContain("?");
        assertThat(val4.validatedBubbles().get(1).typingDelayMs()).isGreaterThanOrEqualTo(10000);
        ConversationState post4 = ConversationState.updateAfterSend(
                NANG_SEN_PAGE_ID, senderId, state4, msg4, dec4, val4.validatedBubbles(), false
        );
        assertThat(post4.downsellOffered()).isTrue();
        assertThat(post4.retentionAttemptCount()).isEqualTo(1);
        history.add(new FanpageBotRequest.ConversationTurn("user", msg4));
        val4.validatedBubbles().forEach(b -> history.add(new FanpageBotRequest.ConversationTurn("assistant", b.text())));

        // Turn 5 (Stage 3 / Nhịp 3): "Thôi để lúc khác vậy" -> stops persuading, 0 questions, 0 new package pitch, warm closing
        String msg5 = "Thôi để lúc khác vậy";
        ConversationState state5 = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, senderId, history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        assertThat(state5.downsellOffered()).isTrue();
        PrimaryActionRouter.RoutingDecision dec5 = PrimaryActionRouter.route(
                msg5, state5, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(dec5.detectedIntent()).isEqualTo("STAGE_3_GRACEFUL_EXIT");
        ThinkingModePolicy.ThinkingDecision think5 = ThinkingModePolicy.decide(
                msg5, state5, dec5, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(think5.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        List<FanpageBotResponse.ReplyBubble> bubbles5 = PrimaryActionRouter.executeActionBubbles(
                dec5, msg5, state5, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult val5 = ReplyContractValidator.validateAndEnforce(
                bubbles5, state5, msg5, dec5.effectiveResponseAction(), dec5.openingRequired(), 10000, ThinkingMode.FAST_NO_THINK, 0L
        );
        assertThat(val5.validatedBubbles()).hasSize(1);
        assertThat(val5.combinedText())
                .doesNotContain("?")
                .doesNotContain("129k")
                .doesNotContain("239k")
                .doesNotContain("339k");
    }

    @Test
    @DisplayName("BT-SPA-014-V324-SCENARIOS-B-C-D: External price comparison, Repair-First on unanswered steps question, and SAFE-NEURO-001 radiating numbness")
    void scenariosB_C_D_V324ExternalComparisonRepairFirstAndSafeNeuro001() {
        // Scenario B: "Sao bên mình cao hơn tiệm gội đầu gần nhà chị thế?" -> OBJECTION_REFRAME_THINK, full-service + no-tip + trial invite
        List<FanpageBotRequest.ConversationTurn> historyB = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá gội đầu dưỡng sinh 80 phút"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói dưỡng sinh thư giãn 80 phút bên em có giá 339k, giá trọn gói không nhận Tip và có ăn nhẹ miễn phí ạ.")
        );
        ConversationState stateB = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-v324-b", historyB,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        String msgB = "Sao bên mình cao hơn tiệm gội đầu gần nhà chị thế?";
        PrimaryActionRouter.RoutingDecision decB = PrimaryActionRouter.route(
                msgB, stateB, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        ThinkingModePolicy.ThinkingDecision thinkB = ThinkingModePolicy.decide(
                msgB, stateB, decB, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(thinkB.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(thinkB.objectionRootCause()).isEqualTo(ThinkingModePolicy.ObjectionRootCause.EXTERNAL_COMPARISON);
        List<FanpageBotResponse.ReplyBubble> bubblesB = PrimaryActionRouter.executeActionBubbles(
                decB, msgB, stateB, "chị", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult valB = ReplyContractValidator.validateAndEnforce(
                bubblesB, stateB, msgB, decB.effectiveResponseAction(), decB.openingRequired(), 10000, ThinkingMode.OBJECTION_REFRAME_THINK, 0L
        );
        assertThat(valB.combinedText().toLowerCase(Locale.ROOT))
                .contains("trải nghiệm thử 1 buổi")
                .contains("🥰")
                .doesNotContain("239k");

        // Scenario C: "Tôi đang hỏi gói 339k gồm những bước gì cơ mà" after an off-topic bot reply -> REPAIR_FIRST, answers 339k steps from KB, 0 booking push
        List<FanpageBotRequest.ConversationTurn> historyC = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Gói 339k gồm những gì?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình muốn đặt lịch khung giờ nào để em giữ chỗ cho mình ạ?")
        );
        String msgC = "Tôi đang hỏi gói 339k gồm những bước gì cơ mà";
        ConversationState stateC = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-v324-c", historyC,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        PrimaryActionRouter.RoutingDecision decC = PrimaryActionRouter.route(
                msgC, stateC, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertThat(decC.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        ThinkingModePolicy.ThinkingDecision thinkC = ThinkingModePolicy.decide(
                msgC, stateC, decC, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        );
        assertThat(thinkC.thinkingMode()).isEqualTo(ThinkingMode.REPAIR_FIRST);
        List<FanpageBotResponse.ReplyBubble> bubblesC = PrimaryActionRouter.executeActionBubbles(
                decC, msgC, stateC, "mình", nangSenPersona, realHtmlChunks, null
        );
        ReplyContractValidator.ValidationResult valC = ReplyContractValidator.validateAndEnforce(
                bubblesC, stateC, msgC, decC.effectiveResponseAction(), decC.openingRequired(), 10000, ThinkingMode.REPAIR_FIRST, 0L
        );
        assertThat(valC.combinedText().toLowerCase(Locale.ROOT))
                .contains("dưỡng sinh thư giãn")
                .contains("80 phút")
                .contains("ngâm chân thảo dược")
                .contains("massage cổ vai gáy")
                .contains("gội thảo dược")
                .doesNotContain("giữ lịch")
                .doesNotContain("hôm nay hay ngày mai");

        // Scenario D (SAFE-NEURO-001): "Tôi bị đau cổ vai gáy kèm tê lan xuống cánh tay và bàn tay", "tê chân", "chèn ép rễ thần kinh" -> immediate medical safety handoff, FAST_NO_THINK, 0 questions
        String msgD = "Tôi bị đau cổ vai gáy kèm tê lan xuống cánh tay và bàn tay";
        assertThat(SentimentIntentLayer.isNeurologicalRiskMessage(msgD)).isTrue();
        assertThat(SentimentIntentLayer.isNeurologicalRiskMessage("Đau lưng kèm tê chân nhiều ngày")).isTrue();
        assertThat(SentimentIntentLayer.isNeurologicalRiskMessage("Bác sĩ bảo mình bị chèn ép rễ thần kinh cổ")).isTrue();
        assertThat(SentimentIntentLayer.isNeurologicalRiskMessage("Mình bị tê tay lan xuống ngón tay")).isTrue();
        ConversationState stateD = ConversationState.hydrate(
                NANG_SEN_PAGE_ID, "user-v324-d", List.of(),
                nangSenPersona.resolvedConfig().safeDomainKeywords(), realHtmlChunks
        );
        for (String neuroInput : List.of(
                msgD,
                "Đau lưng kèm tê chân nhiều ngày",
                "Bác sĩ bảo mình bị chèn ép rễ thần kinh cổ"
        )) {
            PrimaryActionRouter.RoutingDecision decD = PrimaryActionRouter.route(
                    neuroInput, stateD, realHtmlChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
            );
            assertThat(decD.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY);
            assertThat(decD.detectedIntent()).isEqualTo("NEUROLOGICAL_RISK");
            assertThat(decD.correspondingMicroSkillCode()).isEqualTo("safety_handoff_high_risk");
            ThinkingModePolicy.ThinkingDecision thinkD = ThinkingModePolicy.decide(
                    neuroInput, stateD, decD, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
            );
            assertThat(thinkD.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
            List<FanpageBotResponse.ReplyBubble> bubblesD = PrimaryActionRouter.executeActionBubbles(
                    decD, neuroInput, stateD, "mình", nangSenPersona, realHtmlChunks, null
            );
            ReplyContractValidator.ValidationResult valD = ReplyContractValidator.validateAndEnforce(
                    bubblesD, stateD, neuroInput, decD.effectiveResponseAction(), decD.openingRequired(), 10000, ThinkingMode.FAST_NO_THINK, 0L
            );
            assertThat(valD.combinedText().toLowerCase(Locale.ROOT))
                    .containsAnyOf("bác sĩ", "y tế", "thăm khám")
                    .contains("chuyên viên")
                    .doesNotContain("339k")
                    .doesNotContain("399k")
                    .doesNotContain("?");
        }
    }
}
