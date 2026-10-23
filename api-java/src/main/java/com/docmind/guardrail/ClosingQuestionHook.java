package com.docmind.guardrail;

import com.example.docmind.service.bot.FanpageBotPromptBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-Loop Deterministic Guardrail Hook: Closing Question Control.
 * <p>
 * Khống chế chất lượng câu hỏi kết thúc cuộc hội thoại:
 * 1. Triệt tiêu các câu hỏi xin phép khô khan, bị động kiểu Yes/No ("...được không ạ?", "...có tiện không?", "Em có thể tư vấn thêm không?").
 * 2. Loại bỏ câu hỏi dồn dập trong đoạn cuối nhưng luôn bảo đảm giữ lại câu hỏi chốt A/B (CTA) mang tính chuyển đổi cao.
 * 3. Chống lặp lại câu hỏi khảo sát của lượt 1 sang các lượt tiếp theo bằng cơ chế luân chuyển pool câu hỏi theo lịch sử.
 * 4. Tôn trọng trạng thái đặt lịch lũy kế (ConversationBookingState):
 *    - Khi khách đã chốt giờ hẹn (VD: 12h30, tầm trưa): tuyệt đối KHÔNG hỏi lại "Sáng hay Chiều", "ngày thường hay cuối tuần", "xin khung giờ tiện nhất".
 *    - Khi khách đã hoàn tất đặt lịch (đã có SĐT + giờ hẹn, hoặc khách dặn "tự sắp xếp / tới giờ tự tới", hoặc khách phàn nàn hỏi thừa):
 *      xóa bỏ câu hỏi thừa ở cuối và kết thúc bằng lời xác nhận lịch sự, KHÔNG ép chèn câu hỏi chốt mới.
 */
public class ClosingQuestionHook implements GuardrailHook {

    public static final String HOOK_CODE = "HOOK_CLOSING_QUESTION_CONTROL";
    public static final String HOOK_CODE_SHORT = "HOOK_CLOSING_QUESTION";
    public static final String CODE = HOOK_CODE;

    /**
     * Pattern bắt 1 câu hỏi xin phép khô khan/thụ động.
     * Lưu ý: KHÔNG dùng bộ lặp ngoài '(?: ... )+' để tránh StackOverflowError do đệ quy regex trong JVM.
     * Sử dụng non-capturing group đơn lẻ và bounded lookahead (?![^?]*\bhay\b).
     * Bổ sung negative lookahead loại trừ các thuật ngữ khám/chẩn đoán y khoa (kem chống nắng, dị ứng, mụn...).
     */
    private static final Pattern DRY_PERMISSION_QUESTION_PATTERN = Pattern.compile(
            "(?iU)(?<=[.!?\\n]|^)\\s*(?:(?:dạ|hay\\s+là|hay|hoặc\\s+là|hoặc|vậy|thế|a\\s*/\\s*b\\s*[:.\\-]?)[,\\s]+)*(?:"
            + "em\\s+có\\s+thể\\s+(?:tư\\s+vấn|giải\\s+thích|hỗ\\s+trợ|chia\\s+sẻ)[^?]*\\?|"
            + "(?:chị|anh|anh/chị|bạn|mình)\\s+(?:có\\s+)?(?:muốn|cần)\\s+(?:(?:bên\\s+)?em\\s+)?(?:tư\\s+vấn|giải\\s+thích|chia\\s+sẻ|gửi|hỗ\\s+trợ|đặt\\s+lịch|giữ\\s+lịch|thử|trải\\s+nghiệm|tham\\s+khảo)(?![^?]*\\bhay\\b)[^?]*\\?|"
            + "(?:chị|anh|anh/chị|bạn|mình)\\s+(?:có\\s+)?(?:thắc\\s+mắc|câu\\s+hỏi)[^?]*\\?|"
            + "(?:chị|anh|anh/chị|bạn|mình)\\s+(?:có\\s+)?(?:tiện|sẵn\\s+sàng)[^?]*\\?|"
            + "(?:được\\s+không\\s+ạ|có\\s+được\\s+không\\s+ạ|có\\s+tiện\\s+không\\s+ạ)\\s*\\?|"
            + "(?:có\\s+)?không\\s+ạ\\s*\\?"
            + ")"
    );

    private static final Pattern REPEATED_TURN1_QUESTION_PATTERN = Pattern.compile(
            "(?isU)(?:\\n\\s*\\n|\\s+)(?:hiện\\s+tại\\s+)?da\\s+(?:của\\s+)?(?:anh|chị|bạn|mình)?\\s*(?:đang\\s*)?cần\\s+ưu\\s+tiên\\s+cải\\s+thiện[^?]*\\?\\s*$"
    );

    /**
     * Pattern bắt câu hỏi/câu kêu gọi chọn lại khung giờ thừa thãi khi khách ĐÃ chốt khung giờ cụ thể.
     */
    private static final Pattern REDUNDANT_TIME_QUESTION_PATTERN = Pattern.compile(
            "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:"
            + "sáng\\s+hay\\s+chiều|"
            + "ngày\\s+thường\\s+hay\\s+cuối\\s+tuần|"
            + "xin\\s+khung\\s+giờ\\s+tiện\\s+nhất|"
            + "muốn\\s+đổi\\s+sang\\s+khung|"
            + "khung\\s+giờ\\s+nào\\s+tiện|"
            + "ghé\\s+vào\\s+khung\\s+giờ\\s+nào"
            + ")[^.!?]*(?:\\?|!|\\.)\\s*$"
    );

    /**
     * Pattern bắt mọi câu hỏi hậu đặt lịch thừa thãi khi khách đã chốt xong lịch hoặc yêu cầu tự sắp xếp / không cần gọi.
     */
    private static final Pattern POST_BOOKING_TRAILING_QUESTION_PATTERN = Pattern.compile(
            "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:"
            + "sáng\\s+hay\\s+chiều|"
            + "ngày\\s+thường\\s+hay\\s+cuối\\s+tuần|"
            + "xin\\s+khung\\s+giờ|"
            + "đặt\\s+lịch\\s+qua\\s+soi\\s+da|"
            + "giữ\\s+lịch\\s+trước|"
            + "nhận\\s+cuộc\\s+gọi\\s+xác\\s+nhận|"
            + "muốn\\s+đổi\\s+sang\\s+khung|"
            + "tư\\s+vấn\\s+kỹ\\s+hơn\\s+về\\s+lộ\\s+trình|"
            + "trải\\s+nghiệm\\s+trước\\s+1\\s+buổi"
            + ")[^.!?]*(?:\\?|!|\\.)\\s*$"
    );

    private static final Pattern NORM_PRONOUNS_PATTERN = Pattern.compile("(?iU)\\b(anh|chị|bạn|mình|em)\\b");
    private static final Pattern NORM_NON_WORD_PATTERN = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern NORM_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Override
    public String getHookCode() {
        return HOOK_CODE;
    }

    @Override
    public boolean isApplicable(GuardrailContext context, GuardrailDraft draft) {
        if (draft == null || draft.getText() == null || draft.getText().isBlank()) {
            return false;
        }
        if (context == null) {
            return false;
        }
        String stage = context.getSalesStage();
        if (stage == null) {
            return true;
        }
        String upperStage = stage.toUpperCase(Locale.ROOT);
        return !upperStage.equals("HUMAN_HANDOFF")
                && !upperStage.equals("BOOKING_LEAD_CAPTURED")
                && !upperStage.equals("SECURITY_BLOCK")
                && !upperStage.equals("COMPLAINT_OR_DISTRESS")
                && !upperStage.equals("EXPLICIT_HUMAN_REQUEST")
                && !upperStage.equals("COMPLEX_NEGOTIATION")
                && !upperStage.equals("UNGROUNDED_OR_UNKNOWN");
    }

    @Override
    public boolean apply(GuardrailContext context, GuardrailDraft draft) {
        String text = draft.getText();
        if (text == null || text.isBlank()) {
            return false;
        }

        String honorific = context.getCustomerHonorific();
        String salesStage = context.getSalesStage();
        boolean isFirstTurn = context.isFirstTurn();
        List<String> history = context.getConversationHistory();
        String customerMessage = context.getCustomerMessage();
        String combinedChunksLower = context.getKnowledgeChunks() != null
                ? String.join(" ", context.getKnowledgeChunks()).toLowerCase(Locale.ROOT)
                : "";
        String lowerDraft = text.toLowerCase(Locale.ROOT);
        String lowerCustomerMsg = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        boolean isDermatologyDoc = combinedChunksLower.contains("soi da")
                || combinedChunksLower.contains("nám")
                || combinedChunksLower.contains("mụn")
                || (combinedChunksLower.isEmpty() && (
                        lowerDraft.contains("soi da") || lowerDraft.contains("nám") || lowerDraft.contains("mụn")
                        || lowerDraft.contains("liệu trình trị") || lowerCustomerMsg.contains("da ")
                        || lowerCustomerMsg.contains("mụn") || lowerCustomerMsg.contains("nám")));
        boolean isMassageDoc = !isDermatologyDoc && (
                combinedChunksLower.contains("dưỡng sinh")
                || combinedChunksLower.contains("gội đầu")
                || combinedChunksLower.contains("massage")
                || combinedChunksLower.contains("ngâm chân")
                || lowerDraft.contains("massage")
                || lowerDraft.contains("vai gáy")
        );

        String polished = polishClosingQuestions(text, honorific, salesStage, isFirstTurn, history, customerMessage, isDermatologyDoc, isMassageDoc);

        if (!polished.equals(draft.getText())) {
            draft.setText(polished);
            draft.setBubbles(null); // Reset bubbles để BubbleCapHook format lại chuẩn
            draft.addTriggeredHook(HOOK_CODE);
            return true;
        }

        return false;
    }

    private String polishClosingQuestions(
            String text,
            String honorific,
            String salesStage,
            boolean isFirstTurn,
            List<String> history,
            String customerMessage,
            boolean isDermatologyDoc,
            boolean isMassageDoc
    ) {
        String lowerText = text != null ? text.toLowerCase(Locale.ROOT) : "";
        if ("HUMAN_HANDOFF".equalsIgnoreCase(salesStage)
                || "BOOKING_LEAD_CAPTURED".equalsIgnoreCase(salesStage)
                || (salesStage != null && salesStage.toUpperCase(Locale.ROOT).contains("HANDOFF"))
                || (lowerText.contains("chuyên viên") && (
                        lowerText.contains("liên hệ")
                        || lowerText.contains("kết nối")
                        || lowerText.contains("hỗ trợ")
                        || lowerText.contains("kiểm tra")
                        || lowerText.contains("phụ trách")
                        || lowerText.contains("nhắn phản hồi")))
                || lowerText.contains("chuyển người hỗ trợ")
                || lowerText.contains("quản lý")
                || lowerText.contains("kỹ thuật viên trưởng")
                || lowerText.contains("tư vấn viên")
                || lowerText.contains("nhắn em tư vấn nha")
                || lowerText.contains("em hiểu mình đang cân nhắc về chi phí")
                || lowerText.contains("vừa ngân sách hơn")
                || lowerText.contains("cần hỗ trợ về dịch vụ spa hay đặt lịch")) {
            return text;
        }

        FanpageBotPromptBuilder.ConversationBookingState bookingState =
                FanpageBotPromptBuilder.analyzeConversationBookingStateFromStrings(customerMessage, history);

        String lowerCustomer = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        boolean isExplicitHesitation = lowerCustomer.matches(
                "(?isU).*(?:để\\s+(?:chị\\s+|anh\\s+|mình\\s+|em\\s+)?suy\\s+nghĩ|suy\\s+nghĩ\\s+(?:đã|thêm)|cân\\s+nhắc\\s+(?:đã|thêm)|để\\s+xem\\s+đã|chưa\\s+quyết\\s+định).*"
        );
        if (isExplicitHesitation) {
            String strippedQ = text.replaceAll("(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)[^.!?\\n]*\\?\\s*$", "").strip();
            if (strippedQ.endsWith("?")) {
                return text;
            }
            String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
            if (!strippedQ.toLowerCase(Locale.ROOT).contains("thong thả cân nhắc")) {
                return (strippedQ.isEmpty() ? "" : strippedQ + "\n\n")
                        + "Dạ vâng " + h + " cứ thong thả cân nhắc thêm nhé ạ! Bất cứ khi nào vùng cổ vai gáy mỏi nhức cần thả lỏng bằng thảo dược ấm, "
                        + h + " cứ nhắn em hỗ trợ giữ lịch ưu tiên ngay nha ạ.";
            }
            return strippedQ;
        }

        String working = text;
        boolean changed = false;

        // A. Nếu khách đang phàn nàn hỏi lặp/vô lý hoặc đã hoàn tất đặt lịch (Post-Booking Wrap-Up):
        // Tuyệt đối xóa bỏ các câu hỏi thừa ở cuối và KHÔNG tự động chèn thêm câu hỏi mới!
        if (bookingState.isRedundantQuestionComplaint() || bookingState.isPostBookingWrapUp()) {
            Matcher postQ = POST_BOOKING_TRAILING_QUESTION_PATTERN.matcher(working);
            if (postQ.find()) {
                working = working.substring(0, postQ.start()).strip();
                changed = true;
            }
            Matcher redTimeQ = REDUNDANT_TIME_QUESTION_PATTERN.matcher(working);
            if (redTimeQ.find()) {
                working = working.substring(0, redTimeQ.start()).strip();
                changed = true;
            }
            // Nếu đoạn cuối vẫn còn câu hỏi thừa kiểu "?": cắt câu hỏi cuối nếu trước đó đã có câu xác nhận
            int lastQ = working.lastIndexOf('?');
            if (lastQ >= 0) {
                int prevSentEnd = Math.max(working.lastIndexOf(". ", lastQ),
                        Math.max(working.lastIndexOf("! ", lastQ), working.lastIndexOf("\n", lastQ)));
                if (prevSentEnd > 10) {
                    working = working.substring(0, prevSentEnd + (working.charAt(prevSentEnd) == '\n' ? 0 : 1)).strip();
                    changed = true;
                }
            }
            if (working.isBlank()) {
                String slotLabel = bookingState.hasConfirmedTimeSlot() ? bookingState.confirmedTimeSlot() : "12h30 trưa";
                String dateLabel = bookingState.confirmedDate() != null ? " " + bookingState.confirmedDate() : "";
                working = isDermatologyDoc
                        ? "Dạ em đã khóa cố định lịch hẹn lúc " + slotLabel + dateLabel
                          + " cùng Bác sĩ Da liễu và phòng riêng kín đáo cho " + honorific
                          + " rồi ạ, tới giờ " + honorific + " cứ ghé thẳng quầy lễ tân nhé ạ."
                        : "Dạ em đã khóa cố định lịch hẹn lúc " + slotLabel + dateLabel
                          + " cho " + honorific
                          + " rồi ạ, tới giờ " + honorific + " cứ ghé thẳng quầy lễ tân nhé ạ.";
                changed = true;
            }
            return changed ? working : text;
        }

        // B. Nếu khách ĐÃ chốt khung giờ cụ thể (VD: "Tầm trưa", "12h30") nhưng chưa hoàn tất SĐT:
        // Không bao giờ được hỏi lại "Sáng hay Chiều", "ngày thường hay cuối tuần", "xin khung giờ tiện nhất"
        String naturalClosing;
        if (bookingState.hasConfirmedTimeSlot()) {
            Matcher redTimeQ = REDUNDANT_TIME_QUESTION_PATTERN.matcher(working);
            if (redTimeQ.find()) {
                working = working.substring(0, redTimeQ.start()).strip();
                changed = true;
            }
            String slotLabel = bookingState.confirmedTimeSlot();
            String dateSuffix = bookingState.confirmedDate() != null ? " (" + bookingState.confirmedDate() + ")" : "";
            if (!bookingState.hasCapturedPhone()) {
                naturalClosing = isDermatologyDoc
                        ? honorific + " cho em xin Số điện thoại để bên em khóa lịch hẹn lúc "
                          + slotLabel + dateSuffix + " và giữ phòng riêng kín đáo cùng Bác sĩ cho mình nhé ạ?"
                        : honorific + " cho em xin Số điện thoại và cơ sở muốn ghé để bên em khóa lịch hẹn lúc "
                          + slotLabel + dateSuffix + " cho mình nhé ạ?";
            } else {
                // Đã có cả giờ hẹn và SĐT -> không hỏi thêm câu hỏi chọn giờ nữa
                return changed ? working : text;
            }
        } else {
            naturalClosing = selectNaturalClosingQuestion(honorific, salesStage, isFirstTurn, history, customerMessage, isDermatologyDoc, isMassageDoc);
        }

        // 0. Nếu câu hỏi cuối dùng cụm từ yếu/lạc ngữ cảnh 'đang tò mò hơn về...' khi khách đang nêu triệu chứng -> thay bằng câu chốt lịch khám
        if (working.toLowerCase(Locale.ROOT).contains("tò mò hơn về")) {
            String symptomClosing = bookingState.hasConfirmedTimeSlot()
                    ? naturalClosing
                    : "Để tránh ổ viêm lan rộng và vết thâm bám sâu khó phục hồi, " + honorific
                      + " tiện ghé Bác sĩ bên em soi da AI 3D kiểm tra trực tiếp (miễn phí 100%) vào khung giờ Sáng hay Chiều để em giữ lịch ưu tiên cho mình ạ?";
            working = working.replaceAll("(?isU)(?:\\n\\s*\\n|\\s+)(?:dạ\\s+)?(?:anh|chị|bạn|mình)?\\s*đang\\s+tò\\s+mò\\s+hơn\\s+về[^?]*\\?\\s*$", "\n\n" + symptomClosing);
            changed = true;
        }

        // 1. Nếu lượt 2+ mà lặp lại câu hỏi khảo sát da lượt 1 -> thay thế
        if (!isFirstTurn) {
            Matcher repMatcher = REPEATED_TURN1_QUESTION_PATTERN.matcher(working);
            if (repMatcher.find()) {
                String prefixBefore = working.substring(0, repMatcher.start()).strip();
                String sep = repMatcher.group().contains("\n") ? "\n\n" : " ";
                working = prefixBefore + sep + naturalClosing;
                changed = true;
            }
        }

        // 2. Bắt trực diện câu hỏi xin phép máy móc hoặc câu hỏi đóng Yes/No khô khan (xử lý tuyến tính không đệ quy)
        if (working.indexOf('?') >= 0) {
            String lower = working.toLowerCase(Locale.ROOT);
            if (lower.contains("không") || lower.contains("tiện") || lower.contains("thắc mắc")
                    || lower.contains("muốn") || lower.contains("cần") || lower.contains("thể")) {
                Matcher dryMatcher = DRY_PERMISSION_QUESTION_PATTERN.matcher(working);
                if (dryMatcher.find()) {
                    String matchedGroup = dryMatcher.group();
                    String withoutDry = dryMatcher.replaceAll("").strip();
                    if (withoutDry.endsWith("?")) {
                        working = withoutDry;
                    } else if (withoutDry.isEmpty()) {
                        working = "Dạ " + naturalClosing;
                    } else {
                        if (!withoutDry.endsWith(".") && !withoutDry.endsWith("!") && !withoutDry.endsWith("~")) {
                            withoutDry = withoutDry + ".";
                        }
                        String sep = matchedGroup.contains("\n") || withoutDry.endsWith(".") || withoutDry.endsWith("!") ? "\n\n" : " ";
                        working = withoutDry + sep + naturalClosing;
                    }
                    changed = true;
                }
            }
        }

        // 3. Nếu đoạn văn cuối cùng có từ 2 câu hỏi liên tiếp (dồn dập), ưu tiên giữ lại câu hỏi chốt A/B (CTA)
        // thay vì cắt bỏ làm mất lời kêu gọi hành động. Nếu không có câu A/B, giữ lại câu hỏi đầu tiên.
        int lastParaBreak = working.lastIndexOf("\n\n");
        String prefix = lastParaBreak >= 0 ? working.substring(0, lastParaBreak + 2) : "";
        String lastPara = lastParaBreak >= 0 ? working.substring(lastParaBreak + 2) : working;

        int firstQ = lastPara.indexOf('?');
        int lastQ = lastPara.lastIndexOf('?');
        if (firstQ >= 0 && lastQ > firstQ) {
            // Có từ 2 câu hỏi trở lên trong đoạn cuối
            List<String> questions = new ArrayList<>();
            int start = 0;
            int qIdx;
            while ((qIdx = lastPara.indexOf('?', start)) >= 0) {
                questions.add(lastPara.substring(start, qIdx + 1).strip());
                start = qIdx + 1;
            }

            // Ưu tiên câu hỏi có lựa chọn A/B ('hay' / 'hoặc'), nếu không có thì giữ câu hỏi đầu tiên
            String selectedQuestion = null;
            for (int i = questions.size() - 1; i >= 0; i--) {
                if (containsAbChoice(questions.get(i))) {
                    selectedQuestion = questions.get(i);
                    break;
                }
            }
            if (selectedQuestion == null) {
                selectedQuestion = questions.get(0);
            }

            // Tìm câu trần thuật / thông tin mở đầu nếu có trước câu hỏi đầu tiên
            int dotIdx = lastPara.lastIndexOf(". ", firstQ);
            int exclIdx = lastPara.lastIndexOf("! ", firstQ);
            int newlineIdx = lastPara.lastIndexOf("\n", firstQ);
            int statementEnd = Math.max(Math.max(dotIdx, exclIdx), newlineIdx);

            if (statementEnd >= 0) {
                String statement = lastPara.substring(0, statementEnd + (statementEnd == newlineIdx ? 0 : 1)).strip();
                lastPara = statement.isEmpty() ? selectedQuestion : statement + "\n\n" + selectedQuestion;
            } else {
                lastPara = selectedQuestion;
            }

            working = prefix + lastPara;
            changed = true;
        }

        // 4. Nếu câu cuối bị cắt cụt giữa chừng (do giới hạn max_tokens/num_predict, không kết thúc bằng ., !, ?, ~, ạ)
        // thì gọt về câu hoàn chỉnh gần nhất trước khi gắn câu hỏi kết bài.
        working = trimIncompleteTrailingFragment(working);

        // 5. Nếu kết bài hoàn toàn không có câu hỏi nào (thiếu closing question), bổ sung câu hỏi mở phù hợp
        if (!working.endsWith("?") && !working.endsWith("ạ?")) {
            working = working.strip() + "\n\n" + naturalClosing;
            changed = true;
        }

        return changed ? working : text;
    }

    private static String trimIncompleteTrailingFragment(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String trimmed = text.strip();
        if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") || trimmed.endsWith("~")) {
            return trimmed;
        }
        // Nếu kết thúc bằng "ạ" hoặc "nhé" hoặc "nha" nhưng không phải dòng bullet dở dang, thêm dấu chấm câu tự nhiên
        String lastLine = trimmed.contains("\n") ? trimmed.substring(trimmed.lastIndexOf('\n') + 1).strip() : trimmed;
        if (!lastLine.startsWith("-") && !lastLine.startsWith("•") && !lastLine.endsWith(":")
                && lastLine.matches("(?isU).*\\b(ạ|nhé|nha|đó|đấy)\\s*$")) {
            return trimmed + ".";
        }
        int lastSentenceEnd = -1;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                // Tránh cắt nhầm dấu chấm trong số tiền (VD: 399.000)
                boolean isThousandSep = (i > 0 && i + 1 < trimmed.length()
                        && Character.isDigit(trimmed.charAt(i - 1))
                        && Character.isDigit(trimmed.charAt(i + 1)));
                if (!isThousandSep) {
                    lastSentenceEnd = i;
                    break;
                }
            }
        }
        if (lastSentenceEnd >= 25) {
            String candidate = trimmed.substring(0, lastSentenceEnd + 1).strip();
            // Nếu câu trước dấu chấm cuối cùng lại là câu dẫn vào danh sách kiểu "Gói 670k sẽ bao gồm:" đã bị cắt mất phần sau,
            // hoặc nếu sau lastSentenceEnd có câu dẫn kết thúc bằng dấu hai chấm ":"
            return candidate;
        }
        return trimmed;
    }

    private static boolean containsAbChoice(String q) {
        if (q == null) return false;
        String l = q.toLowerCase(Locale.ROOT);
        return containsWord(l, "hay") || containsWord(l, "hoặc");
    }

    private static boolean containsWord(String s, String word) {
        int idx = 0;
        int wlen = word.length();
        while ((idx = s.indexOf(word, idx)) >= 0) {
            boolean startOk = (idx == 0) || !Character.isLetterOrDigit(s.charAt(idx - 1));
            boolean endOk = (idx + wlen == s.length()) || !Character.isLetterOrDigit(s.charAt(idx + wlen));
            if (startOk && endOk) {
                return true;
            }
            idx += wlen;
        }
        return false;
    }

    private String selectNaturalClosingQuestion(
            String honorific,
            String salesStage,
            boolean isFirstTurn,
            List<String> history,
            String customerMessage,
            boolean isDermatologyDoc,
            boolean isMassageDoc
    ) {
        List<String> pool;
        boolean isExplanationOrSymptom = FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(customerMessage)
                || FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(customerMessage);
        FanpageBotPromptBuilder.SymptomSeverity curSev =
                FanpageBotPromptBuilder.detectSymptomSeverity(customerMessage);
        boolean hasStatedFrequency = curSev == FanpageBotPromptBuilder.SymptomSeverity.MILD_OR_OCCASIONAL
                || curSev == FanpageBotPromptBuilder.SymptomSeverity.CHRONIC_OR_DEEP;
        String lowerMsg = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        boolean hasStatedBodyArea = lowerMsg.matches("(?isU).*\\b(vai|cổ|gáy|lưng|đầu|chân|tay|toàn\\s+thân|body)\\b.*");
        if (isMassageDoc) {
            if (isExplanationOrSymptom) {
                if (hasStatedFrequency && hasStatedBodyArea) {
                    pool = List.of(
                            honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?",
                            honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"
                    );
                } else if (hasStatedFrequency) {
                    pool = List.of(
                            honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?",
                            honorific + " đang ưu tiên chăm sóc vùng cổ vai gáy hay thư giãn toàn thân ạ?",
                            honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"
                    );
                } else if (hasStatedBodyArea) {
                    pool = List.of(
                            "Tình trạng này của " + honorific + " là mới xuất hiện hay bị thường xuyên để em chọn gói sát nhất ạ?",
                            honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?",
                            honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"
                    );
                } else {
                    pool = List.of(
                            honorific + " đang muốn tập trung vùng cổ vai gáy hay thư giãn toàn thân để em gợi ý gói phù hợp ạ?",
                            "Tình trạng này của " + honorific + " là mới xuất hiện hay bị thường xuyên để em chọn gói sát nhất ạ?",
                            honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?"
                    );
                }
            } else if ("SOFT_BOOKING_CLOSE".equalsIgnoreCase(salesStage)) {
                pool = List.of(
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để em giữ phòng riêng trước cho mình ạ?",
                        honorific + " tiện ghé cơ sở nào gần mình nhất để bên em sắp xếp chu đáo ạ?",
                        honorific + " cho em xin khung giờ tiện nhất hoặc Số điện thoại để bên em giữ suất ưu đãi trước ạ?"
                );
            } else if (!isFirstTurn || "VALUE_CONSULTATION".equalsIgnoreCase(salesStage)) {
                pool = List.of(
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều, hay cần em tư vấn thêm về quy trình ạ?",
                        honorific + " đang tiện ghé cơ sở nào gần mình hơn để em kiểm tra giờ trống cho mình ạ?",
                        honorific + " muốn bên em giữ lịch hẹn vào hôm nay hay cuối tuần để em sắp xếp phòng ạ?"
                );
            } else {
                pool = List.of(
                        "Hiện tại " + honorific + " đang quan tâm Massage trị liệu cổ vai gáy hay Gội đầu dưỡng sinh ạ?",
                        "Hôm nay " + honorific + " đang muốn giảm nhức mỏi vùng nào nhất để em gợi ý gói phù hợp ạ?"
                );
            }
        } else if (!isDermatologyDoc) {
            // Domain-agnostic closing questions applicable to ANY industry (Fashion, Education, Dental, SaaS, Real Estate, etc.)
            if ("SOFT_BOOKING_CLOSE".equalsIgnoreCase(salesStage)) {
                pool = List.of(
                        honorific + " muốn đăng ký/đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ suất ưu tiên ạ?",
                        honorific + " cho em xin Số điện thoại hoặc khung giờ thuận tiện để bên em xác nhận ngay nhé ạ?"
                );
            } else if (!isFirstTurn || "VALUE_CONSULTATION".equalsIgnoreCase(salesStage)) {
                pool = List.of(
                        honorific + " đang nghiêng về gói cơ bản để trải nghiệm trước hay gói chuyên sâu ạ?",
                        honorific + " muốn bên em giữ suất ưu đãi trước hay cần em gửi thêm thông tin phần nào nữa ạ?"
                );
            } else {
                pool = List.of(
                        "Hiện tại " + honorific + " đang quan tâm gói cơ bản hay gói chuyên sâu để em tư vấn phù hợp ạ?",
                        "Hiện tại " + honorific + " đang ưu tiên nhu cầu nào nhất để em gợi ý đúng gói phù hợp ạ?"
                );
            }
        } else if ("SOFT_BOOKING_CLOSE".equalsIgnoreCase(salesStage)) {
            pool = List.of(
                    honorific + " muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?",
                    honorific + " tiện ghé cơ sở bên em vào ngày thường hay cuối tuần để bên em sắp xếp Bác sĩ tiếp đón chu đáo nhất ạ?",
                    honorific + " cho em xin khung giờ tiện nhất hoặc Số điện thoại để bên em giữ suất ưu đãi và sắp xếp phòng riêng trước cho mình ạ?"
            );
        } else if (!isFirstTurn || "VALUE_CONSULTATION".equalsIgnoreCase(salesStage)) {
            pool = List.of(
                    "Với tình trạng da hiện tại, " + honorific + " muốn bên em đặt lịch soi da AI 3D miễn phí cùng Bác sĩ Da liễu trước hay cần em tư vấn kỹ hơn về lộ trình các buổi ạ?",
                    "Không biết " + honorific + " muốn trải nghiệm trước 1 buổi để cảm nhận hiệu quả hay muốn tham khảo theo Combo để nhận ưu đãi tốt nhất ạ?",
                    honorific + " muốn bên em giữ suất soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để em lên lịch cho mình ạ?"
            );
        } else {
            pool = List.of(
                    "Hiện tại da của " + honorific + " đang cần ưu tiên cải thiện vấn đề nào nhất để em gợi ý liệu trình êm và hợp nhất cho mình ạ?",
                    "Không biết hiện tại " + honorific + " đang quan tâm chăm sóc da định kỳ hay đang cần xử lý tình trạng mụn/nám cụ thể nào ạ?"
            );
        }

        if (history == null || history.isEmpty()) {
            return pool.get(0);
        }

        String joinedHistory = String.join(" ", history).toLowerCase(Locale.ROOT);
        for (String candidate : pool) {
            String lowerCand = candidate.toLowerCase(Locale.ROOT);
            String key;
            if (lowerCand.contains("ưu tiên cải thiện")) key = "ưu tiên cải thiện";
            else if (lowerCand.contains("định kỳ")) key = "định kỳ";
            else if (lowerCand.contains("ngày thường")) key = "ngày thường";
            else if (lowerCand.contains("phòng riêng")) key = "phòng riêng";
            else if (lowerCand.contains("combo")) key = "combo";
            else if (lowerCand.contains("lộ trình các buổi")) key = "lộ trình các buổi";
            else if (lowerCand.contains("khung giờ")) key = "khung giờ";
            else key = lowerCand.substring(0, Math.min(20, lowerCand.length()));

            if (!joinedHistory.contains(key)) {
                return candidate;
            }
        }

        return pool.get(0);
    }
}
