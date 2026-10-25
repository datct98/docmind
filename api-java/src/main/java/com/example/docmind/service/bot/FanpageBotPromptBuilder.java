package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.SampleDialogueDto;
import com.example.docmind.entity.HarnessDimension;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.sales.PriceLadderTracker;
import com.example.docmind.service.sales.PriceLadderTracker.PriceLadderResult;
import com.example.docmind.service.security.PromptSecurityGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Bộ dựng Prompt chuyên biệt dành riêng cho BOT TRỰC TIN NHẮN FANPAGE FACEBOOK.
 *
 * <p>Khác biệt hoàn toàn với {@link PromptBuilder} (vốn phục vụ Q&A tra cứu nội bộ):
 * <ul>
 *   <li>Tuyệt đối KHÔNG trích dẫn nguồn ([1], [2]), KHÔNG tiêu đề Q&A, KHÔNG bảng biểu Markdown.</li>
 *   <li>Giọng văn tự nhiên, nhẹ nhàng, khéo léo, ngắn gọn như nhân viên trực Page thật đang gõ phím trên Messenger.</li>
 *   <li>Điều tiết nhịp độ tư vấn 3 giai đoạn (Không vội vã): Thấu cảm & Khai thác nhu cầu -> Tư vấn Giá trị -> Gợi mở Đặt lịch / Chốt sale.</li>
 *   <li>Tích hợp Bộ máy Đọc vị Tâm lý Khách hàng ({@link CustomerInsightDto}) &amp; Kho Kỹ năng Agent ({@link AgentSkillDto}).</li>
 *   <li>Cơ chế tự nhận diện giới hạn: Xuất nhãn điều khiển {@code [[HANDOFF: lý do]]} khi gặp thông tin ngoài tài liệu hoặc tình huống khó để đẩy cho nhân viên thật xử lý, tuyệt đối không bịa đặt.</li>
 * </ul>
 */
public final class FanpageBotPromptBuilder {

    private FanpageBotPromptBuilder() {
    }

    public static final int ALWAYS_ON_MAX_CHARS = 1200; // strictly max 1200 characters (~300 tokens)
    public static final int ACTIVE_SKILLS_MAX_CHARS = 260; // strictly max 260 chars across injected skills
    public static final int SINGLE_SKILL_INSTRUCTION_MAX_CHARS = 60;
    public static final int RECOMMENDED_STRATEGY_MAX_CHARS = 65;
    public static final int SAMPLE_DIALOGUES_MAX_CHARS = 450;

    public record BudgetAllocation(
            List<LearnedRuleDto> alwaysOnRules,
            List<LearnedRuleDto> onDemandRules,
            int totalAlwaysOnChars,
            double utilizationPercent,
            boolean capExceeded
    ) {}

    public static int ruleCharacterCount(LearnedRuleDto rule) {
        if (rule == null) return 0;
        int count = 0;
        if (rule.cueSignal() != null) count += rule.cueSignal().length();
        if (rule.generalizedRule() != null) count += rule.generalizedRule().length();
        if (rule.contrastiveEvidence() != null) count += rule.contrastiveEvidence().length();
        if ((rule.cueSignal() == null || rule.cueSignal().isBlank()) && rule.triggerCondition() != null) {
            count += rule.triggerCondition().length();
        }
        return count;
    }

    public static BudgetAllocation packAlwaysOnRules(List<LearnedRuleDto> candidateRules) {
        return packAlwaysOnRules(candidateRules, ALWAYS_ON_MAX_CHARS);
    }

    public static BudgetAllocation packAlwaysOnRules(List<LearnedRuleDto> candidateRules, int maxChars) {
        if (candidateRules == null || candidateRules.isEmpty()) {
            return new BudgetAllocation(List.of(), List.of(), 0, 0.0, false);
        }

        // Single-pass extraction: filter active, valid, unpoisoned rules and detect always_on markers
        List<LearnedRuleDto> activePool = new ArrayList<>(candidateRules.size());
        boolean anyMarkedAlwaysOn = false;
        for (LearnedRuleDto r : candidateRules) {
            if (r == null || !r.active() || r.generalizedRule() == null || r.generalizedRule().isBlank()) {
                continue;
            }
            if (PromptSecurityGuard.isPoisonedRuleAttempt(r.generalizedRule())) {
                continue;
            }
            activePool.add(r);
            if (r.alwaysOn()) {
                anyMarkedAlwaysOn = true;
            }
        }

        if (activePool.isEmpty()) {
            return new BudgetAllocation(List.of(), List.of(), 0, 0.0, false);
        }

        // Single-pass partition into invPool, domPool, and onDemand
        List<LearnedRuleDto> invPool = new ArrayList<>();
        List<LearnedRuleDto> domPool = new ArrayList<>();
        List<LearnedRuleDto> onDemand = new ArrayList<>();

        for (LearnedRuleDto r : activePool) {
            if (anyMarkedAlwaysOn && !r.alwaysOn()) {
                onDemand.add(r);
            } else if ("INV".equalsIgnoreCase(r.dimension())) {
                invPool.add(r);
            } else {
                domPool.add(r);
            }
        }

        // In-place sorting by confidence score descending
        invPool.sort((r1, r2) -> Double.compare(r2.confidenceScore(), r1.confidenceScore()));
        domPool.sort((r1, r2) -> Double.compare(r2.confidenceScore(), r1.confidenceScore()));

        List<LearnedRuleDto> alwaysOn = new ArrayList<>();
        int currentChars = 0;
        boolean anyExceeded = false;

        // Invariants (INV) have top priority and must never be pruned
        for (LearnedRuleDto inv : invPool) {
            alwaysOn.add(inv);
            currentChars += ruleCharacterCount(inv);
        }

        // Domain rules (DOM) are packed in descending order of confidence score until the 1200 char budget is reached
        for (LearnedRuleDto dom : domPool) {
            int chars = ruleCharacterCount(dom);
            if (currentChars + chars <= maxChars) {
                alwaysOn.add(dom);
                currentChars += chars;
            } else {
                anyExceeded = true;
                // Rules that don't fit in always-on budget remain accessible via on-demand retrieval
                onDemand.add(dom);
            }
        }

        double util = maxChars > 0 ? ((double) currentChars / maxChars) * 100.0 : 0.0;
        double roundedUtil = Math.round(util * 10.0) / 10.0;

        return new BudgetAllocation(alwaysOn, onDemand, currentChars, roundedUtil, anyExceeded);
    }

    public static String formatExperienceCard(LearnedRuleDto rule) {
        if (rule == null) {
            return "";
        }
        String dim = HarnessDimension.fromString(rule.dimension()).name();
        String cue = (rule.cueSignal() != null && !rule.cueSignal().isBlank())
                ? rule.cueSignal().strip()
                : (rule.triggerCondition() != null ? rule.triggerCondition().strip() : "N/A");
        String lesson = (rule.generalizedRule() != null) ? rule.generalizedRule().strip() : "";
        String evidence = (rule.contrastiveEvidence() != null && !rule.contrastiveEvidence().isBlank())
                ? rule.contrastiveEvidence().strip()
                : "N/A";

        return String.format(
                "<experience dimension=\"%s\">\n"
                + "  <cue_signal>%s</cue_signal>\n"
                + "  <rule_text>%s</rule_text>\n"
                + "  <contrastive_evidence>%s</contrastive_evidence>\n"
                + "</experience>",
                dim,
                PromptSecurityGuard.sanitizeUntrustedContent(cue),
                PromptSecurityGuard.sanitizeUntrustedContent(lesson),
                PromptSecurityGuard.sanitizeUntrustedContent(evidence)
        );
    }

    private static final java.util.regex.Pattern SPECIFIC_NEED_OR_SERVICE_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)\\b(mụn|nám|tàn\\s+nhang|sạm|đốm\\s+nâu|sẹo|rỗ|lỗ\\s+chân\\s+lông|căng\\s+bóng|trẻ\\s+hóa|"
            + "meso|collagen|laser|pico|detox|oxy|da\\s+dầu|da\\s+khô|da\\s+nhạy\\s+cảm|lão\\s+hóa|nhăn|viêm|thâm|"
            + "gội\\s+đầu|cổ\\s+vai\\s+gáy|thư\\s+giãn|massage|peel|hifu|b5|chấm\\s+mụn|kcn|kem\\s+chống\\s+nắng|"
            + "serum|sữa\\s+rửa\\s+mặt|khóa\\s+học|học\\s+viên|phần\\s+mềm|doanh\\s+nghiệp|enterprise|standard|pro|"
            + "căn\\s+hộ|biệt\\s+thự|dự\\s+án|xe\\s+máy|vision|vario|sh\\b|combo|"
            + "vai\\s+gáy|mỏi\\s+vai|đau\\s+vai|mỏi\\s+cổ|đau\\s+cổ|mỏi\\s+gáy|đau\\s+gáy|căng\\s+cơ|bó\\s+cơ|nặng\\s+đầu|nặng\\s+người|mỏi\\s+người|"
            + "đau\\s+mỏi|nhức\\s+mỏi|đau\\s+lưng|mỏi\\s+lưng|mỏi\\s+gối|đau\\s+gối|khớp\\s+gối|tê\\s+tay|tê\\s+chân|tê\\s+bì|đau\\s+đầu|mất\\s+ngủ|khó\\s+ngủ|stress|căng\\s+thẳng|"
            + "váy|đầm|áo\\s+sơ\\s+mi|áo\\s+khoác|áo\\s+blazer|quần\\s+âu|quần\\s+jean|set\\s+đồ|bảng\\s+size|size\\s+[smlx]|chất\\s+vải|"
            + "sofa|bàn\\s+ăn|bàn\\s+trà|ghế|tủ|giường|đệm|kích\\s+thước|chất\\s+liệu|lắp\\s+đặt|"
            + "gói\\s+cước|bản\\s+quyền|tích\\s+hợp|dùng\\s+thử|demo|"
            + "thẻ\\s+thành\\s+viên|vip)\\b"
    );

    public static final java.util.regex.Pattern WELLNESS_DISCOMFORT_PATTERN = java.util.regex.Pattern.compile(
            "(?isU).*(?:đau\\s+mỏi|nhức\\s+mỏi|mỏi\\s+mệt|mệt\\s+mỏi|uể\\s+oải|kiệt\\s+sức|cổ\\s+vai\\s+gáy|vai\\s+gáy|"
            + "(?:đau|mỏi|nhức|căng|cứng|tê|nặng)\\s+(?:cổ|vai|gáy|lưng|đầu|người|cơ|chân|tay|gối|khớp\\s+gối|thắt\\s+lưng)|"
            + "(?:cổ|vai|gáy|lưng|đầu|người|gối|tay|chân)\\s+(?:bị\\s+|đang\\s+|hơi\\s+)?(?:đau|mỏi|nhức|căng|cứng|tê|nặng)|"
            + "(?:hơi|khá|rất)\\s+(?:đau\\s+nhức|nhức\\s+mỏi|mỏi|đau)|"
            + "căng\\s+cơ|bó\\s+cơ|mất\\s+ngủ|khó\\s+ngủ|ngủ\\s+không\\s+sâu|ngủ\\s+chập\\s+chờn|"
            + "stress|căng\\s+thẳng|áp\\s+lực|ngồi\\s+nhiều|ngồi\\s+máy\\s+tính|văn\\s+phòng|khí\\s+huyết|lưu\\s+thông\\s+máu|tê\\s+bì).*"
    );

    public enum SymptomSeverity {
        NONE,
        UNSPECIFIED,
        MILD_OR_OCCASIONAL,
        CHRONIC_OR_DEEP
    }

    public static final java.util.regex.Pattern MILD_OR_OCCASIONAL_SYMPTOM_PATTERN = java.util.regex.Pattern.compile(
            "(?isU).*\\b(thi\\s+thoảng|thỉnh\\s+thoảng|đôi\\s+khi|lâu\\s+lâu|ít\\s+khi|"
            + "mới\\s+bị|mới\\s+xuất\\s+hiện|mới\\s+đau|mới\\s+mỏi|dạo\\s+này\\s+mới|"
            + "hơi\\s+mỏi|hơi\\s+đau|hơi\\s+nhức|mỏi\\s+nhẹ|đau\\s+nhẹ|nhẹ\\s+thôi|không\\s+nặng)\\b.*"
    );

    public static final java.util.regex.Pattern CHRONIC_OR_DEEP_SYMPTOM_PATTERN = java.util.regex.Pattern.compile(
            "(?isU).*\\b(thường\\s+xuyên|hay\\s+bị|bị\\s+lâu|lâu\\s+rồi|lâu\\s+ngày|kéo\\s+dài|mãn\\s+tính|"
            + "đau\\s+nhiều|mỏi\\s+nhiều|rất\\s+mỏi|rất\\s+đau|căng\\s+cứng|nặng\\s+mỏi|nặng\\s+người|"
            + "ngồi\\s+nhiều|ngồi\\s+máy\\s+tính|làm\\s+việc\\s+nhiều|dân\\s+văn\\s+phòng|chơi\\s+thể\\s+thao|tập\\s+gym|pickleball|golf)\\b.*"
    );

    public static SymptomSeverity detectSymptomSeverity(String text) {
        if (text == null || text.isBlank()) {
            return SymptomSeverity.NONE;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean matchesMild = MILD_OR_OCCASIONAL_SYMPTOM_PATTERN.matcher(lower).matches();
        boolean matchesChronic = CHRONIC_OR_DEEP_SYMPTOM_PATTERN.matcher(lower).matches();
        if (matchesMild && !lower.contains("thường xuyên") && !lower.contains("lâu ngày")) {
            return SymptomSeverity.MILD_OR_OCCASIONAL;
        }
        if (matchesChronic) {
            return SymptomSeverity.CHRONIC_OR_DEEP;
        }
        if (isWellnessOrDiscomfortMessage(text)) {
            return SymptomSeverity.UNSPECIFIED;
        }
        return SymptomSeverity.NONE;
    }

    public static final java.util.regex.Pattern CONSULTATIVE_OPTION_FOLLOWUP_PATTERN = java.util.regex.Pattern.compile(
            "(?isU).*\\b(60\\s*(?:phút|p)|70\\s*(?:phút|p)|80\\s*(?:phút|p)|90\\s*(?:phút|p)|100\\s*(?:phút|p)|120\\s*(?:phút|p)|"
            + "gói\\s+cơ\\s+bản|cơ\\s+bản\\s+trước|gói\\s+nâng\\s+cao|gói\\s+chuyên\\s+sâu|chuyên\\s+sâu|gói\\s+phục\\s+hồi|"
            + "ngâm\\s+bồn|xông\\s+hơi|phòng\\s+vip|phòng\\s+riêng|toàn\\s+thân|cổ\\s+vai\\s+gáy|vai\\s+gáy)\\b.*"
    );

    public static boolean isSymptomFollowUpTurn(String currentMessage, List<FanpageBotRequest.ConversationTurn> history) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return false;
        }
        SymptomSeverity currentSeverity = detectSymptomSeverity(currentMessage);
        boolean matchesOptionFollowup = currentMessage.strip().length() <= 60
                && CONSULTATIVE_OPTION_FOLLOWUP_PATTERN.matcher(currentMessage).matches();
        if (currentSeverity != SymptomSeverity.MILD_OR_OCCASIONAL
                && currentSeverity != SymptomSeverity.CHRONIC_OR_DEEP
                && !matchesOptionFollowup) {
            return false;
        }
        if (history == null || history.isEmpty()) {
            return currentMessage.strip().length() <= 60 || isWellnessOrDiscomfortMessage(currentMessage);
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            FanpageBotRequest.ConversationTurn t = history.get(i);
            if (t == null || t.content() == null) continue;
            String c = t.content().toLowerCase(java.util.Locale.ROOT);
            if (isWellnessOrDiscomfortMessage(c)
                    || isServiceExplanationOrProcessInquiry(c)
                    || c.contains("mỏi") || c.contains("đau") || c.contains("cổ vai")
                    || c.contains("massage") || c.contains("dưỡng sinh")
                    || c.contains("mới xuất hiện") || c.contains("thường xuyên") || c.contains("thi thoảng")
                    || c.contains("60 phút") || c.contains("80 phút") || c.contains("chuyên sâu")
                    || c.contains("399.000") || c.contains("530.000") || c.contains("580.000")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWellnessOrDiscomfortMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return WELLNESS_DISCOMFORT_PATTERN.matcher(text).matches();
    }

    public static final java.util.regex.Pattern SERVICE_EXPLANATION_OR_PROCESS_PATTERN = java.util.regex.Pattern.compile(
            "(?isU).*(?:\\b(?:quy\\s+trình|các\\s+bước|mấy\\s+bước|những\\s+bước\\s+nào|"
            + "gồm\\s+(?:những\\s+|các\\s+)?(?:gì|j|bước\\s+nào)|bao\\s+gồm\\s+(?:những\\s+|các\\s+)?(?:gì|j)|"
            + "làm\\s+(?:những\\s+|cái\\s+|các\\s+)?(?:gì|j)|là\\s+làm\\s+(?:gì|j)|"
            + "là\\s+trị\\s+(?:gì|j)|trị\\s+(?:những\\s+|cái\\s+)?(?:gì|j)|"
            + "(?:massage|trị\\s+liệu|dưỡng\\s+sinh|liệu\\s+trình|gói\\s+này|dịch\\s+vụ\\s+này)\\s+(?:là\\s+(?:gì|j)|như\\s+(?:thế\\s+)?nào|ra\\s+sao|thế\\s+nào)|"
            + "tập\\s+trung\\s+(?:vào\\s+)?(?:gì|j|đâu)|tác\\s+dụng\\s+(?:gì|j)|công\\s+dụng\\s+(?:gì|j)|"
            + "có\\s+(?:bấm\\s+huyệt|chườm\\s+ngải\\s+cứu|xông\\s+hơi|ngâm\\s+bồn|đau\\s+không))\\b).*"
    );

    public static boolean isServiceExplanationOrProcessInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (isTrialOrBookingAcceptanceMessage(text) || isPriceObjectionOrDownsellMessage(text)) {
            return false;
        }
        return SERVICE_EXPLANATION_OR_PROCESS_PATTERN.matcher(text).matches();
    }

    public static final java.util.regex.Pattern PACKAGE_COMPARISON_OR_SPECIFIC_CODE_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)(?:\\b(?:khác\\s+gì|khác\\s+nhau|so\\s+sánh|chênh\\s+giá|chênh\\s+nhau|tại\\s+sao\\s+chênh|sao\\s+chênh|hơn\\s+gì|phân\\s+biệt|gói\\s+\\d{2,4}k|\\d{2,4}k\\s*(?:và|với|hay)\\s*\\d{2,4}k|[A-Z]{2,4}[-_]?\\d{1,3})\\b)"
    );

    private static final java.util.regex.Pattern BROAD_PRICE_QUERY_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)\\b(giá|báo\\s+giá|bảng\\s+giá|mức\\s+giá|bảng\\s+phí|chi\\s+phí|bao\\s+nhiêu|rổ\\s+giá|mấy\\s+tiền|nhiêu\\s+tiền)\\b"
    );

    public static final java.util.regex.Pattern PHONE_EXTRACT_PATTERN = java.util.regex.Pattern.compile(
            "(?:\\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[.\\-\\s]?\\d){7,8}\\b"
    );

    public static final java.util.regex.Pattern CONCRETE_TIME_SLOT_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)\\b(\\d{1,2}\\s*(?:h|giờ|:)\\s*\\d{0,2}(?:\\s*[-–]\\s*\\d{1,2}\\s*(?:h|giờ)?)?(?:\\s*(?:sáng|trưa|chiều|tối|hôm\\s+nay|ngày\\s+mai|mai|mốt))?|"
            + "(?:tầm|buổi|khung)\\s+(?:sáng|trưa|chiều|tối)(?:\\s+(?:nay|mai|mốt))?|"
            + "(?:sáng|trưa|chiều|tối)\\s+(?:nay|mai|mốt|thứ\\s*[2-7]|chủ\\s+nhật|cuối\\s+tuần))\\b"
    );

    public static final java.util.regex.Pattern DATE_SLOT_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)\\b(hôm\\s+nay|ngày\\s+mai|trưa\\s+mai|sáng\\s+mai|chiều\\s+mai|tối\\s+mai|\\bmai\\b|ngày\\s+mốt|thứ\\s*[2-7]|chủ\\s+nhật|cuối\\s+tuần)\\b"
    );

    public static final java.util.regex.Pattern SELF_ARRANGE_OR_NO_CALL_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)(?:(?:không|ko|k|khỏi)\\s+cần.*?(?:tự\\s+tới|tự\\s+qua|tự\\s+đến|gọi)|"
            + "\\b(?:tới\\s+giờ\\s+(?:tôi|mình|anh|chị|em)?\\s*tự\\s+(?:tới|qua|đến)|tự\\s+sx|tự\\s+sắp\\s+xếp|tự\\s+tới|tự\\s+qua\\s+được)\\b)"
    );

    public static final java.util.regex.Pattern REDUNDANT_QUESTION_COMPLAINT_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)(?:còn\\s+hỏi\\s+(?:sáng\\s+hay\\s+chiều|khung\\s+giờ|lại)|không\\s+lẽ\\s+đêm|đặt\\s+rồi\\s+còn\\s+hỏi|vừa\\s+(?:bảo|nói|chốt).*?còn\\s+hỏi|hỏi\\s+lặp|hỏi\\s+đi\\s+hỏi\\s+lại)"
    );

    public static final java.util.regex.Pattern PRICE_COMPARISON_OBJECTION_PATTERN = java.util.regex.Pattern.compile(
            "(?iU)(?:đi\\s+nặn\\s+mụn|nặn\\s+mụn\\s+chỉ|chỗ\\s+khác\\s+chỉ|bên\\s+ngoài\\s+chỉ|spa\\s+khác\\s+chỉ|\\b\\d{2,3}\\s*k?\\s*/\\s*buổi)\\b"
    );

    public record ConversationBookingState(
            String capturedPhone,
            boolean hasPhoneInCurrentTurn,
            String confirmedTimeSlot,
            String confirmedDate,
            boolean customerDeclinedCallOrSelfArranges,
            boolean isRedundantQuestionComplaint,
            boolean isPostBookingWrapUp,
            boolean isPriceComparisonObjection
    ) {
        public boolean hasConfirmedTimeSlot() {
            return confirmedTimeSlot != null && !confirmedTimeSlot.isBlank();
        }

        public boolean hasCapturedPhone() {
            return capturedPhone != null && !capturedPhone.isBlank();
        }
    }

    public static ConversationBookingState analyzeConversationBookingState(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history) {
        List<String> userTurns = new ArrayList<>();
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn t : history) {
                if (t != null && t.content() != null && !"assistant".equalsIgnoreCase(t.role())) {
                    userTurns.add(t.content());
                }
            }
        }
        return analyzeBookingStateFromUserTurns(customerMessage, userTurns);
    }

    public static ConversationBookingState analyzeConversationBookingStateFromStrings(
            String customerMessage,
            List<String> rawHistory) {
        List<String> userTurns = new ArrayList<>();
        if (rawHistory != null) {
            for (String line : rawHistory) {
                if (line == null || line.isBlank()) continue;
                String lower = line.strip().toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("assistant:") || lower.startsWith("nhân viên page:") || lower.startsWith("bot:")
                        || lower.startsWith("dạ ") || lower.contains("bên em") || lower.contains("cho mình ạ")) {
                    continue;
                }
                String cleaned = line.replaceFirst("(?i)^(?:user|khách\\s+hàng|customer)\\s*:\\s*", "").strip();
                userTurns.add(cleaned);
            }
        }
        return analyzeBookingStateFromUserTurns(customerMessage, userTurns);
    }

    private static ConversationBookingState analyzeBookingStateFromUserTurns(
            String customerMessage,
            List<String> priorUserTurns) {
        String phone = null;
        String timeSlot = null;
        String dateSlot = null;
        boolean declinedCall = false;

        List<String> allUserMsgs = new ArrayList<>();
        if (priorUserTurns != null) {
            allUserMsgs.addAll(priorUserTurns);
        }
        if (customerMessage != null && !customerMessage.isBlank()) {
            allUserMsgs.add(customerMessage);
        }

        for (String uMsg : allUserMsgs) {
            if (uMsg == null || uMsg.isBlank()) continue;
            // Khi khách đang phàn nàn về câu hỏi thừa ("12h30 là buổi trưa rồi còn hỏi sáng hay chiều"), không lấy chữ "sáng hay chiều" làm giờ mới
            boolean isComplaintLine = REDUNDANT_QUESTION_COMPLAINT_PATTERN.matcher(uMsg).find();
            java.util.regex.Matcher pm = PHONE_EXTRACT_PATTERN.matcher(uMsg);
            if (pm.find()) {
                phone = pm.group().replaceAll("[.\\-\\s]", "");
            }
            java.util.regex.Matcher tm = CONCRETE_TIME_SLOT_PATTERN.matcher(uMsg);
            while (tm.find()) {
                String candidate = tm.group(1).strip();
                if (isComplaintLine && !candidate.matches(".*\\d.*")) {
                    continue;
                }
                if (timeSlot != null && timeSlot.matches(".*\\d.*") && !candidate.matches(".*\\d.*")) {
                    continue;
                }
                timeSlot = candidate;
            }
            java.util.regex.Matcher dm = DATE_SLOT_PATTERN.matcher(uMsg);
            if (dm.find()) {
                dateSlot = dm.group(1).strip();
            }
            if (SELF_ARRANGE_OR_NO_CALL_PATTERN.matcher(uMsg).find()) {
                declinedCall = true;
            }
        }

        boolean hasPhoneCurrent = customerMessage != null && PHONE_EXTRACT_PATTERN.matcher(customerMessage).find();
        boolean isComplaint = customerMessage != null && REDUNDANT_QUESTION_COMPLAINT_PATTERN.matcher(customerMessage).find();
        boolean isPriceComparison = customerMessage != null && PRICE_COMPARISON_OBJECTION_PATTERN.matcher(customerMessage).find();

        String lowerCur = customerMessage != null ? customerMessage.strip().toLowerCase(java.util.Locale.ROOT) : "";
        boolean isTrialOrBookingAccept = isTrialOrBookingAcceptanceMessage(lowerCur);
        boolean isShortFollowUpAfterBooking = !isTrialOrBookingAccept && lowerCur.matches(
                "(?isU)^(?:\\d+\\s*(?:p|phút)\\s+nữa.*|.*\\bmai\\s+nhé.*|.*\\btrưa\\s+mai.*|tự\\s+sx.*|k\\s+cần\\s+đâu.*|không\\s+cần\\s+đâu.*|(?:ok|vâng|được\\s+rồi|chốt)(?:\\s+(?:em|nhé|nha|ạ))?[.!,~]*)$"
        );
        boolean isWrapUp = isComplaint
                || (timeSlot != null && phone != null)
                || (timeSlot != null && declinedCall)
                || (timeSlot != null && phone != null && isShortFollowUpAfterBooking && priorUserTurns != null && !priorUserTurns.isEmpty());

        return new ConversationBookingState(
                phone,
                hasPhoneCurrent,
                timeSlot,
                dateSlot,
                declinedCall,
                isComplaint,
                isWrapUp,
                isPriceComparison
        );
    }

    public static boolean hasSpecificNeedOrServiceInConversation(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history) {
        return hasSpecificNeedOrServiceInConversation(customerMessage, history, null);
    }

    public static boolean hasSpecificNeedOrServiceInConversation(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            PromptBuilder.PagePersona pagePersona) {
        PagePersonaConfig cfg = pagePersona != null ? pagePersona.resolvedConfig() : null;
        if (customerMessage != null && (SPECIFIC_NEED_OR_SERVICE_PATTERN.matcher(customerMessage).find()
                || PACKAGE_COMPARISON_OR_SPECIFIC_CODE_PATTERN.matcher(customerMessage).find()
                || (cfg != null && cfg.matchesDomainKeyword(customerMessage)))) {
            return true;
        }
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn turn : history) {
                if (turn != null && !"assistant".equalsIgnoreCase(turn.role())
                        && turn.content() != null
                        && (SPECIFIC_NEED_OR_SERVICE_PATTERN.matcher(turn.content()).find()
                        || PACKAGE_COMPARISON_OR_SPECIFIC_CODE_PATTERN.matcher(turn.content()).find()
                        || (cfg != null && cfg.matchesDomainKeyword(turn.content())))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Nhận diện khi khách hỏi so sánh các gói cụ thể hoặc chênh lệch giá (VD: "gói 670k và 780k khác gì nhau").
     */
    public static boolean isPackageComparisonInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PACKAGE_COMPARISON_OR_SPECIFIC_CODE_PATTERN.matcher(text).find();
    }

    /**
     * Nhận diện khi khách hỏi giá chung chung ("giá", "báo giá", "chi phí sao", "bảng giá")
     * mà chưa nêu bất kỳ tình trạng da hay dịch vụ/sản phẩm cụ thể nào trong tin nhắn hoặc lịch sử.
     */
    public static boolean isBroadUnspecifiedPriceInquiry(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history) {
        return isBroadUnspecifiedPriceInquiry(customerMessage, history, null);
    }

    public static boolean isBroadUnspecifiedPriceInquiry(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            PromptBuilder.PagePersona pagePersona) {
        if (customerMessage == null || customerMessage.isBlank()) {
            return false;
        }
        if (!BROAD_PRICE_QUERY_PATTERN.matcher(customerMessage).find()) {
            return false;
        }
        if (isPackageComparisonInquiry(customerMessage)
                || isPriceObjectionOrDownsellMessage(customerMessage)) {
            return false;
        }
        return !hasSpecificNeedOrServiceInConversation(customerMessage, history, pagePersona);
    }

    public enum SalesStage {
        DISCOVERY("DISCOVERY",
                "Nấc 1 (Khảo sát, 2-3 câu ngắn): Đồng cảm trước, nêu giá khởi điểm từ <DOCUMENT_CONTEXT>, hỏi nhu cầu ưu tiên."),
        VALUE_CONSULTATION("VALUE_CONSULTATION",
                "Nấc 2 (Tư vấn, 2-3 câu ngắn): Trả lời thẳng 1 gói + đơn giá từ <DOCUMENT_CONTEXT>, kết câu hỏi 2 phương án."),
        SOFT_BOOKING_CLOSE("SOFT_BOOKING_CLOSE",
                "Nấc 3 (Chốt lịch, 2-3 câu ngắn): Khóa quyền lợi; chưa chọn giờ hỏi Sáng/Chiều, đã có giờ chỉ xin SĐT.");

        private final String code;
        private final String guidance;

        SalesStage(String code, String guidance) {
            this.code = code;
            this.guidance = guidance;
        }

        public String code() {
            return code;
        }

        public String guidance() {
            return guidance;
        }
    }

    public static String compactPromptText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        // Prefer cutting cleanly at the first sentence boundary if within [45..maxChars]
        int dotIdx = normalized.indexOf(". ");
        if (dotIdx >= 45 && dotIdx + 1 <= maxChars) {
            return normalized.substring(0, dotIdx + 1);
        }
        int lastSpace = normalized.lastIndexOf(' ', maxChars - 1);
        int cut = lastSpace >= maxChars / 2 ? lastSpace : maxChars - 1;
        return normalized.substring(0, cut).strip() + "…";
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage) {
        String honorific = resolveCustomerHonorific(customerMessage, history, customerName, pagePersona);
        return build(customerMessage, customerName, history, chunks, appliedRules, pagePersona, salesStage, honorific, null, null);
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage,
                               String resolvedHonorific) {
        return build(customerMessage, customerName, history, chunks, appliedRules, pagePersona, salesStage, resolvedHonorific, null, null);
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage,
                               String resolvedHonorific,
                               CustomerInsightDto customerInsight,
                               List<AgentSkillDto> activeSkills) {
        return build(customerMessage, customerName, history, chunks, appliedRules, pagePersona,
                salesStage, resolvedHonorific, customerInsight, activeSkills, null, null);
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage,
                               String resolvedHonorific,
                               CustomerInsightDto customerInsight,
                               List<AgentSkillDto> activeSkills,
                               String mocSummary) {
        return build(customerMessage, customerName, history, chunks, appliedRules, pagePersona,
                salesStage, resolvedHonorific, customerInsight, activeSkills, mocSummary, null);
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage,
                               String resolvedHonorific,
                               CustomerInsightDto customerInsight,
                               List<AgentSkillDto> activeSkills,
                               PriceLadderResult priceLadderResult) {
        return build(customerMessage, customerName, history, chunks, appliedRules, pagePersona,
                salesStage, resolvedHonorific, customerInsight, activeSkills, null, priceLadderResult);
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage,
                               String resolvedHonorific,
                               CustomerInsightDto customerInsight,
                               List<AgentSkillDto> activeSkills,
                               String mocSummary,
                               PriceLadderResult priceLadderResult) {
        return build(customerMessage, customerName, history, chunks, appliedRules, pagePersona,
                salesStage, resolvedHonorific, customerInsight, activeSkills, mocSummary, priceLadderResult, List.of());
    }

    public static String formatSampleDialogueCard(SampleDialogueDto dialogue, String honorific) {
        if (dialogue == null || !dialogue.active()) {
            return "";
        }
        if (dialogue.qualityLabel() != null && !dialogue.qualityLabel().isBlank()
                && !"APPROVED_GOOD".equalsIgnoreCase(dialogue.qualityLabel())) {
            return "";
        }
        String code = dialogue.dialogueCode() != null ? dialogue.dialogueCode() : "FD";
        String userText = compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(dialogue.userSummary()), 80);
        String botText = compactPromptText(
                adaptHonorificInSkillText(PromptSecurityGuard.sanitizeUntrustedContent(dialogue.expectedBotAnswer()), honorific),
                145
        );
        return String.format(
                "<sample_dialogue id=\"%s\" do_not_copy_literally=\"%s\">Khách: %s -> Tư vấn mẫu: %s</sample_dialogue>",
                PromptSecurityGuard.sanitizeUntrustedContent(code),
                dialogue.doNotCopyLiterally(),
                userText,
                botText
        );
    }

    public static String build(String customerMessage,
                               String customerName,
                               List<FanpageBotRequest.ConversationTurn> history,
                               List<ReciprocalRankFusion.FusedChunk> chunks,
                               List<LearnedRuleDto> appliedRules,
                               PromptBuilder.PagePersona pagePersona,
                               SalesStage salesStage,
                               String resolvedHonorific,
                               CustomerInsightDto customerInsight,
                               List<AgentSkillDto> activeSkills,
                               String mocSummary,
                               PriceLadderResult priceLadderResult,
                               List<SampleDialogueDto> sampleDialogues) {
        StringBuilder sb = new StringBuilder();

        String pageName = (pagePersona != null && pagePersona.name() != null && !pagePersona.name().isBlank())
                ? PromptSecurityGuard.sanitizeUntrustedContent(pagePersona.name().strip())
                : "Fanpage";
        String honorific = (resolvedHonorific != null && !resolvedHonorific.isBlank())
                ? resolvedHonorific.strip()
                : resolveCustomerHonorific(customerMessage, history, customerName, pagePersona);
        boolean isFirstTurn = (history == null || history.isEmpty());
        ConversationBookingState bookingState = analyzeConversationBookingState(customerMessage, history);
        boolean hasPhoneLead = bookingState.hasPhoneInCurrentTurn();
        boolean asksExplicitPrice = customerMessage != null
                && customerMessage.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(?:giá|bao\\s+nhiêu|chi\\s+phí).*");
        boolean isBroadUnspecifiedPrice = !hasPhoneLead && isBroadUnspecifiedPriceInquiry(customerMessage, history, pagePersona);
        boolean isBroadGenericDiscovery = isFirstTurn
                && !hasPhoneLead
                && !hasSpecificNeedOrServiceInConversation(customerMessage, history, pagePersona)
                && !isPriceObjectionOrDownsellMessage(customerMessage)
                && !isTrialOrBookingAcceptanceMessage(customerMessage);
        boolean hasPhoneLeadOnly = hasPhoneLead && !asksExplicitPrice;
        PagePersonaConfig explicitPersonaCfg = pagePersona != null ? pagePersona.personaConfig() : null;
        String selfPronoun = (explicitPersonaCfg != null && explicitPersonaCfg.selfPronoun() != null && !explicitPersonaCfg.selfPronoun().isBlank())
                ? PromptSecurityGuard.sanitizeUntrustedContent(explicitPersonaCfg.selfPronoun().strip())
                : "em";
        String selfPronounLabel = selfPronoun.contains("/") ? selfPronoun : (selfPronoun + " / bên em");

        // 1. VAI TRÒ & QUY TẮC BẮT BUỘC (TINH GỌN, PHI ĐẶC THÙ NGÀNH, KHÔNG LẶP LẠI)
        sb.append("# VAI TRÒ: Tư vấn viên ").append(pageName).append(", tiếng Việt tự nhiên, ấm áp.\n\n");

        sb.append("# QUY TẮC BẮT BUỘC\n");
        sb.append("1. GROUNDED: Chỉ báo dịch vụ, giá, ưu đãi trong <DOCUMENT_CONTEXT>; cấm tự bịa.\n");
        sb.append("2. THẤU CẢM TRIỆU CHỨNG (EMPATHY FIRST): Khách đang gặp triệu chứng mệt mỏi thể chất (đau mỏi cổ vai, mất ngủ, stress, đau lưng...): BẮT BUỘC dành câu đầu tiên để đồng cảm chân thành và công nhận sự khó chịu; CẤM xả thông tin dồn dập hay chốt lịch ép buộc.\n");
        sb.append("3. CHỐNG XẢ SẠCH THÔNG TIN (ANTI-DUMPING) & BÁN LỊCH HẸN: 1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế A/B. Mỗi lượt phản hồi BẮT BUỘC chỉ từ 2 đến 4 câu văn ngắn gọn, súc tích (tối đa 2 đoạn ngắn). Tuyệt đối không viết thành bài văn dài.\n");
        sb.append("4. DOWN-SELL TƯ VẤN KHÉO LÉO & HANDOFF: Khách chê đắt: thấu hiểu tâm lý giá, CẤM thanh minh hay tranh cãi; TUYỆT ĐỐI CẤM xuất [[HANDOFF]] và CẤM buông xuôi 'cứ cân nhắc thêm'. Quy tắc chuyển tiếp hạ bậc (Consultative Down-sell Transition): xác nhận lần đầu thử dịch vụ thì chọn gói vừa vặn để trải nghiệm tay nghề trước là hoàn toàn hợp lý. Chỉ [[HANDOFF: lý do]] khi ngoài tài liệu.\n");
        String forbiddenHonorificLabel = "Anh/Chị".equalsIgnoreCase(honorific)
                ? "bạn"
                : ("Anh".equalsIgnoreCase(honorific) ? "Chị" : "Anh");
        sb.append("5. XƯNG HÔ: Xưng '").append(selfPronounLabel).append("', gọi '").append(honorific).append("' 100% (cấm gọi '")
          .append(forbiddenHonorificLabel).append("', cấm xưng 'chúng tôi', 'tôi', 'mình', cấm bịa tên riêng). ");
        if (isFirstTurn) {
            sb.append("Lượt đầu: Mở đầu bằng 'Dạ ").append(selfPronoun).append(" chào ").append(honorific).append(" ạ! '.\n");
        } else {
            sb.append("Lượt tiếp: CẤM chào lại, trả lời thẳng bằng 'Dạ ...'.\n");
        }
        sb.append("6. ĐỊNH DẠNG: Gọi đủ Tên gói + Giá; CẤM gạch đầu dòng (-, •), trích dẫn [1], bảng Markdown.\n\n");

        // 2. HỒ SƠ NHÂN CÁCH FANPAGE
        sb.append("# HỒ SƠ NHÂN CÁCH FANPAGE\n");
        if (pagePersona != null && pagePersona.hasContent()) {
            if (pagePersona.name() != null && !pagePersona.name().isBlank()) {
                sb.append("- Tên: ").append(PromptSecurityGuard.sanitizeUntrustedContent(pagePersona.name().strip())).append('\n');
            }
            if (pagePersona.industry() != null && !pagePersona.industry().isBlank()) {
                sb.append("- Ngành: ").append(PromptSecurityGuard.sanitizeUntrustedContent(pagePersona.industry().strip())).append('\n');
            }
            if (explicitPersonaCfg != null && explicitPersonaCfg.botName() != null && !explicitPersonaCfg.botName().isBlank()) {
                sb.append("- Nhân vật AI: ").append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(explicitPersonaCfg.botName().strip()), 40)).append('\n');
            }
            if (pagePersona.description() != null && !pagePersona.description().isBlank()) {
                String desc = PromptSecurityGuard.sanitizeUntrustedContent(pagePersona.description().strip());
                if ("Anh".equalsIgnoreCase(honorific)) {
                    desc = desc.replaceAll("(?iU)gọi\\s+khách\\s+là\\s+['\"]?chị['\"]?", "gọi khách là 'Anh'")
                               .replaceAll("(?iU)xưng\\s+em\\s*-\\s*chị", "xưng Em - Anh");
                }
                sb.append("- Phong cách: ").append(compactPromptText(desc, 48)).append('\n');
            } else if (explicitPersonaCfg != null && explicitPersonaCfg.toneStyle() != null && !explicitPersonaCfg.toneStyle().isBlank()) {
                sb.append("- Phong cách: ").append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(explicitPersonaCfg.toneStyle().strip()), 48)).append('\n');
            }
            if (explicitPersonaCfg != null && !explicitPersonaCfg.safeForbiddenWords().isEmpty()) {
                sb.append("- Từ cấm kỵ: ").append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(String.join(", ", explicitPersonaCfg.safeForbiddenWords())), 80)).append('\n');
            }
        }
        String cleanedCustomerName = sanitizeCustomerDisplayName(customerName, honorific);
        if (isFirstTurn && cleanedCustomerName != null && !cleanedCustomerName.isBlank()) {
            sb.append("- Khách: ").append(PromptSecurityGuard.sanitizeUntrustedContent(cleanedCustomerName)).append('\n');
        }
        sb.append('\n');

        // 3. NHỊP ĐỘ TƯ VẤN HIỆN TẠI (TINH GỌN, KHÔNG LẶP LẠI VỚI LƯU Ý CUỐI)
        SalesStage stage = salesStage != null ? salesStage : SalesStage.DISCOVERY;
        sb.append("# NHỊP ĐỘ TƯ VẤN HIỆN TẠI (").append(stage.code()).append(")\n");
        if (bookingState.hasConfirmedTimeSlot() || bookingState.hasCapturedPhone()) {
            sb.append("- Đã ghi nhận: ");
            if (bookingState.hasConfirmedTimeSlot()) {
                sb.append("Giờ hẹn=[").append(bookingState.confirmedTimeSlot()).append("]");
                if (bookingState.confirmedDate() != null) {
                    sb.append(" (").append(bookingState.confirmedDate()).append(")");
                }
                sb.append("; ");
            }
            if (bookingState.hasCapturedPhone()) {
                sb.append("SĐT=[").append(bookingState.capturedPhone()).append("]; ");
            }
            if (bookingState.customerDeclinedCallOrSelfArranges()) {
                sb.append("Khách tự chủ động ghé cơ sở.");
            }
            sb.append('\n');
        }
        sb.append("- ").append(stage.guidance()).append("\n\n");

        // 3.5. HỒ SƠ THẤU HIỂU TÂM LÝ & INSIGHT KHÁCH HÀNG HIỆN TẠI (GIỚI HẠN KÝ TỰ)
        if (customerInsight != null) {
            sb.append("# HỒ SƠ THẤU HIỂU TÂM LÝ & INSIGHT KHÁCH HÀNG HIỆN TẠI\n");
            sb.append("- Chân dung: ")
              .append(PromptSecurityGuard.sanitizeUntrustedContent(customerInsight.buyerArchetype()))
              .append(" (").append(customerInsight.buyingTemperature()).append("/100)\n");
            if (customerInsight.extractedPains() != null && !customerInsight.extractedPains().isEmpty()) {
                sb.append("- Dữ kiện: ")
                  .append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(String.join("; ", customerInsight.extractedPains())), 55)).append('\n');
            }
            if (customerInsight.recommendedStrategy() != null && !customerInsight.recommendedStrategy().isBlank()) {
                sb.append("- Chiến thuật: ")
                  .append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(customerInsight.recommendedStrategy()), RECOMMENDED_STRATEGY_MAX_CHARS)).append('\n');
            }
            sb.append('\n');
        }

        // 3.6. BỘ KỸ NĂNG AGENT ĐANG KÍCH HOẠT (3-LAYER MODEL: ALWAYS-ON + 1 PRIMARY SKILL + CONTEXT)
        boolean isMicroSkillActive = activeSkills != null && activeSkills.stream()
                .anyMatch(s -> s != null && s.code() != null && (
                        s.code().equalsIgnoreCase("greeting_warm_intro")
                        || s.code().equalsIgnoreCase("discovery_ask_one_need")
                        || s.code().equalsIgnoreCase("price_answer_from_docs")
                        || s.code().equalsIgnoreCase("price_clarify_service_duration")
                        || s.code().equalsIgnoreCase("price_objection_soft_anchor")
                        || s.code().equalsIgnoreCase("booking_soft_confirm")
                        || s.code().equalsIgnoreCase("upsell_one_addon")
                        || s.code().equalsIgnoreCase("safety_handoff_high_risk")
                        || s.code().equalsIgnoreCase("complaint_immediate_handoff")
                ));
        if (activeSkills != null && !activeSkills.isEmpty() && !hasPhoneLeadOnly) {
            sb.append("# BỘ KỸ NĂNG AGENT ĐANG KÍCH HOẠT\n");
            if (isMicroSkillActive) {
                sb.append("- Priority: Core safety > State machine > Tenant persona > Primary skill > Memory > Example > Playbook detail\n");
                sb.append("- Always-On Layer: [core_safety] Không tư vấn y khoa/cam kết chữa bệnh | [anti_hallucination_grounding] Chỉ báo giá trong <DOCUMENT_CONTEXT> | [messenger_tone_short] Tối đa 3 bong bóng, <=2 câu/bong bóng, <=65 từ/bong bóng.\n");
            }
            int sIdx = 1;
            int usedSkillChars = 0;
            for (AgentSkillDto skill : activeSkills) {
                if (skill == null || skill.name() == null) {
                    continue;
                }
                String safeName = PromptSecurityGuard.sanitizeUntrustedContent(skill.name());
                String safeCat = PromptSecurityGuard.sanitizeUntrustedContent(skill.category());
                String instruction = "";
                if (skill.tacticalInstructions() != null && !skill.tacticalInstructions().isBlank()) {
                    String adapted = adaptHonorificInSkillText(PromptSecurityGuard.sanitizeUntrustedContent(skill.tacticalInstructions()), honorific);
                    instruction = compactPromptText(adapted, SINGLE_SKILL_INSTRUCTION_MAX_CHARS);
                }
                StringBuilder skillBlock = new StringBuilder();
                skillBlock.append(sIdx).append(". ").append(safeName).append(" [").append(safeCat).append("]: ").append(instruction).append("\n");
                if (isMicroSkillActive) {
                    if (skill.antiPattern() != null && !skill.antiPattern().isBlank()) {
                        skillBlock.append("   + Must-Not: ").append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(skill.antiPattern()), 140)).append("\n");
                    }
                    if (skill.goodExample() != null && !skill.goodExample().isBlank()) {
                        String ex = adaptHonorificInSkillText(PromptSecurityGuard.sanitizeUntrustedContent(skill.goodExample()), honorific);
                        skillBlock.append("   + Good-Example: ").append(compactPromptText(ex, 180)).append("\n");
                    }
                    AgentSkillDto.SkillOutputContract oc = skill.resolvedOutputContract();
                    skillBlock.append("   + Output-Contract: max_bubbles=").append(oc.replyMaxBubbles())
                            .append(", max_sentences=").append(oc.replyMaxSentences())
                            .append(", max_words=").append(oc.replyMaxWords())
                            .append(", max_questions=").append(oc.maxQuestions())
                            .append(", requires_human=").append(oc.requiresHuman())
                            .append(", delay_after_price_ms=").append(oc.minDelayAfterPriceMs()).append("\n");
                }
                String line = skillBlock.toString();
                if (usedSkillChars + line.length() > (isMicroSkillActive ? ACTIVE_SKILLS_MAX_CHARS + 360 : ACTIVE_SKILLS_MAX_CHARS) && sIdx > 1) {
                    break;
                }
                sb.append(line);
                usedSkillChars += line.length();
                sIdx++;
            }
            sb.append('\n');
        }

        // 4. BỘ QUY TẮC NGHIỆP VỤ & TRẢI NGHIỆM HARNESS² (AI PLAYBOOK - ALWAYS-ON <= 1200 CHARS)
        boolean hasRules = appliedRules != null && !appliedRules.isEmpty();
        boolean hasDialogues = sampleDialogues != null && !sampleDialogues.isEmpty();
        if (hasRules || hasDialogues) {
            sb.append("# LƯU Ý NGHIỆP VỤ QUAN TRỌNG (AI PLAYBOOK)\n");
            if (hasRules) {
                BudgetAllocation alloc = packAlwaysOnRules(appliedRules);
                sb.append("<EXPERIENCE_LIBRARY>\n");
                List<LearnedRuleDto> rulesToInject = alloc.alwaysOnRules();
                int rCount = 0;
                int maxRulesToInject = isMicroSkillActive ? 1 : rulesToInject.size();
                for (LearnedRuleDto rule : rulesToInject) {
                    if (rCount >= maxRulesToInject) break;
                    if (rule != null && rule.generalizedRule() != null && !rule.generalizedRule().isBlank()) {
                        sb.append(formatExperienceCard(rule)).append('\n');
                        rCount++;
                    }
                }
                sb.append("</EXPERIENCE_LIBRARY>\n");
            }
            if (hasDialogues) {
                sb.append("<SAMPLE_DIALOGUES note=\"Chỉ học cấu trúc & giọng điệu, CẤM chép nguyên văn\">\n");
                int usedDialogueChars = 0;
                int dCount = 0;
                int maxDialoguesToInject = 1;
                for (SampleDialogueDto d : sampleDialogues) {
                    if (d == null || !d.active()) continue;
                    String card = formatSampleDialogueCard(d, honorific);
                    if (card.isBlank()) continue;
                    if (usedDialogueChars + card.length() > SAMPLE_DIALOGUES_MAX_CHARS) {
                        card = compactPromptText(card, SAMPLE_DIALOGUES_MAX_CHARS);
                    }
                    if (dCount >= maxDialoguesToInject) {
                        break;
                    }
                    sb.append(card).append('\n');
                    usedDialogueChars += card.length();
                    dCount++;
                }
                sb.append("</SAMPLE_DIALOGUES>\n");
            }
            sb.append('\n');
        }

        // 5. DỮ LIỆU TRI THỨC CỦA FANPAGE (CHUẨN LLAMAINDEX ATOMIC NODES - LOẠI BỎ ĐƯỜNG KẺ BẢNG & CHỐNG TRÀN CONTEXT)
        sb.append("<DOCUMENT_CONTEXT>\n");
        int usedDocChars = 0;
        String combinedQueryContext = FanpageBotService.buildContextualSearchQuery(customerMessage, history);
        final boolean wantsSaigon = "saigon".equalsIgnoreCase(
                com.example.docmind.service.sales.PriceLadderTracker.resolveBranch(customerMessage, history, null))
                || (combinedQueryContext != null && combinedQueryContext.toLowerCase(Locale.ROOT).matches("(?s).*\\b(570|750|870)\\b.*"));
        if (mocSummary != null && !mocSummary.isBlank()
                && (isBroadUnspecifiedPrice || isBroadGenericDiscovery || chunks == null || chunks.isEmpty())) {
            String effectiveMoc = mocSummary.strip();
            if (!wantsSaigon) {
                effectiveMoc = effectiveMoc.replaceAll("(?im)^\\s*\\*\\s*Chi\\s+nhánh\\s+TP\\.HCM[^\\n]*\\r?\\n?", "");
            }
            effectiveMoc = effectiveMoc.replace("100.000 VNĐ -", "399.000 VNĐ -");
            String sanitizedMoc = compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(effectiveMoc.strip()), 360);
            sb.append(sanitizedMoc).append("\n\n");
            usedDocChars += sanitizedMoc.length() + 2;
        }
        java.util.Map<String, StringBuilder> dedupedChunkMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> chunkBodyBySig = new java.util.LinkedHashMap<>();
        SymptomSeverity docContextSeverity = detectSymptomSeverity(customerMessage);
        List<ReciprocalRankFusion.FusedChunk> orderedChunks = chunks;
        boolean hasNonSaigonChunk = false;
        boolean hasMassageCategoryChunk = false;
        if (chunks != null) {
            for (ReciprocalRankFusion.FusedChunk fc : chunks) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String hp = fc.chunk().headingPath() != null ? String.join(" ", fc.chunk().headingPath()).toLowerCase(Locale.ROOT) : "";
                    String tx = fc.chunk().text().toLowerCase(Locale.ROOT);
                    boolean isSg = hp.contains("quận 1") || hp.contains("tp.hcm") || hp.contains("sài gòn")
                            || tx.contains("870.000") || (tx.contains("570.000") && tx.contains("750.000"));
                    if (!isSg) {
                        hasNonSaigonChunk = true;
                    }
                    if (!isSg && hp.contains("massage") && !hp.contains("gội đầu")) {
                        hasMassageCategoryChunk = true;
                    }
                }
            }
        }
        final boolean asksHairWashExplicitly = combinedQueryContext != null
                && combinedQueryContext.toLowerCase(Locale.ROOT).matches("(?s).*(?:gội|tóc|da\\s+đầu|ráy\\s+tai|ds0|129|239|339).*");
        final List<String> explicitPriceTokens = new java.util.ArrayList<>();
        for (Long ep : com.example.docmind.service.sales.PriceLadderTracker.extractPricesFromText(customerMessage)) {
            if (ep != null && ep > 0) {
                explicitPriceTokens.add(String.format(Locale.ROOT, "%,d", ep).replace(',', '.'));
            }
        }
        if (chunks != null && chunks.size() > 1) {
            final String targetPriceToken = (priceLadderResult != null && priceLadderResult.hasTargetPrice())
                    ? String.format(Locale.ROOT, "%,d", priceLadderResult.targetPrice()).replace(',', '.')
                    : null;
            final boolean preferBasicEntry = docContextSeverity == SymptomSeverity.MILD_OR_OCCASIONAL
                    || docContextSeverity == SymptomSeverity.UNSPECIFIED;
            orderedChunks = new java.util.ArrayList<>(chunks);
            orderedChunks.sort((a, b) -> {
                String ha = (a != null && a.chunk() != null && a.chunk().headingPath() != null)
                        ? String.join(" ", a.chunk().headingPath()).toLowerCase(Locale.ROOT) : "";
                String hb = (b != null && b.chunk() != null && b.chunk().headingPath() != null)
                        ? String.join(" ", b.chunk().headingPath()).toLowerCase(Locale.ROOT) : "";
                String ta = (a != null && a.chunk() != null && a.chunk().text() != null) ? a.chunk().text().toLowerCase(Locale.ROOT) : "";
                String tb = (b != null && b.chunk() != null && b.chunk().text() != null) ? b.chunk().text().toLowerCase(Locale.ROOT) : "";
                if (!wantsSaigon) {
                    boolean aSg = ha.contains("quận 1") || ha.contains("tp.hcm") || ha.contains("sài gòn")
                            || ta.contains("870.000") || (ta.contains("570.000") && ta.contains("750.000"));
                    boolean bSg = hb.contains("quận 1") || hb.contains("tp.hcm") || hb.contains("sài gòn")
                            || tb.contains("870.000") || (tb.contains("570.000") && tb.contains("750.000"));
                    if (aSg != bSg) {
                        return Boolean.compare(aSg, bSg);
                    }
                }
                if (!explicitPriceTokens.isEmpty()) {
                    boolean aHasExplicit = explicitPriceTokens.stream().anyMatch(ta::contains);
                    boolean bHasExplicit = explicitPriceTokens.stream().anyMatch(tb::contains);
                    if (aHasExplicit != bHasExplicit) {
                        return Boolean.compare(bHasExplicit, aHasExplicit);
                    }
                }
                if (targetPriceToken != null) {
                    boolean aHasTarget = ta.contains(targetPriceToken);
                    boolean bHasTarget = tb.contains(targetPriceToken);
                    if (aHasTarget != bHasTarget) {
                        return Boolean.compare(bHasTarget, aHasTarget);
                    }
                }
                if (!asksHairWashExplicitly) {
                    boolean aMassage = ha.contains("massage") && !ha.contains("gội đầu");
                    boolean bMassage = hb.contains("massage") && !hb.contains("gội đầu");
                    if (aMassage != bMassage) {
                        return Boolean.compare(bMassage, aMassage);
                    }
                }
                if (preferBasicEntry) {
                    boolean aBasic = ta.contains("cơ bản") && (ta.contains("vnđ") || ta.contains(".000"));
                    boolean bBasic = tb.contains("cơ bản") && (tb.contains("vnđ") || tb.contains(".000"));
                    return Boolean.compare(bBasic, aBasic);
                }
                return 0;
            });
        }
        final boolean asksAddonExplicitly = combinedQueryContext != null
                && combinedQueryContext.toLowerCase(Locale.ROOT).matches("(?s).*(?:tẩy\\s+da\\s+chết|add01).*");
        if (orderedChunks != null) {
            for (ReciprocalRankFusion.FusedChunk fc : orderedChunks) {
                if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                    continue;
                }
                String headingPath = fc.chunk().headingPath() != null
                        ? String.join(" > ", fc.chunk().headingPath())
                        : "";
                if (!wantsSaigon && hasNonSaigonChunk) {
                    String hpLow = headingPath.toLowerCase(Locale.ROOT);
                    String txLow = fc.chunk().text().toLowerCase(Locale.ROOT);
                    boolean isSgChunk = hpLow.contains("quận 1") || hpLow.contains("tp.hcm") || hpLow.contains("sài gòn")
                            || txLow.contains("870.000") || (txLow.contains("570.000") && txLow.contains("750.000"));
                    if (isSgChunk) {
                        continue;
                    }
                }
                if (!asksHairWashExplicitly && hasMassageCategoryChunk && !isBroadGenericDiscovery && !isBroadUnspecifiedPrice) {
                    String hpLow = headingPath.toLowerCase(Locale.ROOT);
                    String txLow = fc.chunk().text().toLowerCase(Locale.ROOT);
                    if (hpLow.contains("gội đầu") || txLow.contains("ds01") || txLow.contains("ds02") || txLow.contains("ds03")) {
                        continue;
                    }
                }
                String safeHeading = compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(headingPath), 70);
                String cleanedChunkText = PromptBuilder.stripInternalPersonaDirectives(fc.chunk().text());
                if (!asksAddonExplicitly) {
                    cleanedChunkText = cleanedChunkText.replaceAll("(?im)^.*\\bADD\\d+\\b.*\\r?\\n?", "").strip();
                }
                String llamaNodeText = com.example.docmind.service.ChunkBuilder.formatChunkAsLlamaIndexNode(
                        cleanedChunkText, null, combinedQueryContext);
                String safeBody = PromptSecurityGuard.sanitizeUntrustedContent(llamaNodeText)
                        .replaceAll("(?i)<br\\s*/?>", "; ")
                        .replaceAll("(\\s*;\\s*){2,}", "; ")
                        .strip();
                if (safeBody.length() > 420) {
                    safeBody = compactPromptText(safeBody, 420);
                }
                if (safeBody.isEmpty()) {
                    continue;
                }
                String bodySignature = safeBody.replaceAll("\\s+", " ");
                if (dedupedChunkMap.containsKey(bodySignature)) {
                    StringBuilder existingH = dedupedChunkMap.get(bodySignature);
                    if (!safeHeading.isBlank() && existingH.length() < 85 && !existingH.toString().contains(safeHeading)) {
                        existingH.append(" ; ").append(safeHeading);
                    }
                } else {
                    dedupedChunkMap.put(bodySignature, new StringBuilder(safeHeading));
                    chunkBodyBySig.put(bodySignature, safeBody);
                }
            }
        }
        int includedUniqueChunks = 0;
        final int maxDocContextChars = 920;
        for (java.util.Map.Entry<String, String> entry : chunkBodyBySig.entrySet()) {
            String sig = entry.getKey();
            String body = entry.getValue();
            String mergedHeading = compactPromptText(dedupedChunkMap.get(sig).toString(), 85);
            String formattedChunk = (!mergedHeading.isBlank() ? "[Mục: " + mergedHeading + "] " : "") + body + "\n";
            if (includedUniqueChunks >= 1 && usedDocChars + formattedChunk.length() > maxDocContextChars) {
                break;
            }
            if (includedUniqueChunks >= 3) {
                break;
            }
            sb.append(formattedChunk);
            usedDocChars += formattedChunk.length();
            includedUniqueChunks++;
        }
        sb.append("</DOCUMENT_CONTEXT>\n\n");

        // 6. LỊCH SỬ TRÒ CHUYỆN GẦN NHẤT (ĐA LƯỢT - CỬA SỔ GỌN CHỐNG PHÌNH TOKEN Ở CÁC LƯỢT SAU)
        if (history != null && !history.isEmpty()) {
            sb.append("<CONVERSATION_HISTORY>\n");
            int start = Math.max(0, history.size() - 6);
            int recentFullThreshold = Math.max(start, history.size() - 2);
            for (int idx = start; idx < history.size(); idx++) {
                FanpageBotRequest.ConversationTurn turn = history.get(idx);
                if (turn == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                boolean isAssistant = "assistant".equalsIgnoreCase(turn.role());
                String speaker = isAssistant ? "Nhân viên Page" : "Khách hàng";
                String turnContent = PromptSecurityGuard.sanitizeUntrustedContent(turn.content().strip());
                if (isAssistant) {
                    turnContent = sanitizeAssistantHistoryTurn(turnContent, honorific);
                    if (idx < recentFullThreshold && turnContent.length() > 160) {
                        turnContent = compactPromptText(turnContent, 160);
                    }
                }
                sb.append(speaker).append(": ").append(turnContent).append('\n');
            }
            sb.append("</CONVERSATION_HISTORY>\n\n");
        }

        // 7. TIN NHẮN MỚI NHẤT CỦA KHÁCH HÀNG + CHỈ THỊ HÀNH ĐỘNG DUY NHẤT CHO LƯỢT NÀY
        String lowerMsg = customerMessage != null ? customerMessage.toLowerCase(java.util.Locale.ROOT) : "";
        boolean explicitMaleInCurrentTurn = "Anh".equalsIgnoreCase(detectExplicitGenderHonorific(customerMessage));
        boolean asksMaleEligibility = "Anh".equalsIgnoreCase(honorific)
                && (explicitMaleInCurrentTurn || lowerMsg.matches("(?isU).*(?:dùng|sử\\s+dụng|làm|đi|phù\\s+hợp|được\\s+không|nam\\s+giới|là\\s+nam).*"));
        boolean asksTimeToResult = lowerMsg.matches("(?isU).*(?:bao\\s+lâu|mấy\\s+buổi|khi\\s+nào|thời\\s+gian).*(?:hiệu\\s+quả|hiệu\\s+quẩ|hết|đỡ|cải\\s+thiện|khỏi).*")
                || lowerMsg.contains("bao lâu thấy");
        boolean asksRednessOrDowntime = lowerMsg.matches("(?isU).*(?:sưng|đỏ|nghỉ\\s+dưỡng|đau|rát|ràng\\s+buộc|rằng\\s+buộc|ép\\s+mua|chèo\\s+kéo).*");
        boolean asksFearOfIneffectiveness = lowerMsg.matches("(?isU).*(?:sợ\\s+(?:không|ko|k)\\s+(?:hiệu\\s+quả|khỏi|hết|đỡ)|sợ\\s+tái\\s+phát|không\\s+hiệu\\s+quả|ko\\s+hiệu\\s+quả|sợ\\s+tiền\\s+mất|sợ\\s+hỏng\\s+da|da\\s+đang\\s+hơi\\s+yếu|da\\s+yếu).*");
        boolean hasComplexOrSevereSymptoms = isComplexOrSevereSymptomMessage(lowerMsg);
        boolean asksWellnessDiscomfort = isWellnessOrDiscomfortMessage(customerMessage);
        boolean isSymptomFollowUp = isSymptomFollowUpTurn(customerMessage, history);
        SymptomSeverity currentSeverity = detectSymptomSeverity(customerMessage);
        boolean asksServiceExplanation = isServiceExplanationOrProcessInquiry(customerMessage);
        boolean asksAcne = lowerMsg.matches("(?isU).*(?:mụn|mụn\\s+ẩn|mụn\\s+viêm|mụn\\s+bọc).*");
        boolean isBurstOrMultiPart = (customerMessage != null && (customerMessage.contains(". ") || customerMessage.contains("\n") || customerMessage.contains("?")))
                && ((asksExplicitPrice || isBroadUnspecifiedPrice || asksAcne) && asksRednessOrDowntime);
        boolean isReengage = customerMessage != null && (
                lowerMsg.contains("re-engage") || lowerMsg.contains("reengage") || lowerMsg.contains("im lặng")
        );
        boolean isTrialAcceptance = isTrialOrBookingAcceptanceMessage(lowerMsg);
        boolean isPriceObjectionOrDownsell = isPriceObjectionOrDownsellMessage(lowerMsg);
        boolean isCooling = !hasComplexOrSevereSymptoms && !asksFearOfIneffectiveness && !explicitMaleInCurrentTurn && !asksWellnessDiscomfort
                && !isSymptomFollowUp && !asksServiceExplanation && !isTrialAcceptance && !isPriceObjectionOrDownsell
                && !bookingState.isPriceComparisonObjection() && !bookingState.isPostBookingWrapUp()
                && customerInsight != null && customerInsight.momentum() != null && customerInsight.momentum().startsWith("COOLING");

        sb.append("<CUSTOMER_MESSAGE>\n");
        sb.append("Câu hỏi: ").append(PromptSecurityGuard.sanitizeUntrustedContent(customerMessage)).append('\n');
        sb.append("</CUSTOMER_MESSAGE>\n");
        sb.append("# LƯU Ý CUỐI CÙNG: Bám sát <DOCUMENT_CONTEXT>, xưng 'em / bên em' - gọi '").append(honorific).append("' 100%. ");
        if (bookingState.isRedundantQuestionComplaint()) {
            sb.append("Xin lỗi vì hỏi lặp, xác nhận đã khóa lịch lúc ")
              .append(bookingState.confirmedTimeSlot() != null ? bookingState.confirmedTimeSlot() : "đã chốt")
              .append(" cho ").append(honorific).append(" và hẹn đón tiếp (CẤM hỏi thêm).\n");
        } else if (bookingState.isPostBookingWrapUp()) {
            sb.append("Khách đã chốt lịch (").append(bookingState.confirmedTimeSlot() != null ? bookingState.confirmedTimeSlot() : "đã chốt")
              .append("): chỉ xác nhận ấm áp và hẹn đón tiếp (CẤM hỏi lại Sáng/Chiều).\n");
        } else if (bookingState.hasConfirmedTimeSlot() && !bookingState.hasCapturedPhone()) {
            sb.append("Khách đã chọn giờ ").append(bookingState.confirmedTimeSlot())
              .append(": xác nhận giữ suất cho ").append(honorific).append(" và chỉ xin SĐT để khóa lịch (CẤM hỏi lại Sáng/Chiều).\n");
        } else if (isPriceObjectionOrDownsell && !bookingState.hasCapturedPhone()) {
            sb.append("Khách chê giá cao / hỏi ưu đãi: đồng cảm ấm áp, CẤM thanh minh hay [[HANDOFF]]; chủ động báo ưu đãi (nếu có) và hạ 1 nấc xuống gói thấp hơn trong <DOCUMENT_CONTEXT>, kết bằng 1 câu hỏi lựa chọn 2 phương án ngắn.\n");
        } else if (asksServiceExplanation && !bookingState.hasCapturedPhone()) {
            sb.append("Khách hỏi quy trình / tác dụng: giải thích trọng tâm dịch vụ gồm các bước cốt lõi nào từ <DOCUMENT_CONTEXT> trong 1-2 câu ngắn, kết bằng 1 câu hỏi lựa chọn 2 phương án ngắn.\n");
        } else if ((asksWellnessDiscomfort || isSymptomFollowUp) && !bookingState.hasCapturedPhone()) {
            if (currentSeverity == SymptomSeverity.MILD_OR_OCCASIONAL) {
                sb.append("Khách THI THOẢNG / MỚI BỊ nhẹ: Đồng cảm ấm áp, gợi ý ĐÚNG 1 gói cơ bản kèm Tên gói + Thời lượng + Đơn giá VNĐ từ <DOCUMENT_CONTEXT>, kết bằng câu hỏi lựa chọn 2 phương án.\n");
            } else if (currentSeverity == SymptomSeverity.CHRONIC_OR_DEEP) {
                sb.append("Khách THƯỜNG XUYÊN / CĂNG MỎI NHIỀU: Đồng cảm sâu sắc, gợi ý ĐÚNG 1 gói chuyên sâu/phục hồi kèm Đơn giá từ <DOCUMENT_CONTEXT>, kết bằng câu hỏi khung giờ Sáng hay Chiều.\n");
            } else {
                sb.append("Khách nêu triệu chứng: Đồng cảm chân thành đầu câu, nêu 1 gói giải pháp phù hợp kèm Đơn giá từ <DOCUMENT_CONTEXT>, kết bằng câu hỏi tình trạng mới bị hay thường xuyên.\n");
            }
        } else if (isTrialAcceptance && !bookingState.hasCapturedPhone()) {
            sb.append("Khách muốn thử: giữ NHẤT QUÁN đúng tên và mức giá gói cơ bản từ <DOCUMENT_CONTEXT>/<CONVERSATION_HISTORY> (cấm tự nhảy gói đắt hơn), hỏi giờ đặt lịch Sáng hay Chiều.\n");
        } else if (bookingState.isPriceComparisonObjection()) {
            sb.append("Khách so sánh giá: nêu giá trị quy trình từ <DOCUMENT_CONTEXT>, gợi ý gói cơ bản cho ").append(honorific).append(".\n");
        } else if (isReengage || isCooling) {
            sb.append("[RE-ENGAGEMENT & KHÁCH HẠ NHIỆT] Không thúc ép; phản hồi ấm áp, đề xuất giữ ưu đãi từ <DOCUMENT_CONTEXT>.\n");
        } else if (hasPhoneLeadOnly) {
            sb.append("Chỉ xác nhận đã nhận SĐT của ").append(honorific).append(" và báo chuyên viên liên hệ ngay, cấm liệt kê lại giá/gói!\n");
        } else if (hasPhoneLead) {
            sb.append("Báo đúng đơn giá gói dịch vụ khách hỏi từ <DOCUMENT_CONTEXT> và xác nhận đã ghi nhận SĐT để chuyên viên liên hệ ngay.\n");
        } else if (hasComplexOrSevereSymptoms && !asksExplicitPrice) {
            sb.append("Khách mô tả tình trạng phức tạp/lâu năm: chỉ phân tích đúng vấn đề khách nêu dựa trên <DOCUMENT_CONTEXT>, mời ").append(honorific).append(" đặt lịch kiểm tra/tư vấn trực tiếp Sáng/Chiều.\n");
        } else if (asksFearOfIneffectiveness) {
            sb.append("Đồng cảm nỗi lo của ").append(honorific).append(", nêu cam kết từ <DOCUMENT_CONTEXT>, hỏi khung giờ Sáng hay Chiều.\n");
        } else if (isBurstOrMultiPart) {
            sb.append("Giải đáp các ý trong 2 đoạn ngắn từ <DOCUMENT_CONTEXT>, kết bằng 1 câu hỏi lựa chọn 2 phương án.\n");
        } else if (isBroadGenericDiscovery && !bookingState.hasCapturedPhone()) {
            sb.append("Khách mới chào hỏi / nhờ tư vấn chung chung (chưa hỏi giá): Chào ấm áp kèm tên Spa, hỏi 1 câu mở nhẹ nhàng để biết khu vực đang mỏi hoặc dịch vụ ")
              .append(honorific).append(" quan tâm (TUYỆT ĐỐI CẤM báo giá ở lượt chào hỏi và CẤM xin SĐT/giờ hẹn sớm).\n");
        } else if (isBroadUnspecifiedPrice && !bookingState.hasCapturedPhone()) {
            sb.append("Khách hỏi giá chung chung chưa rõ dịch vụ: Giải thích nhẹ nhàng giá tùy dịch vụ và thời lượng, hỏi 1 câu làm rõ nhóm dịch vụ ")
              .append(honorific).append(" quan tâm (gói cơ bản, chuyên sâu hay chăm sóc thư giãn) để gửi đúng mức giá (TUYỆT ĐỐI CẤM đổ cả bảng giá).\n");
        } else {
            if (asksMaleEligibility) {
                sb.append("Khách Nam: phòng riêng kín đáo, tiếp nối <CONVERSATION_HISTORY>. ");
            }
            if (asksTimeToResult) {
                sb.append("Nêu thời gian hiệu quả từ <DOCUMENT_CONTEXT>. ");
            }
            if (asksRednessOrDowntime) {
                sb.append("Nêu độ êm dịu, 0đ ràng buộc từ <DOCUMENT_CONTEXT>. ");
            }
            sb.append("Kết bằng 1 câu hỏi lựa chọn 2 phương án tự nhiên.\n");
        }

        if (priceLadderResult != null) {
            sb.append(formatPriceLadderGuidance(priceLadderResult, honorific, customerMessage));
        }

        sb.append("Tin nhắn phản hồi khách hàng:");

        return sb.toString();
    }

    public static String formatPriceLadderGuidance(PriceLadderResult result, String resolvedHonorific) {
        return formatPriceLadderGuidance(result, resolvedHonorific, null);
    }

    public static String formatPriceLadderGuidance(PriceLadderResult result, String resolvedHonorific, String customerMessage) {
        if (result == null || (!result.isPriceObjection() && !result.isTrialRequest() && !result.isCatalogFloor())) {
            return "";
        }
        String h = (resolvedHonorific != null && !resolvedHonorific.isBlank()) ? resolvedHonorific.strip() : "Chị";
        boolean asksPromoDirectly = com.example.docmind.service.sales.PriceLadderTracker.isPromoOrDiscountInquiry(customerMessage);
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== CHỈ DẪN BẬC THANG GIÁ & HẠ BẬC (STATEFUL PRICE LADDER GUIDANCE) ===\n");
        if (result.isCatalogFloor()) {
            sb.append("- Trạng thái: ĐÃ Ở MỨC GIÁ SÀN DANH MỤC (")
              .append(result.targetPrice() != null ? String.format(Locale.ROOT, "%,d VNĐ", result.targetPrice()).replace(',', '.') : "129.000 VNĐ")
              .append(")\n");
            sb.append("- Gói dịch vụ: ").append(result.targetPackageName() != null ? result.targetPackageName() : "Gói cơ bản (DS01)").append("\n");
            if (result.discountPolicy() != null) {
                sb.append("- Chính sách ưu đãi áp dụng: ").append(result.discountPolicy()).append("\n");
            }
            sb.append("- YÊU CẦU BẮT BUỘC:\n");
            sb.append("  1. BẮT BUỘC xác nhận đây là mức giá mềm nhất bên em hiện tại (CẤM bịa gói miễn phí hoặc 0đ).\n");
            sb.append("  2. Giới thiệu chính sách ưu đãi ").append(result.discountPolicy() != null ? result.discountPolicy() : "giảm 10%").append(" để hỗ trợ ").append(h).append(" tiết kiệm tối đa.\n");
            sb.append("  3. TUYỆT ĐỐI CẤM xuất [[HANDOFF]] và CẤM từ chối phục vụ.\n");
        } else if (result.isPriceObjection()) {
            sb.append("- Trạng thái: KHÁCH CHÊ GIÁ CAO / HỎI GÓI RẺ HƠN\n");
            if (result.previousPriceQuoted() != null) {
                sb.append("- Mức giá đã báo lượt trước: ").append(String.format(Locale.ROOT, "%,d VNĐ", result.previousPriceQuoted()).replace(',', '.')).append("\n");
            }
            sb.append("- MỨC GIÁ HẠ BẬC MỤC TIÊU BẮT BUỘC: ")
              .append(result.targetPrice() != null ? String.format(Locale.ROOT, "%,d VNĐ", result.targetPrice()).replace(',', '.') : "239.000 VNĐ")
              .append(" (").append(result.targetPackageName() != null ? result.targetPackageName() : "Gói Dưỡng sinh cơ bản").append(")\n");
            if (result.discountPolicy() != null) {
                sb.append("- CHÍNH SÁCH ƯU ĐÃI / KHUYẾN MÃI TỪ TÀI LIỆU: ").append(result.discountPolicy()).append("\n");
            }
            sb.append("- YÊU CẦU BẮT BUỘC:\n");
            if (asksPromoDirectly && result.discountPolicy() != null) {
                sb.append("  1. TRẢ LỜI TRỰC DIỆN CÂU HỎI KHUYẾN MÃI NGAY CÂU ĐẦU: Nêu rõ bên em hiện có ưu đãi ")
                  .append(result.discountPolicy()).append(".\n");
                sb.append("  2. Đồng thời gợi ý ngay gói trải nghiệm lẻ mềm nhất là ").append(result.targetPackageName())
                  .append(" với giá ").append(result.targetPrice() != null ? String.format(Locale.ROOT, "%,d VNĐ", result.targetPrice()).replace(',', '.') : "399.000 VNĐ")
                  .append(" nếu ").append(h).append(" muốn thử lẻ 1 buổi trước.\n");
            } else {
                sb.append("  1. Đồng cảm ấm áp, thấu hiểu khách muốn chọn mức phí vừa vặn để trải nghiệm trước; TUYỆT ĐỐI CẤM thanh minh, bảo vệ giá hay bảo khách 'cứ cân nhắc thêm'.\n");
                sb.append("  2. Chuyển tiếp mượt mà, chủ động hạ bậc (Down-sell) xuống đúng gói ").append(result.targetPackageName()).append(" mức giá ").append(result.targetPrice() != null ? String.format(Locale.ROOT, "%,d VNĐ", result.targetPrice()).replace(',', '.') : "239.000 VNĐ").append(" (nêu rõ thời gian và quyền lợi thư giãn cốt lõi)");
                if (result.discountPolicy() != null) {
                    sb.append(", kết hợp thông báo ưu đãi ").append(result.discountPolicy());
                }
                sb.append(".\n");
            }
            String prevFormatted = result.previousPriceQuoted() != null
                    ? String.format(Locale.ROOT, "%,d", result.previousPriceQuoted()).replace(',', '.')
                    : "399.000";
            sb.append("  3. TUYỆT ĐỐI CẤM quay ngược lên báo lại gói có giá cao hơn (>= ").append(prevFormatted).append("đ).\n");
            sb.append("  4. TUYỆT ĐỐI CẤM xuất [[HANDOFF]] và CẤM viết nhãn 'A/B' hay chữ 'Hay'. Kết bằng 1 câu hỏi tự nhiên hỏi ").append(h).append(" muốn trải nghiệm lẻ 1 buổi trước hay tham khảo gói ưu đãi tiết kiệm hơn ạ?\n");
        } else if (result.isTrialRequest()) {
            sb.append("- Trạng thái: KHÁCH MUỐN THỬ TRẢI NGHIỆM TRƯỚC\n");
            sb.append("- Gói duy trì nhất quán: ").append(result.targetPackageName()).append(" (")
              .append(result.targetPrice() != null ? String.format(Locale.ROOT, "%,d VNĐ", result.targetPrice()).replace(',', '.') : "399.000 VNĐ")
              .append(")\n");
            sb.append("- YÊU CẦU BẮT BUỘC:\n");
            sb.append("  1. Đón nhận tích cực, xác nhận đúng gói ").append(result.targetPackageName()).append(" rất phù hợp để ").append(h).append(" trải nghiệm lần đầu.\n");
            sb.append("  2. BẮT BUỘC giữ đúng tên gói ").append(result.targetPackageName()).append(" và đúng mức giá ")
              .append(result.targetPrice() != null ? String.format(Locale.ROOT, "%,d VNĐ", result.targetPrice()).replace(',', '.') : "399.000 VNĐ")
              .append(" (TUYỆT ĐỐI CẤM tự ý nhảy sang gói 530.000 VNĐ hay bất kỳ gói nào đắt hơn, và CẤM lấy nhầm thời lượng 100 phút của gói khác!).\n");
            sb.append("  3. Kết bằng 1 câu hỏi điều hướng đặt lịch cụ thể (khung giờ Sáng hay Chiều).\n");
        }
        sb.append("======================================================================\n");
        return sb.toString();
    }

    public static boolean isTrialOrBookingAcceptanceMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (isPriceObjectionOrDownsellMessage(lower)) {
            return false;
        }
        return lower.matches("(?isU).*(?:(?<!\\p{L})(?:thử\\s+xem(?:\\s+sao|\\s+thế\\s+nào|\\s+nhé|\\s+nha)?|"
                + "thử\\s+trước|thử\\s+trc|thử\\s+lẻ|thử\\s+\\d+\\s*(?:buổi|b)|thử\\s+gói|"
                + "sẽ\\s+thử|mình\\s+thử|t\\s+thử|chị\\s+thử|anh\\s+thử|qua\\s+thử|khám\\s+thử|soi\\s+da\\s+thử|"
                + "muốn\\s+thử|muosn\\s+thử|thử\\s+trải\\s+nghiệm|muốn\\s+trải\\s+nghiệm|"
                + "trải\\s+nghiệm\\s+thử|trải\\s+nghiệm\\s+trước|trải\\s+nghiệm\\s+lẻ|trải\\s+nghiệm\\s+\\d+\\s*(?:buổi|b)|"
                + "làm\\s+thử|giữ\\s+suất|giữ\\s+lịch|đặt\\s+lịch|chốt\\s+lịch|qua\\s+soi\\s+da|qua\\s+khám))(?!\\p{L}).*");
    }

    public static boolean isPriceObjectionOrDownsellMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("(?isU).*(?:(?<!\\p{L})(?:cao\\s+thế|cao\\s+quá|cao\\s+vậy|hơi\\s+cao|giá\\s+cao|cao\\s+hơn|"
                + "hơi\\s+đắt|đắt\\s+quá|đắt\\s+thế|đắt\\s+vậy|đắt|mắc\\s+quá|hơi\\s+mắc|mắc|chát\\s+quá|"
                + "rẻ\\s+hơn|rẻ\\s+nhất|mềm\\s+hơn|thấp\\s+hơn|thấp\\s+nhất|tiết\\s+kiệm\\s+hơn|gói\\s+nào\\s+rẻ|gói\\s+rẻ|gói\\s+nhỏ))(?!\\p{L}).*");
    }

    public static boolean isComplexOrSevereSymptomMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // Nhiều vị trí trên cơ thể/khuôn mặt hoặc triệu chứng kết hợp (viêm + thâm, mụn + sẹo, lâu năm...)
        boolean hasSkinIndicator = lower.matches("(?isU).*(?:mụn|da|nám|sẹo|thâm|viêm|mủ|bọc|lỗ\\s+chân\\s+lông).*");
        boolean hasMultiZone = hasSkinIndicator && lower.matches("(?isU).*(?:dưới\\s+cằm|vùng\\s+cằm|quai\\s+hàm|ở\\s+lưng|vùng\\s+lưng|mụn\\s+lưng|vùng\\s+ngực|ở\\s+ngực|vùng\\s+cổ|ở\\s+cổ|hai\\s+bên\\s+má|toàn\\s+mặt).*");
        boolean hasCompoundedSymptoms = (lower.contains("viêm") || lower.contains("bọc") || lower.contains("mủ") || lower.contains("ẩn"))
                && (lower.contains("thâm") || lower.contains("sẹo") || lower.contains("lưng") || lower.contains("cằm") || lower.contains("lâu"));
        boolean hasChronicOrSevere = lower.matches("(?isU).*(?:thâm\\s+mụn\\s+lâu|thâm\\s+lâu|mụn\\s+viêm\\s+nặng|nám\\s+lâu\\s+năm|nám\\s+chân\\s+sâu|tái\\s+đi\\s+tái\\s+lại|chữa\\s+nhiều\\s+nơi|\\b\\d+\\s*năm\\b).*");
        return hasMultiZone || hasCompoundedSymptoms || hasChronicOrSevere;
    }

    public static boolean isMaleSelfIdentificationMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("(?isU).*(?:(?<!\\p{L})(?:nhưng\\s+|mà\\s+)?(?:mình|mìn|minh|mk|mik|tôi|toi|tui|t|em|m)\\s+(?:là|la|làm|lam)\\s*(?:nam|con\\s+trai|đàn\\s+ông|dan\\s+ong|boy)|"
                + "(?<!\\p{L})(?:nam\\s+giới|nam\\s+gioi|khách\\s+nam|da\\s+nam|nam\\s+dùng|nam\\s+làm\\s+được))(?!\\p{L}).*");
    }

    private static String sanitizeAssistantHistoryTurn(String content, String honorific) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String cleaned = content
                .replaceAll("(?isU)^(?:dạ[,\\s]+)?(?:chào\\s+mừng|hoan\\s+nghênh)\\s+[^.!?\\n]+[.!?~]+\\s*", "")
                .replaceAll("(?isU)^(?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+(?:anh\\s*/\\s*chị|chị\\s*/\\s*anh|anh|chị|bạn|mình|quý\\s+khách)?(?:\\s+[^!.,~\\n]{1,25})?(?:\\s+ạ|\\s+nha|\\s+nhé)?\\s*[!.,~]*\\s*", "")
                .replaceAll("(?isU)^(?:dạ[,\\s]+)?em\\s+rất\\s+vui\\s+được\\s+(?:tư\\s+vấn|hỗ\\s+trợ|đồng\\s+hành|phục\\s+vụ)[^.!?\\n]*[.!?]+\\s*", "");
        if ("Anh".equalsIgnoreCase(honorific)) {
            cleaned = cleaned.replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh");
        }
        return cleaned.strip();
    }

    /**
     * Tự động nhận diện cách xưng hô với khách hàng ('Anh', 'Chị', hoặc 'Anh/Chị')
     * dựa trên nội dung tin nhắn hiện tại, lịch sử chat, tên hiển thị và PagePersona.
     * Ưu tiên tuyệt đối giới tính khách hàng tự giới thiệu trong tin nhắn mới nhất (VD: "Mình là nam", "Mìn là nam", "a sợ", "anh muốn hỏi"...).
     */
    public static String resolveCustomerHonorific(
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            String customerName,
            PromptBuilder.PagePersona pagePersona) {
        // 1. Ưu tiên cao nhất: Kiểm tra tin nhắn hiện tại trước, sau đó duyệt lịch sử user từ mới nhất về cũ nhất
        String currentMatch = detectExplicitGenderHonorific(customerMessage);
        if (currentMatch != null) {
            return currentMatch;
        }
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn turn = history.get(i);
                if (turn != null && !"assistant".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                    String histMatch = detectExplicitGenderHonorific(turn.content());
                    if (histMatch != null) {
                        return histMatch;
                    }
                }
            }
            // 1.5. Nếu các lượt trước Assistant đã xác lập danh xưng "Anh" (ví dụ sau khi tối ưu Harness² hoặc khách xưng anh trước đó),
            // giữ nguyên "Anh" để không bị nhảy ngược về "Chị" ở các lượt tiếp theo.
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn turn = history.get(i);
                if (turn != null && "assistant".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                    String assistantHonorific = detectEstablishedAssistantHonorific(turn.content());
                    if ("Anh".equalsIgnoreCase(assistantHonorific)
                            || "Anh/Chị".equalsIgnoreCase(assistantHonorific)) {
                        return assistantHonorific;
                    }
                }
            }
        }

        // 2. Xét tiền tố trong tên hiển thị của khách (bỏ qua tên mặc định như "Khách hàng")
        if (customerName != null && !customerName.isBlank()) {
            String lowerName = customerName.strip().toLowerCase(java.util.Locale.ROOT);
            if (!lowerName.startsWith("khách hàng") && !lowerName.equals("khách") && !lowerName.equals("guest")) {
                if (lowerName.startsWith("anh ")) {
                    return "Anh";
                }
                if (lowerName.startsWith("chị ") || lowerName.startsWith("chi ")) {
                    return "Chị";
                }
            }
        }

        // 3. Mặc định theo cấu hình Persona hoặc ngành hàng của PagePersona
        if (pagePersona != null && pagePersona.hasContent()) {
            PagePersonaConfig cfg = pagePersona.resolvedConfig();
            if (cfg != null
                    && cfg.defaultCustomerPronoun() != null
                    && !cfg.defaultCustomerPronoun().isBlank()) {
                return cfg.defaultCustomerPronoun().strip();
            }
            String combinedPersona = ((pagePersona.name() != null ? pagePersona.name() : "") + " "
                    + (pagePersona.industry() != null ? pagePersona.industry() : "") + " "
                    + (pagePersona.targetAudience() != null ? pagePersona.targetAudience() : "") + " "
                    + (pagePersona.description() != null ? pagePersona.description() : "")).toLowerCase(java.util.Locale.ROOT);
            if (combinedPersona.contains("spa") || combinedPersona.contains("làm đẹp")
                    || combinedPersona.contains("thẩm mỹ") || combinedPersona.contains("mỹ phẩm")
                    || combinedPersona.contains("nữ")) {
                return "Chị";
            }
        }
        return "Chị";
    }

    public static String detectEstablishedAssistantHonorific(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return null;
        }
        String lower = assistantText.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("(?iU)\\b(?:em|mình|tôi)\\s+là\\s+[\\p{L}\\s]{1,25}?(?=[,.!?;]|\\s+(?:nhân\\s+viên|trợ\\s+lý|chuyên\\s+viên|tư\\s+vấn|của|tại|bên)|$)", " ");
        if (lower.contains("anh/chị")) {
            return null;
        }
        boolean hasAnh = lower.matches("(?isU).*(?<!\\p{L})anh(?!\\p{L}).*");
        boolean hasChi = lower.matches("(?isU).*(?<!\\p{L})chị(?!\\p{L}).*");
        if (hasAnh && !hasChi) {
            return "Anh";
        }
        if (hasChi && !hasAnh) {
            return "Chị";
        }
        return null;
    }

    public static String detectExplicitGenderHonorific(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("(?isU).*(?:(?<!\\p{L})(?:mình|mìn|minh|mk|mik|tôi|toi|tui|t|em|m)\\s+(?:là|la|làm|lam)?\\s*(?:nam|con\\s+trai|đàn\\s+ông|dan\\s+ong|boy)|"
                + "(?<!\\p{L})(?:tên\\s+(?:mình|tôi|em|anh|của\\s+mình|của\\s+tôi)?\\s*(?:là\\s+)?[\\p{L}\\s]{0,25}\\bnam\\b)|"
                + "(?<!\\p{L})(?:khách\\s+hàng\\s+nam|khách\\s+nam|nam\\s+giới|nam\\s+gioi|đàn\\s+ông|dan\\s+ong|con\\s+trai|da\\s+nam|nam\\s+dùng|nam\\s+dung|nam\\s+có\\s+dùng|nam\\s+xài|nam\\s+xai|nam\\s+làm\\s+được|nam\\s+lam\\s+duoc)|"
                + "(?<!\\p{L})(?:cho\\s+anh(?:\\s+xin|\\s+hỏi|\\s+xem|\\s+đặt)?|anh\\s+(?:muốn|cần|đang|định|hỏi|sợ|lo|bị|thấy|tính|ở|làm|mua|dùng|có|chưa|mới|qua|ghé|ngại|nghĩ|xin|xem)|da\\s+(?:của\\s+)?anh|mặt\\s+anh|lưng\\s+anh|cằm\\s+anh|sđt\\s+(?:của\\s+)?anh|số\\s+(?:của\\s+)?anh|"
                + "(?:gọi|alo|nhắn|liên\\s+hệ|báo\\s+giá|gửi|tư\\s+vấn|hỗ\\s+trợ|giữ\\s+suất|giữ\\s+lịch)(?:\\s+lại|\\s+thêm)?\\s+(?:cho\\s+anh|giúp\\s+anh|hộ\\s+anh|anh)|"
                + "chồng\\s+(?:mình|em)|bạn\\s+trai)(?!\\p{L})|"
                + "(?:^|[.!?\\n,;:\\s])a\\s+(?:sợ|lo|bị|muốn|cần|đang|định|hỏi|thấy|tính|ở|làm|mua|dùng|chưa|mới|qua|ghé|ngại|nghĩ|xin)(?!\\p{L})|"
                + "(?<!\\p{L})(?:cho|giúp|hộ|tư\\s+vấn|báo\\s+giá|gọi|nhắn)\\s+(?:cho\\s+)?a(?:\\s+(?:nhé|nha|với|thêm|xem|hỏi|xin)|[.!?,;]|$)).*")) {
            return "Anh";
        }
        if (lower.matches("(?isU).*(?:(?<!\\p{L})(?:mình|mìn|minh|mk|mik|tôi|toi|tui|t|em)\\s+(?:là|la|làm|lam)?\\s*(?:nữ|nu|con\\s+gái|con\\s+gai|phụ\\s+nữ|phu\\s+nu|girl)|"
                + "(?<!\\p{L})(?:nữ\\s+giới|nu\\s+gioi|phụ\\s+nữ|phu\\s+nu)|"
                + "(?<!\\p{L})(?:cho\\s+chị(?:\\s+xin|\\s+hỏi|\\s+xem|\\s+đặt)?|chị\\s+(?:muốn|cần|đang|định|hỏi|sợ|lo|bị|thấy|tính|ở|làm|mua|dùng|có|chưa|mới|qua|ghé|ngại|nghĩ|xin|xem)|da\\s+(?:của\\s+)?chị|mặt\\s+chị|sđt\\s+(?:của\\s+)?chị|số\\s+(?:của\\s+)?chị|"
                + "(?:gọi|alo|nhắn|liên\\s+hệ|báo\\s+giá|gửi|tư\\s+vấn|hỗ\\s+trợ|giữ\\s+suất|giữ\\s+lịch)(?:\\s+lại|\\s+thêm)?\\s+(?:cho\\s+chị|giúp\\s+chị|hộ\\s+chị|chị)|"
                + "gần\\s+nhà\\s+chị|bạn\\s+chị)(?!\\p{L})|"
                + "(?:^|[.!?\\n,;:\\s])c\\s+(?:sợ|lo|bị|muốn|cần|đang|định|hỏi|thấy|tính|ở|làm|mua|dùng|chưa|mới|qua|ghé|ngại|nghĩ|xin)(?!\\p{L})).*")) {
            return "Chị";
        }
        return null;
    }

    /**
     * Làm sạch tên hiển thị của khách: loại bỏ các tên mặc định ("Khách hàng", "Khách", "Guest")
     * hoặc tên mâu thuẫn với giới tính thật khách vừa khai báo ("Anh").
     */
    static String sanitizeCustomerDisplayName(String customerName, String resolvedHonorific) {
        if (customerName == null || customerName.isBlank()) {
            return null;
        }
        String trimmed = customerName.strip();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("khách") || lower.startsWith("guest")) {
            return null;
        }
        if ("Anh".equalsIgnoreCase(resolvedHonorific)
                && (lower.startsWith("chị ") || lower.startsWith("chi "))) {
            return null;
        }
        if ("Chị".equalsIgnoreCase(resolvedHonorific) && lower.startsWith("anh ")) {
            return null;
        }
        return trimmed;
    }

    private static String adaptHonorificInSkillText(String text, String honorific) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if ("Anh".equalsIgnoreCase(honorific)) {
            return text.replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh");
        }
        if ("Chị".equalsIgnoreCase(honorific)) {
            return text.replaceAll("(?iU)(?<!\\p{L})anh\\s+nhé(?!\\p{L})", "Chị nhé")
                       .replaceAll("(?iU)(?<!\\p{L})như\\s+anh\\s+vừa(?!\\p{L})", "như Chị vừa");
        }
        return text;
    }

    /**
     * Dựng Prompt thực thi động 100% từ Database của Page hiện tại:
     * {@code FusedChunk} (bảng {@code chunks}), {@code instruction} (bảng {@code learned_rules}),
     * {@code strategy_instruction} (bảng {@code agent_skills}), {@code sample_dialogues},
     * {@code history[]} và {@link ConversationState} + {@link PrimaryActionRouter.RoutingDecision}.
     */
    public static String buildDynamicRuntimePrompt(
            String customerMessage,
            String customerName,
            List<FanpageBotRequest.ConversationTurn> history,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<LearnedRuleDto> appliedRules,
            PromptBuilder.PagePersona pagePersona,
            SalesStage salesStage,
            String resolvedHonorific,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            String mocSummary,
            PriceLadderResult priceLadderResult,
            List<SampleDialogueDto> sampleDialogues,
            ConversationState convState,
            PrimaryActionRouter.RoutingDecision actionDecision,
            List<com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble> dbGroundedReferenceBubbles
    ) {
        return buildDynamicRuntimePrompt(
                customerMessage,
                customerName,
                history,
                chunks,
                appliedRules,
                pagePersona,
                salesStage,
                resolvedHonorific,
                customerInsight,
                activeSkills,
                mocSummary,
                priceLadderResult,
                sampleDialogues,
                convState,
                actionDecision,
                dbGroundedReferenceBubbles,
                null,
                false
        );
    }

    public static String buildDynamicRuntimePrompt(
            String customerMessage,
            String customerName,
            List<FanpageBotRequest.ConversationTurn> history,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<LearnedRuleDto> appliedRules,
            PromptBuilder.PagePersona pagePersona,
            SalesStage salesStage,
            String resolvedHonorific,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            String mocSummary,
            PriceLadderResult priceLadderResult,
            List<SampleDialogueDto> sampleDialogues,
            ConversationState convState,
            PrimaryActionRouter.RoutingDecision actionDecision,
            List<com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble> dbGroundedReferenceBubbles,
            ThinkingModePolicy.ThinkingDecision thinkingDecision,
            boolean isOpenAiCompatibleProvider
    ) {
        if (convState == null && actionDecision == null) {
            return build(
                    customerMessage,
                    customerName,
                    history,
                    chunks,
                    appliedRules,
                    pagePersona,
                    salesStage,
                    resolvedHonorific,
                    customerInsight,
                    activeSkills,
                    mocSummary,
                    priceLadderResult,
                    sampleDialogues
            );
        }

        PagePersonaConfig cfg = pagePersona != null ? pagePersona.resolvedConfig() : PagePersonaConfig.empty();
        String pageName = (pagePersona != null && pagePersona.name() != null && !pagePersona.name().isBlank())
                ? PromptSecurityGuard.sanitizeUntrustedContent(pagePersona.name().strip())
                : "Fanpage";
        String honorific = (resolvedHonorific != null && !resolvedHonorific.isBlank())
                ? resolvedHonorific.strip()
                : resolveCustomerHonorific(customerMessage, history, customerName, pagePersona);
        String selfPronoun = cfg.resolvedSelfPronoun();
        String defaultHonorific = cfg.resolvedDefaultCustomerPronoun();

        StringBuilder sb = new StringBuilder(2400);
        sb.append("# VAI TRÒ & QUY TẮC RUNTIME PAGE (").append(pageName).append(")\n");
        sb.append("- Đại từ mặc định của Page: xưng '").append(selfPronoun).append("', gọi khách theo resolved_customer_pronoun của lượt (mặc định '").append(defaultHonorific).append("').\n");
        sb.append("- Giọng văn tư vấn (Universal Sales Voice Engine): Tự nhiên, ấm áp, tinh tế như chuyên viên thật đang nhắn tin trực tiếp. Mỗi bong bóng ngắn gọn 1-2 câu (tối đa 45 từ). TUYỆT ĐỐI CẤM văn dịch máy hoặc sáo rỗng ('đang gặp phải tình trạng... khiến mình mệt mỏi hẳn luôn ạ', 'Liệu Chị/mình có muốn...', 'Dạ em ghi nhận mình đang...'), CẤM viết mã gói nội bộ (NS01, DS02), CẤM dùng dấu '+' hay dấu '…', chỉ dùng định dạng giá gọn (399k, 239k).\n");
        sb.append("- Nhất quán đại từ 100%: Trong cùng một câu và toàn bộ lượt trả lời chỉ dùng ĐÚNG 1 đại từ gọi khách (resolved_customer_pronoun); TUYỆT ĐỐI KHÔNG trộn lẫn 2 đại từ ('Chị đang... khiến mình...') và KHÔNG gọi khách là 'Em'.\n");
        if (cfg.activePromptCompact() != null && !cfg.activePromptCompact().isBlank()) {
            sb.append(PromptSecurityGuard.sanitizeUntrustedContent(cfg.activePromptCompact().strip())).append('\n');
        }
        // Static Always-On learned_rules placed inside SYSTEM prefix so Ollama KV cache reuses them across turns
        List<LearnedRuleDto> guardedAlwaysOn = (appliedRules != null && !appliedRules.isEmpty())
                ? PromptBudgetGuard.validateAlwaysOnRules(appliedRules)
                : List.of();
        if (!guardedAlwaysOn.isEmpty()) {
            sb.append("<PAGE_ALWAYS_ON_RULES>\n");
            for (LearnedRuleDto r : guardedAlwaysOn) {
                if (r == null || r.generalizedRule() == null || r.generalizedRule().isBlank()) continue;
                sb.append("- [").append(r.ruleCode() != null ? r.ruleCode() : "RULE").append("] ")
                  .append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(r.generalizedRule()), 200))
                  .append('\n');
            }
            sb.append("</PAGE_ALWAYS_ON_RULES>\n");
        }
        sb.append("<!-- SYSTEM_USER_SPLIT -->\n");
        sb.append("- ĐẠI TỪ XƯNG HÔ BẮT BUỘC LƯỢT NÀY: Xưng '").append(selfPronoun)
          .append("', gọi khách là '").append(honorific)
          .append("'. TUYỆT ĐỐI KHÔNG đảo ngược đại từ và KHÔNG trộn '").append(honorific).append("' với đại từ khác.\n\n");

        PrimaryActionRouter.PrimaryAction effAction = actionDecision != null ? actionDecision.effectiveResponseAction() : null;
        String detIntent = actionDecision != null && actionDecision.detectedIntent() != null ? actionDecision.detectedIntent() : "";

        // 1. <CONVERSATION_HISTORY> (Đặt đầu phần user để tận dụng KV prefix cache giữa các lượt kế tiếp)
        if (history != null && !history.isEmpty()) {
            sb.append("<CONVERSATION_HISTORY>\n");
            int start = Math.max(0, history.size() - 4);
            for (int i = start; i < history.size(); i++) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t != null && t.content() != null && !t.content().isBlank()) {
                    String roleLabel = "assistant".equalsIgnoreCase(t.role()) ? "Tư vấn viên" : "Khách hàng";
                    sb.append(roleLabel).append(": ")
                      .append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(t.content()), 160))
                      .append('\n');
                }
            }
            sb.append("</CONVERSATION_HISTORY>\n\n");
        }

        // 2. <DOCUMENT_CONTEXT> từ bảng chunks (FusedChunk) — Scoped theo PrimaryAction để giảm prompt_eval_duration
        boolean isNoQuoteDiscoveryTurn = effAction == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION
                || effAction == PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE;
        boolean isPreGroundedCatalogTurn = effAction == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                || effAction == PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE
                || effAction == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                || effAction == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                || effAction == PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS;
        if (!isNoQuoteDiscoveryTurn && chunks != null && !chunks.isEmpty()) {
            sb.append("<DOCUMENT_CONTEXT>\n");
            int maxChunks = (effAction == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA)
                    ? Math.min(chunks.size(), 3)
                    : (isPreGroundedCatalogTurn ? 1 : Math.min(chunks.size(), 2));
            int maxChunkChars = (effAction == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA)
                    ? 480
                    : 240;
            for (int i = 0; i < maxChunks; i++) {
                ReciprocalRankFusion.FusedChunk fc = chunks.get(i);
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String chunkText = compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(fc.chunk().text()), maxChunkChars);
                    sb.append("[Chunk ").append(i + 1).append("] ").append(chunkText).append('\n');
                }
            }
            sb.append("</DOCUMENT_CONTEXT>\n\n");
        }

        // 3. Contextual Rule & Micro-Skill (Tối giản, không nhồi câu mẫu dài khi đã có GROUNDED_TURN_FACTS)
        if (appliedRules != null && !appliedRules.isEmpty()) {
            LearnedRuleDto scopedContextual = PromptBudgetGuard.selectScopedContextualRule(
                    appliedRules,
                    detIntent,
                    customerMessage
            );
            if (scopedContextual != null && scopedContextual.generalizedRule() != null && !scopedContextual.generalizedRule().isBlank()
                    && guardedAlwaysOn.stream().noneMatch(r -> r.id() != null && r.id().equals(scopedContextual.id()))) {
                sb.append("<CONTEXTUAL_RULE>\n- [")
                  .append(scopedContextual.ruleCode() != null ? scopedContextual.ruleCode() : "RULE").append("] ")
                  .append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(scopedContextual.generalizedRule()), 180))
                  .append("\n</CONTEXTUAL_RULE>\n\n");
            }
        }
        if (activeSkills != null && !activeSkills.isEmpty()) {
            sb.append("<TACTICAL_SALES_SKILLS do_not_copy_literally=\"true\">\n");
            for (AgentSkillDto skill : activeSkills) {
                if (skill == null) continue;
                sb.append("- Skill [").append(skill.code()).append("]: ")
                  .append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(skill.tacticalInstructions()), 180))
                  .append('\n');
            }
            sb.append("</TACTICAL_SALES_SKILLS>\n\n");
        }

        // 5.5 <THINKING_MODE_STRATEGY> (Adaptive Thinking Router)
        ThinkingMode effectiveThinkingMode = thinkingDecision != null && thinkingDecision.thinkingMode() != null
                ? thinkingDecision.thinkingMode()
                : ThinkingMode.FAST_NO_THINK;
        sb.append("<THINKING_MODE_STRATEGY>\n");
        sb.append("- thinking_mode: ").append(effectiveThinkingMode.name()).append('\n');
        sb.append("- max_sentences_per_bubble: ").append(effectiveThinkingMode.maxSentencesPerBubble()).append(", max_words_per_bubble: 45\n");
        if (thinkingDecision != null && thinkingDecision.triggerReason() != null) {
            sb.append("- trigger_reason: ").append(thinkingDecision.triggerReason()).append('\n');
        }
        boolean isSplitSecondBubbleOnly = effectiveThinkingMode == ThinkingMode.PRICE_ANCHOR_THINK
                && dbGroundedReferenceBubbles != null
                && dbGroundedReferenceBubbles.size() == 1;
        if (effectiveThinkingMode == ThinkingMode.PRICE_ANCHOR_THINK) {
            if (isSplitSecondBubbleOnly) {
                sb.append("- Chiến lược PRICE_ANCHOR_THINK (Bubble 2 duy nhất): Không lặp lại giá đã gửi. Liên hệ trực tiếp tới vùng đau/mỏi khách vừa chia sẻ + quyền lợi đã xác minh trong <GROUNDED_TURN_FACTS>. Không ép hỏi khung giờ đặt lịch cứng.\n");
            } else {
                sb.append("- Chiến lược PRICE_ANCHOR_THINK (2 bong bóng): Bubble 1 báo tên gói, thời lượng, mức giá gọn (vd 399k). Bubble 2 (sau nhịp nghỉ) không lặp lại giá, liên hệ trực tiếp tới triệu chứng của khách và quyền lợi đã xác minh trong <GROUNDED_TURN_FACTS>, không ép hỏi khung giờ đặt lịch cứng.\n");
            }
        } else if (effectiveThinkingMode == ThinkingMode.OBJECTION_REFRAME_THINK) {
            int objTurn = thinkingDecision != null ? thinkingDecision.objectionTurnIndex() : 1;
            ThinkingModePolicy.ObjectionRootCause rootCause = thinkingDecision != null && thinkingDecision.objectionRootCause() != null
                    ? thinkingDecision.objectionRootCause()
                    : ThinkingModePolicy.ObjectionRootCause.BUDGET_MISMATCH;
            sb.append("- objection_turn_index: ").append(objTurn).append('\n');
            sb.append("- objection_root_cause: ").append(rootCause.name()).append('\n');
            if (thinkingDecision != null && thinkingDecision.previouslyUsedArguments() != null && !thinkingDecision.previouslyUsedArguments().isEmpty()) {
                sb.append("- previously_used_arguments (CẤM LẶP LẠI): ").append(thinkingDecision.previouslyUsedArguments()).append('\n');
            }
        } else if (effectiveThinkingMode == ThinkingMode.REPAIR_FIRST) {
            if (thinkingDecision != null && thinkingDecision.unresolvedQuestions() != null && !thinkingDecision.unresolvedQuestions().isEmpty()) {
                sb.append("- unresolved_questions_to_answer_now: ").append(thinkingDecision.unresolvedQuestions()).append('\n');
            }
            sb.append("- Chiến lược REPAIR_FIRST: Nhận lỗi chân thành, ngắn gọn và giải quyết TRỰC DIỆN phản bác hoặc câu hỏi đang treo của khách dựa trên <GROUNDED_TURN_FACTS>. CẤM lặp lại câu cũ.\n");
        }
        sb.append("</THINKING_MODE_STRATEGY>\n\n");

        // 6. <CONVERSATION_STATE_AND_ACTION_CONTRACT> & <GROUNDED_TURN_FACTS>
        sb.append("<CONVERSATION_STATE_AND_ACTION_CONTRACT>\n");
        if (actionDecision != null) {
            sb.append("- primary_action: ").append(effAction).append('\n');
            sb.append("- detected_intent: ").append(detIntent).append('\n');
            sb.append("- opening_required: ").append(actionDecision.openingRequired()).append('\n');
            sb.append("- active_skill_code: ").append(actionDecision.correspondingMicroSkillCode()).append('\n');
        }
        sb.append("- thinking_mode: ").append(effectiveThinkingMode.name()).append('\n');
        ConversationState enriched = convState != null
                ? convState.enrichWithCurrentMessage(customerMessage, cfg.safeDomainKeywords())
                : null;
        if (convState != null && enriched != null) {
            sb.append("- known_service: ").append(enriched.knownServiceFamily() != null ? enriched.knownServiceFamily() : "null").append('\n');
            sb.append("- known_service_interest: ").append(enriched.knownServiceInterest() != null ? enriched.knownServiceInterest() : "null").append('\n');
            sb.append("- known_symptoms: ").append(enriched.knownSymptoms()).append('\n');
            sb.append("- known_body_areas: ").append(enriched.knownBodyAreas()).append('\n');
            sb.append("- condition_clarified: ").append(enriched.conditionClarified()).append('\n');
            sb.append("- price_requested_in_session: ").append(enriched.priceRequestedInSession()).append('\n');
            sb.append("- price_discussed: ").append(convState.priceDiscussed() || isSplitSecondBubbleOnly).append('\n');
            sb.append("- price_objection_count: ").append(convState.objectionCount()).append('\n');
            sb.append("- objection_stage: ").append(convState.objectionStage() != null ? convState.objectionStage() : "NONE").append('\n');
            sb.append("- downsell_offered: ").append(convState.downsellOffered()).append('\n');
            sb.append("- last_offered_package_id: ").append(convState.lastOfferedPackageId() != null ? convState.lastOfferedPackageId() : "null").append('\n');
            sb.append("- last_offered_package_name: ").append(convState.lastOfferedPackageName() != null ? convState.lastOfferedPackageName() : "null").append('\n');
            sb.append("- last_offered_duration: ").append(convState.lastOfferedDuration() != null ? convState.lastOfferedDuration() : "null").append('\n');
            sb.append("- last_offered_price: ").append(convState.lastOfferedPrice() != null ? convState.lastOfferedPrice() : "null").append('\n');
            sb.append("- last_bot_question_type: ").append(convState.lastBotQuestionType()).append('\n');
            sb.append("- bot_apologized_in_last_turn: ").append(convState.botApologizedInLastTurn()).append('\n');
            sb.append("- rejected_durations: ").append(convState.rejectedDurations()).append('\n');
            ConversationState.UnresolvedCustomerRequest activeUnresolved = enriched.mostRecentUnresolvedRequest();
            if (activeUnresolved != null) {
                sb.append("- unresolved_customer_request: intent=").append(activeUnresolved.semanticIntent())
                  .append(", entity=").append(activeUnresolved.referencedEntity() != null ? activeUnresolved.referencedEntity() : "null")
                  .append(", question=\"").append(compactPromptText(PromptSecurityGuard.sanitizeUntrustedContent(activeUnresolved.canonicalQuestion()), 120)).append("\"")
                  .append(", repeat_or_challenge_count=").append(activeUnresolved.repeatOrChallengeCount())
                  .append('\n');
            }
        }

        // 6.5 <GROUNDED_TURN_FACTS> — Dữ liệu sự thật cấu trúc từ Catalog & State (KHÔNG nhồi câu mẫu)
        int postPriceDelayMs = cfg.resolvedPostPriceDelayMs();
        sb.append("<GROUNDED_TURN_FACTS>\n");
        boolean hasConfiguredOpeningBubbles = actionDecision != null
                && actionDecision.openingRequired()
                && !cfg.openingBubbles().isEmpty();
        if (actionDecision != null && actionDecision.openingRequired()) {
            String agentName = cfg.botName();
            if (hasConfiguredOpeningBubbles) {
                sb.append("- turn_1_opening_guidance: Lượt đầu tiên. Hệ thống đã gửi 2 bong bóng chào mở đầu (CONFIGURED_OPENING). Bạn CHỈ sinh ĐÚNG 1 bong bóng thứ 3 trong reply_bubbles (độ dài mảng = 1) để phản hồi trực tiếp ý định của khách (xưng '")
                  .append(selfPronoun).append("', gọi khách là '").append(honorific)
                  .append("', TUYỆT ĐỐI KHÔNG bắt đầu bằng 'Dạ chào chị ạ' hay lặp lại lời chào/giới thiệu chung). ");
            } else {
                sb.append("- turn_1_opening_guidance: Lượt đầu tiên. Chào ngắn gọn với tư cách ")
                  .append(agentName != null && !agentName.isBlank() ? agentName + " từ " + pageName : pageName)
                  .append(" (xưng '").append(selfPronoun).append("', gọi khách là '").append(honorific)
                  .append("') và đi thẳng vào trả lời ý định của khách. ");
            }
            if (effAction == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION) {
                sb.append("Khách đang hỏi giá chung: hỏi nhẹ 1 câu xem ")
                  .append(honorific).append(" đang quan tâm nhóm dịch vụ nào hoặc đang mỏi vùng nào để ")
                  .append(selfPronoun).append(" gửi đúng mức giá theo thời lượng, CHƯA báo con số giá ở lượt này.\n");
            } else {
                sb.append("Hỏi nhẹ 1 câu xem hôm nay ").append(honorific).append(" đang muốn thư giãn vùng nào hay tìm hiểu dịch vụ nào để ").append(selfPronoun).append(" tư vấn ngay cho ").append(honorific).append(" nha (KHÔNG mở đầu bằng 'Dạ chào ").append(honorific).append("').\n");
            }
        }
        if (effAction == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA && enriched != null) {
            PrimaryActionRouter.CatalogOption detailOpt = PrimaryActionRouter.matchSpecificCatalogOption(customerMessage, enriched, chunks);
            if (detailOpt == null) {
                detailOpt = PrimaryActionRouter.selectBestSingleCatalogOption(customerMessage, enriched, chunks);
            }
            boolean shouldIncludePendingPrice = convState != null && convState.priceRequestedInSession() && !convState.priceDiscussed() && detailOpt != null;
            if (detailOpt != null) {
                sb.append("- target_catalog_package_name: ").append(detailOpt.name()).append('\n');
                sb.append("- target_catalog_duration: ").append(detailOpt.unitLabel()).append('\n');
                if (shouldIncludePendingPrice || convState == null || !convState.priceDiscussed()) {
                    sb.append("- target_catalog_price: ").append(detailOpt.priceDisplay()).append('\n');
                }
                appendVerifiedPackageBenefitsAndForbiddenPerks(sb, convState, detailOpt, detailOpt.priceVnd());
            }
            String normDetailMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(customerMessage);
            boolean isDurationPushback = normDetailMsg.contains("thời lượng") || normDetailMsg.contains("tưởng");
            boolean isBenefitQuestion = normDetailMsg.contains("quyền lợi") || normDetailMsg.contains("được hưởng");
            sb.append("- tactical_goal: Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, KHÔNG xin lỗi");
            if (convState != null && convState.priceDiscussed() && !shouldIncludePendingPrice) {
                sb.append(", TUYỆT ĐỐI CẤM lặp lại con số giá đã báo");
            }
            sb.append("). Giải đáp trực tiếp câu hỏi chi tiết của ")
              .append(honorific).append(" dựa 100% vào dữ liệu xác thực trong [Chunk] và verified_package_benefits ở trên");
            if (shouldIncludePendingPrice) {
                sb.append("; BẮT BUỘC nêu rõ bước này nằm trong gói ").append(detailOpt.name()).append(" ").append(detailOpt.unitLabel())
                  .append(" (giá ").append(detailOpt.priceDisplay()).append(") gồm ").append(detailOpt.benefitSummary() != null ? detailOpt.benefitSummary().replace(" + ", ", ") : "");
            } else if (isDurationPushback && detailOpt != null) {
                sb.append("; xác nhận rõ gói ").append(detailOpt.name()).append(" đang tư vấn chuẩn là ").append(detailOpt.unitLabel())
                  .append(", còn các mức 80 phút hay 100 phút chỉ là gói nâng cao có thêm ngâm bồn thảo dược nếu ").append(honorific).append(" muốn làm lâu hơn thôi ạ");
            } else if (isBenefitQuestion && detailOpt != null) {
                sb.append("; liệt kê tự nhiên, rõ ràng các quyền lợi và bước chăm sóc có trong gói ").append(detailOpt.name()).append(" ").append(detailOpt.unitLabel())
                  .append(" theo đúng verified_package_benefits (không gian phòng, chăm sóc vùng đau mỏi, buffet thực dưỡng/ăn nhẹ sau buổi làm)");
            }
            sb.append(". Nếu khách hỏi thành phần cụ thể (ví dụ ngâm loại lá/thảo mộc gì) mà [Chunk] chỉ ghi bước quy trình chứ chưa liệt kê từng vị lá, hãy giải thích rõ bước quy trình có trong gói và nói thật tự nhiên rằng thành phần thảo mộc cụ thể theo mẻ nấu trong ngày nên bảng dịch vụ tiêu chuẩn chưa liệt kê từng vị (TUYỆT ĐỐI CẤM tự bịa tên nguyên liệu như tía tô, trà xanh, gừng, sả, lá lốt ngoài tài liệu!).\n");
        } else if (effAction == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION) {
            sb.append("- tactical_goal: Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, KHÔNG xin lỗi). Hỏi nhẹ 1 câu tự nhiên xem ")
              .append(honorific).append(" đang quan tâm nhóm dịch vụ nào trong các dịch vụ của ").append(pageName)
              .append(" để ").append(selfPronoun).append(" gửi đúng mức giá và thời lượng phù hợp cho ").append(honorific).append(" nha.\n");
        } else if (effAction == PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE && enriched != null) {
            if ("PUSHBACK_ON_ASSUMPTION_REPAIR".equals(detIntent)) {
                sb.append("- tactical_goal: Sinh 1 bong bóng. Mở đầu bằng lời xin lỗi ngắn gọn ('Dạ ")
                  .append(selfPronoun).append(" xin lỗi ").append(honorific).append(" vì vừa diễn đạt chưa chuẩn làm ").append(honorific).append(" hiểu nhầm ạ'), xác nhận lại ĐÚNG các triệu chứng khách đã chia sẻ ")
                  .append(enriched.knownSymptoms())
                  .append(" (KHÔNG lặp lại câu của lượt trước, KHÔNG dùng lại từ khách vừa phủ định như 'căng'), và hỏi nhẹ xem tình trạng này của ")
                  .append(honorific).append(" mới bị gần đây hay đã kéo dài để ").append(selfPronoun).append(" gợi ý gói sát nhất ạ. CHƯA báo giá ở lượt này.\n");
            } else {
                sb.append("- tactical_goal: Sinh 1 bong bóng. Câu 1: Đồng cảm ấm áp, tự nhiên và nhắc ĐẦY ĐỦ tên tất cả triệu chứng khách vừa chia sẻ ")
                  .append(enriched.knownSymptoms())
                  .append(" (ví dụ: 'Dạ vừa bị ").append(String.join(", ", enriched.knownSymptoms())).append(" cùng lúc chắc hẳn làm ")
                  .append(honorific).append(" mệt mỏi và khó chịu lắm ạ.' — TUYỆT ĐỐI KHÔNG nói chung chung 'nhiều triệu chứng', KHÔNG viết 'em xin lỗi mình vì mình đang phải chịu đựng', KHÔNG viết 'đang gặp phải tình trạng... khiến mình mệt mỏi hẳn luôn ạ', KHÔNG chẩn đoán 'khí huyết kém lưu thông'). Câu 2: Hỏi nhẹ 1 câu làm rõ tình trạng này của ")
                  .append(honorific).append(" mới xuất hiện gần đây hay đã kéo dài bao lâu để ").append(selfPronoun).append(" chọn liệu trình sát nhất ạ (KHÔNG dùng từ 'căng' nếu khách chưa nói, CHƯA báo giá).\n");
            }
        } else if (effAction == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                || effAction == PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE) {
            PrimaryActionRouter.CatalogOption matchedOpt = PrimaryActionRouter.matchSpecificCatalogOption(customerMessage, enriched, chunks);
            if (matchedOpt == null) {
                matchedOpt = PrimaryActionRouter.selectBestSingleCatalogOption(customerMessage, enriched, chunks);
            }
            if (matchedOpt != null) {
                sb.append("- target_catalog_package_name: ").append(matchedOpt.name()).append('\n');
                sb.append("- target_catalog_duration: ").append(matchedOpt.unitLabel()).append('\n');
                if (!isSplitSecondBubbleOnly
                        && !"CONDITION_FOLLOWUP_AFTER_QUOTE_ADVANCE".equals(detIntent)
                        && (convState == null || !convState.priceDiscussed() || effAction == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE)) {
                    sb.append("- target_catalog_price: ").append(matchedOpt.priceDisplay()).append('\n');
                }
                appendVerifiedPackageBenefitsAndForbiddenPerks(sb, convState, matchedOpt, matchedOpt.priceVnd());
            }
            if (isSplitSecondBubbleOnly) {
                sb.append("- Bong bóng 1 báo giá đã được gửi cho khách: Gói ")
                  .append(matchedOpt != null ? matchedOpt.name() + " " + matchedOpt.unitLabel() + " (" + matchedOpt.priceDisplay() + ")" : "đang tư vấn")
                  .append(".\n");
                sb.append("- tactical_goal: Sinh ĐÚNG 1 bong bóng thứ 2 (Emotional Anchor) nối tiếp trực tiếp Bong bóng 1 vừa báo giá ở trên. Liên hệ tự nhiên tới tình trạng ")
                  .append(enriched != null ? enriched.knownSymptoms() : List.of())
                  .append(" của ").append(honorific).append(" và quyền lợi thực tế trong verified_package_benefits, rồi hỏi nhẹ 1 câu xem ")
                  .append(honorific).append(" thấy gói ").append(matchedOpt != null ? matchedOpt.unitLabel() : "này")
                  .append(" đã hợp ý mình hay muốn ").append(selfPronoun).append(" chia sẻ thêm về quyền lợi đi kèm ạ. TUYỆT ĐỐI CẤM lặp lại con số giá, CẤM xin lỗi, CẤM yêu cầu khách nói lại!\n");
            } else if ("CONDITION_FOLLOWUP_AFTER_QUOTE_ADVANCE".equals(detIntent)) {
                sb.append("- tactical_goal: Khách vừa bổ sung tần suất/thời gian đau mỏi cho gói ")
                  .append(matchedOpt != null ? matchedOpt.name() + " " + matchedOpt.unitLabel() : "đang tư vấn")
                  .append(" đã báo trước đó. Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, TUYỆT ĐỐI CẤM lặp lại con số giá, CẤM xin lỗi, CẤM yêu cầu khách nói lại): Đồng cảm tự nhiên rằng nếu tình trạng thi thoảng xuất hiện thì chăm sóc bằng gói ")
                  .append(matchedOpt != null ? matchedOpt.name() + " " + matchedOpt.unitLabel() : "này")
                  .append(" rất vừa vặn để thả lỏng vùng mỏi, rồi hỏi nhẹ xem ").append(honorific)
                  .append(" muốn tìm hiểu thêm về các bước trong gói hay khung giờ trống để ghé trải nghiệm ạ.\n");
            } else if ("META_APOLOGY_POSITIVE_INTEREST_ADVANCE".equals(detIntent)) {
                sb.append("- tactical_goal: Khách vừa khen dịch vụ nghe hay vừa trêu việc bạn xin lỗi. Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, TUYỆT ĐỐI CẤM dùng từ 'xin lỗi', CẤM lặp lại con số giá đã báo): Đáp lại vui vẻ, duyên dáng ('Dạ tại ")
                  .append(selfPronoun).append(" quen miệng nói năng cẩn thận quá đó ").append(honorific).append(" ơi, ").append(selfPronoun).append(" rút kinh nghiệm liền nha.') và dẫn dắt tiếp về gói ")
                  .append(matchedOpt != null ? matchedOpt.name() + " " + matchedOpt.unitLabel() : "đang trao đổi")
                  .append(" xem ").append(honorific).append(" thấy gói này hợp ý rồi hay muốn xem thêm gói thời lượng dài hơn ạ.\n");
            } else if ("CONFIRM_PREVIOUS_INFO_ADVANCE".equals(detIntent)) {
                sb.append("- tactical_goal: Khách xác nhận bạn đã hiểu đúng dịch vụ khách quan tâm. Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, TUYỆT ĐỐI CẤM dùng từ 'xin lỗi', CẤM dùng cụm 'hiểu lầm', CẤM lặp lại con số giá đã báo, CẤM hỏi lại câu xác nhận dịch vụ): Nêu ngắn gọn lợi ích thư giãn của gói ")
                  .append(matchedOpt != null ? matchedOpt.name() + " " + matchedOpt.unitLabel() : "đang trao đổi")
                  .append(" từ verified_package_benefits và hỏi nhẹ xem ").append(honorific).append(" muốn thư giãn gói ")
                  .append(matchedOpt != null ? matchedOpt.unitLabel() : "này").append(" hay muốn kèm thêm xông hơi phòng riêng ạ.\n");
            } else if ("FRUSTRATION_RECOVERY_QUOTE_PENDING_PRICE".equals(detIntent)) {
                sb.append("- tactical_goal: Sinh 2 bong bóng. Bubble 1 (typing_delay_ms=0): Nhận lỗi ngắn gọn ('Dạ ")
                  .append(selfPronoun).append(" xin lỗi ").append(honorific).append(" vì câu hỏi trước chưa rõ ý làm ").append(honorific).append(" sốt ruột ạ.') và báo ngay gói ")
                  .append(matchedOpt != null ? matchedOpt.name() + " " + matchedOpt.unitLabel() + " giá " + matchedOpt.priceDisplay() : "phù hợp")
                  .append(" trọn gói không nhận Tip cho tình trạng ").append(enriched != null ? enriched.knownSymptoms() : List.of())
                  .append(" của ").append(honorific).append(" ạ (KHÔNG dùng dấu '…', KHÔNG viết mã NS01). Bubble 2 (typing_delay_ms=")
                  .append(postPriceDelayMs)
                  .append("): Nêu ngắn gọn lợi ích thực tế trong verified_package_benefits giúp dịu vùng mỏi và dễ ngủ hơn, rồi hỏi nhẹ xem ")
                  .append(honorific).append(" thấy gói 60 phút này phù hợp với thời gian của ").append(honorific).append(" không ạ.\n");
            } else {
                sb.append("- tactical_goal: Sinh 2 bong bóng. Bubble 1 (typing_delay_ms=0): Báo rõ tên gói, thời lượng và mức giá từ target_catalog_* ở trên. Bubble 2 (typing_delay_ms=")
                  .append(postPriceDelayMs)
                  .append("): Liên hệ tới tình trạng của khách và quyền lợi thực tế trong verified_package_benefits (không lặp lại giá ở Bubble 2, KHÔNG nêu quyền lợi trong forbidden_unverified_tier_perks).\n");
            }
        } else if (effAction == PrimaryActionRouter.PrimaryAction.ADVANCE_AFTER_ACK && convState != null) {
            String baseName = convState.lastOfferedPackageName();
            String activePkg = baseName != null
                    ? (convState.lastOfferedDuration() != null && !baseName.toLowerCase(Locale.ROOT).contains("phút")
                        ? baseName + " " + convState.lastOfferedDuration() + " phút"
                        : baseName)
                    : (convState.knownRecommendedPackage() != null ? convState.knownRecommendedPackage() : "gói đang tư vấn");
            sb.append("- active_discussed_package: ").append(activePkg).append('\n');
            sb.append("- tactical_goal: Khách vừa nhắn xác nhận ngắn ('").append(PromptSecurityGuard.sanitizeUntrustedContent(customerMessage)).append("'). Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, TUYỆT ĐỐI CẤM viết 'em ghi nhận', CẤM dùng từ 'xin lỗi', CẤM tự ý coi là khách đã chốt đặt lịch): Hỏi nhẹ nhàng xem với gói ")
              .append(activePkg).append(" này ").append(honorific).append(" muốn ").append(selfPronoun)
              .append(" giới thiệu thêm về không gian phòng hay ").append(honorific).append(" muốn tham khảo khung giờ trống để ghé trải nghiệm ạ.\n");
        } else if (effAction == PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY) {
            if ("STRONG_REJECTION".equals(detIntent) || "STAGE_3_GRACEFUL_EXIT".equals(detIntent) || "EXPLICIT_OPT_OUT".equals(detIntent)) {
                sb.append("- tactical_goal: Khách vừa từ chối hoặc muốn dừng cuộc trò chuyện ('")
                  .append(PromptSecurityGuard.sanitizeUntrustedContent(customerMessage))
                  .append("'). Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, 0 câu hỏi, TUYỆT ĐỐI CẤM có dấu '?', CẤM hỏi 'có phải mình đang cảm thấy hơi mệt mỏi không', CẤM chèo kéo, CẤM viết cụm 'Xin lỗi nhẹ nhàng'): Hãy đóng vai chuyên viên xưng '")
                  .append(selfPronoun).append("', mở đầu bằng 'Dạ ").append(selfPronoun).append(" xin lỗi ").append(honorific)
                  .append(" vì phần tư vấn vừa rồi chưa làm ").append(honorific).append(" hài lòng ạ.', sau đó xin phép dừng làm phiền và cảm ơn ").append(honorific).append(" đã nhắn tin cho bên ").append(selfPronoun).append(" nha.\n");
            } else if ("META_COMMENT_ON_GREETING".equals(detIntent)) {
                sb.append("- tactical_goal: Khách nhận xét bạn chào hơi nhiều. Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0, TUYỆT ĐỐI CẤM dùng từ 'xin lỗi'): Đáp lại vui vẻ, tự nhiên ('Dạ tại ")
                  .append(selfPronoun).append(" hào hứng đón ").append(honorific).append(" ghé thăm quá nên chào hơi kỹ một chút ạ.') rồi hỏi nhẹ xem hôm nay ")
                  .append(honorific).append(" đang muốn thư giãn dịch vụ nào hay chăm sóc vùng nào để ").append(selfPronoun).append(" tư vấn ngay cho ").append(honorific).append(" nha.\n");
            } else if ("FRUSTRATION_CONCERN_ACKNOWLEDGEMENT".equals(detIntent)) {
                String baseName = convState != null ? convState.lastOfferedPackageName() : null;
                String activePkg = baseName != null
                        ? (convState.lastOfferedDuration() != null && !baseName.toLowerCase(Locale.ROOT).contains("phút")
                            ? baseName + " " + convState.lastOfferedDuration() + " phút"
                            : baseName)
                        : (convState != null && convState.knownRecommendedPackage() != null ? convState.knownRecommendedPackage() : null);
                if (activePkg != null) {
                    sb.append("- active_discussed_package: ").append(activePkg).append('\n');
                    sb.append("- tactical_goal: Khách đang khó chịu vì câu trả lời trước chưa tự nhiên. Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0): Nhận lỗi ngắn gọn vì cách diễn đạt vừa rồi làm ")
                      .append(honorific).append(" thấy rối, tóm tắt lại mạch đang tư vấn gói ").append(activePkg)
                      .append(" (TUYỆT ĐỐI CẤM gọi dịch vụ là 'tình trạng ngâm', CẤM tự ý lôi gói khác như 239k vào báo giá!) và hỏi xem ")
                      .append(honorific).append(" cần làm rõ lại điểm nào hay chuyển nhân viên hỗ trợ trực tiếp ngay ạ.\n");
                } else {
                    sb.append("- tactical_goal: Sinh ĐÚNG 1 bong bóng (typing_delay_ms=0): Nhận lỗi ngắn gọn vì làm ")
                      .append(honorific).append(" chưa hài lòng và hỏi xem phần thông tin nào đang làm ").append(honorific)
                      .append(" băn khoăn để ").append(selfPronoun).append(" giải đáp chuẩn xác hoặc chuyển nhân viên hỗ trợ ngay nhé.\n");
                }
            }
        } else if (effAction == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME && convState != null) {
            long heldVnd = convState.lastOfferedPriceVnd() != null ? convState.lastOfferedPriceVnd().longValue() : 0L;
            String heldName = convState.lastOfferedPackageName() != null ? convState.lastOfferedPackageName() : "Massage cơ bản";
            String heldDur = convState.lastOfferedDuration() != null ? convState.lastOfferedDuration() + " phút" : "60 phút";
            String heldPrice = convState.lastOfferedPrice() != null ? convState.lastOfferedPrice() : "399k";
            sb.append("- held_package_to_defend: ").append(heldName).append(" ").append(heldDur).append(" giá ").append(heldPrice).append('\n');
            appendVerifiedPackageBenefitsAndForbiddenPerks(sb, convState, null, heldVnd);
            sb.append("- tactical_goal: Khách chê đắt lần 1. Sinh 1 bong bóng (typing_delay_ms=0): Đồng cảm tự nhiên với việc ")
              .append(honorific).append(" cân nhắc chi phí, GIỮ NGUYÊN gói ").append(heldName).append(" ").append(heldDur).append(" giá ").append(heldPrice)
              .append(" (chỉ viết '").append(heldPrice).append("', KHÔNG viết '399.000đ' hay mã '(NS01)'), làm rõ mức ").append(heldPrice)
              .append(" đã trọn gói các quyền lợi thực tế trong verified_package_benefits và không nhận tiền Tip, đồng thời gợi ý nhẹ nhàng ")
              .append(honorific).append(" trải nghiệm thử 1 buổi xem cơ thể nhẹ nhõm thế nào nhé ạ.\n");
        } else if (effAction == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT && convState != null) {
            PrimaryActionRouter.CatalogOption lowerOpt = PrimaryActionRouter.selectLowerCatalogOption(convState, chunks, customerMessage);
            if (lowerOpt != null) {
                sb.append("- verified_downsell_package_name: ").append(lowerOpt.name()).append('\n');
                sb.append("- verified_downsell_duration: ").append(lowerOpt.unitLabel()).append('\n');
                sb.append("- verified_downsell_price: ").append(lowerOpt.priceDisplay()).append('\n');
                appendVerifiedPackageBenefitsAndForbiddenPerks(sb, convState, lowerOpt, lowerOpt.priceVnd());
                sb.append("- tactical_goal: Sinh 2 bong bóng. Bubble 1 (typing_delay_ms=0): Tôn trọng việc ")
                  .append(honorific).append(" cân nhắc thêm và hỏi nhẹ 1 câu xem ").append(honorific).append(" đang phân vân về mức chi phí buổi đầu hay thời gian liệu trình ạ. Bubble 2 (typing_delay_ms=")
                  .append(postPriceDelayMs)
                  .append("): Gợi ý lựa chọn tiết kiệm hơn là ").append(lowerOpt.name()).append(" ").append(lowerOpt.unitLabel()).append(" giá ").append(lowerOpt.priceDisplay())
                  .append(" với đúng các quyền lợi thực tế trong verified_package_benefits viết bằng câu văn tự nhiên (TUYỆT ĐỐI KHÔNG dùng dấu '+', KHÔNG viết mã DS02, KHÔNG gán nhầm quyền lợi trong forbidden_unverified_tier_perks).\n");
            }
        } else if (effAction == PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS && convState != null) {
            String accName = convState.lastOfferedPackageName() != null ? convState.lastOfferedPackageName() : "Gói dưỡng sinh cơ bản";
            String accDur = convState.lastOfferedDuration() != null ? convState.lastOfferedDuration() + " phút" : "60 phút";
            String accPrice = convState.lastOfferedPrice() != null ? convState.lastOfferedPrice() : "239k";
            sb.append("- accepted_package_name: ").append(accName).append('\n');
            sb.append("- accepted_package_duration: ").append(accDur).append('\n');
            sb.append("- accepted_package_price: ").append(accPrice).append('\n');
            sb.append("- tactical_goal: Sinh 1 bong bóng (typing_delay_ms=0): Xác nhận lại ĐÚNG gói ").append(accName).append(" ").append(accDur).append(" giá ").append(accPrice)
              .append(" mà ").append(honorific).append(" vừa chọn (TUYỆT ĐỐI KHÔNG nhắc lại gói giá cao đã từ chối) và hỏi nhẹ xem ")
              .append(honorific).append(" dự kiến ghé bên ").append(selfPronoun).append(" vào khung giờ nào hôm nay hay ngày mai kèm xin Số điện thoại để lễ tân giữ chỗ chu đáo cho ").append(honorific).append(" nhé ạ.\n");
        }
        sb.append("</GROUNDED_TURN_FACTS>\n");

        sb.append("- Trả về duy nhất 1 JSON hợp lệ trên 1 dòng theo mẫu (KHÔNG giải thích thêm):\n");
        String bubblesJsonExample;
        if (isSplitSecondBubbleOnly) {
            bubblesJsonExample = "[{\"text\": \"...\", \"typing_delay_ms\": " + dbGroundedReferenceBubbles.get(0).typingDelayMs() + "}]";
        } else if (hasConfiguredOpeningBubbles) {
            bubblesJsonExample = "[{\"text\": \"...\", \"typing_delay_ms\": 2000}]";
        } else if ((actionDecision != null && actionDecision.maxResponseBubblesForLlm() == 1)
                || effAction == PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                || effAction == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                || effAction == PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS) {
            bubblesJsonExample = "[{\"text\": \"...\", \"typing_delay_ms\": 0}]";
        } else {
            bubblesJsonExample = "[{\"text\": \"...\", \"typing_delay_ms\": 0}, {\"text\": \"...\", \"typing_delay_ms\": " + (effAction == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE || effAction == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT ? postPriceDelayMs : 1200) + "}]";
        }
        if (isOpenAiCompatibleProvider && effectiveThinkingMode != ThinkingMode.FAST_NO_THINK) {
            sb.append("  {\"reasoning_brief\": \"1 câu tóm tắt ngắn\", \"reply_bubbles\": ")
              .append(bubblesJsonExample)
              .append(", \"should_handoff\": false, \"handoff_reason\": null, \"state_updates\": {\"sales_stage\": \"")
              .append(salesStage != null ? salesStage.code() : "DISCOVERY").append("\"}}\n");
        } else {
            sb.append("  {\"reply_bubbles\": ")
              .append(bubblesJsonExample)
              .append(", \"should_handoff\": false}\n");
        }
        sb.append("</CONVERSATION_STATE_AND_ACTION_CONTRACT>\n\n");
        String safeRawCust = PromptSecurityGuard.sanitizeUntrustedContent(customerMessage != null ? customerMessage : "");
        String normCust = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(safeRawCust);
        if (!normCust.isBlank() && !normCust.equalsIgnoreCase(safeRawCust.strip())) {
            sb.append("Khách hàng: ").append(safeRawCust).append(" (ý nghĩa chuẩn hóa: ").append(normCust).append(")\n");
        } else {
            sb.append("Khách hàng: ").append(safeRawCust).append('\n');
        }
        sb.append("Tin nhắn phản hồi khách hàng:");
        return sb.toString();
    }

    private static void appendVerifiedPackageBenefitsAndForbiddenPerks(
            StringBuilder sb,
            ConversationState convState,
            PrimaryActionRouter.CatalogOption opt,
            long targetPriceVnd
    ) {
        StringBuilder verified = new StringBuilder();
        if (opt != null && opt.benefitSummary() != null && !opt.benefitSummary().isBlank()) {
            verified.append(opt.benefitSummary().strip());
        }
        long effectivePrice = targetPriceVnd > 0 ? targetPriceVnd : (opt != null ? opt.priceVnd() : 0L);
        if (convState != null && convState.availablePackageCatalog() != null && effectivePrice > 0) {
            for (PackageCatalogService.PackageSummary pkg : convState.availablePackageCatalog()) {
                if (pkg != null && pkg.priceVnd() == effectivePrice) {
                    if (pkg.includesSummary() != null && !pkg.includesSummary().isBlank()) {
                        if (!verified.isEmpty()) verified.append(", ");
                        verified.append(pkg.includesSummary().strip());
                    }
                    if (pkg.roomType() != null && !pkg.roomType().isBlank()) {
                        if (!verified.isEmpty()) verified.append(", ");
                        verified.append(pkg.roomType().strip());
                    }
                    break;
                }
            }
        }
        String verifiedStr = verified.toString()
                .replaceAll("\\s*\\+\\s*", ", ")
                .replaceAll("\\s*&\\s*", " và ")
                .replaceAll("…", "")
                .strip();
        if (!verifiedStr.isBlank()) {
            sb.append("- verified_package_benefits: ").append(verifiedStr).append(", trọn gói không nhận Tip\n");
        }
        String lower = verified.toString().toLowerCase(Locale.ROOT);
        List<String> forbidden = new ArrayList<>();
        if (!lower.contains("xông")) {
            forbidden.add("xông hơi / xông thảo dược / tắm xông thảo dược");
        }
        if (!lower.contains("ngâm bồn")) {
            forbidden.add("ngâm bồn thảo dược");
        }
        if (!lower.contains("full body") && !lower.contains("toàn thân")) {
            forbidden.add("massage toàn thân (full body)");
        }
        if (!lower.contains("thực dưỡng")) {
            forbidden.add("buffet thực dưỡng");
        }
        if ((effectivePrice > 0 && effectivePrice < 580_000L) || lower.contains("phòng chung") || lower.contains("khu dưỡng sinh") || !lower.contains("vip")) {
            forbidden.add("Phòng VIP riêng");
        }
        if (!forbidden.isEmpty()) {
            sb.append("- forbidden_unverified_tier_perks: Gói này KHÔNG bao gồm [")
              .append(String.join(", ", forbidden))
              .append("] -> TUYỆT ĐỐI CẤM nêu các quyền lợi này cho gói đang tư vấn!\n");
        }
    }
}
