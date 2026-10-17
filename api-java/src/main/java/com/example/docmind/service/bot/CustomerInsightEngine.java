package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bộ máy Đọc vị Tâm lý & Insight Khách hàng Thời gian thực (CustomerInsightEngine)
 * kết hợp Dynamic Skill Router chọn Top 3 Kỹ năng Agent sát nhất cho từng lượt hội thoại.
 *
 * <p>Hoạt động hoàn toàn tất định với độ trễ &lt; 1ms (không phát sinh lượt gọi LLM phụ),
 * giúp nâng cấp phản hồi của Bot từ "nhân viên đọc bảng giá" thành "Chuyên gia Tư vấn & Chốt Sale thực thụ".
 */
@Component
public class CustomerInsightEngine {

    private static final Pattern CHRONIC_PAIN_PATTERN = Pattern.compile(
            "(?iU)(\\b\\d+\\s*năm\\b|lâu\\s+năm|nhiều\\s+năm|dậy\\s+thì|mãn\\s+tính|"
            + "chữa\\s+nhiều\\s+nơi|điều\\s+trị\\s+nhiều\\s+nơi|không\\s+khỏi|tái\\s+đi\\s+tái\\s+lại|"
            + "sẹo\\s+rỗ|sẹo\\s+lõm|rỗ\\s+lâu\\s+năm|nám\\s+chân\\s+sâu|nám\\s+lâu\\s+năm)"
    );

    private static final Pattern PRICE_OBJECTION_PATTERN = Pattern.compile(
            "(?iU)\\b(giá|bao\\s+nhiêu|chi\\s+phí|đắt|cao\\s+thế|cao\\s+hơn|rẻ\\s+hơn|"
            + "chiết\\s+khấu|khuyến\\s+mãi|combo|ưu\\s+đãi|phụ\\s+phí|phát\\s+sinh|tiền\\s+tip|tip)\\b"
    );

    private static final Pattern MALE_OR_DISCREET_PATTERN = Pattern.compile(
            "(?iU)(\\bnam\\s+giới\\b|\\bkhách\\s+nam\\b|\\bđàn\\s+ông\\b|\\bcon\\s+trai\\b|"
            + "\\bnam\\s+(?:dùng|sử\\s+dụng|xài|làm|đi|trị)\\b|\\bda\\s+nam\\b|"
            + "(?:mình|mìn|minh|mk|tôi|t)\\s+(?:là|la|làm|lam)?\\s*nam\\b|"
            + "\\btrung\\s+niên\\b|\\blớn\\s+tuổi\\b|\\bphòng\\s+riêng\\b|\\bphòng\\s+vip\\b|\\bkín\\s+đáo\\b|\\b2\\s+vợ\\s+chồng\\b|\\bcặp\\s+đôi\\b)"
    );

    private static final Pattern FEAR_OR_RISK_PATTERN = Pattern.compile(
            "(?iU)\\b(sợ\\s+đau|có\\s+đau\\s+không|sưng|đỏ|nghỉ\\s+dưỡng|bảo\\s+hành|cam\\s+kết|"
            + "tái\\s+phát|an\\s+toàn|chèo\\s+kéo|ép\\s+mua|phụ\\s+phí|tiền\\s+tip|khám\\s+thử|mất\\s+phí)\\b"
    );

    private static final Pattern TIME_TO_RESULT_PATTERN = Pattern.compile(
            "(?iU)(bao\\s+lâu|mấy\\s+buổi|khi\\s+nào|thời\\s+gian).*(hiệu\\s+quả|hiệu\\s+quẩ|hết|đỡ|cải\\s+thiện|khỏi)|bao\\s+lâu\\s+thấy"
    );

    private static final Pattern BOOKING_INTENT_PATTERN = Pattern.compile(
            "(?iU)\\b(đặt\\s+lịch|giữ\\s+lịch|giữ\\s+chỗ|đăng\\s+ký|chốt|qua\\s+soi\\s+da|qua\\s+khám|"
            + "sđt|số\\s+điện\\s+thoại|chiều\\s+thứ|sáng\\s+thứ|cuối\\s+tuần\\s+này)\\b"
    );

    private static final List<AgentSkillDto> BUILT_IN_SKILLS = createBuiltInSkills();
    private static final Set<String> BUILT_IN_CODES = Set.of(
            "PSYCH_CHRONIC_EMPATHY",
            "PSYCH_ZERO_RISK_REVERSAL",
            "PSYCH_DISCREET_VIP_CARE",
            "SALES_SPIN_DIAGNOSIS",
            "SALES_VALUE_FRAMING_PRICE",
            "SALES_SOCIAL_PROOF_REASSURANCE",
            "SALES_MICRO_COMMITMENT_CLOSE",
            "COMM_MIRROR_AND_PACE",
            "COMM_NO_REPEAT_MEMORY",
            "SALES_CROSS_INDUSTRY_CONSULT"
    );

    public static List<AgentSkillDto> builtInDefaultSkills() {
        return BUILT_IN_SKILLS;
    }

    /**
     * Phân tích toàn diện tâm lý khách hàng, nỗi đau tích lũy qua lịch sử chat, rào cản ngầm và nhiệt độ mua hàng.
     */
    public CustomerInsightDto analyzeInsight(
            String currentMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            String resolvedHonorific,
            FanpageBotPromptBuilder.SalesStage salesStage,
            FanpageBotResponse.ExtractedLead extractedLead) {

        String honorific = (resolvedHonorific != null && !resolvedHonorific.isBlank())
                ? resolvedHonorific.strip()
                : "Chị";
        String msg = currentMessage != null ? currentMessage.strip() : "";
        String lowerMsg = msg.toLowerCase(Locale.ROOT);

        List<String> allUserMessages = new ArrayList<>();
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn turn : history) {
                if (turn != null && !"assistant".equalsIgnoreCase(turn.role())
                        && turn.content() != null && !turn.content().isBlank()) {
                    allUserMessages.add(turn.content().strip());
                }
            }
        }
        if (!msg.isBlank()) {
            allUserMessages.add(msg);
        }
        String combinedUserText = String.join(" . ", allUserMessages);
        String lowerCombined = combinedUserText.toLowerCase(Locale.ROOT);

        // 1. Trích xuất Nỗi đau & Dữ kiện khách hàng đã chia sẻ xuyên suốt các lượt (Memory Facts)
        Set<String> pains = new LinkedHashSet<>();
        if (CHRONIC_PAIN_PATTERN.matcher(combinedUserText).find()) {
            pains.add("Tình trạng kéo dài lâu năm (mụn/nám mãn tính cần đánh giá chuyên sâu)");
        }
        if (lowerCombined.contains("sẹo rỗ") || lowerCombined.contains("sẹo lõm")) {
            pains.add("Kèm sẹo rỗ tổn thương cấu trúc da (cần Bác sĩ soi da AI 3D trực tiếp)");
        }
        if (lowerCombined.contains("mụn") || lowerCombined.contains("da dầu") || lowerCombined.contains("viêm")) {
            pains.add("Đang gặp tình trạng mụn / viêm / nền da dầu");
        }
        if (lowerCombined.contains("nám") || lowerCombined.contains("tàn nhang") || lowerCombined.contains("sạm") || lowerCombined.contains("đốm nâu")) {
            pains.add("Quan tâm xử lý sắc tố nám / tàn nhang");
        }
        if (lowerCombined.contains("trẻ hóa") || lowerCombined.contains("căng bóng") || lowerCombined.contains("lão hóa")
                || lowerCombined.contains("nhăn") || lowerCombined.contains("da khô") || lowerCombined.contains("meso")) {
            pains.add("Quan tâm phục hồi, căng bóng & chống lão hóa da");
        }
        if (lowerCombined.contains("gội đầu") || lowerCombined.contains("cổ vai gáy") || lowerCombined.contains("thư giãn") || lowerCombined.contains("mỏi")) {
            pains.add("Nhu cầu thư giãn dưỡng sinh & giảm đau mỏi cổ vai gáy");
        }
        if (lowerCombined.contains("khóa học") || lowerCombined.contains("lộ trình học") || lowerCombined.contains("học viên")) {
            pains.add("Quan tâm lộ trình khóa học & cam kết chất lượng đầu ra");
        }
        if (lowerCombined.contains("phần mềm") || lowerCombined.contains("triển khai") || lowerCombined.contains("doanh nghiệp") || lowerCombined.contains("sla")) {
            pains.add("Nhu cầu triển khai giải pháp B2B / Doanh nghiệp & cam kết SLA");
        }
        if (lowerCombined.contains("đổi trả") || lowerCombined.contains("giao hàng") || lowerCombined.contains("vận chuyển") || lowerCombined.contains("chính hãng")) {
            pains.add("Quan tâm nguồn gốc chính hãng, vận chuyển & chính sách đổi trả");
        }
        if ("Anh".equalsIgnoreCase(honorific) || MALE_OR_DISCREET_PATTERN.matcher(combinedUserText).find()) {
            if (lowerCombined.contains("trung niên") || lowerCombined.contains("lớn tuổi")) {
                pains.add("Khách hàng Nam giới / độ tuổi trung niên (đề cao sự riêng tư, khoa học)");
            } else if ("Anh".equalsIgnoreCase(honorific)) {
                pains.add("Khách hàng Nam giới (cần tư vấn rõ tính phù hợp & sự kín đáo)");
            } else {
                pains.add("Quan tâm không gian phòng riêng VIP kín đáo");
            }
        }
        if (TIME_TO_RESULT_PATTERN.matcher(combinedUserText).find()) {
            pains.add("Đang hỏi mốc thời gian & số buổi thấy rõ hiệu quả");
        }
        if (PRICE_OBJECTION_PATTERN.matcher(combinedUserText).find()) {
            pains.add("Quan tâm bảng giá niêm yết, quyền lợi đi kèm & mức giảm Combo");
        }
        if (extractedLead != null && extractedLead.hasBuyingOrBookingSignal()) {
            if (extractedLead.phoneNumber() != null) {
                pains.add("Đã gửi SĐT liên hệ (" + extractedLead.phoneNumber() + ") cần chuyên viên tư vấn thêm");
            } else {
                pains.add("Đã bộc lộ ý định đặt lịch hẹn thăm khám / trải nghiệm");
            }
        }
        if (pains.isEmpty() && !msg.isBlank()) {
            String snippet = msg.length() > 65 ? msg.substring(0, 65) + "..." : msg;
            pains.add("Nhu cầu khách đang hỏi: \"" + snippet + "\"");
        }

        // 2. Nhận diện Rào cản tâm lý & Nỗi sợ ngầm (Hidden Concerns)
        Set<String> concerns = new LinkedHashSet<>();
        if (CHRONIC_PAIN_PATTERN.matcher(combinedUserText).find()) {
            concerns.add("Tâm lý đa nghi do bị lâu năm; sợ quảng cáo quá đà hoặc mua gói mà không cải thiện thực sự");
        }
        if (lowerCombined.contains("đắt") || lowerCombined.contains("giá cao") || lowerCombined.contains("phụ phí") || lowerCombined.contains("tip")) {
            concerns.add("Sợ giá cao hơn mặt bằng chung, lo phát sinh chi phí ẩn hoặc văn hóa vòi tiền Tip");
        } else if (PRICE_OBJECTION_PATTERN.matcher(combinedUserText).find()) {
            concerns.add("Cân nhắc hiệu quả nhận được có xứng đáng với mức chi phí đầu tư hay không");
        }
        if ("Anh".equalsIgnoreCase(honorific) || MALE_OR_DISCREET_PATTERN.matcher(combinedUserText).find()) {
            concerns.add("E ngại môi trường Spa đông khách nữ, cần sự tôn trọng, nhanh gọn và phòng riêng kín đáo");
        }
        if (TIME_TO_RESULT_PATTERN.matcher(combinedUserText).find()) {
            concerns.add("Sợ liệu trình kéo dài không hồi kết mà không biết rõ lộ trình mấy buổi thì cải thiện");
        }
        if (FEAR_OR_RISK_PATTERN.matcher(combinedUserText).find()) {
            concerns.add("Lo lắng rủi ro đau rát, thời gian nghỉ dưỡng hoặc nguy cơ tái phát sau khi làm");
        }
        if (concerns.isEmpty()) {
            concerns.add("Muốn kiểm chứng sự tận tâm và chuyên môn thực tế của Page trước khi quyết định");
        }

        // 3. Phân loại Chân dung Tâm lý Khách hàng (BuyerArchetype) ở lượt hiện tại
        boolean hasBookingLead = extractedLead != null && extractedLead.hasBuyingOrBookingSignal();
        boolean currentIsChronic = CHRONIC_PAIN_PATTERN.matcher(msg).find();
        boolean historyIsChronic = CHRONIC_PAIN_PATTERN.matcher(combinedUserText).find();
        boolean currentIsMaleOrDiscreet = MALE_OR_DISCREetCheck(msg, honorific, allUserMessages.size());
        boolean currentIsPrice = PRICE_OBJECTION_PATTERN.matcher(msg).find() && !hasBookingLead;
        boolean currentIsFearOrTime = FEAR_OR_RISK_PATTERN.matcher(msg).find() || TIME_TO_RESULT_PATTERN.matcher(msg).find();

        String archetype;
        String archetypeLabel;

        if (hasBookingLead || BOOKING_INTENT_PATTERN.matcher(msg).find()) {
            archetype = "READY_TO_BOOK";
            archetypeLabel = "Sẵn sàng Chốt lịch / Đã để lại Tín hiệu Liên hệ";
        } else if (currentIsChronic) {
            archetype = "SKEPTICAL_CHRONIC";
            archetypeLabel = "Đau khổ lâu năm / Đa nghi — Cần sự thật & An tâm Y khoa";
        } else if (currentIsMaleOrDiscreet) {
            archetype = "DISCREET_DECISIVE";
            archetypeLabel = "Khách Nam / Trung niên — Cần Riêng tư, Khoa học & Tinh tế";
        } else if (currentIsPrice) {
            archetype = "PRICE_CONSCIOUS";
            archetypeLabel = "Cân nhắc Ngân sách & Giá trị — Cần Neo quyền lợi & Cam kết 0 phụ phí";
        } else if (currentIsFearOrTime) {
            if (historyIsChronic) {
                archetype = "SKEPTICAL_CHRONIC";
                archetypeLabel = "Đau khổ lâu năm / Đa nghi — Cần lộ trình trung thực & An tâm Y khoa";
            } else {
                archetype = "HESITANT_FIRST_TIMER";
                archetypeLabel = "Khách mới Cẩn trọng — Cần xóa bỏ nỗi sợ rủi ro & Bảo hành rõ ràng";
            }
        } else if (historyIsChronic) {
            archetype = "SKEPTICAL_CHRONIC";
            archetypeLabel = "Đau khổ lâu năm / Đa nghi — Cần sự thật & An tâm Y khoa";
        } else {
            archetype = "EXPLORING_CONSULTATION";
            archetypeLabel = "Đang tìm hiểu Giải pháp — Cần Chẩn đoán Chuyên gia (SPIN)";
        }

        // 4. Tính điểm Nhiệt độ Mua hàng (Buying Temperature 0 - 100)
        int temp = 36;
        int userTurns = allUserMessages.size();
        temp += Math.min(24, (userTurns - 1) * 10);
        if (historyIsChronic || pains.size() >= 2) {
            temp += 12;
        }
        if (PRICE_OBJECTION_PATTERN.matcher(combinedUserText).find()) {
            temp += 10;
        }
        if (TIME_TO_RESULT_PATTERN.matcher(combinedUserText).find()) {
            temp += 10;
        }
        if (lowerMsg.contains("đắt") || lowerMsg.contains("cao hơn")) {
            temp -= 6;
        }
        if (salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE) {
            temp = Math.max(temp, 72);
        }
        if (hasBookingLead) {
            temp = (extractedLead.phoneNumber() != null && !extractedLead.phoneNumber().isBlank()) ? 95 : 85;
        }
        temp = Math.max(20, Math.min(98, temp));

        String tempLabel;
        if (temp >= 75) {
            tempLabel = "HOT";
        } else if (temp >= 48) {
            tempLabel = "WARM";
        } else {
            tempLabel = "COLD";
        }

        // 5. Khuyến nghị Chiến thuật Tư vấn Tức thời (Recommended Strategy)
        boolean asksPriceInCurrentTurn = PRICE_OBJECTION_PATTERN.matcher(msg).find();
        String recommendedStrategy = switch (archetype) {
            case "READY_TO_BOOK" -> asksPriceInCurrentTurn
                    ? "Báo giá ngắn gọn, chuẩn xác gói dịch vụ " + honorific + " vừa hỏi theo đúng dữ liệu tài liệu, đồng thời xác nhận đã ghi nhận thông tin SĐT / lịch hẹn để chuyên viên liên hệ hỗ trợ " + honorific + " ngay."
                    : "Xác nhận ngay thông tin SĐT / lịch hẹn của " + honorific + " thật ngắn gọn, ấm áp; báo chuyên viên liên hệ qua điện thoại/Zalo ngay và TUYỆT ĐỐI KHÔNG liệt kê lại bảng giá hay hỏi lại tình trạng.";
            case "SKEPTICAL_CHRONIC" ->
                    "Đồng cảm sâu sắc với hành trình lâu năm của " + honorific + "; phân tách trung thực phạm vi cải thiện của gói tiêu chuẩn và phần cần Bác sĩ soi da AI 3D 0đ đánh giá riêng (tuyệt đối không hứa lố).";
            case "DISCREET_DECISIVE" ->
                    "Khẳng định rõ dịch vụ hoàn toàn phù hợp và hiệu quả cao cho khách hàng Nam giới / Trung niên; giữ giọng văn điềm đạm, tôn trọng, nhấn mạnh không gian kín đáo và trả lời thẳng trọng tâm cho " + honorific + ".";
            case "PRICE_CONSCIOUS" ->
                    "Áp dụng Value Stacking: Nêu rõ quyền lợi Bác sĩ chuyên khoa + Bảo hành văn bản + Cam kết trọn gói 0 phụ phí/0 Tip trước khi báo giá; gợi mở bài toán tiết kiệm 10-15% với gói Combo.";
            case "HESITANT_FIRST_TIMER" ->
                    "Đưa rủi ro của " + honorific + " về 0% (Zero-Risk Reversal) bằng cam kết bảo hành, quy trình êm dịu và đề xuất buổi soi da AI 3D miễn phí 100% cùng Bác sĩ trước khi quyết định.";
            default ->
                    "Giải đáp ngắn gọn đúng trọng tâm câu hỏi của " + honorific + " và đặt đúng 1 câu hỏi phân loại chuyên môn nhẹ nhàng (SPIN) để dẫn dắt mạch tư vấn tự nhiên.";
        };

        return new CustomerInsightDto(
                archetype,
                archetypeLabel,
                temp,
                tempLabel,
                honorific,
                new ArrayList<>(pains),
                new ArrayList<>(concerns),
                recommendedStrategy
        );
    }

    private static boolean MALE_OR_DISCREetCheck(String currentMsg, String honorific, int turnCount) {
        if (currentMsg != null && MALE_OR_DISCREET_PATTERN.matcher(currentMsg).find()) {
            return true;
        }
        return "Anh".equalsIgnoreCase(honorific) && turnCount <= 1;
    }

    /**
     * Dynamic Skill Router: Chấm điểm và chọn ra Top 3 Kỹ năng Agent phù hợp nhất với tâm lý và giai đoạn của khách.
     * Đảm bảo kỹ năng tùy chỉnh / theo Page được kích hoạt ngay khi khớp tín hiệu và không để 1 nhóm đơn lẻ độc chiếm cả 3 vị trí.
     */
    public List<AgentSkillDto> selectActiveSkills(
            CustomerInsightDto insight,
            String currentMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            FanpageBotPromptBuilder.SalesStage salesStage,
            List<AgentSkillDto> availableSkills) {

        List<AgentSkillDto> pool = (availableSkills != null && !availableSkills.isEmpty())
                ? availableSkills.stream().filter(AgentSkillDto::active).toList()
                : BUILT_IN_SKILLS;

        if (pool.isEmpty()) {
            return List.of();
        }

        String msg = currentMessage != null ? currentMessage.toLowerCase(Locale.ROOT) : "";
        StringBuilder histBuilder = new StringBuilder(msg);
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn t : history) {
                if (t != null && t.content() != null) {
                    histBuilder.append(' ').append(t.content().toLowerCase(Locale.ROOT));
                }
            }
        }
        String fullConvo = histBuilder.toString();
        boolean isMultiTurn = history != null && !history.isEmpty();
        String archetype = insight != null && insight.buyerArchetype() != null ? insight.buyerArchetype() : "EXPLORING_CONSULTATION";
        String honorific = insight != null && insight.honorific() != null ? insight.honorific() : "Chị";

        record ScoredSkill(AgentSkillDto skill, int score) {}
        List<ScoredSkill> scored = new ArrayList<>(pool.size());

        for (AgentSkillDto skill : pool) {
            int score = skill.priority();
            String code = skill.code() != null ? skill.code().toUpperCase(Locale.ROOT) : "";
            int matchedInCurrentMsg = 0;
            int matchedInHistory = 0;

            // 1. Điểm khớp từ khóa trong trigger_signals
            if (skill.triggerSignals() != null && !skill.triggerSignals().isBlank()) {
                String[] signals = skill.triggerSignals().split("[,;]+");
                for (String rawSig : signals) {
                    String sig = rawSig.strip().toLowerCase(Locale.ROOT);
                    if (sig.length() >= 2) {
                        if (msg.contains(sig)) {
                            score += 45; // Khớp trực tiếp ở tin nhắn hiện tại
                            matchedInCurrentMsg++;
                        } else if (fullConvo.contains(sig)) {
                            score += 18; // Khớp trong lịch sử hội thoại
                            matchedInHistory++;
                        }
                    }
                }
            }

            // 2. Điểm thưởng kích hoạt cho Kỹ năng Tùy chỉnh / Kỹ năng riêng của Page hoặc Kỹ năng học từ Báo sai & Dạy AI
            boolean isCustomSkill = !BUILT_IN_CODES.contains(code);
            boolean isPageScoped = skill.pageId() != null && !skill.pageId().isBlank();
            if (isCustomSkill || isPageScoped) {
                if (matchedInCurrentMsg > 0) {
                    score += 85;
                } else if (matchedInHistory > 0) {
                    score += 40;
                }
                if (isPageScoped && (matchedInCurrentMsg > 0 || matchedInHistory > 0)) {
                    score += 25;
                }
            }

            // 3. Điểm thưởng theo Chân dung Tâm lý (BuyerArchetype) & Giai đoạn Tư vấn
            switch (archetype) {
                case "SKEPTICAL_CHRONIC" -> {
                    if ("PSYCH_CHRONIC_EMPATHY".equals(code)) score += 120;
                    if ("PSYCH_ZERO_RISK_REVERSAL".equals(code)) score += 95;
                    if ("SALES_SOCIAL_PROOF_REASSURANCE".equals(code)) score += 80;
                }
                case "DISCREET_DECISIVE" -> {
                    if ("PSYCH_DISCREET_VIP_CARE".equals(code)) score += 130;
                    if ("COMM_MIRROR_AND_PACE".equals(code)) score += 85;
                    if ("SALES_SOCIAL_PROOF_REASSURANCE".equals(code)) score += 75;
                }
                case "PRICE_CONSCIOUS" -> {
                    if ("SALES_VALUE_FRAMING_PRICE".equals(code)) score += 130;
                    if ("PSYCH_ZERO_RISK_REVERSAL".equals(code)) score += 95;
                    if ("SALES_MICRO_COMMITMENT_CLOSE".equals(code)) score += 70;
                }
                case "HESITANT_FIRST_TIMER" -> {
                    if ("PSYCH_ZERO_RISK_REVERSAL".equals(code)) score += 120;
                    if ("SALES_SOCIAL_PROOF_REASSURANCE".equals(code)) score += 95;
                }
                case "READY_TO_BOOK" -> {
                    if ("SALES_MICRO_COMMITMENT_CLOSE".equals(code)) score += 125;
                    if ("COMM_NO_REPEAT_MEMORY".equals(code)) score += 95;
                }
                default -> {
                    if ("SALES_SPIN_DIAGNOSIS".equals(code)) score += 105;
                    if ("COMM_MIRROR_AND_PACE".equals(code)) score += 80;
                    if ("SALES_CROSS_INDUSTRY_CONSULT".equals(code)) score += 70;
                }
            }

            if ("Anh".equalsIgnoreCase(honorific) && "PSYCH_DISCREET_VIP_CARE".equals(code)) {
                score += 65;
            }
            if (isMultiTurn && "COMM_NO_REPEAT_MEMORY".equals(code)) {
                score += 60;
            }
            if (salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE && "SALES_MICRO_COMMITMENT_CLOSE".equals(code)) {
                score += 55;
            }

            scored.add(new ScoredSkill(skill, score));
        }

        scored.sort(Comparator.comparingInt(ScoredSkill::score).reversed());

        // Chọn Top 3 Kỹ năng nhưng giới hạn tối đa 2 kỹ năng cùng 1 Category (nếu có Category khác khả dụng)
        // để đảm bảo sự cân bằng giữa Tâm lý học (PSYCHOLOGY) - Chiến thuật Sale (SALES_STRATEGY) - Giao tiếp (COMMUNICATION).
        List<AgentSkillDto> selected = new ArrayList<>(3);
        java.util.Map<String, Integer> categoryCount = new java.util.HashMap<>();
        List<AgentSkillDto> deferredSameCategory = new ArrayList<>();

        for (ScoredSkill s : scored) {
            if (selected.size() >= 3) {
                break;
            }
            String cat = s.skill().category() != null ? s.skill().category().toUpperCase(Locale.ROOT) : "SALES_STRATEGY";
            int currentCatCount = categoryCount.getOrDefault(cat, 0);
            if (currentCatCount >= 2) {
                deferredSameCategory.add(s.skill());
                continue;
            }
            selected.add(s.skill());
            categoryCount.put(cat, currentCatCount + 1);
        }

        for (AgentSkillDto fallback : deferredSameCategory) {
            if (selected.size() >= 3) {
                break;
            }
            selected.add(fallback);
        }
        return selected;
    }

    private static List<AgentSkillDto> createBuiltInSkills() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return List.of(
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000001"),
                        "PSYCH_CHRONIC_EMPATHY",
                        "[Tâm lý] Thấu cảm Nỗi đau Kinh niên & Xây dựng Niềm tin",
                        "PSYCHOLOGY",
                        "Dành cho khách hàng gặp vấn đề kéo dài lâu năm (mụn/nám 5-15 năm, sẹo rỗ, tình trạng mãn tính, từng thử nhiều nơi không hiệu quả) đang mang tâm lý đa nghi, mệt mỏi.",
                        "lâu năm, nhiều năm, 5 năm, 10 năm, 15 năm, dậy thì, chữa nhiều nơi, không khỏi, tái phát, sẹo rỗ, nặng, mãn tính, mất niềm tin",
                        "1. Mở đầu bằng sự đồng cảm chân thành với hành trình kiên trì của khách (không phán xét, không dùng lời sáo rỗng). 2. Phân tách minh bạch: chỉ rõ gói/sản phẩm tiêu chuẩn hỗ trợ được phần nào và phần nào cần Chuyên gia / Bác sĩ thăm khám trực tiếp để lên phác đồ cá nhân hóa. 3. Tuyệt đối không hứa hẹn cam kết 100% khỏi ngay lập tức khi chưa đánh giá thực tế.",
                        "Dạ hành trình sống chung với tình trạng này suốt nhiều năm chắc hẳn làm mình rất mệt mỏi và cẩn trọng hơn khi tìm nơi điều trị. Với nền da có kèm sẹo rỗ lâu năm, gói Trị Mụn Chuẩn Y Khoa bên em sẽ giúp làm dịu viêm và làm sạch nhân mụn trước, còn phần sẹo rỗ cần Bác sĩ Da liễu soi da AI 3D trực tiếp để đánh giá độ sâu đáy sẹo rồi mới lên phác đồ chuẩn cho mình ạ.",
                        "Tuyệt đối KHÔNG nổ quá đà rằng gói cơ bản trị sạch được sẹo rỗ/tình trạng lâu năm; KHÔNG thúc ép mua gói lớn ngay khi khách chưa tin tưởng.",
                        95,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000002"),
                        "PSYCH_ZERO_RISK_REVERSAL",
                        "[Tâm lý] Xóa bỏ Rào cản Sợ rủi ro & Sợ bị Chèo kéo (Zero-Risk Reversal)",
                        "PSYCHOLOGY",
                        "Giải tỏa tâm lý phòng vệ của khách hàng mới lần đầu tìm hiểu: sợ đau, sợ phát sinh chi phí ẩn, sợ bị ép mua thêm gói hoặc phải đưa tiền Tip.",
                        "sợ đau, có đau không, sưng, nghỉ dưỡng, phụ phí, phát sinh, tiền tip, chèo kéo, ép mua, khám thử, mất phí không, bảo hành, cam kết, an toàn",
                        "1. Đưa rủi ro tâm lý của khách về 0% bằng các cam kết minh bạch có trong tài liệu (ví dụ: miễn phí soi da/tư vấn ban đầu 100%, báo giá trọn gói không phát sinh phụ phí, văn hóa không nhận tiền Tip, bảo hành bằng văn bản). 2. Nhấn mạnh quyền tự quyết hoàn toàn thuộc về khách sau khi trải nghiệm/thăm khám.",
                        "Dạ bên em cam kết mức giá báo cho mình là trọn gói 100%, hoàn toàn không phát sinh phụ phí ẩn và nhân viên tuyệt đối không nhận tiền Tip ạ. Buổi soi da AI 3D cùng Bác sĩ cũng được miễn phí hoàn toàn để mình nắm rõ tình trạng trước khi quyết định ạ.",
                        "Không trả lời lập lờ về chi phí phụ thu; không né tránh câu hỏi về bảo hành hoặc tác dụng phụ.",
                        90,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000003"),
                        "PSYCH_DISCREET_VIP_CARE",
                        "[Tâm lý] Tâm lý Riêng tư, Kín đáo & Tinh tế (Khách Nam / Trung niên / VIP)",
                        "PSYCHOLOGY",
                        "Phục vụ nhóm khách hàng Nam giới, khách Trung niên hoặc khách đi theo cặp đôi vốn thường ngại ngùng nơi đông người và đề cao sự riêng tư, nhanh gọn, khoa học.",
                        "nam giới, khách nam, đàn ông, con trai, mình là nam, anh muốn, chồng chị, bạn trai, trung niên, lớn tuổi, phòng riêng, kín đáo, cặp đôi, ngại",
                        "1. Khẳng định tự nhiên rằng rất nhiều khách hàng Nam giới / Quý khách hàng dùng dịch vụ/sản phẩm hiệu quả cao. 2. Sử dụng giọng điệu điềm đạm, tôn trọng, tập trung vào tính khoa học, tiết kiệm thời gian và không gian riêng tư kín đáo (như phòng VIP riêng biệt nếu có trong tài liệu). 3. Đồng nhất xưng hô chuẩn mực (Anh hoặc Chị).",
                        "Dạ hoàn toàn phù hợp Anh nhé! Tại bên em có rất nhiều khách hàng nam giới qua chăm sóc và điều trị da vì phác đồ chuẩn Y khoa đi thẳng vào hiệu quả thực tế, không gian lại kín đáo, lịch sự nên Anh hoàn toàn thoải mái khi trải nghiệm ạ.",
                        "Tuyệt đối KHÔNG đọc câu máy móc bên em chuyên phục vụ nữ giới 22-45 tuổi; không dùng từ ngữ quá điệu đà khi nói chuyện với khách Nam.",
                        88,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000004"),
                        "SALES_SPIN_DIAGNOSIS",
                        "[Sale] Chẩn đoán Chuyên gia SPIN & Khơi gợi Nhu cầu ẩn",
                        "SALES_STRATEGY",
                        "Áp dụng ở giai đoạn Mở đầu (DISCOVERY) khi khách mới hỏi thăm chung chung trong mọi ngành hàng (Spa, Khóa học, Mỹ phẩm, Nội thất, B2B).",
                        "tư vấn, có gói nào, dịch vụ gì, sản phẩm nào, bên mình có, muốn tìm hiểu, mới bị, bắt đầu, giới thiệu giúp",
                        "1. Trả lời thẳng vào câu hỏi của khách trong 2-3 câu ngắn gọn, súc tích. 2. Đặt ĐÚNG 1 câu hỏi phân loại chuyên môn nhẹ nhàng (về tình trạng hiện tại, mục tiêu ưu tiên hoặc thời gian mong muốn) để khách cảm nhận đang được Chuyên gia thực thụ chẩn đoán chứ không phải nhân viên đọc kịch bản.",
                        "Dạ bên em hiện có các phác đồ chuyên sâu được cá nhân hóa theo từng nền da ạ. Hiện tại mình đang muốn ưu tiên xử lý dứt điểm tình trạng mụn/nám hay muốn phục hồi trẻ hóa da để em gợi ý giải pháp sát nhất cho mình ạ?",
                        "Không đổ nguyên bảng giá dài dòng ngay câu đầu tiên; không đặt 2-3 câu hỏi dồn dập như tra khảo khách.",
                        86,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000005"),
                        "SALES_VALUE_FRAMING_PRICE",
                        "[Sale] Neo Giá trị & Chia nhỏ Lợi ích khi Khách băn khoăn về Giá (Value Stacking)",
                        "SALES_STRATEGY",
                        "Áp dụng khi khách hỏi giá, so sánh giá với đối thủ giá rẻ, chê đắt hoặc phân vân giữa mua lẻ và mua theo gói/Combo.",
                        "giá sao, bao nhiêu tiền, chi phí, đắt thế, giá cao, chỗ khác rẻ hơn, giảm giá, chiết khấu, combo, ưu đãi, mua lẻ, 1 buổi",
                        "1. Áp dụng công thức Value Stacking: Nêu rõ giá trị chuyên môn, công nghệ chính hãng, thời lượng, quà tặng và chế độ bảo hành TRƯỚC hoặc CÙNG LÚC với con số giá niêm yết. 2. Đặt bài toán so sánh thông minh giữa mua lẻ và ưu đãi theo lộ trình Combo (10% - 15% - 20%) giúp khách thấy rõ lợi ích kinh tế lâu dài mà không cảm thấy bị ép buộc.",
                        "Dạ mức chi phí bên em đi kèm 100% Bác sĩ chuyên khoa trực tiếp thăm khám, dược mỹ phẩm chính hãng và cam kết bảo hành bằng văn bản ạ. Nếu mình đi theo lộ trình Combo Tiết Kiệm (3-4 buổi) sẽ được giảm ngay 10%, hoặc Combo Toàn Diện (5-9 buổi) giảm 15% kèm quà tặng chăm sóc tại nhà nên tối ưu chi phí hơn rất nhiều so với đi lẻ từng buổi ạ.",
                        "Không chỉ cộc lốc quăng một con số giá tiền; không chê bai đối thủ cạnh tranh; không tự ý tính nhẩm sai tổng tiền.",
                        92,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000006"),
                        "SALES_SOCIAL_PROOF_REASSURANCE",
                        "[Sale] Củng cố Niềm tin bằng Thực tế Chuyên môn & Lộ trình Rõ ràng",
                        "SALES_STRATEGY",
                        "Kích hoạt khi khách hỏi bao lâu thì thấy hiệu quả, mấy buổi thì đỡ, liệu có hợp với mình không trong các dịch vụ cần lộ trình (Làm đẹp, Giáo dục, Sức khỏe).",
                        "bao lâu, mấy buổi, khi nào, thời gian, thấy hiệu quả, có hết không, có đỡ không, hiệu quả không, hợp không, kết quả",
                        "1. Giải thích rõ cảm nhận tích cực ngay từ buổi đầu tiên (ví dụ: thời lượng mỗi buổi, cảm giác êm dịu/thông thoáng/cải thiện bước đầu). 2. Nêu trung thực khung lộ trình thực tế trong tài liệu (thường theo phác đồ 3-4 buổi hoặc 5-9 buổi tùy cơ địa và mức độ thực tế sau khi kiểm tra).",
                        "Dạ mỗi buổi liệu trình kéo dài 60-75 phút giúp nền da dịu viêm và thông thoáng rõ rệt ngay từ những buổi đầu tiên ạ. Còn tổng số buổi cụ thể sẽ phụ thuộc vào cơ địa và mức độ nền da sau khi Bác sĩ soi da AI 3D trực tiếp, thông thường khách hàng sẽ thấy sự thay đổi bền vững theo lộ trình từ 3-4 buổi (Combo Tiết Kiệm) đến 5-9 buổi (Combo Toàn Diện) ạ.",
                        "Tuyệt đối không hứa hẹn chỉ 1 buổi là sạch vĩnh viễn 100% trái với quy chuẩn khoa học trong tài liệu.",
                        84,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000007"),
                        "SALES_MICRO_COMMITMENT_CLOSE",
                        "[Sale] Chốt Mềm bằng Cam kết Nhỏ & Câu hỏi Lựa chọn Kép (Alternative Choice Close)",
                        "SALES_STRATEGY",
                        "Kích hoạt khi khách đã nắm rõ thông tin, có tín hiệu quan tâm sâu (WARM/HOT) hoặc ở giai đoạn SOFT_BOOKING_CLOSE.",
                        "đặt lịch, giữ lịch, qua xem, đăng ký, làm thử, trải nghiệm, cuối tuần, rảnh, khung giờ, sáng hay chiều, tư vấn kỹ hơn, địa chỉ",
                        "1. Chuyển từ câu hỏi đóng (Có/Không) sang câu hỏi Lựa chọn Kép nhẹ nhàng (Sáng hay Chiều, Trong tuần hay Cuối tuần, Trải nghiệm 1 buổi trước hay giữ ưu đãi Combo). 2. Đề xuất một bước tiến nhỏ không rủi ro (như giữ lịch khám/soi da AI 3D miễn phí hoặc giữ suất quà tặng) để khách dễ dàng gật đầu.",
                        "Dạ để bên em sắp xếp Bác sĩ tiếp đón chu đáo nhất mà mình không phải chờ đợi lâu, mình tiện ghé vào khung giờ Sáng hay Chiều để em giữ suất soi da AI 3D miễn phí trước cho mình ạ?",
                        "Không dùng các câu giục giã thô thiển (Chốt luôn không chị, Mua ngay đi kẻo hết).",
                        89,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000008"),
                        "COMM_MIRROR_AND_PACE",
                        "[Giao tiếp] Đồng điệu Nhịp thở & Ngôn từ Hội thoại (Mirroring & Pacing)",
                        "COMMUNICATION",
                        "Kỹ năng giao tiếp nền tảng giúp điều chỉnh độ dài, nhịp câu và sắc thái cảm xúc khớp với phong cách nhắn tin của khách hàng trên Messenger.",
                        "nhắn ngắn, hỏi nhanh, tâm sự, chia sẻ, tư vấn tự nhiên, messenger",
                        "1. Nếu khách nhắn ngắn, hỏi thẳng vào trọng tâm (kiểu người bận rộn/quyết đoán): trả lời gọn gàng, súc tích, cấu trúc rõ ý. 2. Nếu khách trải lòng dài về nỗi buồn/sự tự ti: mở đầu bằng câu vỗ về ấm áp trước khi đi vào giải pháp. 3. Chia thành 2 đoạn chat ngắn tự nhiên, tuyệt đối không lặp lại câu chào hay câu hỏi khách đã trả lời ở lượt trước.",
                        "Dạ em ghi nhận tình trạng mình vừa chia sẻ ạ. Với nhu cầu này bên em sẽ ưu tiên phác đồ êm dịu, tiết kiệm thời gian nghỉ dưỡng nhất cho mình.",
                        "Không dùng văn phong công văn hành chính (Chúng tôi xin thông báo, Theo tài liệu được cung cấp); không lặp lại cùng một mẫu câu mở đầu ở mọi lượt.",
                        82,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000009"),
                        "COMM_NO_REPEAT_MEMORY",
                        "[Giao tiếp] Trí nhớ Hội thoại Xuyên suốt & Cấm Hỏi Lặp (Active Listening Memory)",
                        "COMMUNICATION",
                        "Đảm bảo Bot ghi nhớ toàn bộ dữ kiện khách đã nói ở các lượt trước (tình trạng, giới tính, thời gian bị, mối bận tâm) để nối mạch hội thoại mượt mà.",
                        "lượt 2, hội thoại đa lượt, hỏi tiếp, thế còn, vậy còn, như đã nói",
                        "1. Kiểm tra kỹ danh sách Vấn đề & Dữ kiện khách đã chia sẻ trong Hồ sơ Insight trước khi đặt câu hỏi. 2. Nếu khách đã nói tình trạng (ví dụ: bị mụn lâu năm, kèm sẹo rỗ, nám gò má), TUYỆT ĐỐI KHÔNG hỏi lại hiện tại da mình đang gặp vấn đề gì. 3. Liên kết tự nhiên dữ kiện cũ của khách với câu trả lời mới.",
                        "Dạ với tình trạng mụn lâu năm kèm sẹo rỗ như Anh vừa chia sẻ ở trên, bên em khuyên mình nên để Bác sĩ soi da đánh giá trực tiếp trước khi chọn gói Combo ạ.",
                        "Cấm quên những gì khách vừa nói cách đó 1-2 lượt; cấm hỏi lại câu hỏi khảo sát lượt 1 khi đã sang lượt 2+.",
                        87,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000010"),
                        "SALES_CROSS_INDUSTRY_CONSULT",
                        "[Đa ngành] Tư vấn Giải pháp Đúng Nhu cầu (E-commerce / Khóa học / Dịch vụ B2B)",
                        "SALES_STRATEGY",
                        "Kỹ năng tư vấn đa ngành (Thời trang, Mỹ phẩm, Khóa học, Phần mềm, Nội thất, Bất động sản) tập trung khớp đúng nhu cầu sử dụng với lợi ích thực tế.",
                        "phù hợp, chọn loại nào, gói nào tốt, so sánh, khác nhau, bảo hành, đổi trả, giao hàng, lộ trình học, triển khai",
                        "1. Khớp nối chính xác đặc điểm của khách hàng (đối tượng, mục đích sử dụng, ngân sách) với dòng sản phẩm/dịch vụ tương ứng trong tài liệu. 2. Nêu rõ chính sách bảo hành, đổi trả hoặc cam kết đồng hành để tăng tỷ lệ chuyển đổi. 3. Giữ đúng nhân cách thương hiệu (Page Persona) đã cấu hình cho từng kênh.",
                        "Dạ tùy theo mục tiêu hiện tại của mình, bên em sẽ gợi ý phương án tối ưu nhất cả về hiệu quả lẫn ngân sách, kèm đầy đủ chính sách bảo hành chính hãng để mình yên tâm sử dụng lâu dài ạ.",
                        "Không tư vấn chung chung một kiểu cho mọi phân khúc khách hàng; không gán ghép tính năng của sản phẩm này sang sản phẩm khác.",
                        80,
                        true,
                        null,
                        now,
                        now
                )
        );
    }
}
