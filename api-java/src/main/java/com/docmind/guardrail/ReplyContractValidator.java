package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.bot.ConversationState;
import com.example.docmind.service.bot.PrimaryActionRouter;
import com.example.docmind.service.bot.PrimaryActionRouter.PrimaryAction;
import com.example.docmind.service.bot.ThinkingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bộ kiểm duyệt hợp đồng đầu ra 20 luật cứng (Domain-Agnostic Reply Contract Validator v3.1.4 + v3.3 Adaptive Thinking).
 * <p>
 * Bao gồm 12 quy tắc nền tảng v3.1, 8 quy tắc mở rộng v3.1.4 và cơ chế Adaptive Thinking:
 * <ul>
 *   <li>{@code NO_PRESSURE_BEFORE_CONDITION}: Cấm hỏi lực nhẹ/vừa khi chưa làm rõ tình trạng.</li>
 *   <li>{@code NO_BROAD_MENU_AFTER_SERVICE_KNOWN}: Cấm hỏi lại menu nhóm dịch vụ khi đã biết nhóm dịch vụ.</li>
 *   <li>{@code NO_REPEAT_QUESTION_INTENT}: Cấm lặp lại cùng một question_intent quá 1 lần.</li>
 *   <li>{@code FRUSTRATION_NO_REPEAT_CLARIFY}: Khi khách bức xúc, cấm hỏi lại câu hỏi làm rõ cũ.</li>
 *   <li>{@code PRICE_AFTER_ONE_CLARIFY_PER_INTENT}: Sau khi đã hỏi loại dịch vụ và thời lượng tối đa 1 lần, không hỏi vòng thêm.</li>
 *   <li>{@code RECOMMEND_WHEN_CONDITION_READY}: Khi đã hỏi/làm rõ tình trạng, bắt buộc tiến tới gợi ý gói dịch vụ.</li>
 *   <li>{@code NO_NUMERIC_PRICE_REPEAT_AFTER_DISCUSSED}: Cấm lặp lại con số giá trong GENERIC_SHORT_DISCOVERY sau khi đã báo giá.</li>
 *   <li>{@code POST_PRICE_DELAY_ENFORCE}: Bong bóng ngay sau bong bóng chứa giá có delay động {@code max(0, targetPostPricePauseMs - elapsedSincePriceBubbleMs)}.</li>
 * </ul>
 */
public final class ReplyContractValidator {

    private ReplyContractValidator() {
    }

    public static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?iU)\\b\\d{2,3}(?:\\.\\d{3})*\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b)"
    );
    private static final Pattern INTERNAL_LEAK_PATTERN = Pattern.compile(
            "(?isU).*(?:system\\s+prompt|chunk_id|document_id|<document_context>|<experience_library>|template_id|reasoning_brief|<think>).*"
    );
    private static final Pattern MEDICAL_CLAIM_PATTERN = Pattern.compile(
            "(?isU).*(?:cam\\s+kết\\s+khỏi|chữa\\s+khỏi(?:\\s+hẳn)?|điều\\s+trị\\s+dứt\\s+điểm|trị\\s+dứt\\s+điểm|khỏi\\s+bệnh\\s+100%|hết\\s+tê\\s+tay|khỏi\\s+đau\\s+đầu|điều\\s+trị\\s+bệnh|chữa\\s+thoát\\s+vị|chữa\\s+thoái\\s+hóa|cam\\s+kết\\s+hết\\s+đau|đảm\\s+bảo\\s+khỏi|khí\\s+huyết\\s+(?:lên\\s+vùng\\s+đầu\\s+)?kém\\s+lưu\\s+thông|tắc\\s+nghẽn\\s+kinh\\s+lạc|máu\\s+lên\\s+não\\s+kém).*"
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
    private static final Pattern UNSTATED_CANG_ASSUMPTION_PATTERN = Pattern.compile(
            "(?isU).*(?:thường\\s+căng\\s+sau\\s+ngồi\\s+lâu|căng\\s+sau\\s+ngồi\\s+lâu\\s+hay\\s+vận\\s+động|đang\\s+bị\\s+căng\\b).*"
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
        return validateAndEnforce(
                bubbles,
                state,
                customerMessage,
                effectiveAction,
                openingRequired,
                minPostPriceDelayMs,
                null,
                null
        );
    }

    public static ValidationResult validateAndEnforce(
            List<ReplyBubble> bubbles,
            ConversationState state,
            String customerMessage,
            PrimaryAction effectiveAction,
            boolean openingRequired,
            int minPostPriceDelayMs,
            ThinkingMode thinkingMode,
            Long elapsedSincePriceBubbleMs
    ) {
        boolean isLlmValidation = thinkingMode != null;
        int requiredDelay = Math.max(10_000, minPostPriceDelayMs);
        boolean hasDynamicElapsed = elapsedSincePriceBubbleMs != null && elapsedSincePriceBubbleMs > 0L;
        int effectivePostPriceDelay = hasDynamicElapsed
                ? (int) Math.max(0L, (long) requiredDelay - elapsedSincePriceBubbleMs)
                : requiredDelay;
        int maxSentencesPerBubble = thinkingMode != null ? Math.max(3, thinkingMode.maxSentencesPerBubble()) : 2;
        int maxWordsPerBubble = Math.max(65, thinkingMode != null ? thinkingMode.maxWordsPerBubble() : 65);
        List<String> errors = new ArrayList<>();
        if (bubbles == null || bubbles.isEmpty()) {
            errors.add("EMPTY_BUBBLES");
            return new ValidationResult(errors, List.of(), "");
        }

        ConversationState safeState = state != null ? state : ConversationState.initial();
        ConversationState enrichedState = safeState.enrichWithCurrentMessage(customerMessage, List.of());
        boolean isFrustratedNow = PrimaryActionRouter.isFrustrationMessage(customerMessage);
        boolean customerEverStatedCang = (customerMessage != null
                && customerMessage.toLowerCase(Locale.ROOT).contains("căng")
                && !com.example.docmind.service.bot.SentimentIntentLayer.isNegationOrPushbackOnAssumption(customerMessage))
                || (enrichedState.knownSymptoms() != null
                    && enrichedState.knownSymptoms().stream().anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("căng")));

        List<ReplyBubble> working = new ArrayList<>(bubbles);

        // 1. Tối đa 3 bong bóng
        if (working.size() > 3) {
            errors.add("TOO_MANY_BUBBLES");
            if (!isLlmValidation) {
                working = new ArrayList<>(working.subList(0, 3));
            }
        }

        for (int i = 0; i < working.size(); i++) {
            ReplyBubble b = working.get(i);
            if (b == null || b.text() == null) continue;
            String text = isLlmValidation ? b.text() : b.text().strip();
            String lower = text.toLowerCase(Locale.ROOT);

            String dynamicOptionPair = resolveDynamicOptionPair(safeState);
            // v3.1.4 Check 1: NO_PRESSURE_BEFORE_CONDITION
            if (!enrichedState.conditionClarified() && PRESSURE_QUESTION_PATTERN.matcher(text).matches()) {
                errors.add("NO_PRESSURE_BEFORE_CONDITION");
                if (!isLlmValidation) {
                    text = text.replaceAll(
                            "(?isU)[^.!?]*?(?:lực\\s+nhẹ\\s+hay\\s+lực\\s+vừa|lực\\s+nhẹ\\s+thư\\s+giãn\\s+hay\\s+lực\\s+vừa|thích\\s+lực\\s+nhẹ\\s+hay)[^.!?]*[?]",
                            "Mình gặp tình trạng này lâu chưa hay mới gần đây ạ?"
                    ).strip();
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // v3.2.4 Check: UNSTATED_CANG_ASSUMPTION
            if (!customerEverStatedCang && UNSTATED_CANG_ASSUMPTION_PATTERN.matcher(text).matches()) {
                errors.add("UNSTATED_CANG_ASSUMPTION");
            }

            // v3.1.4 Check 2: NO_BROAD_MENU_AFTER_SERVICE_KNOWN
            if (enrichedState.serviceFamilyKnown() && BROAD_SERVICE_MENU_PATTERN.matcher(text).matches() && text.contains("?")) {
                errors.add("NO_BROAD_MENU_AFTER_SERVICE_KNOWN");
                if (!isLlmValidation) {
                    text = "Dạ với nhóm dịch vụ này, mình muốn xem " + dynamicOptionPair.replace(" hoặc ", " hay ") + " để em báo giá cụ thể ạ?";
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // v3.1.4 Check 3 & 6: NO_REPEAT_QUESTION_INTENT & RECOMMEND_WHEN_CONDITION_READY
            if ((enrichedState.conditionClarified() || safeState.conditionAskedCount() >= 1)
                    && !safeState.packageRecommended()
                    && effectiveAction != PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                    && (CONDITION_QUESTION_PATTERN.matcher(text).matches() || PRESSURE_QUESTION_PATTERN.matcher(text).matches())) {
                errors.add("RECOMMEND_WHEN_CONDITION_READY");
                errors.add("NO_REPEAT_QUESTION_INTENT");
                if (!isLlmValidation) {
                    text = "Dạ với tình trạng hiện tại của mình, em gợi ý thử " + dynamicOptionPair + " ạ. Mình muốn xem chi phí gói nào trước ạ?";
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // v3.1.4 Check 4: FRUSTRATION_NO_REPEAT_CLARIFY (never default to package sales pitch on frustration)
            if (isFrustratedNow && (BROAD_SERVICE_MENU_PATTERN.matcher(text).matches()
                    || lower.contains("lực nhẹ hay lực vừa")
                    || lower.contains("vui lòng cho biết dịch vụ"))) {
                errors.add("FRUSTRATION_NO_REPEAT_CLARIFY");
                if (!isLlmValidation) {
                    text = "Dạ em thành thật xin lỗi vì trả lời chưa sát ý mình ạ. Mình cho em biết phần thông tin nào đang cần giải đáp rõ hơn để em hỗ trợ ngay nhé?";
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // Exact-duplicate bubble hash guard against previous bot bubbles in the conversation
            if (safeState.lastBotBubbleHashes() != null
                    && safeState.lastBotBubbleHashes().contains(ConversationState.shortHash(text))) {
                errors.add("REPEATED_BUBBLE_HASH");
            }

            // Clause-overlap guard on PUSHBACK_ON_ASSUMPTION_REPAIR (do not repeat opening empathy sentence from previous turn)
            if (effectiveAction == PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                    && safeState.lastAssistantMessage() != null
                    && !safeState.lastAssistantMessage().isBlank()
                    && hasSubstantialSentenceOverlap(text, safeState.lastAssistantMessage())) {
                if (!errors.contains("REPEATED_BUBBLE_HASH")) {
                    errors.add("REPEATED_BUBBLE_HASH");
                }
            }

            // Inverted customer pronoun guard ("Em đang quan tâm...", "Em muốn...")
            if (HonorificConsistencyHook.matchesInvertedCustomerPronoun(text)) {
                errors.add("INVERTED_CUSTOMER_PRONOUN");
                if (!isLlmValidation) {
                    text = HonorificConsistencyHook.repairInvertedCustomerPronoun(text, "mình");
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // Mechanical benefit concatenation guard ("Gói này gồm ... rất dễ chịu ạ.")
            if (lower.matches("(?isU).*\\bgói\\s+này\\s+gồm\\b[^.!?]*\\brất\\s+(?:dễ\\s+chịu|phù\\s+hợp)\\s+ạ.*")) {
                errors.add("MECHANICAL_BENEFIT_CONCAT");
            }

            // v3.2.5 Check: ROBOTIC_OR_MIXED_PRONOUN_VOICE ("Chị đang gặp phải tình trạng... khiến mình mệt mỏi hẳn luôn ạ", "Liệu Chị có muốn...")
            boolean customerUsesToiOrMinh = customerMessage != null
                    && customerMessage.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(tôi|tui|tớ|mình)\\b.*")
                    && !customerMessage.toLowerCase(Locale.ROOT).matches("(?isU).*\\bchị\\b.*");
            boolean hasMixedChiAndMinh = customerUsesToiOrMinh
                    && lower.matches("(?isU).*(?<!nhiều\\sanh/)(?<!\\p{L})chị(?!\\p{L}).*")
                    && lower.matches("(?isU).*(?<!\\p{L})mình(?!\\p{L}).*");
            boolean hasRoboticTranslationese = lower.contains("đang gặp phải tình trạng")
                    || lower.contains("mệt mỏi hẳn luôn")
                    || lower.matches("(?isU).*\\bliệu\\s+(?:mình|chị|anh)\\s+có\\s+muốn\\b.*");
            if (hasMixedChiAndMinh || hasRoboticTranslationese) {
                errors.add("ROBOTIC_OR_MIXED_PRONOUN_VOICE");
            }

            // v3.2.6 Check: SERVICE_MISCLASSIFIED_AS_SYMPTOM ("tình trạng ngâm", "tình trạng massage")
            if (lower.matches("(?isU).*\\btình\\s+trạng\\s+(?:ngâm|massage|mát\\s+xa|gội\\s+đầu|xông\\s+hơi|tắm\\s+tráng)\\b.*")) {
                errors.add("SERVICE_MISCLASSIFIED_AS_SYMPTOM");
            }

            // v3.2.6 Check: UNGROUNDED_HERB_INGREDIENT (forbid inventing specific bath herbs not in KB)
            if (lower.matches("(?isU).*\\b(tía\\s+tô|trà\\s+xanh|lá\\s+lốt|hương\\s+nhu|vỏ\\s+bưởi|ngải\\s+cứu.*gừng|gừng.*sả|tía\\s+tô.*gừng)\\b.*")
                    && !lower.contains("trứng ngải cứu")) {
                errors.add("UNGROUNDED_HERB_INGREDIENT");
            }

            // v3.2.6 Check: UNNECESSARY_APOLOGY_ON_META_COMMENT (forbid knee-jerk apology on meta-comment, confirmation, ACK, or clarify turns)
            if (customerMessage != null
                    && (com.example.docmind.service.bot.SentimentIntentLayer.isMetaConversationOnBotBehavior(customerMessage)
                        || com.example.docmind.service.bot.SentimentIntentLayer.isConfirmPreviousInfoMessage(customerMessage)
                        || effectiveAction == PrimaryAction.CLARIFY_SERVICE_DURATION
                        || effectiveAction == PrimaryAction.ADVANCE_AFTER_ACK)
                    && lower.matches("(?isU).*\\b(?:em\\s+xin\\s+lỗi|xin\\s+lỗi\\s+(?:chị|anh|mình)|thứ\\s+lỗi|(?:em|mình)\\s+đã\\s+hiểu\\s+lầm)\\b.*")
                    && !lower.matches("(?isU).*\\b(?:không|đâu)\\s+(?:có\\s+gì\\s+)?(?:phải\\s+)?(?:xin\\s+lỗi|hiểu\\s+lầm)\\b.*")) {
                errors.add("UNNECESSARY_APOLOGY_ON_META_COMMENT");
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
                if (!isLlmValidation) {
                    text = PRICE_PATTERN.matcher(text).replaceAll("mức chi phí ở tin nhắn trên").replaceAll("\\(\\s*mức chi phí ở tin nhắn trên\\s*\\)", "").strip();
                    lower = text.toLowerCase(Locale.ROOT);
                }
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
                            if (!isLlmValidation) {
                                text = text.replaceAll("(?iU)" + Pattern.quote(rejDur), replacementDur);
                                lower = text.toLowerCase(Locale.ROOT);
                            }
                        }
                    }
                }
            }

            // Check rejected higher price repeat on CONFIRM_BOOKING_DETAILS after downsell
            if (effectiveAction == PrimaryAction.CONFIRM_BOOKING_DETAILS
                    && safeState.downsellOffered()
                    && safeState.lastOfferedPrice() != null) {
                java.math.BigDecimal acceptedBd = ConversationState.parsePriceToBigDecimal(safeState.lastOfferedPrice());
                if (acceptedBd != null) {
                    java.util.regex.Matcher pm = PRICE_PATTERN.matcher(text);
                    while (pm.find()) {
                        java.math.BigDecimal quotedBd = ConversationState.parsePriceToBigDecimal(pm.group());
                        if (quotedBd != null && quotedBd.compareTo(acceptedBd) != 0) {
                            errors.add("VR-004A_NO_REJECTED_PACKAGE_REPEAT");
                        }
                    }
                }
            }

            // v3.2.1 price_grounding_check: Mọi con số giá trong câu trả lời phải khớp gói thực tế trong availablePackageCatalog
            if (safeState.availablePackageCatalog() != null && !safeState.availablePackageCatalog().isEmpty()) {
                java.util.regex.Matcher pm = PRICE_PATTERN.matcher(text);
                List<Long> mentionedPricesVnd = new ArrayList<>();
                while (pm.find()) {
                    java.math.BigDecimal quotedBd = ConversationState.parsePriceToBigDecimal(pm.group());
                    if (quotedBd != null) {
                        long quotedVnd = quotedBd.longValue();
                        mentionedPricesVnd.add(quotedVnd);
                        boolean groundedInCatalog = safeState.availablePackageCatalog().stream()
                                .anyMatch(pkg -> pkg != null && pkg.priceVnd() == quotedVnd);
                        if (!groundedInCatalog) {
                            errors.add("UNGROUNDED_CATALOG_PRICE");
                        }
                    }
                }
                if (hasUngroundedPackageBenefitClaim(lower, mentionedPricesVnd, safeState, effectiveAction)) {
                    errors.add("UNGROUNDED_PACKAGE_BENEFIT");
                }
            }

            // v3.2.1 VR-007E: Cấm câu mẫu máy móc "Nếu chưa đúng ý..."
            if (lower.contains("nếu chưa đúng ý")) {
                errors.add("VR-007E_NO_CANNED_AMBIGUOUS_FALLBACK");
                if (!isLlmValidation) {
                    text = text.replaceAll("(?isU)\\s*Nếu\\s+chưa\\s+đúng\\s+ý[^.?!]*[.?!]?", "").strip();
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // v3.1.5.3 VR-007B/C: Cấm số liệu giả & claim tuyệt đối
            if (FAKE_STATS_SOCIAL_PROOF_PATTERN.matcher(text).matches()) {
                errors.add("VR-007B_NO_FAKE_STATS_OR_ABSOLUTE_SOCIAL_PROOF");
            }

            // v3.1.5.3 VR-007D: Social proof định tính chỉ dùng khi docs_has_feedback = true
            if (!safeState.docsHasFeedback() && QUALITATIVE_SOCIAL_PROOF_PATTERN.matcher(text).matches()) {
                errors.add("VR-007D_UNGROUNDED_SOCIAL_PROOF");
                if (!isLlmValidation) {
                    text = text.replaceAll("(?isU),?\\s*nhiều\\s+anh/chị\\s+sau\\s+buổi\\s+đầu\\s+trải\\s+nghiệm\\s+có\\s+phản\\s+hồi\\s+khá\\s+tốt\\s+ạ",
                            " cùng không gian yên tĩnh và sạch sẽ ạ");
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // v3.1.5.3 VR-007H: Cấm tự hứa "phòng riêng" khi docs_has_private_room = false
            if (!safeState.docsHasPrivateRoom() && lower.contains("phòng riêng")) {
                errors.add("VR-007H_UNGROUNDED_PRIVATE_ROOM");
                if (!isLlmValidation) {
                    text = text.replaceAll("(?iU)giữ\\s+phòng\\s+riêng", "giữ lịch")
                               .replaceAll("(?iU)phòng\\s+riêng", "lịch hẹn");
                    lower = text.toLowerCase(Locale.ROOT);
                }
            }

            // v3.1.5.3 VR-015A: Emoji safety
            if (FORBIDDEN_EMOJI_PATTERN.matcher(text).find()) {
                errors.add("VR-015A_FORBIDDEN_EMOJI");
                if (!isLlmValidation) {
                    text = FORBIDDEN_EMOJI_PATTERN.matcher(text).replaceAll("");
                }
            }

            if (!isLlmValidation) {
                text = text.replaceAll("(?iU)\\b(\\d{2,4}k)\\s*\\(\\s*\\d{1,3}(?:\\.\\d{3})+\\s*(?:đ|vnđ|vnd)?\\s*\\)", "$1");
                text = text.replaceAll("(?iU)^\\s*Dạ\\s+em\\s+ghi\\s+nhận\\s+mình\\s+đang\\s+([^.,!?]+?)\\s+ạ\\.",
                        "Dạ tình trạng $1 chắc đang làm mình khá khó chịu ạ.");
            }

            // 3. Tối đa N câu mỗi bong bóng
            if (countSentences(text) > maxSentencesPerBubble) {
                errors.add("TOO_MANY_SENTENCES");
                if (!isLlmValidation) {
                    text = trimToMaxSentences(text, maxSentencesPerBubble);
                }
            }

            // 4. Tối đa 65 từ mỗi bong bóng
            if (countWords(text) > maxWordsPerBubble) {
                errors.add("TOO_LONG");
                if (!isLlmValidation) {
                    text = trimToMaxWords(text, maxWordsPerBubble);
                }
            }

            // 5. Tối đa 1 câu hỏi mỗi bong bóng
            if (countQuestionMarks(text) > 1) {
                errors.add("TOO_MANY_QUESTIONS");
                if (!isLlmValidation) {
                    text = keepSingleQuestionInBubble(text);
                }
            }

            // 6. Không leak nội bộ
            if (INTERNAL_LEAK_PATTERN.matcher(text).matches()) {
                errors.add("INTERNAL_LEAK");
            }

            // 7. Không cam kết y khoa hoặc tự chẩn đoán bệnh lý
            if (MEDICAL_CLAIM_PATTERN.matcher(text).matches()) {
                errors.add("MEDICAL_CLAIM");
            }

            // 8. Nếu đã bàn giá, không lặp giá trừ khi EXPLAIN_UNIT_VALUE, ANSWER_PRICE_ONCE, HANDLE_PRICE_OBJECTION_REPEAT, HANDLE_PRICE_OBJECTION_FIRST_TIME hoặc CONFIRM_BOOKING_DETAILS
            if (safeState.priceDiscussed()
                    && effectiveAction != PrimaryAction.EXPLAIN_UNIT_VALUE
                    && effectiveAction != PrimaryAction.ANSWER_PRICE_ONCE
                    && effectiveAction != PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                    && effectiveAction != PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                    && effectiveAction != PrimaryAction.CONFIRM_BOOKING_DETAILS) {
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

            // 12 (v3.3.4 RC-03). Cấm đẩy trách nhiệm cho khách / bắt khách nói lại thay vì tự hiểu ngữ cảnh
            if (lower.matches("(?isU).*(?:kiểm\\s+tra\\s+lại\\s+lịch\\s+sử\\s+trò\\s+chuyện|nói\\s+lại\\s+cho\\s+em\\s+hiểu\\s+rõ\\s+hơn|mình\\s+có\\s+thể\\s+nói\\s+lại).*")) {
                errors.add("FORBIDDEN_HISTORY_DEFLECTION_OR_REPEAT_PROMPT");
            }

            working.set(i, new ReplyBubble(text, b.typingDelayMs()));
        }

        // v3.3.4 RC-02: Bubble 2 sau báo giá / chê đắt phải là neo cảm xúc MỚI, không lặp lại Bubble 1.
        //  - Không mở đầu máy móc "Dạ em hiểu rồi ạ".
        //  - Không nhắc lại tên gói đã nêu ở Bubble 1 (tên gói lấy ĐỘNG từ catalog đang active của tenant, không hardcode).
        //  - Ở lần chê đắt đầu tiên, Bubble 1 không được lặp lại con số giá.
        if (working.size() >= 2
                && (effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                    || effectiveAction == PrimaryAction.RECOMMEND_ONE_PACKAGE
                    || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME)) {
            String b1Lower = working.get(0) != null && working.get(0).text() != null ? working.get(0).text().toLowerCase(Locale.ROOT) : "";
            String b2Lower = working.get(1) != null && working.get(1).text() != null ? working.get(1).text().toLowerCase(Locale.ROOT) : "";
            boolean b2MechanicalOpening = b2Lower.strip().startsWith("dạ em hiểu rồi ạ");
            boolean b2RepeatsPackageName = false;
            if (safeState.availablePackageCatalog() != null) {
                for (com.example.docmind.service.bot.PackageCatalogService.PackageSummary pkg : safeState.availablePackageCatalog()) {
                    String name = pkg != null && pkg.displayName() != null ? pkg.displayName().strip().toLowerCase(Locale.ROOT) : "";
                    if (name.length() >= 6 && b1Lower.contains(name) && b2Lower.contains(name)) {
                        b2RepeatsPackageName = true;
                        break;
                    }
                }
            }
            boolean objectionB1RepeatsPrice = effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                    && PRICE_PATTERN.matcher(b1Lower).find();
            if (b2MechanicalOpening || b2RepeatsPackageName || objectionB1RepeatsPrice) {
                errors.add("CROSS_BUBBLE_REDUNDANCY_OR_PRICE_REPEAT");
            }
        }
        // v3.3.4 RC-02 (split-bubble): Bubble 2 được sinh ở request riêng (GENERATE_PRICE_ANCHOR_BUBBLE) nên validator chỉ
        // thấy 1 bong bóng. Bubble 1 đã nêu tên gói đang báo giá (lưu trong state) -> Bubble 2 không được mở đầu máy móc
        // hay gọi lại đúng tên gói đó.
        if (working.size() == 1 && effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE && hasDynamicElapsed
                && working.get(0) != null && working.get(0).text() != null) {
            String anchorLower = working.get(0).text().toLowerCase(Locale.ROOT).strip();
            String quotedName = safeState.lastOfferedPackageName() != null
                    ? safeState.lastOfferedPackageName().strip().toLowerCase(Locale.ROOT)
                    : "";
            boolean anchorMechanicalOpening = anchorLower.startsWith("dạ em hiểu rồi ạ") || anchorLower.startsWith("em hiểu rồi ạ");
            boolean anchorRepeatsQuotedName = quotedName.length() >= 6 && anchorLower.contains(quotedName);
            if (anchorMechanicalOpening || anchorRepeatsQuotedName) {
                errors.add("CROSS_BUBBLE_REDUNDANCY_OR_PRICE_REPEAT");
            }
        }

        // Enforce total question marks across all bubbles <= 1 (or 0 on handoff / Stage 3 graceful exit / strong rejection)
        boolean isNoQuestionAction = effectiveAction == PrimaryAction.HARD_HANDOFF_SAFETY
                || effectiveAction == PrimaryAction.HARD_HANDOFF_COMPLAINT
                || "STAGE_3_GRACEFUL_EXIT".equals(safeState.objectionStage())
                || "STAGE_3_GRACEFUL_EXIT".equals(enrichedState.objectionStage())
                || "STAGE_3_GRACEFUL_EXIT".equals(enrichedState.lastIntent())
                || com.example.docmind.service.bot.SentimentIntentLayer.isStrongRejection(customerMessage);
        int maxTotalQuestions = isNoQuestionAction ? 0 : 1;
        int totalQuestionsSeen = 0;
        for (int i = working.size() - 1; i >= 0; i--) {
            ReplyBubble b = working.get(i);
            if (b == null || b.text() == null) continue;
            int qInBubble = countQuestionMarks(b.text());
            if (qInBubble > 0) {
                if (totalQuestionsSeen + qInBubble > maxTotalQuestions) {
                    errors.add("TOO_MANY_QUESTIONS");
                    if (!isLlmValidation) {
                        String neutralized = b.text().replace('?', '.');
                        working.set(i, new ReplyBubble(neutralized, b.typingDelayMs()));
                    }
                } else {
                    totalQuestionsSeen += qInBubble;
                }
            }
        }

        // 12 & v3.1.4 Check 8: POST_PRICE_DELAY_ENFORCE — Sau bong bóng chứa giá, bong bóng kế tiếp có delay động max(0, requiredDelay - elapsedSincePriceBubbleMs)
        boolean isSingleAnchorBubbleTurn = working.size() == 1 && effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE && hasDynamicElapsed;
        if (isSingleAnchorBubbleTurn) {
            ReplyBubble singleAnchor = working.get(0);
            if (singleAnchor != null && singleAnchor.text() != null) {
                if (PRICE_PATTERN.matcher(singleAnchor.text()).find()) {
                    errors.add("REPEATED_PRICE_IN_ANCHOR_BUBBLE");
                    errors.add("REPEATED_PRICE_AFTER_DISCUSSED");
                }
                working.set(0, new ReplyBubble(singleAnchor.text(), effectivePostPriceDelay));
            }
        }
        boolean previousContainsPrice = false;
        int previousPriceCount = 0;
        for (int i = 0; i < working.size(); i++) {
            ReplyBubble b = working.get(i);
            if (b == null || b.text() == null) continue;
            if (i == 0 && !isSingleAnchorBubbleTurn && b.typingDelayMs() != 0) {
                working.set(0, new ReplyBubble(b.text(), 0));
                b = working.get(0);
            }
            if (previousContainsPrice) {
                if (effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                        && previousPriceCount == 1
                        && PRICE_PATTERN.matcher(b.text()).find()) {
                    errors.add("REPEATED_PRICE_IN_ANCHOR_BUBBLE");
                    errors.add("REPEATED_PRICE_AFTER_DISCUSSED");
                }
                if (hasDynamicElapsed) {
                    working.set(i, new ReplyBubble(b.text(), effectivePostPriceDelay));
                } else if (b.typingDelayMs() < effectivePostPriceDelay) {
                    errors.add("MISSING_10S_DELAY_AFTER_PRICE");
                    errors.add("POST_PRICE_DELAY_ENFORCE");
                    working.set(i, new ReplyBubble(b.text(), effectivePostPriceDelay));
                }
            }
            java.util.regex.Matcher pm = PRICE_PATTERN.matcher(b.text());
            int count = 0;
            while (pm.find()) {
                count++;
            }
            previousContainsPrice = count > 0;
            previousPriceCount = count;
        }

        if (!isLlmValidation) {
            for (int i = 0; i < working.size(); i++) {
                ReplyBubble b = working.get(i);
                if (b != null && b.text() != null) {
                    working.set(i, new ReplyBubble(BubbleCapHook.capitalizeFirstLetter(b.text()), b.typingDelayMs()));
                }
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
        int cap = Math.max(1, maxSentences);
        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= cap) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        if (text.contains("?") && cap >= 2) {
            for (int i = 0; i < cap - 1; i++) {
                if (i > 0) sb.append(' ');
                sb.append(sentences[i].strip());
            }
            sb.append(' ').append(sentences[sentences.length - 1].strip());
            return sb.toString().strip();
        }
        for (int i = 0; i < cap; i++) {
            if (i > 0) sb.append(' ');
            sb.append(sentences[i].strip());
        }
        return sb.toString().strip();
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

    private static boolean hasSubstantialSentenceOverlap(String currentText, String previousAssistantText) {
        if (currentText == null || previousAssistantText == null) {
            return false;
        }
        String[] currSentences = currentText.split("(?<=[.!?])\\s+");
        String[] prevSentences = previousAssistantText.split("(?<=[.!?])\\s+");
        for (String cs : currSentences) {
            String normCurr = normalizeSentenceForOverlap(cs);
            if (normCurr.split("\\s+").length < 8) {
                continue;
            }
            for (String ps : prevSentences) {
                String normPrev = normalizeSentenceForOverlap(ps);
                if (normCurr.equals(normPrev)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeSentenceForOverlap(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[.,!?\"'()\\[\\]]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean hasUngroundedPackageBenefitClaim(
            String lowerText,
            List<Long> mentionedPricesVnd,
            ConversationState safeState,
            PrimaryAction effectiveAction
    ) {
        if (lowerText == null || safeState == null || safeState.availablePackageCatalog() == null
                || safeState.availablePackageCatalog().isEmpty()) {
            return false;
        }
        boolean claimsSauna = (lowerText.contains("xông hơi")
                || lowerText.contains("xông thảo dược")
                || lowerText.contains("tắm xông"))
                && !lowerText.matches("(?isU).*(?:không\\s+(?:có|bao\\s+gồm)|chưa\\s+(?:có|bao\\s+gồm))[^.!?]{0,40}(?:xông\\s+hơi|xông\\s+thảo\\s+dược|tắm\\s+xông).*");
        boolean claimsHerbalBath = lowerText.contains("ngâm bồn")
                && !lowerText.matches("(?isU).*(?:không\\s+(?:có|bao\\s+gồm)|chưa\\s+(?:có|bao\\s+gồm))[^.!?]{0,40}ngâm\\s+bồn.*");
        boolean claimsVipRoom = (lowerText.contains("phòng vip") || lowerText.contains("phòng riêng"))
                && !lowerText.matches("(?isU).*(?:không\\s+(?:có|bao\\s+gồm|phải)|chưa\\s+(?:có|bao\\s+gồm)|phòng\\s+chung)[^.!?]{0,40}(?:phòng\\s+vip|phòng\\s+riêng).*");
        boolean claimsFullBody = (lowerText.contains("massage toàn thân")
                || lowerText.contains("full body")
                || lowerText.contains("massage body"))
                && !lowerText.matches("(?isU).*(?:không\\s+(?:có|bao\\s+gồm)|chưa\\s+(?:có|bao\\s+gồm))[^.!?]{0,40}(?:massage\\s+toàn\\s+thân|full\\s+body|massage\\s+body).*");
        boolean claimsBuffet = lowerText.contains("buffet thực dưỡng")
                && !lowerText.matches("(?isU).*(?:không\\s+(?:có|bao\\s+gồm)|chưa\\s+(?:có|bao\\s+gồm))[^.!?]{0,40}buffet\\s+thực\\s+dưỡng.*");
        if (!claimsSauna && !claimsHerbalBath && !claimsVipRoom && !claimsFullBody && !claimsBuffet) {
            return false;
        }

        List<Long> targetPrices = new ArrayList<>();
        if (mentionedPricesVnd != null && !mentionedPricesVnd.isEmpty()) {
            targetPrices.addAll(mentionedPricesVnd);
        } else if ((effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                || effectiveAction == PrimaryAction.CONFIRM_BOOKING_DETAILS)
                && safeState.lastOfferedPriceVnd() != null
                && safeState.lastOfferedPriceVnd().longValue() > 0L) {
            targetPrices.add(safeState.lastOfferedPriceVnd().longValue());
        }

        if (targetPrices.isEmpty()) {
            return false;
        }

        List<com.example.docmind.service.bot.PackageCatalogService.PackageSummary> matchingPackages = safeState.availablePackageCatalog().stream()
                .filter(pkg -> pkg != null && targetPrices.contains(pkg.priceVnd()))
                .toList();
        if (matchingPackages.isEmpty()) {
            return false;
        }

        if (claimsSauna) {
            boolean anyHasSauna = matchingPackages.stream().anyMatch(pkg -> {
                String combined = ((pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                        + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                return combined.contains("xông");
            });
            if (!anyHasSauna) {
                return true;
            }
        }
        if (claimsHerbalBath) {
            boolean anyHasBath = matchingPackages.stream().anyMatch(pkg -> {
                String combined = ((pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                        + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                return combined.contains("ngâm bồn");
            });
            if (!anyHasBath) {
                return true;
            }
        }
        if (claimsVipRoom) {
            boolean anyHasVip = matchingPackages.stream().anyMatch(pkg -> {
                String room = (pkg.roomType() != null ? pkg.roomType() : "").toLowerCase(Locale.ROOT);
                String combined = ((pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                        + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                return room.contains("vip") || room.contains("riêng") || combined.contains("phòng vip");
            });
            if (!anyHasVip) {
                return true;
            }
        }
        if (claimsFullBody) {
            boolean anyHasFullBody = matchingPackages.stream().anyMatch(pkg -> {
                String code = (pkg.code() != null ? pkg.code() : "").toUpperCase(Locale.ROOT);
                String combined = ((pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                        + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                return combined.contains("full body") || combined.contains("toàn thân")
                        || combined.contains("massage body")
                        || (code.startsWith("NS") && !code.startsWith("DS"));
            });
            if (!anyHasFullBody) {
                return true;
            }
        }
        if (claimsBuffet) {
            boolean anyHasBuffet = matchingPackages.stream().anyMatch(pkg -> {
                String code = (pkg.code() != null ? pkg.code() : "").toUpperCase(Locale.ROOT);
                String combined = ((pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                        + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                return combined.contains("buffet")
                        || (code.startsWith("NS") && !code.startsWith("DS"));
            });
            if (!anyHasBuffet) {
                return true;
            }
        }
        return false;
    }
}
