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
            + "sẹo\\s+rỗ|sẹo\\s+lõm|rỗ\\s+lâu\\s+năm|nám\\s+chân\\s+sâu|nám\\s+lâu\\s+năm|"
            + "thâm\\s+mụn\\s+lâu|thâm\\s+lâu|dưới\\s+cằm.*lưng|lưng.*cằm)"
    );

    private static final Pattern PRICE_OBJECTION_PATTERN = Pattern.compile(
            "(?iU)\\b(giá|bao\\s+nhiêu|chi\\s+phí|đắt|hơi\\s+đắt|đắt\\s+thế|đắt\\s+quá|đắt\\s+vậy|mắc|hơi\\s+mắc|chát|"
            + "cao\\s+thế|cao\\s+quá|cao\\s+vậy|hơi\\s+cao|giá\\s+cao|cao\\s+hơn|rẻ|rẻ\\s+hơn|rẻ\\s+nhất|mềm\\s+hơn|"
            + "thấp\\s+hơn|thấp\\s+nhất|tiết\\s+kiệm|gói\\s+nhỏ|chiết\\s+khấu|khuyến\\s+mãi|combo|ưu\\s+đãi|phụ\\s+phí|phát\\s+sinh|tiền\\s+tip|tip)\\b"
    );

    private static final Pattern MALE_OR_DISCREET_PATTERN = Pattern.compile(
            "(?iU)(\\bnam\\s+giới\\b|\\bkhách\\s+nam\\b|\\bđàn\\s+ông\\b|\\bcon\\s+trai\\b|"
            + "\\bnam\\s+(?:dùng|sử\\s+dụng|xài|làm|đi|trị)\\b|\\bda\\s+nam\\b|"
            + "(?:mình|mìn|minh|mk|tôi|t)\\s+(?:là|la|làm|lam)?\\s*nam\\b|"
            + "\\btrung\\s+niên\\b|\\blớn\\s+tuổi\\b|\\bphòng\\s+riêng\\b|\\bphòng\\s+vip\\b|\\bkín\\s+đáo\\b|\\b2\\s+vợ\\s+chồng\\b|\\bcặp\\s+đôi\\b)"
    );

    private static final Pattern FEAR_OR_RISK_PATTERN = Pattern.compile(
            "(?iU)\\b(sợ\\s+đau|có\\s+đau\\s+không|đau\\s+không|sưng|đỏ|nghỉ\\s+dưỡng|bảo\\s+hành|cam\\s+kết|"
            + "sợ\\s+không\\s+hiệu\\s+quả|sợ\\s+ko\\s+hiệu\\s+quả|không\\s+hiệu\\s+quả|sợ\\s+không\\s+khỏi|"
            + "ràng\\s+buộc|rằng\\s+buộc|tái\\s+phát|an\\s+toàn|chèo\\s+kéo|ép\\s+mua|phụ\\s+phí|tiền\\s+tip|khám\\s+thử|mất\\s+phí)\\b"
    );

    private static final Pattern TIME_TO_RESULT_PATTERN = Pattern.compile(
            "(?iU)(bao\\s+lâu|mấy\\s+buổi|khi\\s+nào|thời\\s+gian).*(hiệu\\s+quả|hiệu\\s+quẩ|hết|đỡ|cải\\s+thiện|khỏi)|bao\\s+lâu\\s+thấy"
    );

    private static final Pattern BOOKING_INTENT_PATTERN = Pattern.compile(
            "(?iU)\\b(đặt\\s+lịch|giữ\\s+lịch|giữ\\s+chỗ|giữ\\s+suất|đăng\\s+ký|chốt|qua\\s+soi\\s+da|qua\\s+khám|"
            + "thử\\s+xem|sẽ\\s+thử|mình\\s+thử|t\\s+thử|trải\\s+nghiệm\\s+thử|làm\\s+thử|khám\\s+thử|soi\\s+da\\s+thử|"
            + "sđt|số\\s+điện\\s+thoại|chiều\\s+thứ|sáng\\s+thứ|cuối\\s+tuần\\s+này)\\b"
    );

    private static final Pattern HALF_HEARTED_OR_COOLING_PATTERN = Pattern.compile(
            "(?iU)^\\s*(ừ|uk|ukm|uh|uhm|ừm|um|ok|oke|oki|okie|vâng|da|dạ|rồi|roi|the\\s+a|thế\\s+à|the\\s+ha|thế\\s+hả|vay\\s+a|vậy\\s+à)(?:\\s+(?:em|shop|ad|bạn|nhé|nha|ạ))?\\s*[.!,~?]*$"
            + "|(?iU)(?:\\b|^\\s*)(?:ừ|uk|ukm|uh|uhm|ừm|ok|vâng|dạ)?\\s*(?:thôi\\s+)?(?:(?:để\\s+)?(?:chị|anh|em|mình|t|bạn)?\\s*(?:xem|coi|nghĩ|suy\\s+nghĩ|tính)\\s*(?:thêm|lại|đã|sao)(?:\\s+(?:nhé|nha|ạ))?|thôi\\s+để\\s+khi\\s+khác|chưa\\s+cần|chưa\\s+vội|tạm\\s+thời\\s+chưa|khi\\s+khác)\\b"
    );

    private static final Pattern PRICE_HESITATION_PATTERN = Pattern.compile(
            "(?iU)\\b(hơi\\s+đắt|đắt\\s+nhỉ|đắt\\s+quá|đắt\\s+thế|đắt\\s+vậy|giá\\s+cao|giá\\s+căng|cao\\s+nhỉ|cao\\s+thế|cao\\s+quá|cao\\s+vậy|cao\\s+ghê|cao\\s+dữ|cao\\s+phết|hơi\\s+cao|"
            + "căng\\s+nhỉ|căng\\s+thế|chát\\s+nhỉ|chát\\s+thế|hơi\\s+chát|chát\\s+quá|cao\\s+hơn|đắt|mắc\\s+nhỉ|mắc\\s+thế|mắc|hơi\\s+mắc|đau\\s+ví|quá\\s+sức|nhiều\\s+tiền\\s+quá|"
            + "vượt\\s+ngân\\s+sách|rẻ\\s+hơn|rẻ\\s+nhất|mềm\\s+hơn|thấp\\s+hơn|thấp\\s+nhất|gói\\s+nào\\s+rẻ|gói\\s+nhỏ)\\b"
    );

    private static final List<AgentSkillDto> DEFAULT_BUILT_IN_SKILLS = createDefaultBuiltInSkills();
    private static final List<AgentSkillDto> COOLING_PERSUASION_SKILLS = createCoolingPersuasionSkills();
    private static final List<AgentSkillDto> BUILT_IN_SKILLS = java.util.stream.Stream.concat(
            DEFAULT_BUILT_IN_SKILLS.stream(),
            COOLING_PERSUASION_SKILLS.stream()
    ).toList();
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
            "SALES_CROSS_INDUSTRY_CONSULT",
            "SALES_ANTI_INFO_DUMPING_PACING",
            "SALES_APPOINTMENT_HOOK_CLOSE",
            "SALES_CHRIS_VOSS_CALIBRATED_QUESTION",
            "PSYCH_ZERO_PRESSURE_TAKEAWAY",
            "PSYCH_TAKEAWAY_REVERSE",
            "PSYCH_PATTERN_INTERRUPT_CURIOSITY",
            "PSYCH_PATTERN_INTERRUPT",
            "SALES_LOSS_AVERSION_RESERVATION"
    );

    public static List<AgentSkillDto> builtInDefaultSkills() {
        return DEFAULT_BUILT_IN_SKILLS;
    }

    public static List<AgentSkillDto> coolingPersuasionSkills() {
        return COOLING_PERSUASION_SKILLS;
    }

    /**
     * Phân tích toàn diện tâm lý khách hàng, nỗi đau tích lũy qua lịch sử chat, rào cản ngầm và nhiệt độ mua hàng.
     */
    /**
     * Phân tích toàn diện tâm lý khách hàng, nỗi đau tích lũy qua lịch sử chat, rào cản ngầm và nhiệt độ mua hàng.
     */
    public CustomerInsightDto analyzeInsight(
            String currentMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            String resolvedHonorific,
            FanpageBotPromptBuilder.SalesStage salesStage,
            FanpageBotResponse.ExtractedLead extractedLead) {
        return analyzeInsight(currentMessage, history, resolvedHonorific, salesStage, extractedLead, null, null, null);
    }

    /**
     * Phân tích tâm lý khách hàng nâng cao với bộ đo Nhiệt độ Mua hàng trạng thái (Stateful Buying Temperature),
     * tự động tính toán độ suy giảm nhiệt khi khách im lặng (idle decay) hoặc nhắn đối phó / ngập ngừng (half-hearted cooling).
     */
    public CustomerInsightDto analyzeInsight(
            String currentMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            String resolvedHonorific,
            FanpageBotPromptBuilder.SalesStage salesStage,
            FanpageBotResponse.ExtractedLead extractedLead,
            Integer previousTemperature,
            Integer replyDelaySeconds,
            Integer idleSeconds) {

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
        if (lowerCombined.contains("gội đầu") || lowerCombined.contains("cổ") || lowerCombined.contains("thư giãn")
                || lowerCombined.contains("mỏi") || lowerCombined.contains("đau") || lowerCombined.contains("nhức") || lowerCombined.contains("tê")) {
            Set<String> wellnessScopes = new LinkedHashSet<>();
            for (String uMsg : allUserMessages) {
                String uLower = uMsg.toLowerCase(Locale.ROOT);
                if (uLower.contains("gội đầu") || uLower.contains("cổ") || uLower.contains("thư giãn")
                        || uLower.contains("mỏi") || uLower.contains("đau") || uLower.contains("nhức") || uLower.contains("tê")) {
                    for (String sc : ConversationState.extractTargetScopes(uLower, List.of())) {
                        if (!Set.of("da", "mụn", "nám", "sẹo").contains(sc)) {
                            wellnessScopes.add(sc);
                        }
                    }
                }
            }
            String scopeLabel = !wellnessScopes.isEmpty() ? String.join(" ", wellnessScopes) : "cơ thể";
            pains.add("Nhu cầu thư giãn dưỡng sinh & giảm đau mỏi " + scopeLabel);
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
        boolean currentIsComplexSymptom = FanpageBotPromptBuilder.isComplexOrSevereSymptomMessage(msg);
        boolean currentIsChronic = CHRONIC_PAIN_PATTERN.matcher(msg).find() || currentIsComplexSymptom;
        boolean historyIsChronic = CHRONIC_PAIN_PATTERN.matcher(combinedUserText).find() || currentIsComplexSymptom;
        boolean currentIsMaleOrDiscreet = MALE_OR_DISCREetCheck(msg, honorific, allUserMessages.size());
        boolean currentIsPrice = (PRICE_OBJECTION_PATTERN.matcher(msg).find()
                || CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(msg, true)) && !hasBookingLead;
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

        // 4. Tính điểm Nhiệt độ Mua hàng (Buying Temperature 0 - 100) & Trạng thái Động lượng (Momentum)
        int effectivePrevTemp = (previousTemperature != null)
                ? previousTemperature
                : derivePreviousTemperature(allUserMessages, history, pains, historyIsChronic, hasBookingLead);

        int temp;
        String momentum;
        String decayReason = null;

        boolean isIdleDecay = (idleSeconds != null && idleSeconds >= 300)
                || (replyDelaySeconds != null && replyDelaySeconds >= 300);
        boolean isHalfHearted = HALF_HEARTED_OR_COOLING_PATTERN.matcher(msg).find();
        boolean isPriceHesitation = PRICE_HESITATION_PATTERN.matcher(msg).find()
                || CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(msg, true);
        boolean isCoolingBrushOff = isHalfHearted && !isPriceHesitation && !hasBookingLead;

        if (isPriceHesitation && !hasBookingLead) {
            archetype = "PRICE_CONSCIOUS";
            archetypeLabel = "Cân nhắc Ngân sách & Giá trị — Cần Gợi ý Gói Mềm Hơn (Down-sell) & Ưu đãi";
        }

        if (isIdleDecay) {
            int elapsed = (idleSeconds != null && idleSeconds > 0)
                    ? idleSeconds
                    : (replyDelaySeconds != null ? replyDelaySeconds : 300);
            int idleDecay = (elapsed >= 1800) ? 35 : 18;
            temp = Math.max(0, effectivePrevTemp - idleDecay);
            momentum = "COOLING_IDLE";
            decayReason = "COOLING_IDLE";
        } else if (isCoolingBrushOff) {
            temp = Math.max(0, effectivePrevTemp - 20);
            momentum = "COOLING_HALF_HEARTED";
            decayReason = "COOLING_HALF_HEARTED";
        } else if (isPriceHesitation && !hasBookingLead) {
            temp = Math.max(0, effectivePrevTemp - 8);
            momentum = "COOLING_HALF_HEARTED";
            decayReason = "PRICE_HESITATION";
        } else {
            int calculatedTemp = 36;
            int userTurns = allUserMessages.size();
            calculatedTemp += Math.min(24, (userTurns - 1) * 10);
            if (historyIsChronic || pains.size() >= 2) {
                calculatedTemp += 12;
            }
            if (PRICE_OBJECTION_PATTERN.matcher(combinedUserText).find()) {
                calculatedTemp += 10;
            }
            if (TIME_TO_RESULT_PATTERN.matcher(combinedUserText).find()) {
                calculatedTemp += 10;
            }
            if (lowerMsg.contains("đắt") || lowerMsg.contains("cao hơn")) {
                calculatedTemp -= 6;
            }
            if (salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE) {
                calculatedTemp = Math.max(calculatedTemp, 72);
            }
            if (hasBookingLead) {
                int leadTemp = (extractedLead.phoneNumber() != null && !extractedLead.phoneNumber().isBlank()) ? 95 : 85;
                if (BOOKING_INTENT_PATTERN.matcher(msg).find() || lowerMsg.contains("chốt")) {
                    leadTemp = Math.min(100, leadTemp + 5);
                }
                calculatedTemp = Math.max(calculatedTemp, Math.max(leadTemp, effectivePrevTemp));
            }
            temp = Math.max(0, Math.min(100, calculatedTemp));

            int diff = temp - effectivePrevTemp;
            if (diff > 3) {
                momentum = (effectivePrevTemp < 50 && diff >= 10) ? "RECOVERING" : "RISING";
            } else if (diff < -3) {
                momentum = "COOLING_HALF_HEARTED";
                decayReason = "COOLING_HALF_HEARTED";
            } else {
                momentum = "STABLE";
            }
        }

        int temperatureDelta = temp - effectivePrevTemp;

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
        boolean isBroadUnspecifiedPrice = FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(msg, history);
        FanpageBotPromptBuilder.ConversationBookingState bookingState =
                FanpageBotPromptBuilder.analyzeConversationBookingState(msg, history);
        String recommendedStrategy;
        if (bookingState.isRedundantQuestionComplaint()) {
            recommendedStrategy = "Khách hàng phản ánh Bot hỏi lặp lại khung giờ khi đã chốt lịch ("
                    + (bookingState.confirmedTimeSlot() != null ? bookingState.confirmedTimeSlot() : "12h30")
                    + "): Xin lỗi chân thành, xác nhận đã khóa chuẩn xác lịch hẹn cho " + honorific
                    + " và hẹn đón tiếp tại cơ sở (TUYỆT ĐỐI KHÔNG đặt thêm câu hỏi ở cuối!).";
        } else if (bookingState.isPostBookingWrapUp()) {
            recommendedStrategy = "Khách hàng đã hoàn tất chốt lịch hẹn ("
                    + (bookingState.confirmedTimeSlot() != null ? bookingState.confirmedTimeSlot() : "đã chốt giờ")
                    + "): Xác nhận ấm áp theo đúng yêu cầu của " + honorific
                    + " và kết thúc gọn gàng, lịch sự (TUYỆT ĐỐI KHÔNG hỏi lặp lại Sáng/Chiều hay ngày thường/cuối tuần!).";
        } else if (bookingState.isPriceComparisonObjection()) {
            recommendedStrategy = "Khách hàng đang so sánh chi phí với dịch vụ giá rẻ bên ngoài (Value Framing): "
                    + "Phân tích ngắn gọn sự khác biệt về chất lượng quy trình, sự an toàn và quyền lợi đi kèm trong <DOCUMENT_CONTEXT>, sau đó gợi ý gói trải nghiệm cơ bản hợp ngân sách cho "
                    + honorific + ".";
        } else if (isPriceHesitation && !hasBookingLead) {
            recommendedStrategy = "Khách hàng đang chê giá cao / thấy đắt hoặc tìm gói rẻ hơn (Down-sell & Value Framing): "
                    + "Đồng cảm ngay với ngân sách của " + honorific + ", TUYỆT ĐỐI KHÔNG nói 'cứ suy nghĩ thêm' và KHÔNG chuyển nhân viên (HANDOFF). "
                    + "Chủ động giới thiệu ngay 1-2 gói dịch vụ có mức giá thấp hơn/mềm hơn trong <DOCUMENT_CONTEXT> (nêu rõ tên gói, đơn giá niêm yết, thời lượng và cơ sở nếu khác nhau) kèm ưu đãi/voucher hiện có, rồi hỏi "
                    + honorific + " muốn thử trải nghiệm gói nào trước.";
        } else if ("COOLING_HALF_HEARTED".equals(momentum)) {
            recommendedStrategy = "Khách hàng có dấu hiệu do dự / trả lời xã giao ngắn (Takeaway & Pattern Interrupt): "
                    + "Lùi lại 1 bước, buông bỏ áp lực bán hàng, tôn trọng thời gian cân nhắc của " + honorific
                    + " và đặt 1 câu hỏi vi mô gợi mở nhu cầu hoặc tháo gỡ băn khoăn thực tế.";
        } else if ("COOLING_IDLE".equals(momentum)) {
            recommendedStrategy = "Khách hàng đang im lặng / mất tương tác (Loss-Aversion Reservation): "
                    + "Kéo lại tương tác nhẹ nhàng bằng cách đề xuất giữ trước suất ưu đãi trong <DOCUMENT_CONTEXT> cho " + honorific
                    + " mà không thúc ép chốt đơn.";
        } else {
            recommendedStrategy = switch (archetype) {
                case "READY_TO_BOOK" -> asksPriceInCurrentTurn
                        ? "Báo giá ngắn gọn, chuẩn xác gói dịch vụ " + honorific + " vừa hỏi theo đúng dữ liệu tài liệu, đồng thời xác nhận đã ghi nhận thông tin SĐT / lịch hẹn để chuyên viên liên hệ hỗ trợ " + honorific + " ngay."
                        : (bookingState.hasConfirmedTimeSlot() && !bookingState.hasCapturedPhone()
                            ? "Xác nhận giữ suất lúc " + bookingState.confirmedTimeSlot() + " cho " + honorific + " và chỉ xin Số điện thoại để khóa lịch (cấm hỏi lại Sáng hay Chiều!)."
                            : "Xác nhận ngay thông tin SĐT / lịch hẹn của " + honorific + " thật ngắn gọn, ấm áp; báo chuyên viên liên hệ qua điện thoại/Zalo ngay và TUYỆT ĐỐI KHÔNG liệt kê lại bảng giá hay hỏi lại tình trạng.");
                case "SKEPTICAL_CHRONIC" -> currentIsComplexSymptom
                        ? "Đồng cảm sâu sắc với tình trạng kéo dài của " + honorific
                          + "; phân tích rõ giải pháp phù hợp trong <DOCUMENT_CONTEXT> và dẫn dắt "
                          + honorific + " đặt lịch trải nghiệm/kiểm tra trực tiếp theo khung giờ Sáng/Chiều."
                        : "Đồng cảm sâu sắc với hành trình lâu năm của " + honorific + "; tư vấn trung thực phạm vi cải thiện của gói dịch vụ trong <DOCUMENT_CONTEXT> (tuyệt đối không hứa lố).";
                case "DISCREET_DECISIVE" ->
                        "Khẳng định rõ dịch vụ hoàn toàn phù hợp và hiệu quả cao cho khách hàng Nam giới / Trung niên; giữ giọng văn điềm đạm, tôn trọng, nhấn mạnh không gian kín đáo và trả lời thẳng trọng tâm cho " + honorific + ".";
                case "PRICE_CONSCIOUS" -> isBroadUnspecifiedPrice
                        ? "Áp dụng Value Stacking & Chống xả sạch thông tin (Nấc 1): TUYỆT ĐỐI KHÔNG liệt kê toàn bộ bảng giá nhiều dịch vụ ngay! Chỉ nêu mức giá khởi điểm thấp nhất/dễ tiếp cận nhất trong <DOCUMENT_CONTEXT> + 1 quyền lợi đi kèm + đặt 1 câu hỏi lựa chọn A/B ngắn gọn hỏi nhu cầu ưu tiên của " + honorific + "."
                        : "Áp dụng Value Stacking & Phân bổ thông tin (Nấc 2): Báo giá chuẩn xác gói dịch vụ phù hợp nhất trong <DOCUMENT_CONTEXT>, giữ nhất quán với mức giá đã báo ở lượt trước (hoặc nêu rõ chênh lệch theo cơ sở nếu có), kèm 1 quyền lợi đi kèm và đặt 1 câu hỏi lựa chọn A/B.";
                case "HESITANT_FIRST_TIMER" ->
                        "Đưa rủi ro của " + honorific + " về 0% (Zero-Risk Reversal) bằng cam kết minh bạch, quy trình nhẹ nhàng không chèo kéo từ <DOCUMENT_CONTEXT> và gợi ý gói cơ bản dễ trải nghiệm trước.";
                default ->
                        "Giải đáp ngắn gọn đúng trọng tâm câu hỏi của " + honorific + " theo quy tắc '1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế A/B' (SPIN) để dẫn dắt mạch tư vấn tự nhiên.";
            };
        }

        return new CustomerInsightDto(
                archetype,
                archetypeLabel,
                temp,
                previousTemperature != null ? previousTemperature : (history != null && !history.isEmpty() ? effectivePrevTemp : null),
                temperatureDelta,
                momentum,
                tempLabel,
                decayReason,
                honorific,
                new ArrayList<>(pains),
                new ArrayList<>(concerns),
                recommendedStrategy
        );
    }

    private static int derivePreviousTemperature(
            List<String> allUserMessages,
            List<FanpageBotRequest.ConversationTurn> history,
            Set<String> pains,
            boolean historyIsChronic,
            boolean hasBookingLead) {
        if (history == null || history.isEmpty()) {
            return 36;
        }
        int priorUserTurns = 0;
        StringBuilder priorUserText = new StringBuilder();
        for (FanpageBotRequest.ConversationTurn turn : history) {
            if (turn != null && !"assistant".equalsIgnoreCase(turn.role())
                    && turn.content() != null && !turn.content().isBlank()) {
                priorUserTurns++;
                priorUserText.append(" ").append(turn.content().strip());
            }
        }
        if (priorUserTurns == 0) {
            return 36;
        }
        String priorText = priorUserText.toString();
        int base = 36 + Math.min(24, (priorUserTurns - 1) * 10);
        if (historyIsChronic || pains.size() >= 2) base += 12;
        if (PRICE_OBJECTION_PATTERN.matcher(priorText).find()) base += 10;
        if (TIME_TO_RESULT_PATTERN.matcher(priorText).find()) base += 10;
        if (BOOKING_INTENT_PATTERN.matcher(priorText).find() || hasBookingLead) base = Math.max(base, 75);
        return Math.max(0, Math.min(100, base));
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

        List<AgentSkillDto> base = (availableSkills != null && !availableSkills.isEmpty())
                ? availableSkills.stream()
                        .filter(s -> s != null && s.active() && (s.confidenceScore() <= 0.0 || s.confidenceScore() >= 0.45))
                        .toList()
                : BUILT_IN_SKILLS;

        if (base.isEmpty()) {
            return List.of();
        }

        List<AgentSkillDto> pool = new ArrayList<>(
                base.stream().map(CleanAgentOrchestratorService::sanitizeSkillDisplayNameForUi).toList()
        );
        boolean hasPersuasion = pool.stream().anyMatch(s -> "PSYCH_ZERO_PRESSURE_TAKEAWAY".equalsIgnoreCase(s.code()));
        if (!hasPersuasion) {
            pool.addAll(COOLING_PERSUASION_SKILLS);
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
        boolean isBroadUnspecifiedPrice = FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(currentMessage, history);
        String archetype = insight != null && insight.buyerArchetype() != null ? insight.buyerArchetype() : "EXPLORING_CONSULTATION";
        String honorific = insight != null && insight.honorific() != null ? insight.honorific() : "Chị";

        // Lọc bỏ các Kỹ năng Toàn cục (page_id == null) nếu Page hiện tại đã có Kỹ năng Riêng (page_id != null) ghi đè/xung đột chiến thuật
        List<AgentSkillDto> pageScopedSkills = pool.stream()
                .filter(s -> s.pageId() != null && !s.pageId().isBlank())
                .toList();
        if (!pageScopedSkills.isEmpty()) {
            boolean hasMicroSkills = pageScopedSkills.stream()
                    .anyMatch(s -> s.code() != null && (
                            "greeting_warm_intro".equalsIgnoreCase(s.code())
                            || "discovery_ask_one_need".equalsIgnoreCase(s.code())
                            || "price_answer_from_docs".equalsIgnoreCase(s.code())
                            || "price_clarify_service_duration".equalsIgnoreCase(s.code())
                            || "price_objection_soft_anchor".equalsIgnoreCase(s.code())
                    ));
            if (hasMicroSkills) {
                AgentSkillDto matchedMicro = selectDeterministicMicroSkill(
                        pageScopedSkills, msg, currentMessage, history, isBroadUnspecifiedPrice);
                if (matchedMicro != null) {
                    return List.of(matchedMicro);
                }
            }
            pool = pool.stream()
                    .filter(candidate -> {
                        if (candidate.pageId() != null && !candidate.pageId().isBlank()) {
                            return true;
                        }
                        return pageScopedSkills.stream()
                                .noneMatch(pageSkill -> AgentSkillService.areTacticallyConflicting(pageSkill, candidate));
                    })
                    .toList();
        }

        record ScoredSkill(AgentSkillDto skill, int score) {}
        List<ScoredSkill> scored = new ArrayList<>(pool.size());
        boolean isBareClosingAfterHesitation = CleanAgentOrchestratorService.isBareAcknowledgmentMessage(currentMessage)
                && CleanAgentOrchestratorService.wasPriorCustomerTurnHesitation(history);
        boolean isHesitationOrDeferralTurn = CleanAgentOrchestratorService.isHesitationOrDeferralMessage(currentMessage)
                || isBareClosingAfterHesitation;

        for (AgentSkillDto skill : pool) {
            int score = skill.priority();
            String code = skill.code() != null ? skill.code().toUpperCase(Locale.ROOT) : "";
            boolean isBookingAcceptanceSkill = "SPA_TRIAL_ACCEPTANCE_BOOKING".equals(code)
                    || "SPA_BOOKING_ADVANCE_AFTER_ACK".equals(code)
                    || "BOOKING_SOFT_CONFIRM".equals(code)
                    || "SALES_MICRO_COMMITMENT_CLOSE".equals(code)
                    || "SALES_APPOINTMENT_HOOK_CLOSE".equals(code)
                    || (skill.category() != null && "CLOSING".equalsIgnoreCase(skill.category()));
            int matchedInCurrentMsg = 0;
            int matchedInHistory = 0;

            // 1. Điểm khớp từ khóa trong trigger_signals (Giới hạn trần để tránh skill nhiều từ đồng nghĩa lấn át skill tùy chỉnh)
            if (skill.triggerSignals() != null && !skill.triggerSignals().isBlank()) {
                String[] signals = skill.triggerSignals().split("[,;]+");
                for (String rawSig : signals) {
                    String sig = rawSig.strip().toLowerCase(Locale.ROOT);
                    if (sig.length() >= 2) {
                        if (isHesitationOrDeferralTurn && isBookingAcceptanceSkill
                                && (sig.equals("ok") || sig.equals("oke") || sig.equals("ừ") || sig.equals("uk") || sig.equals("vâng") || sig.equals("dạ"))) {
                            continue;
                        }
                        if (msg.contains(sig)) {
                            matchedInCurrentMsg++;
                        } else if (fullConvo.contains(sig)) {
                            matchedInHistory++;
                        }
                    }
                }
                score += Math.min(3, matchedInCurrentMsg) * 45;
                score += Math.min(2, matchedInHistory) * 18;
            }
            if (isHesitationOrDeferralTurn) {
                if (isBookingAcceptanceSkill || "SPA_LUKEWARM_MOOD_BOOST".equals(code) || "LUKEWARM_MOOD_BOOST".equals(code)) {
                    score -= 350;
                } else if ("SPA_GRACEFUL_EXIT_AFTER_REJECTION".equals(code)
                        || "SPA_STAGE3_GRACEFUL_EXIT".equals(code)
                        || "PSYCH_ZERO_PRESSURE_TAKEAWAY".equals(code)) {
                    score += 180;
                }
            }

            // 2. Điểm thưởng kích hoạt công bằng cho Kỹ năng Tùy chỉnh trên UI / Kỹ năng riêng của Page
            boolean isCustomSkill = !BUILT_IN_CODES.contains(code);
            boolean isPageScoped = skill.pageId() != null && !skill.pageId().isBlank();
            if (isCustomSkill || isPageScoped) {
                if (matchedInCurrentMsg > 0) {
                    score += 135;
                } else if (matchedInHistory > 0) {
                    score += 55;
                }
                if (isPageScoped && (matchedInCurrentMsg > 0 || matchedInHistory > 0)) {
                    score += 55;
                }
            }

            // 3. Điểm thưởng theo Chân dung Tâm lý (BuyerArchetype) & Giai đoạn Tư vấn
            switch (archetype) {
                case "SKEPTICAL_CHRONIC" -> {
                    if ("PSYCH_CHRONIC_EMPATHY".equals(code)) score += 125;
                    if ("SALES_APPOINTMENT_HOOK_CLOSE".equals(code)) score += 110;
                    if ("PSYCH_ZERO_RISK_REVERSAL".equals(code)) score += 95;
                    if ("SALES_SOCIAL_PROOF_REASSURANCE".equals(code)) score += 80;
                }
                case "DISCREET_DECISIVE" -> {
                    if ("PSYCH_DISCREET_VIP_CARE".equals(code)) score += 130;
                    if ("COMM_MIRROR_AND_PACE".equals(code)) score += 85;
                    if ("SALES_SOCIAL_PROOF_REASSURANCE".equals(code)) score += 75;
                }
                case "PRICE_CONSCIOUS" -> {
                    if ("SALES_ANTI_INFO_DUMPING_PACING".equals(code)) score += isBroadUnspecifiedPrice ? 145 : 120;
                    if ("SALES_VALUE_FRAMING_PRICE".equals(code)) score += 130;
                    if ("PSYCH_ZERO_RISK_REVERSAL".equals(code)) score += 95;
                    if ("SALES_APPOINTMENT_HOOK_CLOSE".equals(code)) score += 85;
                    if ("SALES_MICRO_COMMITMENT_CLOSE".equals(code)) score += 70;
                }
                case "HESITANT_FIRST_TIMER" -> {
                    if ("PSYCH_ZERO_RISK_REVERSAL".equals(code)) score += 125;
                    if ("SALES_APPOINTMENT_HOOK_CLOSE".equals(code)) score += 105;
                    if ("SALES_SOCIAL_PROOF_REASSURANCE".equals(code)) score += 95;
                }
                case "READY_TO_BOOK" -> {
                    if ("SALES_MICRO_COMMITMENT_CLOSE".equals(code)) score += 125;
                    if ("SALES_APPOINTMENT_HOOK_CLOSE".equals(code)) score += 115;
                    if ("COMM_NO_REPEAT_MEMORY".equals(code)) score += 95;
                }
                default -> {
                    if ("SALES_SPIN_DIAGNOSIS".equals(code)) score += 105;
                    if ("SALES_ANTI_INFO_DUMPING_PACING".equals(code)) score += 95;
                    if ("COMM_MIRROR_AND_PACE".equals(code)) score += 80;
                    if ("SALES_CROSS_INDUSTRY_CONSULT".equals(code)) score += 70;
                }
            }

            boolean isCooling = (insight != null) && (
                    insight.momentum() != null && insight.momentum().startsWith("COOLING")
            );
            boolean isPriceHesitationTurn = (insight != null && ("PRICE_HESITATION".equals(insight.decayReason()) || "PRICE_HESITATION".equals(insight.momentum())))
                    || PRICE_HESITATION_PATTERN.matcher(msg).find();

            if (isPriceHesitationTurn) {
                if ("SALES_VALUE_FRAMING_PRICE".equals(code)) {
                    score += 170;
                }
                if ("COMM_NO_REPEAT_MEMORY".equals(code)) {
                    score += 85;
                }
                if ("PSYCH_ZERO_PRESSURE_TAKEAWAY".equals(code) || "PSYCH_TAKEAWAY_REVERSE".equals(code)) {
                    score -= 150;
                }
            } else if (isCooling) {
                if ("PSYCH_ZERO_PRESSURE_TAKEAWAY".equals(code) || "PSYCH_TAKEAWAY_REVERSE".equals(code)) {
                    score += 140;
                }
                if ("PSYCH_PATTERN_INTERRUPT_CURIOSITY".equals(code) || "PSYCH_PATTERN_INTERRUPT".equals(code)) {
                    score += 130;
                }
                if ("SALES_LOSS_AVERSION_RESERVATION".equals(code)) {
                    score += 125;
                }
                if ("SALES_MICRO_COMMITMENT_CLOSE".equals(code) || "SALES_APPOINTMENT_HOOK_CLOSE".equals(code)) {
                    score -= 60;
                }
            }

            if ("Anh".equalsIgnoreCase(honorific) && "PSYCH_DISCREET_VIP_CARE".equals(code)) {
                score += 65;
            }
            if (isMultiTurn && "COMM_NO_REPEAT_MEMORY".equals(code)) {
                score += 60;
            }
            if (!isCooling && salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE
                    && ("SALES_MICRO_COMMITMENT_CLOSE".equals(code) || "SALES_APPOINTMENT_HOOK_CLOSE".equals(code))) {
                score += 55;
            }

            // 4. Điểm thưởng củng cố từ lịch sử chuyển đổi thực tế (conversion_count tối đa +20) & độ tín nhiệm (confidence_score)
            int conversionBoost = Math.min(20, Math.max(0, skill.conversionCount()) * 4);
            double conf = skill.confidenceScore() <= 0.0 ? 0.90 : skill.confidenceScore();
            int confidenceAdjustment = (int) Math.round((conf - 0.85) * 40.0);
            score += conversionBoost + confidenceAdjustment;

            scored.add(new ScoredSkill(skill, score));
        }

        scored.sort(Comparator.comparingInt(ScoredSkill::score).reversed());

        // Mỗi câu trả lời chỉ kích hoạt tối đa 1 kỹ năng duy nhất (MAX_ACTIVE_SKILLS = 1)
        // có điểm phù hợp cao nhất với ngữ cảnh để tránh xung đột chiến thuật trong prompt.
        if (scored.isEmpty()) {
            return List.of();
        }
        return List.of(scored.get(0).skill());
    }

    public static boolean hasComplaintSignal(String lowerMsg) {
        if (lowerMsg == null || lowerMsg.isBlank()) {
            return false;
        }
        return lowerMsg.matches("(?isU).*(?:\\b(đau\\s+quá|tệ\\s+thật|tệ\\s+quá|quá\\s+tệ|khiếu\\s+nại|bức\\s+xúc|hoàn\\s+tiền|gặp\\s+quản\\s+lý|gặp\\s+nhân\\s+viên|gặp\\s+người\\s+thật)\\b|hôm\\s+qua.*làm.*\\bđau\\b).*");
    }

    public static boolean hasHighRiskHealthSignal(String lowerMsg) {
        if (lowerMsg == null || lowerMsg.isBlank()) {
            return false;
        }
        return lowerMsg.matches("(?isU).*(?:\\b(mang\\s+thai|đang\\s+bầu|có\\s+bầu|bầu\\s+\\d+|thai\\s+kỳ|mới\\s+sinh|phẫu\\s+thuật|mới\\s+mổ|vết\\s+thương\\s+hở|viêm\\s+da\\s+nặng|sốt\\s+cao|huyết\\s+áp\\s+cao|cao\\s+huyết\\s+áp|tim\\s+mạch|giãn\\s+tĩnh\\s+mạch|thoát\\s+vị|gãy\\s+xương|trật\\s+khớp|tức\\s+ngực|đau\\s+ngực|khó\\s+thở|đau\\s+đầu\\s+dữ\\s+dội|chóng\\s+mặt\\s+nhiều|teo\\s+cơ|tê\\s+lan\\s+xuống\\s+tay|đau\\s+tai|ù\\s+tai|viêm\\s+tai|chảy\\s+dịch\\s+tai)\\b|\\btai\\b[^.!?\\n]{0,30}\\b(đau|ù|viêm|chảy)\\b).*");
    }

    public static boolean isPureGreetingOrOpening(String lowerMsg, boolean isFirstTurn) {
        if (lowerMsg == null || lowerMsg.isBlank()) {
            return isFirstTurn;
        }
        return lowerMsg.matches("(?isU)^\\s*(?:chào|xin\\s+chào|hi|hello|alo|shop\\s+ơi|em\\s+ơi|bên\\s+mình\\s+ơi|tư\\s+vấn|tư\\s+vấn\\s+giúp|cho\\s+mình\\s+hỏi|cho\\s+chị\\s+hỏi|cho\\s+anh\\s+hỏi)(?:\\s+(?:shop|em|bạn|ad|nhé|nha|ạ|với))?\\s*[.!,?~]*$");
    }

    private AgentSkillDto selectDeterministicMicroSkill(
            List<AgentSkillDto> pageScopedSkills,
            String lowerMsg,
            String rawMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            boolean isBroadUnspecifiedPrice) {
        String targetCode;
        if (hasComplaintSignal(lowerMsg)) {
            targetCode = "complaint_immediate_handoff";
        } else if (hasHighRiskHealthSignal(lowerMsg)) {
            targetCode = "safety_handoff_high_risk";
        } else if (CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(rawMessage, true)
                || (!CleanAgentOrchestratorService.isNormalPriceOrDetailInquiryMessage(rawMessage)
                    && (FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(rawMessage)
                        || lowerMsg.matches("(?isU).*\\b(đắt|hơi\\s+cao|cao\\s+nhỉ|cao\\s+thế|cao\\s+quá|cao\\s+vậy|đắt\\s+vậy|rẻ\\s+hơn|mềm\\s+hơn|vượt\\s+ngân\\s+sách)\\b.*")))) {
            targetCode = "price_objection_soft_anchor";
        } else if (isBroadUnspecifiedPrice
                || (lowerMsg.matches("(?isU).*\\b(giá|bảng\\s+giá|bao\\s+nhiêu|chi\\s+phí|rổ\\s+giá|mấy\\s+tiền)\\b.*")
                    && ConversationState.extractDurationFromText(lowerMsg) == null
                    && ConversationState.extractTargetScopes(lowerMsg, List.of()).isEmpty())) {
            targetCode = "price_clarify_service_duration";
        } else if (lowerMsg.matches("(?isU).*\\b(giá|bao\\s+nhiêu|chi\\s+phí|mấy\\s+tiền)\\b.*")) {
            targetCode = "price_answer_from_docs";
        } else if (lowerMsg.matches("(?isU).*\\b(đặt\\s+lịch|giữ\\s+lịch|tối\\s+nay|chiều\\s+nay|sáng\\s+mai|chiều\\s+mai|ghé\\s+lúc|qua\\s+lúc|0\\d{8,10})\\b.*")) {
            targetCode = "booking_soft_confirm";
        } else if (lowerMsg.matches("(?isU).*\\b(chốt\\s+gói|lấy\\s+gói|chọn\\s+gói|thêm\\s+gì|kèm\\s+gì)\\b.*")
                && history != null && !history.isEmpty()) {
            targetCode = "upsell_one_addon";
        } else if (FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(rawMessage)
                || !ConversationState.extractAllNeeds(lowerMsg, List.of()).isEmpty()
                || lowerMsg.matches("(?isU).*\\b(mỏi|đau|nhức|căng|mất\\s+ngủ|stress|thư\\s+giãn|massage|chăm\\s+sóc)\\b.*")) {
            targetCode = "discovery_ask_one_need";
        } else {
            targetCode = (history == null || history.isEmpty()) ? "greeting_warm_intro" : "discovery_ask_one_need";
        }

        for (AgentSkillDto s : pageScopedSkills) {
            if (s.code() != null && s.code().equalsIgnoreCase(targetCode)) {
                return s;
            }
        }
        return null;
    }

    private static List<AgentSkillDto> createDefaultBuiltInSkills() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return List.of(
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000001"),
                        "PSYCH_CHRONIC_EMPATHY",
                        "[Tâm lý FBI - Chris Voss] Thấu cảm Chiến thuật 5 Chữ & Đi Thẳng Giải Pháp (Tactical Empathy)",
                        "PSYCHOLOGY",
                        "Áp dụng kỹ thuật Tactical Empathy & Labeling của FBI (Chris Voss) khi khách nêu triệu chứng, nỗi đau kéo dài hoặc sự mệt mỏi, đa nghi.",
                        "lâu năm, nhiều năm, 5 năm, 10 năm, chữa nhiều nơi, không khỏi, tái phát, sẹo rỗ, nặng, mãn tính, mất niềm tin, mỏi, đau nhức, căng cứng, mất ngủ, stress",
                        "1. Gắn nhãn cảm xúc cực gọn trong 5-8 chữ đầu câu (VD: 'Dạ bị mỏi vậy khó chịu lắm ạ,'), CẤM viết câu giải thích nguyên nhân sách vở dài dòng. 2. Đi thẳng vào ĐÚNG 1 gói/giải pháp sát nhất trong <DOCUMENT_CONTEXT> kèm lợi ích cốt lõi và đơn giá trong 1 câu ngắn. 3. Tổng phản hồi tối đa 2-3 câu ngắn (30-45 chữ).",
                        "Dạ bị căng mỏi vùng vai vậy khó chịu lắm ạ, gói Massage cơ bản (60 phút, 399.000 VNĐ) bên em sẽ ấn huyệt và chườm ngải cứu giúp nhẹ vai ngay buổi đầu.\n\nAnh/Chị muốn trải nghiệm gói 60 phút này trước hay gói chuyên sâu 90 phút ạ?",
                        "CẤM viết câu giải thích nguyên nhân dài dòng ('Điều này thường do căng thẳng...'); CẤM nổ quá đà trái với <DOCUMENT_CONTEXT>.",
                        95,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000002"),
                        "PSYCH_ZERO_RISK_REVERSAL",
                        "[Tâm lý Hành vi - Kahneman] Đảo Ngược Rủi Ro & Xóa Sợ Chi Phí Ẩn (Zero-Risk Reversal)",
                        "PSYCHOLOGY",
                        "Ứng dụng Lý thuyết Triển vọng (Kahneman Prospect Theory) giải tỏa tâm lý sợ mất mát: sợ đau, sợ phụ phí ẩn, sợ bị ép mua hoặc phải đưa tiền Tip.",
                        "sợ đau, có đau không, sưng, nghỉ dưỡng, phụ phí, phát sinh, tiền tip, chèo kéo, ép mua, khám thử, mất phí không, bảo hành, cam kết, an toàn",
                        "1. Đưa rủi ro tâm lý về 0% ngay trong 1 câu ngắn bằng cam kết minh bạch trong <DOCUMENT_CONTEXT> (trọn gói 0 phụ phí, 0 nhận Tip, bảo hành/kiểm tra miễn phí). 2. Giữ tổng tin nhắn <= 45 chữ, kết bằng 1 câu hỏi A/B ngắn.",
                        "Dạ mức giá bên em báo là trọn gói 100%, cam kết không phát sinh phụ phí và nhân viên tuyệt đối không nhận Tip ạ.\n\nAnh/Chị muốn thử gói cơ bản trước hay cần em giữ ưu đãi khung giờ Sáng/Chiều ạ?",
                        "Không trả lời lập lờ dài dòng; không né tránh câu hỏi về phụ phí hoặc bảo hành.",
                        90,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000003"),
                        "PSYCH_DISCREET_VIP_CARE",
                        "[Tâm lý VIP] Tư Vấn Tinh Gọn, Riêng Tư & Trọng Tâm (Khách Nam / Trung Niên / Bận Rộn)",
                        "PSYCHOLOGY",
                        "Phục vụ khách Nam giới, Trung niên hoặc khách bận rộn: ghét đọc chữ dài, đề cao sự riêng tư, nhanh gọn và đi thẳng vào kết quả.",
                        "nam giới, khách nam, đàn ông, con trai, mình là nam, anh muốn, chồng chị, bạn trai, trung niên, lớn tuổi, phòng riêng, kín đáo, cặp đôi, ngại",
                        "1. Khẳng định ngay trong 1 câu ngắn rằng dịch vụ rất phù hợp và có không gian riêng tư/kín đáo (nếu có trong <DOCUMENT_CONTEXT>). 2. Cắt bỏ 100% từ ngữ rườm rà, giữ tổng phản hồi <= 40 chữ, đồng nhất xưng hô chuẩn mực.",
                        "Dạ bên em có phòng riêng kín đáo phục vụ rất nhiều khách Nam nên Anh hoàn toàn thoải mái ạ.\n\nAnh tiện ghé khung giờ Sáng hay Chiều để em giữ phòng riêng trước cho mình ạ?",
                        "Tuyệt đối KHÔNG đọc câu máy móc về tệp khách hàng nội bộ; không viết quá 3 câu.",
                        88,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000004"),
                        "SALES_SPIN_DIAGNOSIS",
                        "[Sale Đỉnh Cao - SPIN & CLOSER] Chẩn Đoán 1 Chạm & Quy Tắc Nhịp Thở 40 Chữ",
                        "SALES_STRATEGY",
                        "Kết hợp SPIN Selling (Neil Rackham) và CLOSER (Alex Hormozi) cho chat Messenger: Cực ngắn, súc tích, 1 Giá trị + 1 Câu hỏi A/B.",
                        "tư vấn, có gói nào, dịch vụ gì, sản phẩm nào, bên mình có, muốn tìm hiểu, mới bị, bắt đầu, giới thiệu giúp, mỏi, đau, thi thoảng",
                        "1. QUY TẮC 40 CHỮ: Toàn bộ phản hồi BẮT BUỘC chỉ 2-3 câu ngắn (tổng 30-45 chữ, tối đa 2 bong bóng chat). 2. Bong bóng 1 (1-2 câu): Đồng cảm 5 chữ + Nêu thẳng ĐÚNG 1 gói/giải pháp phù hợp nhất (tên gói + thời lượng/quy cách + giá) từ <DOCUMENT_CONTEXT>. CẤM viết câu giải thích nguyên nhân rườm rà! 3. Bong bóng 2 (1 câu <= 15 chữ): Đặt 1 câu hỏi A/B ngắn gọn (CẤM hỏi lại điều khách đã nói).",
                        "Dạ bị mỏi vai vậy khó chịu lắm ạ, gói Massage cơ bản (60 phút, 399.000 VNĐ) bên em ấn huyệt và chườm ngải cứu giúp nhẹ vai ngay buổi đầu.\n\nAnh/Chị muốn thử gói 60 phút này trước hay gói chuyên sâu 90 phút ạ?",
                        "CẤM viết quá 3 câu; CẤM giải thích nguyên nhân dài dòng ('Điều này thường do...'); CẤM hỏi lại tần suất khi khách đã nói 'thi thoảng' hoặc 'thường xuyên'.",
                        92,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000005"),
                        "SALES_VALUE_FRAMING_PRICE",
                        "[Đàm phán Giá - Harvard & Ackerman] Neo Giá Trị & Lùi Bậc Giá Mềm (Value Framing & Down-sell)",
                        "SALES_STRATEGY",
                        "Kỹ thuật đàm phán giá Harvard & Ackerman khi khách hỏi giá, chê đắt ('cao thế', 'hơi đắt') hoặc tìm gói rẻ hơn.",
                        "giá, giá sao, báo giá, bảng giá, bao nhiêu tiền, chi phí, đắt, hơi đắt, đắt thế, đắt quá, cao thế, cao quá, giá cao, rẻ hơn, mềm hơn, thấp hơn, tiết kiệm, giảm giá, chiết khấu, combo, ưu đãi",
                        "1. Hỏi giá chung: Chỉ báo 1 mức giá khởi điểm dễ tiếp cận nhất từ <DOCUMENT_CONTEXT> kèm 1 quyền lợi cốt lõi trong 1 câu ngắn + 1 câu hỏi A/B. 2. Khách chê đắt / tìm gói rẻ hơn: Đồng cảm 5 chữ, CẤM thanh minh và CẤM [[HANDOFF]]; chủ động lùi 1 nấc xuống gói mềm hơn trong <DOCUMENT_CONTEXT> kèm ưu đãi hiện có trong 1-2 câu ngắn.",
                        "Dạ nếu mình muốn trải nghiệm nhẹ nhàng tiết kiệm trước thì bên em có gói cơ bản chi phí rất mềm trong bảng giá, vẫn trọn gói quyền lợi đi kèm ạ.\n\nAnh/Chị muốn thử gói cơ bản này trước hay nhận mã ưu đãi giảm giá ạ?",
                        "CẤM chuyển nhân viên (HANDOFF) hoặc buông xuôi 'cứ suy nghĩ thêm' khi khách chê đắt; CẤM liệt kê tràn lan cả bảng giá.",
                        94,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000006"),
                        "SALES_SOCIAL_PROOF_REASSURANCE",
                        "[Tâm lý Thuyết phục - Cialdini] Bằng Chứng Thực Tế & Lộ Trình Minh Bạch",
                        "SALES_STRATEGY",
                        "Kích hoạt khi khách hỏi bao lâu thấy hiệu quả, mấy buổi thì đỡ, quy trình gồm gì hoặc liệu có hợp với mình không.",
                        "bao lâu, mấy buổi, khi nào, thời gian, thấy hiệu quả, có hết không, có đỡ không, hiệu quả không, hợp không, kết quả, là làm gì, gồm gì",
                        "1. Giải thích trực diện cảm nhận cải thiện ngay từ buổi đầu và các bước cốt lõi trong <DOCUMENT_CONTEXT> chỉ trong 1-2 câu ngắn gọn. 2. Kết bằng 1 câu hỏi lựa chọn A/B dưới 15 chữ.",
                        "Dạ ngay sau buổi đầu (60-90 phút) mình sẽ thấy nhẹ hẳn vùng căng mỏi nhờ ấn huyệt mở cơ và chườm thảo dược ấm ạ.\n\nAnh/Chị muốn trải nghiệm thử 1 buổi lẻ trước hay tham khảo gói ưu đãi ạ?",
                        "Không hứa hẹn phóng đại ngoài <DOCUMENT_CONTEXT>; không viết giải thích dài quá 2 câu.",
                        84,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000007"),
                        "SALES_MICRO_COMMITMENT_CLOSE",
                        "[Đàm phán Chốt Đơn - Cialdini] Chốt Mềm Bằng Cam Kết Nhỏ & Lựa Chọn Kép (Micro-Commitment)",
                        "SALES_STRATEGY",
                        "Kích hoạt khi khách đã nắm thông tin, có tín hiệu quan tâm (WARM/HOT) hoặc ở giai đoạn SOFT_BOOKING_CLOSE.",
                        "đặt lịch, giữ lịch, qua xem, đăng ký, làm thử, trải nghiệm, cuối tuần, rảnh, khung giờ, sáng hay chiều, tư vấn kỹ hơn, địa chỉ",
                        "1. Thay câu hỏi đóng (Có/Không) bằng câu hỏi Lựa chọn Kép dưới 15 chữ (Sáng hay Chiều, Gói cơ bản hay Chuyên sâu). 2. Gắn 1 quyền lợi giữ chỗ/ưu đãi từ <DOCUMENT_CONTEXT> để khách dễ dàng gật đầu.",
                        "Dạ để bên em chuẩn bị phòng chu đáo không phải chờ đợi, Anh/Chị tiện ghé khung giờ Sáng hay Chiều để em giữ lịch ưu tiên trước ạ?",
                        "Không giục giã thô thiển; không viết quá 2 câu.",
                        89,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000008"),
                        "COMM_MIRROR_AND_PACE",
                        "[Giao tiếp NLP] Đồng Điệu Nhịp Thở 35 Chữ & Cắt Gọn Từ Thừa (Ultra-Concise Pacing)",
                        "COMMUNICATION",
                        "Kỹ thuật NLP Mirroring & Pacing giúp tin nhắn ngắn gọn như người thật chat trên điện thoại, đọc lướt 3 giây hiểu ngay.",
                        "nhắn ngắn, hỏi nhanh, tâm sự, chia sẻ, tư vấn tự nhiên, messenger, thi thoảng, mỏi, giá",
                        "1. Khách nhắn càng ngắn, Bot càng phải trả lời CỰC KỲ NGẮN GỌN: tối đa 2 câu ở đoạn 1 (<= 35 chữ) + 1 câu hỏi ở đoạn 2 (<= 15 chữ). 2. Xóa bỏ 100% câu văn giải thích nguyên nhân chung chung ('Điều này thường do...'). 3. Tuyệt đối không chào lại từ lượt 2.",
                        "Dạ nếu thi thoảng mới mỏi thì gói Massage cơ bản 60 phút (399.000 VNĐ) là vừa vặn nhất để giãn cơ nhẹ nhàng ạ.\n\nAnh/Chị muốn đặt lịch trải nghiệm vào khung giờ Sáng hay Chiều ạ?",
                        "CẤM viết đoạn văn dài trên 3 câu; CẤM dùng văn phong công văn hay giải thích lan man.",
                        86,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000009"),
                        "COMM_NO_REPEAT_MEMORY",
                        "[Giao tiếp Thực Chiến] Trí Nhớ Hội Thoại Xuyên Suốt & Cấm Hỏi Lặp (Active Memory)",
                        "COMMUNICATION",
                        "Ghi nhớ chính xác dữ kiện khách đã nói ở các lượt trước (tình trạng, tần suất 'thi thoảng'/'thường xuyên', giới tính, ngân sách) để chốt gọn.",
                        "lượt 2, hội thoại đa lượt, hỏi tiếp, thế còn, vậy còn, như đã nói, thi thoảng, thường xuyên, mới bị",
                        "1. Nếu khách vừa trả lời tần suất/mức độ (VD: 'Thi thoảng bị', 'Mới bị'), BẮT BUỘC chốt ngay 1 gói phù hợp nhất trong <DOCUMENT_CONTEXT> trong 1 câu ngắn. 2. TUYỆT ĐỐI CẤM hỏi lặp lại câu hỏi về tần suất hay tình trạng khách vừa trả lời!",
                        "Dạ nếu mình chỉ thi thoảng mới mỏi thì gói cơ bản 60 phút (399.000 VNĐ) bên em là vừa vặn và tiết kiệm nhất ạ.\n\nAnh/Chị tiện ghé khung giờ Sáng hay Chiều để em giữ phòng trước cho mình ạ?",
                        "CẤM hỏi lại 'tình trạng thường xuyên hay mới xuất hiện' khi khách đã trả lời 'thi thoảng bị'.",
                        91,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000010"),
                        "SALES_CROSS_INDUSTRY_CONSULT",
                        "[Đàm phán & Tư vấn Đa Ngành] Khớp Nối Đích Danh Giải Pháp & Câu Hỏi Định Hướng (Chris Voss Calibrated Match)",
                        "SALES_STRATEGY",
                        "Kỹ năng tư vấn & đàm phán đa ngành: Khớp nhu cầu khách hàng với đúng 1 giải pháp tối ưu nhất trong tài liệu chỉ trong 2 câu ngắn.",
                        "phù hợp, chọn loại nào, gói nào tốt, so sánh, khác nhau, bảo hành, đổi trả, giao hàng, lộ trình học, triển khai",
                        "1. Chọn ĐÚNG 1 gói/sản phẩm khớp nhất với nhu cầu khách vừa nêu trong <DOCUMENT_CONTEXT> (nêu tên + điểm mạnh nhất + giá trong 1 câu gọn). 2. Đặt 1 câu hỏi định hướng ngắn (< 15 chữ) giúp khách dễ chốt phương án.",
                        "Dạ với nhu cầu của mình, gói tiêu chuẩn bên em là tối ưu nhất cả về thời lượng lẫn chi phí trọn gói ạ.\n\nAnh/Chị muốn trải nghiệm gói này trước hay cần so sánh thêm gói chuyên sâu ạ?",
                        "Không tư vấn dàn trải nhiều gói cùng lúc; không viết dài quá 45 chữ.",
                        83,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000011"),
                        "SALES_ANTI_INFO_DUMPING_PACING",
                        "[Sale Pacing 3 Nấc] Nhử Giá Khởi Điểm & Chống Xả Sạch Thông Tin (Micro-Drip Pacing)",
                        "SALES_STRATEGY",
                        "Ngăn chặn tuyệt đối lỗi xả bảng giá dài dòng làm khách ngợp và im lặng (seen không rep).",
                        "giá, báo giá, bảng giá, giá sao, chi phí, nhiêu tiền, bao nhiêu, xin giá, giá cả, cho xin bảng giá, tư vấn giá",
                        "1. Khi khách hỏi 'giá' chung chung: Chỉ nêu mức giá khởi điểm dễ tiếp cận nhất trong <DOCUMENT_CONTEXT> kèm 1 quyền lợi đi kèm trong ĐÚNG 1 câu ngắn. 2. Đặt 1 câu hỏi lựa chọn A/B dưới 15 chữ để phân loại nhu cầu.",
                        "Dạ các gói dịch vụ bên em khởi điểm chỉ từ mức giá cơ bản theo bảng giá niêm yết, trọn gói quyền lợi và không nhận Tip ạ.\n\nHiện tại Anh/Chị đang quan tâm hạng mục nào để em báo đúng gói hợp nhất ạ?",
                        "CẤM liệt kê quá 2 mức giá trong 1 tin nhắn; CẤM viết quá 45 chữ.",
                        96,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000012"),
                        "SALES_APPOINTMENT_HOOK_CLOSE",
                        "[Sale Hormozi CLOSER] Bán Lịch Hẹn & Khống Chế Khung Giờ A/B Ngắn Gọn",
                        "SALES_STRATEGY",
                        "Chuyển hóa cuộc tư vấn thành lịch hẹn/đăng ký trực tiếp bằng câu hỏi khung giờ Sáng/Chiều cực gọn.",
                        "đặt lịch, giữ lịch, qua xem, đăng ký, làm thử, trải nghiệm, cuối tuần, rảnh, khung giờ, sáng hay chiều",
                        "1. Nêu 1 quyền lợi thực tế trong <DOCUMENT_CONTEXT> khi đặt lịch trước (giữ phòng riêng, giữ ưu đãi, không phải chờ đợi) trong 1 câu ngắn. 2. Hỏi câu lựa chọn khung giờ Sáng/Chiều dưới 15 chữ.",
                        "Dạ để bên em chuẩn bị phòng riêng chu đáo không phải chờ đợi, Anh/Chị tiện ghé khung giờ Sáng hay Chiều để em giữ lịch trước ạ?",
                        "Không bịa dịch vụ ngoài <DOCUMENT_CONTEXT>; không ép đặt lịch khi khách đang chê đắt.",
                        91,
                        true,
                        null,
                        now,
                        now
                )
        );
    }

    private static List<AgentSkillDto> createCoolingPersuasionSkills() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return List.of(
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000013"),
                        "PSYCH_ZERO_PRESSURE_TAKEAWAY",
                        "[Tâm lý Đàm phán - Sandler] Lùi Một Bước - Buông Bỏ Áp Lực Bán (Zero-Pressure Takeaway)",
                        "PSYCHOLOGY",
                        "Kỹ thuật Sandler Negative Reverse xóa bỏ áp lực bán hàng ngay khi khách do dự ('ừ', 'để xem', 'để nghĩ thêm'), hạ rào cản phòng vệ.",
                        "ừ, uk, ok, vâng, để xem, để chị xem, để xem thêm, để nghĩ đã, suy nghĩ thêm, để chị nghĩ, thôi, chưa cần, khi khác",
                        "1. Khi khách trả lời cụt lủn ('ừ', 'để xem'): Đồng ý nhẹ nhàng trong 1 câu ngắn, buông bỏ áp lực bán hàng. 2. Gợi ý nhẹ gói cơ bản tiết kiệm nhất trong <DOCUMENT_CONTEXT> bằng 1 câu hỏi không áp lực (Tổng <= 40 chữ).",
                        "Dạ không sao đâu ạ, mình cứ tham khảo thoải mái nhé. Nếu muốn thử nhẹ nhàng trước thì bên em có gói cơ bản chi phí rất mềm, Anh/Chị có muốn em gửi gói đó để dễ cân nhắc hơn không ạ?",
                        "CẤM dùng kỹ năng này khi khách đang hỏi gói rẻ hơn; CẤM viết dài dòng.",
                        95,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000014"),
                        "PSYCH_PATTERN_INTERRUPT_CURIOSITY",
                        "[Tâm lý Đàm phán - Pattern Interrupt] Phá Vỡ Khuôn Mẫu Bằng Câu Hỏi Vi Mô (Micro-Curiosity Hook)",
                        "PSYCHOLOGY",
                        "Cắt đứt phản xạ trả lời xã giao của khách bằng 1 góc nhìn ngắn gọn và 1 câu hỏi vi mô (< 15 chữ) sát nhu cầu thực tế.",
                        "ừ, ok, thế à, vậy à, để xem, im lặng, không biết, chưa rõ",
                        "1. Không lặp lại câu hỏi bán hàng cũ. 2. Đưa ra 1 nhận định ngắn 1 câu từ <DOCUMENT_CONTEXT> kèm 1 câu hỏi vi mô (< 15 chữ) để khách dễ dàng trả lời.",
                        "Dạ thường khách mới trải nghiệm lần đầu chọn gói cơ bản 60 phút là đã thấy nhẹ người rõ rệt rồi ạ. Hiện điều làm Anh/Chị băn khoăn nhất là về thời gian hay chi phí ạ?",
                        "Không hỏi câu dài phức tạp; không viết quá 2 câu.",
                        93,
                        true,
                        null,
                        now,
                        now
                ),
                new AgentSkillDto(
                        UUID.fromString("70000000-0000-0000-0000-000000000015"),
                        "SALES_LOSS_AVERSION_RESERVATION",
                        "[Đàm phán - Kahneman Loss Aversion] Giữ Suất Ưu Đãi 0đ Ràng Buộc (Privilege Reservation)",
                        "SALES_STRATEGY",
                        "Kích hoạt tâm lý sợ mất quyền lợi (Loss Aversion) tinh tế: Đề xuất giữ trước mã ưu đãi trong <DOCUMENT_CONTEXT> hoàn toàn 0đ ràng buộc.",
                        "để xem, giữ suất, ưu đãi, hết hạn, khuyến mãi, giữ chỗ, im lặng, suy nghĩ thêm, giá cao",
                        "1. Đề xuất giữ trước suất ưu đãi có trong <DOCUMENT_CONTEXT> cho khách hoàn toàn miễn phí 0đ ràng buộc (nếu bận không qua tự hủy không mất phí) chỉ trong 1-2 câu ngắn.",
                        "Dạ hay là em cứ tạm giữ suất ưu đãi hiện có cho Anh/Chị trước nhé, hoàn toàn 0đ ràng buộc, nếu mình bận chưa qua được cũng không sao hết ạ?",
                        "CẤM đe dọa thô thiển; CẤM tự bịa ưu đãi không có trong <DOCUMENT_CONTEXT>.",
                        94,
                        true,
                        null,
                        now,
                        now
                )
        );
    }
}
