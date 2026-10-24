package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.bot.ConversationState;
import com.example.docmind.service.bot.PrimaryActionRouter;
import com.example.docmind.service.bot.PrimaryActionRouter.PrimaryAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bộ kiểm duyệt hợp đồng đầu ra 20 luật cứng (Domain-Agnostic Reply Contract Validator v3.1.4).
 * <p>
 * Bao gồm 12 quy tắc nền tảng v3.1 và 8 quy tắc mở rộng v3.1.4:
 * <ul>
 *   <li>{@code NO_PRESSURE_BEFORE_CONDITION}: Cấm hỏi lực nhẹ/vừa khi chưa làm rõ tình trạng.</li>
 *   <li>{@code NO_BROAD_MENU_AFTER_SERVICE_KNOWN}: Cấm hỏi lại menu nhóm dịch vụ khi đã biết nhóm dịch vụ.</li>
 *   <li>{@code NO_REPEAT_QUESTION_INTENT}: Cấm lặp lại cùng một question_intent quá 1 lần.</li>
 *   <li>{@code FRUSTRATION_NO_REPEAT_CLARIFY}: Khi khách bức xúc, cấm hỏi lại câu hỏi làm rõ cũ.</li>
 *   <li>{@code PRICE_AFTER_ONE_CLARIFY_PER_INTENT}: Sau khi đã hỏi loại dịch vụ và thời lượng tối đa 1 lần, không hỏi vòng thêm.</li>
 *   <li>{@code RECOMMEND_WHEN_CONDITION_READY}: Khi đã hỏi/làm rõ tình trạng, bắt buộc tiến tới gợi ý gói dịch vụ.</li>
 *   <li>{@code NO_NUMERIC_PRICE_REPEAT_AFTER_DISCUSSED}: Cấm lặp lại con số giá trong GENERIC_SHORT_DISCOVERY sau khi đã báo giá.</li>
 *   <li>{@code POST_PRICE_DELAY_ENFORCE}: Bong bóng ngay sau bong bóng chứa giá phải có delay &gt;= 10.000ms.</li>
 * </ul>
 */
public final class ReplyContractValidator {

    private ReplyContractValidator() {
    }

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?iU)\\b\\d{2,3}(?:\\.\\d{3})*\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b)"
    );
    private static final Pattern INTERNAL_LEAK_PATTERN = Pattern.compile(
            "(?isU).*(?:system\\s+prompt|chunk_id|document_id|<document_context>|<experience_library>|template_id).*"
    );
    private static final Pattern MEDICAL_CLAIM_PATTERN = Pattern.compile(
            "(?isU).*(?:cam\\s+kết\\s+khỏi|chữa\\s+khỏi(?:\\s+hẳn)?|điều\\s+trị\\s+dứt\\s+điểm|trị\\s+dứt\\s+điểm|khỏi\\s+bệnh\\s+100%|hết\\s+tê\\s+tay|khỏi\\s+đau\\s+đầu|điều\\s+trị\\s+bệnh|chữa\\s+thoát\\s+vị|chữa\\s+thoái\\s+hóa|cam\\s+kết\\s+hết\\s+đau|đảm\\s+bảo\\s+khỏi).*"
    );
    private static final Pattern FAKE_STATS_SOCIAL_PROOF_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:9\\d|100)\\s*%|10\\.000\\s+khách|hàng\\s+nghìn\\s+khách|tất\\s+cả\\s+khách\\s+đều|ai\\s+cũng\\s+hài\\s+lòng|khách\\s+nào\\s+cũng\\s+thích|100%\\s+khách|chắc\\s+chắn\\s+hài\\s+lòng|cam\\s+kết\\s+hài\\s+lòng\\s+100%|đều\\s+phản\\s+hồi).*"
    );
    private static final Pattern QUALITATIVE_SOCIAL_PROOF_PATTERN = Pattern.compile(
            "(?isU).*(?:nhiều\\s+anh/chị\\s+sau\\s+buổi\\s+đầu|phản\\s+hồi\\s+khá\\s+tốt).*"
    );
    private static final Pattern FORBIDDEN_EMOJI_PATTERN = Pattern.compile(
            "[😂🤣😒🙄😤😡💸🤑]"
    );
    private static final Pattern DUPLICATE_OPENING_PREFIX = Pattern.compile(
            "(?isU)^\\s*dạ\\s+(?:em\\s+)?chào\\s+[^,.!?]+[,!.]\\s*(?:em\\s+là\\s+[^,.!?]+[,!.]\\s*)?"
    );
    private static final Pattern PRESSURE_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*(?:lực\\s+nhẹ\\s+hay\\s+lực\\s+vừa|lực\\s+nhẹ\\s+thư\\s+giãn\\s+hay\\s+lực\\s+vừa|thích\\s+lực\\s+nhẹ\\s+hay).*"
    );
    private static final Pattern BROAD_SERVICE_MENU_PATTERN = Pattern.compile(
            "(?isU).*(?:đang\\s+quan\\s+tâm|muốn\\s+làm|muốn\\s+chọn)\\s+[^?]+,\\s*[^?]+\\s+hay\\s+[^?]+\\?(?:\\s|$)"
    );
    private static final Pattern CONDITION_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*(?:bị\\s+lâu\\s+chưa|mới\\s+gần\\s+đây.*ngồi\\s+lâu).*"
    );

    public record ValidationResult(
            List<String> errors,
            List<ReplyBubble> validatedBubbles,
            String combinedText
    ) {
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }

    public static ValidationResult validateAndEnforce(
            List<ReplyBubble> bubbles,
            ConversationState state,
            String customerMessage,
            PrimaryAction effectiveAction,
            boolean openingRequired,
            int minPostPriceDelayMs
    ) {
        int requiredDelay = Math.max(10_000, minPostPriceDelayMs);
        List<String> errors = new ArrayList<>();
        if (bubbles == null || bubbles.isEmpty()) {
            errors.add("EMPTY_BUBBLES");
            return new ValidationResult(errors, List.of(), "");
        }

        ConversationState safeState = state != null ? state : ConversationState.initial();
        ConversationState enrichedState = safeState.enrichWithCurrentMessage(customerMessage, List.of());
        boolean isFrustratedNow = PrimaryActionRouter.isFrustrationMessage(customerMessage);

        List<ReplyBubble> working = new ArrayList<>(bubbles);

        // 1. Tối đa 3 bong bóng
        if (working.size() > 3) {
            errors.add("TOO_MANY_BUBBLES");
            working = new ArrayList<>(working.subList(0, 3));
        }

        // 2. Nếu opening_required, bong bóng thứ 3 không được tự lặp lại câu chào
        if (openingRequired && working.size() >= 3) {
            ReplyBubble b3 = working.get(2);
            if (b3 != null && b3.text() != null && DUPLICATE_OPENING_PREFIX.matcher(b3.text()).find()) {
                errors.add("LLM_MUST_NOT_GENERATE_OPENING");
                String cleanedB3 = DUPLICATE_OPENING_PREFIX.matcher(b3.text()).replaceFirst("Dạ ").strip();
                working.set(2, new ReplyBubble(cleanedB3, b3.typingDelayMs()));
            }
        }

        int nonOpeningStartIdx = openingRequired ? Math.min(2, working.size() - 1) : 0;

        for (int i = 0; i < working.size(); i++) {
            ReplyBubble b = working.get(i);
            if (b == null || b.text() == null) continue;
            String text = b.text().strip();
            String lower = text.toLowerCase(Locale.ROOT);
            boolean isOpeningBubble = openingRequired && i < nonOpeningStartIdx;

            if (!isOpeningBubble) {
                String dynamicOptionPair = resolveDynamicOptionPair(safeState);
                // v3.1.4 Check 1: NO_PRESSURE_BEFORE_CONDITION
                if (!enrichedState.conditionClarified() && PRESSURE_QUESTION_PATTERN.matcher(text).matches()) {
                    errors.add("NO_PRESSURE_BEFORE_CONDITION");
                    text = text.replaceAll(
                            "(?isU)[^.!?]*?(?:lực\\s+nhẹ\\s+hay\\s+lực\\s+vừa|lực\\s+nhẹ\\s+thư\\s+giãn\\s+hay\\s+lực\\s+vừa|thích\\s+lực\\s+nhẹ\\s+hay)[^.!?]*[?]",
                            "Mình gặp tình trạng này lâu chưa hay mới gần đây ạ?"
                    ).strip();
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.4 Check 2: NO_BROAD_MENU_AFTER_SERVICE_KNOWN
                if (enrichedState.serviceFamilyKnown() && BROAD_SERVICE_MENU_PATTERN.matcher(text).matches() && text.contains("?")) {
                    errors.add("NO_BROAD_MENU_AFTER_SERVICE_KNOWN");
                    text = "Dạ với nhóm dịch vụ này, mình muốn xem " + dynamicOptionPair.replace(" hoặc ", " hay ") + " để em báo giá cụ thể ạ?";
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.4 Check 3 & 6: NO_REPEAT_QUESTION_INTENT & RECOMMEND_WHEN_CONDITION_READY
                if ((enrichedState.conditionClarified() || safeState.conditionAskedCount() >= 1)
                        && !safeState.packageRecommended()
                        && (CONDITION_QUESTION_PATTERN.matcher(text).matches() || PRESSURE_QUESTION_PATTERN.matcher(text).matches())) {
                    errors.add("RECOMMEND_WHEN_CONDITION_READY");
                    errors.add("NO_REPEAT_QUESTION_INTENT");
                    text = "Dạ với tình trạng hiện tại của mình, em gợi ý thử " + dynamicOptionPair + " ạ. Mình muốn xem chi phí gói nào trước ạ?";
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.4 Check 4: FRUSTRATION_NO_REPEAT_CLARIFY (never default to package sales pitch on frustration)
                if (isFrustratedNow && (BROAD_SERVICE_MENU_PATTERN.matcher(text).matches()
                        || lower.contains("lực nhẹ hay lực vừa")
                        || lower.contains("vui lòng cho biết dịch vụ"))) {
                    errors.add("FRUSTRATION_NO_REPEAT_CLARIFY");
                    text = "Dạ em thành thật xin lỗi vì trả lời chưa sát ý mình ạ. Mình cho em biết phần thông tin nào đang cần giải đáp rõ hơn để em hỗ trợ ngay nhé?";
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // Exact-duplicate bubble hash guard against previous bot bubbles in the conversation
                if (safeState.lastBotBubbleHashes() != null
                        && safeState.lastBotBubbleHashes().contains(ConversationState.shortHash(text))) {
                    errors.add("REPEATED_BUBBLE_HASH");
                }

                // Mechanical benefit concatenation guard ("Gói này gồm ... rất dễ chịu ạ.")
                if (lower.matches("(?isU).*\\bgói\\s+này\\s+gồm\\b[^.!?]*\\brất\\s+(?:dễ\\s+chịu|phù\\s+hợp)\\s+ạ.*")) {
                    errors.add("MECHANICAL_BENEFIT_CONCAT");
                }

                // v3.1.4 Check 5: PRICE_AFTER_ONE_CLARIFY_PER_INTENT
                if (safeState.clarifyServiceTypeCount() >= 1 && safeState.clarifyDurationCount() >= 1
                        && effectiveAction == PrimaryAction.CLARIFY_SERVICE_DURATION) {
                    errors.add("PRICE_AFTER_ONE_CLARIFY_PER_INTENT");
                }

                // v3.1.4 Check 7: NO_NUMERIC_PRICE_REPEAT_AFTER_DISCUSSED
                if (safeState.priceDiscussed() && effectiveAction == PrimaryAction.GENERIC_SHORT_DISCOVERY
                        && PRICE_PATTERN.matcher(text).find()) {
                    errors.add("NO_NUMERIC_PRICE_REPEAT_AFTER_DISCUSSED");
                    text = PRICE_PATTERN.matcher(text).replaceAll("mức chi phí ở tin nhắn trên").replaceAll("\\(\\s*mức chi phí ở tin nhắn trên\\s*\\)", "").strip();
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.5.3 VR-004A: NO_REJECTED_PACKAGE_REPEAT (chỉ áp dụng khi đã có rejectedDurations/rejectedPackages)
                if (safeState.rejectedDurations() != null && !safeState.rejectedDurations().isEmpty()) {
                    String replacementDur = safeState.downsellDuration() != null && !safeState.downsellDuration().isBlank()
                            ? safeState.downsellDuration()
                            : (safeState.downsellPackage() != null && !safeState.downsellPackage().isBlank()
                                ? ConversationState.toDisplayPackageName(safeState.downsellPackage(), null)
                                : "gói ngắn hơn");
                    for (String rejDur : safeState.rejectedDurations()) {
                        if (rejDur != null && !rejDur.isBlank() && lower.contains(rejDur.toLowerCase(Locale.ROOT))) {
                            if (!isNamingValidDifferentCatalogPackageAtDuration(lower, rejDur, safeState)) {
                                errors.add("VR-004A_NO_REJECTED_PACKAGE_REPEAT");
                                text = text.replaceAll("(?iU)" + Pattern.quote(rejDur), replacementDur);
                                lower = text.toLowerCase(Locale.ROOT);
                            }
                        }
                    }
                }

                // v3.2.1 price_grounding_check: Mọi con số giá trong câu trả lời phải khớp gói thực tế trong availablePackageCatalog
                if (safeState.availablePackageCatalog() != null && !safeState.availablePackageCatalog().isEmpty()) {
                    java.util.regex.Matcher pm = PRICE_PATTERN.matcher(text);
                    while (pm.find()) {
                        java.math.BigDecimal quotedBd = ConversationState.parsePriceToBigDecimal(pm.group());
                        if (quotedBd != null) {
                            long quotedVnd = quotedBd.longValue();
                            boolean groundedInCatalog = safeState.availablePackageCatalog().stream()
                                    .anyMatch(pkg -> pkg != null && pkg.priceVnd() == quotedVnd);
                            if (!groundedInCatalog) {
                                errors.add("UNGROUNDED_CATALOG_PRICE");
                            }
                        }
                    }
                }

                // v3.2.1 VR-007E: Cấm câu mẫu máy móc "Nếu chưa đúng ý..."
                if (lower.contains("nếu chưa đúng ý")) {
                    errors.add("VR-007E_NO_CANNED_AMBIGUOUS_FALLBACK");
                    text = text.replaceAll("(?isU)\\s*Nếu\\s+chưa\\s+đúng\\s+ý[^.?!]*[.?!]?", "").strip();
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.5.3 VR-007B/C: Cấm số liệu giả & claim tuyệt đối
                if (FAKE_STATS_SOCIAL_PROOF_PATTERN.matcher(text).matches()) {
                    errors.add("VR-007B_NO_FAKE_STATS_OR_ABSOLUTE_SOCIAL_PROOF");
                }

                // v3.1.5.3 VR-007D: Social proof định tính chỉ dùng khi docs_has_feedback = true
                if (!safeState.docsHasFeedback() && QUALITATIVE_SOCIAL_PROOF_PATTERN.matcher(text).matches()) {
                    errors.add("VR-007D_UNGROUNDED_SOCIAL_PROOF");
                    text = text.replaceAll("(?isU),?\\s*nhiều\\s+anh/chị\\s+sau\\s+buổi\\s+đầu\\s+trải\\s+nghiệm\\s+có\\s+phản\\s+hồi\\s+khá\\s+tốt\\s+ạ",
                            " cùng không gian yên tĩnh và sạch sẽ ạ");
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.5.3 VR-007H: Cấm tự hứa "phòng riêng" khi docs_has_private_room = false
                if (!safeState.docsHasPrivateRoom() && lower.contains("phòng riêng")) {
                    errors.add("VR-007H_UNGROUNDED_PRIVATE_ROOM");
                    text = text.replaceAll("(?iU)giữ\\s+phòng\\s+riêng", "giữ lịch")
                               .replaceAll("(?iU)phòng\\s+riêng", "lịch hẹn");
                    lower = text.toLowerCase(Locale.ROOT);
                }

                // v3.1.5.3 VR-015A: Emoji safety
                if (FORBIDDEN_EMOJI_PATTERN.matcher(text).find()) {
                    errors.add("VR-015A_FORBIDDEN_EMOJI");
                    text = FORBIDDEN_EMOJI_PATTERN.matcher(text).replaceAll("");
                }

                // v3.2.1: Chuẩn hóa 1 định dạng giá gọn duy nhất (399k thay vì 399k (399.000đ)) và bỏ câu máy móc "Dạ em ghi nhận mình đang..."
                text = text.replaceAll("(?iU)\\b(\\d{2,4}k)\\s*\\(\\s*\\d{1,3}(?:\\.\\d{3})+\\s*(?:đ|vnđ|vnd)?\\s*\\)", "$1");
                text = text.replaceAll("(?iU)^\\s*Dạ\\s+em\\s+ghi\\s+nhận\\s+mình\\s+đang\\s+([^.,!?]+?)\\s+ạ\\.",
                        "Dạ tình trạng $1 chắc đang làm mình khá khó chịu ạ.");
            }

            // 3. Tối đa 2 câu mỗi bong bóng
            if (countSentences(text) > 2) {
                errors.add("TOO_MANY_SENTENCES");
                text = trimToMaxSentences(text, 2);
            }

            // 4. Tối đa 45 từ mỗi bong bóng
            if (countWords(text) > 45) {
                errors.add("TOO_LONG");
                text = trimToMaxWords(text, 45);
            }

            // 5. Tối đa 1 câu hỏi mỗi bong bóng
            if (countQuestionMarks(text) > 1) {
                errors.add("TOO_MANY_QUESTIONS");
                text = keepSingleQuestionInBubble(text);
            }

            // 6. Không leak nội bộ
            if (INTERNAL_LEAK_PATTERN.matcher(text).matches()) {
                errors.add("INTERNAL_LEAK");
            }

            // 7. Không cam kết y khoa
            if (MEDICAL_CLAIM_PATTERN.matcher(text).matches()) {
                errors.add("MEDICAL_CLAIM");
            }

            // 8. Nếu đã bàn giá, không lặp giá trừ khi EXPLAIN_UNIT_VALUE, ANSWER_PRICE_ONCE hoặc HANDLE_PRICE_OBJECTION_REPEAT (báo giá gói hạ bậc)
            if (safeState.priceDiscussed()
                    && effectiveAction != PrimaryAction.EXPLAIN_UNIT_VALUE
                    && effectiveAction != PrimaryAction.ANSWER_PRICE_ONCE
                    && effectiveAction != PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT) {
                if (PRICE_PATTERN.matcher(text).find()) {
                    errors.add("REPEATED_PRICE_AFTER_DISCUSSED");
                }
            }

            // 9. Không lặp template đồng cảm giá lần 1 khi đang ở HANDLE_PRICE_OBJECTION_REPEAT
            if (effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                    && lower.contains("cân nhắc về chi phí")) {
                errors.add("REPEATED_TEMPLATE");
            }

            // 10. Acknowledgement không được báo giá lại
            if (PrimaryActionRouter.isAcknowledgementMessage(customerMessage)
                    && (effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                        || effectiveAction == PrimaryAction.CLARIFY_SERVICE_DURATION
                        || PRICE_PATTERN.matcher(text).find())) {
                errors.add("WRONG_ACTION_FOR_ACK");
            }

            // 11. So sánh đơn giá không được khuyên chọn gói ngắn hơn vừa ngân sách hơn
            if (effectiveAction == PrimaryAction.EXPLAIN_UNIT_VALUE
                    && lower.contains("gói ngắn vừa ngân sách")) {
                errors.add("UNIT_PRICE_LOGIC_ERROR");
            }

            working.set(i, new ReplyBubble(text, b.typingDelayMs()));
        }

        // Enforce total question marks across all bubbles <= 1 (or 0 on handoff)
        boolean isHandoffAction = effectiveAction == PrimaryAction.HARD_HANDOFF_SAFETY
                || effectiveAction == PrimaryAction.HARD_HANDOFF_COMPLAINT;
        int maxTotalQuestions = isHandoffAction ? 0 : 1;
        int totalQuestionsSeen = 0;
        for (int i = working.size() - 1; i >= 0; i--) {
            ReplyBubble b = working.get(i);
            if (b == null || b.text() == null) continue;
            int qInBubble = countQuestionMarks(b.text());
            if (qInBubble > 0) {
                if (totalQuestionsSeen + qInBubble > maxTotalQuestions) {
                    errors.add("TOO_MANY_QUESTIONS");
                    String neutralized = b.text().replace('?', '.');
                    working.set(i, new ReplyBubble(neutralized, b.typingDelayMs()));
                } else {
                    totalQuestionsSeen += qInBubble;
                }
            }
        }

        // 12 & v3.1.4 Check 8: POST_PRICE_DELAY_ENFORCE — Sau bong bóng chứa giá, bong bóng kế tiếp phải delay >= requiredDelay (10.000ms)
        boolean previousContainsPrice = false;
        for (int i = 0; i < working.size(); i++) {
            ReplyBubble b = working.get(i);
            if (b == null || b.text() == null) continue;
            if (previousContainsPrice) {
                if (b.typingDelayMs() < requiredDelay) {
                    errors.add("MISSING_10S_DELAY_AFTER_PRICE");
                    errors.add("POST_PRICE_DELAY_ENFORCE");
                    working.set(i, new ReplyBubble(b.text(), requiredDelay));
                }
            }
            previousContainsPrice = PRICE_PATTERN.matcher(b.text()).find();
        }

        for (int i = 0; i < working.size(); i++) {
            ReplyBubble b = working.get(i);
            if (b != null && b.text() != null) {
                working.set(i, new ReplyBubble(BubbleCapHook.capitalizeFirstLetter(b.text()), b.typingDelayMs()));
            }
        }

        String combinedText = String.join("\n\n", working.stream().map(ReplyBubble::text).toList());
        return new ValidationResult(errors, working, combinedText);
    }

    public static int countSentences(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String normalized = text.replaceAll("\\d+\\.\\d+", "0");
        String[] parts = normalized.split("[.!?]+(?:\\s+|$)");
        int count = 0;
        for (String p : parts) {
            if (!p.isBlank()) {
                count++;
            }
        }
        return count;
    }

    public static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }

    private static int countQuestionMarks(String text) {
        if (text == null) return 0;
        int c = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '?') c++;
        }
        return c;
    }

    private static String trimToMaxSentences(String text, int maxSentences) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= maxSentences) {
            return text;
        }
        if (text.contains("?")) {
            return (sentences[0].strip() + " " + sentences[sentences.length - 1].strip()).strip();
        }
        return (sentences[0].strip() + " " + sentences[1].strip()).strip();
    }

    private static String trimToMaxWords(String text, int maxWords) {
        String[] words = text.strip().split("\\s+");
        if (words.length <= maxWords) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        String res = sb.toString().strip();
        if (text.endsWith("?") && !res.endsWith("?")) {
            res = res + " ạ?";
        } else if (!res.endsWith(".") && !res.endsWith("!") && !res.endsWith("?")) {
            res = res + ".";
        }
        return res;
    }

    private static String keepSingleQuestionInBubble(String text) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        List<String> kept = new ArrayList<>();
        boolean questionKept = false;
        for (int i = sentences.length - 1; i >= 0; i--) {
            String s = sentences[i].strip();
            if (s.endsWith("?")) {
                if (!questionKept) {
                    kept.add(0, s);
                    questionKept = true;
                }
            } else {
                kept.add(0, s);
            }
        }
        return String.join(" ", kept).strip();
    }

    private static String resolveDynamicOptionPair(ConversationState safeState) {
        if (safeState != null) {
            if (safeState.recommendedOptions() != null && safeState.recommendedOptions().size() >= 2) {
                return "gói " + safeState.recommendedOptions().get(0) + " hoặc " + safeState.recommendedOptions().get(1);
            }
            if (safeState.knownRecommendedPackage() != null && !safeState.knownRecommendedPackage().isBlank()) {
                return "gói " + safeState.knownRecommendedPackage();
            }
            if (safeState.recommendedOptions() != null && !safeState.recommendedOptions().isEmpty()) {
                return "gói " + safeState.recommendedOptions().get(0);
            }
        }
        return "gói dịch vụ tiêu chuẩn hoặc chuyên sâu";
    }

    private static boolean isNamingValidDifferentCatalogPackageAtDuration(
            String lowerText,
            String rejDur,
            ConversationState safeState
    ) {
        if (safeState == null || safeState.availablePackageCatalog() == null || safeState.availablePackageCatalog().isEmpty()) {
            return false;
        }
        int dur = ConversationState.parseDurationInt(rejDur);
        if (dur <= 0) {
            return false;
        }
        java.util.Set<String> rejectedPkgs = safeState.rejectedPackages() != null
                ? safeState.rejectedPackages()
                : java.util.Set.of();
        for (com.example.docmind.service.bot.PackageCatalogService.PackageSummary pkg : safeState.availablePackageCatalog()) {
            if (pkg == null || pkg.durationMinutes() != dur) {
                continue;
            }
            String disp = pkg.displayName().toLowerCase(Locale.ROOT);
            String canon = ConversationState.toCanonicalPackageCode(disp, String.valueOf(dur));
            if (canon != null && rejectedPkgs.contains(canon)) {
                continue;
            }
            if (!disp.isBlank() && lowerText.contains(disp)) {
                return true;
            }
        }
        return false;
    }
}
