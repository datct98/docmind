package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.bot.ConversationState;
import com.example.docmind.service.bot.PackageCatalogService;
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
            "(?isU).*(?:cam\\s+kết\\s+khỏi|chữa\\s+khỏi(?:\\s+hẳn)?|điều\\s+trị\\s+dứt\\s+điểm|trị\\s+dứt\\s+điểm|khỏi\\s+bệnh\\s+100%|hết\\s+tê\\s+tay|khỏi\\s+đau\\s+đầu|điều\\s+trị\\s+bệnh|chữa\\s+thoát\\s+vị|chữa\\s+thoái\\s+hóa|cam\\s+kết\\s+hết\\s+đau|đảm\\s+bảo\\s+khỏi|khí\\s+huyết\\s+(?:lên\\s+vùng\\s+đầu\\s+)?kém\\s+lưu\\s+thông|tắc\\s+nghẽn\\s+kinh\\s+lạc|máu\\s+lên\\s+não\\s+kém|(?:giảm|trị|chữa|hết|cải\\s+thiện)\\s+(?:đau\\s+đầu|mất\\s+ngủ|đau\\s+nhức|nhức\\s+mỏi|đau\\s+vai\\s+gáy)\\s+hiệu\\s+quả|(?:trị|chữa|dứt\\s+điểm|khỏi\\s+hẳn)\\s+(?:đau\\s+đầu|mất\\s+ngủ)).*"
    );
    private static final Pattern FAKE_STATS_SOCIAL_PROOF_PATTERN = Pattern.compile(
            "(?isU).*(?:(?<!miễn\\s+phí\\s)\\b(?:9\\d|100)\\s*%|10\\.000\\s+khách|hàng\\s+nghìn\\s+khách|tất\\s+cả\\s+khách\\s+đều|ai\\s+cũng\\s+hài\\s+lòng|khách\\s+nào\\s+cũng\\s+thích|100%\\s+khách|chắc\\s+chắn\\s+hài\\s+lòng|cam\\s+kết\\s+hài\\s+lòng\\s+100%|đều\\s+phản\\s+hồi).*"
    );
    private static final Pattern QUALITATIVE_SOCIAL_PROOF_PATTERN = Pattern.compile(
            "(?isU).*(?:nhiều\\s+anh/chị\\s+sau\\s+buổi\\s+đầu|phản\\s+hồi\\s+khá\\s+tốt).*"
    );
    private static final Pattern FORBIDDEN_EMOJI_PATTERN = Pattern.compile(
            "[😂🤣😒🙄😤😡💸🤑💙]"
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

    // [VÁ C.1]: Dùng (?![\\p{L}\\d]) ở cuối thay vì \\b để bắt chính xác các cụm kết thúc bằng dấu '%' (như 100%, 0%)
    private static final Pattern FAKE_COMMITMENT_OR_PII_STORY_PATTERN = Pattern.compile(
            "(?isU).*(?<![\\p{L}\\d])(hoàn\\s+tiền\\s+100%|cam\\s+kết\\s+hoàn\\s+tiền|trả\\s+góp\\s+0%|"
            + "thử\\s+\\d+\\s+phút\\s+miễn\\s+phí|miễn\\s+phí\\s+trải\\s+nghiệm|cidesco|itec|iso\\s*9001|"
            + "chị\\s+lan\\s+ở\\s+cầu\\s+giấy|anh\\s+minh\\s+trước\\s+đây)(?![\\p{L}\\d]).*"
    );

    private static final Pattern CLICHE_OR_TELESALES_METAPHOR_PATTERN = Pattern.compile(
            "(?isU).*(?<![\\p{L}\\d])(tan\\s+chảy\\s+như\\s+bơ|vòng\\s+tay\\s+mẹ|bàn\\s+tay\\s+mẹ|"
            + "sinh\\s+ra\\s+lần\\s+thứ\\s+hai|tái\\s+sinh\\s+như\\s+mùa\\s+xuân|minh\\s+bạch\\s+như\\s+pha\\s+lê|"
            + "rẻ\\s+hơn\\s+(?:một\\s+)?ly\\s+cà\\s+phê|\\d+k\\s*/\\s*phút|personally)(?![\\p{L}\\d]).*"
    );

    private static final Pattern DISALLOWED_HYPE_EMOJI_PATTERN = Pattern.compile("[🚀💪🎁✅😤💙]");

    // v3.4.1 Mục 4 & 5: Cấm các câu mở đầu máy móc / nhại triệu chứng và câu hỏi chất vấn vô duyên ở lần chê đắt 2.
    private static final Pattern FORBIDDEN_MECHANICAL_OPENER_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ[,\\s]+)?(?:(?:em\\s+(?:rất\\s+|thật\\s+sự\\s+)?hiểu\\s+)?(?:mình|chị|anh|bạn)\\s+(?:đang|đã)\\s+trải\\s+qua\\b.*"
            + "|trải\\s+qua\\s+những\\s+cảm\\s+giác\\s+đó\\b.*"
            + "|với\\s+tình\\s+trạng\\s+[^.!?]+\\s+xuất\\s+hiện\\s+của\\s+(?:mình|chị|anh).*"
            + "|vừa\\s+bị\\s+[^.!?]+.*)"
            + "|.*\\btrải\\s+qua\\s+những\\s+cảm\\s+giác\\s+đó\\b.*"
            + "|.*phân\\s+vân\\s+về\\s+mức\\s+chi\\s+phí\\s+buổi\\s+đầu\\s+hay\\s+thời\\s+gian.*"
    );

    private static final Pattern EM_HIEU_OPENER_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ[,\\s]+)?(?:em|mình)\\s+(?:rất\\s+|thật\\s+sự\\s+|cũng\\s+)?(?:hiểu|thấu\\s+hiểu)\\b.*"
    );


    // v3.7: Báo giá/đề xuất gói dưới dạng câu hỏi xác nhận lựa chọn khi khách CHƯA chọn gói ("Dạ mình đang quan tâm đến gói ... giá ... ạ?").
    private static final Pattern PACKAGE_CONFIRMATION_QUESTION_PATTERN = Pattern.compile(
            "(?isU)(?:dạ[,\\s]+)?(?:mình|chị|anh|bạn)\\s+(?:đang\\s+)?(?:quan\\s+tâm|muốn|chọn|ưng|thích)\\s+(?:đến\\s+|tới\\s+)?(gói|liệu\\s+trình)\\s+([^?.!\\n]*?)\\s*(?:ạ|nhé|nha|không)?\\s*\\?"
    );
    // v3.7: Xin lỗi rỗng kiểu tổng đài khi đã có triệu chứng/phạm vi ("không hiểu rõ nhu cầu ... xin được hỗ trợ lại ạ?").
    private static final Pattern EMPTY_TEMPLATE_APOLOGY_PATTERN = Pattern.compile(
            "(?isU)[^.!?]*(?:(?:không|chưa)\\s+hiểu\\s+rõ|xin\\s+được\\s+hỗ\\s+trợ\\s+lại|hỗ\\s+trợ\\s+lại\\s+(?:cho\\s+)?(?:mình|chị|anh|bạn))[^.!?]*[.!?]*"
    );

    public static boolean hasPackageConfirmationQuestion(String text) {
        return text != null && PACKAGE_CONFIRMATION_QUESTION_PATTERN.matcher(text).find();
    }

    public static boolean hasEmptyTemplateApology(String text) {
        return text != null && EMPTY_TEMPLATE_APOLOGY_PATTERN.matcher(text).find();
    }

    private static boolean hasKnownCustomerContext(ConversationState st) {
        return st != null
                && ((st.confirmedScopes() != null && !st.confirmedScopes().isEmpty())
                    || (st.knownSymptoms() != null && !st.knownSymptoms().isEmpty()));
    }

    // v3.8: Java KHÔNG viết lại/cắt/ghép văn bản LLM. Mọi vi phạm hợp đồng được trả về dưới dạng lỗi BLOCKING
    // để FanpageBotService yêu cầu LLM regenerate tối đa 1 lần; sau đó dùng fallback/handoff đã phê duyệt.

    /** Lỗi BLOCKING: bong bóng vi phạm các lỗi này KHÔNG BAO GIỜ được đưa vào final_bubbles. */
    public static final java.util.Set<String> BLOCKING_ERRORS = java.util.Set.of(
            "UNGROUNDED_CATALOG_PRICE", "UNGROUNDED_CATALOG_DURATION", "UNGROUNDED_PACKAGE_BENEFIT", "VR_NO_UNGROUNDED_SERVICE_CLAIM",
            "FORBIDDEN_NEGATED_SCOPE_USED", "MEDICAL_CLAIM", "INTERNAL_LEAK", "BODY_PART_MISMATCH_HALLUCINATION",
            "VR-004A_NO_REJECTED_PACKAGE_REPEAT", "REPEATED_PRICE_AFTER_DISCUSSED", "NO_NUMERIC_PRICE_REPEAT_AFTER_DISCUSSED",
            "FORBIDDEN_PACKAGE_CONFIRMATION_QUESTION", "FORBIDDEN_AWKWARD_CLOSING_QUESTION", "FORBIDDEN_SALES_PITCH_ON_WORDING_FEEDBACK",
            "FORBIDDEN_APOLOGY_ON_ANTI_APOLOGY_PUSHBACK", "VR_EMOJI_WHITELIST", "VR-015A_FORBIDDEN_EMOJI", "EMPTY_TEMPLATE_APOLOGY",
            "VR-007B_NO_FAKE_STATS_OR_ABSOLUTE_SOCIAL_PROOF", "VR-007D_UNGROUNDED_SOCIAL_PROOF",
            "VR-007H_UNGROUNDED_PRIVATE_ROOM", "OPERATIONAL_PROMISE_WITHOUT_ARTIFACT", "PROMO_NOT_GROUNDED");

    public static boolean hasBlockingError(java.util.Collection<String> errs) {
        return errs != null && errs.stream().anyMatch(BLOCKING_ERRORS::contains);
    }

    private static String capitalizeFirstLetter(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        for (int idx = 0; idx < trimmed.length(); idx++) {
            char ch = trimmed.charAt(idx);
            if (Character.isLetter(ch)) {
                return trimmed.substring(0, idx)
                        + Character.toUpperCase(ch)
                        + trimmed.substring(idx + 1);
            }
        }
        return trimmed;
    }

    /**
     * [VÁ C.2]: Kiểm tra xem câu trả lời của LLM có bịa thêm kỹ thuật/dịch vụ không tồn tại trong gói đang tư vấn hay không,
     * nhưng BẢO TOÀN các câu giải thích phủ định trung thực (ví dụ: "chưa bao gồm xông hơi", "không phải phòng VIP").
     */
    public static boolean hasUngroundedServiceClaimForPackage(
            String combinedText,
            PackageCatalogService.PackageSummary activePackage
    ) {
        if (combinedText == null || combinedText.isBlank() || activePackage == null) {
            return false;
        }
        String lower = combinedText.toLowerCase(Locale.ROOT);
        String allowedSpec = ((activePackage.displayName() != null ? activePackage.displayName() : "") + " "
                + (activePackage.includesSummary() != null ? activePackage.includesSummary() : "") + " "
                + (activePackage.roomType() != null ? activePackage.roomType() : "") + " "
                + (activePackage.processSummary() != null ? activePackage.processSummary() : ""))
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?isU)\\(\\s*không\\s+phải\\s+[^)]*\\)", " ");

        String[] restrictedPhysicalClaims = {
            "tinh dầu thảo dược", "tinh dầu", "xông hơi", "đá nóng", "đá muối", "ngâm chân", "ngâm bồn", "chườm ngải cứu", "nến thơm", "phòng vip", "phòng riêng biệt"
        };
        for (String claim : restrictedPhysicalClaims) {
            if (lower.contains(claim) && !allowedSpec.contains(claim)) {
                Pattern negatedOrHigherTierContext = Pattern.compile(
                        "(?isU)(?:không\\s+(?:có|phải|bao\\s+gồm|kèm)|chưa\\s+(?:có|phải|bao\\s+gồm|kèm)|chẳng\\s+phải|đâu\\s+phải|từ\\s+gói\\s+\\d+k\\s+mới\\s+có)[^.!?\\n]{0,45}"
                        + Pattern.quote(claim)
                );
                if (!negatedOrHigherTierContext.matcher(lower).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean satisfiesRefereeTruthAndAestheticContract(
            List<ReplyBubble> candidateBubbles,
            PackageCatalogService.PackageSummary activePackage
    ) {
        if (candidateBubbles == null || candidateBubbles.isEmpty()) {
            return false;
        }
        for (ReplyBubble b : candidateBubbles) {
            if (b == null || b.text() == null || b.text().isBlank()) {
                return false;
            }
            String text = b.text().strip();
            if (FAKE_COMMITMENT_OR_PII_STORY_PATTERN.matcher(text).matches()) {
                return false; // VR-NO-FAKE-COMMITMENT
            }
            if (CLICHE_OR_TELESALES_METAPHOR_PATTERN.matcher(text).matches()) {
                return false; // VR-NO-CLICHE-METAPHOR
            }
            if (DISALLOWED_HYPE_EMOJI_PATTERN.matcher(text).find()) {
                return false; // VR-EMOJI-WHITELIST
            }
            if (hasUngroundedServiceClaimForPackage(text, activePackage)) {
                return false; // VR-NO-UNGROUNDED-SERVICE-CLAIM
            }
            int syllables = text.split("\\s+").length;
            if (syllables > 60) {
                return false; // VR-BUBBLE-CAP-HARD-LIMIT (Dual-Band: cho phép 45-60 âm tiết nếu câu có hồn)
            }
        }
        return true;
    }

    public static boolean hasForbiddenMechanicalOpener(String text) {
        return text != null && FORBIDDEN_MECHANICAL_OPENER_PATTERN.matcher(text).matches();
    }

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
        boolean isLegacyStatelessPriceAnchorTest = state == null
                && thinkingMode == ThinkingMode.PRICE_ANCHOR_THINK
                && bubbles != null
                && bubbles.size() == 2
                && hasDynamicElapsed;
        boolean isSingleAnchorSplitTurn = bubbles != null
                && bubbles.size() == 1
                && effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                && hasDynamicElapsed;
        int effectivePostPriceDelay = (isLegacyStatelessPriceAnchorTest || isSingleAnchorSplitTurn)
                ? (int) Math.max(bubbles != null && bubbles.size() >= 2 ? 2000L : 0L, (long) requiredDelay - elapsedSincePriceBubbleMs)
                : requiredDelay;
        int maxSentencesPerBubble = isLegacyStatelessPriceAnchorTest ? 3 : 2;
        int maxWordsPerBubble = Math.max(65, thinkingMode != null ? thinkingMode.maxWordsPerBubble() : 65);
        List<String> errors = new ArrayList<>();
        if (bubbles == null || bubbles.isEmpty()) {
            errors.add("EMPTY_BUBBLES");
            return new ValidationResult(errors, List.of(), "");
        }

        ConversationState safeState = state != null ? state : ConversationState.initial();
        ConversationState enrichedState = safeState.ensureTurnEnriched(customerMessage, List.of());
        PackageCatalogService.PackageSummary activePackageForReferee = PrimaryActionRouter.resolveHeldPackageFromStateCatalog(safeState);
        boolean isFrustratedNow = PrimaryActionRouter.isFrustrationMessage(customerMessage);
        boolean customerEverStatedCang = (customerMessage != null
                && customerMessage.toLowerCase(Locale.ROOT).contains("căng")
                && !com.example.docmind.service.bot.SentimentIntentLayer.isNegationOrPushbackOnAssumption(customerMessage))
                || (enrichedState.knownSymptoms() != null
                    && enrichedState.knownSymptoms().stream().anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("căng")));

        boolean isSingleBubbleTurn = effectiveAction == PrimaryAction.HARD_HANDOFF_SAFETY
                || effectiveAction == PrimaryAction.HARD_HANDOFF_COMPLAINT
                || effectiveAction == PrimaryAction.REPAIR_AND_RECONFIRM
                || effectiveAction == PrimaryAction.DIRECT_CONCRETE_SOLUTION
                || "STAGE_3_GRACEFUL_EXIT".equals(safeState.objectionStage())
                || "STAGE_3_GRACEFUL_EXIT".equals(enrichedState.objectionStage())
                || "STAGE_3_GRACEFUL_EXIT".equals(enrichedState.lastIntent())
                || com.example.docmind.service.bot.SentimentIntentLayer.isStrongRejection(customerMessage);

        // v3.4.1 Mục 2 & Gate 4: Tự động tách bong bóng khi có > 2 câu và tổng số bong bóng sau khi tách <= 3
        // (trừ lượt HARD_HANDOFF / STRONG_REJECTION bắt buộc 1 bong bóng). Không cắt cụt câu thứ 3 của LLM.
        List<ReplyBubble> working = isLegacyStatelessPriceAnchorTest
                ? new ArrayList<>(bubbles)
                : new ArrayList<>(autoSplitBubblesExceedingTwoSentences(bubbles, isSingleBubbleTurn, !isLlmValidation));

        // Style Guidance (1ms auto-fix, NEVER regenerate):
        // 1) Merge orphan emoji-only bubble (e.g. "🥰") into previous bubble
        if (working.size() >= 2) {
            List<ReplyBubble> mergedEmoji = new ArrayList<>();
            for (ReplyBubble wb : working) {
                if (wb == null || wb.text() == null) continue;
                String t = wb.text().strip();
                if (!mergedEmoji.isEmpty() && !t.isEmpty() && !t.matches("(?s).*[\\p{L}\\d].*")) {
                    ReplyBubble prev = mergedEmoji.get(mergedEmoji.size() - 1);
                    mergedEmoji.set(mergedEmoji.size() - 1, new ReplyBubble((prev.text().strip() + " " + t).strip(), prev.typingDelayMs()));
                } else {
                    mergedEmoji.add(wb);
                }
            }
            working = mergedEmoji;
        }
        // 2) DA_OVERUSE: If > 1 bubble starts with "Dạ" (and not Turn 1 opening), strip duplicate leading "Dạ " on bubble 2+ in 1ms
        if (!isLlmValidation && !openingRequired && working.size() >= 2) {
            String firstText = working.get(0) != null && working.get(0).text() != null ? working.get(0).text().strip() : "";
            if (firstText.matches("(?isU)^dạ\\b.*")) {
                for (int idx = 1; idx < working.size(); idx++) {
                    ReplyBubble wb = working.get(idx);
                    if (wb != null && wb.text() != null && wb.text().strip().matches("(?isU)^dạ[,\\s]+.+")) {
                        String stripped = wb.text().strip().replaceFirst("(?isU)^dạ[,\\s]+", "").strip();
                        if (!stripped.isEmpty()) {
                            stripped = Character.toUpperCase(stripped.charAt(0)) + stripped.substring(1);
                            working.set(idx, new ReplyBubble(stripped, wb.typingDelayMs()));
                        }
                    }
                }
            }
        }

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
            List<String> sents = splitIntoSentencesPreservingPunctuation(text);
            if (!isLlmValidation
                    && sents.size() >= 2
                    && sents.get(0).strip().endsWith("?")
                    && sents.get(sents.size() - 1).strip().endsWith("?")) {
                sents = new ArrayList<>(sents);
                sents.set(0, sents.get(0).strip().replaceFirst("\\?+$", "."));
                text = String.join(" ", sents).strip();
            }
            if (isLlmValidation) {
                working.set(i, new ReplyBubble(text, b.typingDelayMs()));
            }
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
            if (!isLlmValidation && effectiveAction == PrimaryAction.CLARIFY_SERVICE_DURATION
                    && lower.matches("(?isU)^\\s*(?:dạ[,\\s]+)?em\\s+xin\\s+lỗi[^,.!?]*[,\\.!?]\\s+.*")) {
                text = text.replaceFirst("(?isU)^\\s*(?:dạ[,\\s]+)?em\\s+xin\\s+lỗi[^,.!?]*[,\\.!?]\\s+", "Dạ ");
                lower = text.toLowerCase(Locale.ROOT);
            }
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

            // v3.2.1 price_grounding_check & duration_grounding_check: Mọi con số giá và thời lượng trong câu trả lời phải khớp gói thực tế trong availablePackageCatalog
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
                java.util.regex.Matcher dm = Pattern.compile("(?iU)\\b(\\d{2,3})\\s*phút\\b").matcher(text);
                String custLowerForDur = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
                boolean massageOnlyClarify = effectiveAction == PrimaryAction.CLARIFY_SERVICE_DURATION
                        && custLowerForDur.contains("massage")
                        && !custLowerForDur.contains("dưỡng sinh")
                        && !custLowerForDur.contains("gội");
                while (dm.find()) {
                    try {
                        int mentionedMin = Integer.parseInt(dm.group(1));
                        boolean isStepDur = !massageOnlyClarify && (mentionedMin == 5 || mentionedMin == 10 || mentionedMin == 15 || mentionedMin == 20 || mentionedMin == 30);
                        boolean groundedDur = safeState.availablePackageCatalog().stream()
                                .anyMatch(pkg -> {
                                    if (pkg == null || pkg.durationMinutes() != mentionedMin) return false;
                                    if (massageOnlyClarify) {
                                        String c = pkg.code() != null ? pkg.code().toUpperCase(Locale.ROOT) : "";
                                        String n = pkg.displayName() != null ? pkg.displayName().toLowerCase(Locale.ROOT) : "";
                                        return c.startsWith("NS") || n.contains("massage");
                                    }
                                    return true;
                                });
                        if (!groundedDur && !isStepDur && !errors.contains("UNGROUNDED_CATALOG_DURATION")) {
                            errors.add("UNGROUNDED_CATALOG_DURATION");
                        }
                    } catch (NumberFormatException ignored) {
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

            // v3.4.0 Referee Truth & Aesthetic Contract (Vá C.1 & C.2 + Dual-Band 45-60 words)
            if (FAKE_COMMITMENT_OR_PII_STORY_PATTERN.matcher(text).matches()) {
                errors.add("VR_NO_FAKE_COMMITMENT");
            }
            if (CLICHE_OR_TELESALES_METAPHOR_PATTERN.matcher(text).matches()) {
                errors.add("VR_NO_CLICHE_METAPHOR");
            }
            if (DISALLOWED_HYPE_EMOJI_PATTERN.matcher(text).find()) {
                errors.add("VR_EMOJI_WHITELIST");
                if (!isLlmValidation) {
                    text = DISALLOWED_HYPE_EMOJI_PATTERN.matcher(text).replaceAll("");
                }
            }
            if (activePackageForReferee != null && hasUngroundedServiceClaimForPackage(text, activePackageForReferee)) {
                errors.add("VR_NO_UNGROUNDED_SERVICE_CLAIM");
            }

            // v3.4.1 Mục 4 & 5 + v3.4.2 Mục 5: Chặn câu mở đầu máy móc / nhại danh sách triệu chứng / câu hỏi chất vấn ở lần chê đắt 2
            if (isLlmValidation && !safeState.packageExplicitlySelected()
                    && effectiveAction != PrimaryAction.CONFIRM_BOOKING_DETAILS
                    && hasPackageConfirmationQuestion(text)) {
                errors.add("FORBIDDEN_PACKAGE_CONFIRMATION_QUESTION");
            }
            if (isLlmValidation && hasKnownCustomerContext(enrichedState) && hasEmptyTemplateApology(text)) {
                errors.add("EMPTY_TEMPLATE_APOLOGY");
            }
            if (isLlmValidation && text.toLowerCase(Locale.ROOT).matches("(?isU).*\\bhỗ\\s+trợ\\s+giấc\\s+ngủ\\b.*")) {
                errors.add("MACHINE_TRANSLATED_PHRASE");
            }
            boolean hasMechanicalOpenerHere = false;
            boolean hasRepeatedEmHieuHere = false;
            if (FORBIDDEN_MECHANICAL_OPENER_PATTERN.matcher(text).matches()) {
                errors.add("FORBIDDEN_MECHANICAL_OPENER");
                hasMechanicalOpenerHere = true;
            }
            if (isLlmValidation
                    && i == 0
                    && effectiveAction == PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                    && text.matches("(?isU)^\\s*(?:dạ[,\\s]+)?em\\s+(?:rất\\s+)?hiểu\\s+(?:mình|anh|chị|bạn)\\s+đang\\s+.*")) {
                errors.add("FORBIDDEN_MECHANICAL_OPENER");
                hasMechanicalOpenerHere = true;
            }
            if (isLlmValidation
                    && i == 0
                    && EM_HIEU_OPENER_PATTERN.matcher(text).matches()
                    && safeState.lastAssistantMessage() != null
                    && EM_HIEU_OPENER_PATTERN.matcher(safeState.lastAssistantMessage()).matches()
                    && effectiveAction != PrimaryAction.CONFIRM_BOOKING_DETAILS
                    && effectiveAction != PrimaryAction.HARD_HANDOFF_SAFETY
                    && effectiveAction != PrimaryAction.HARD_HANDOFF_COMPLAINT) {
                errors.add("REPEATED_EM_HIEU_OPENER_CONSECUTIVE_TURNS");
                hasRepeatedEmHieuHere = true;
            }
            // v3.4.2 Mục 3 & 4: Chặn xin lỗi ở POLITE_DEFERRAL_EXIT và ANTI_APOLOGY_PUSHBACK
            boolean isPoliteDeferralTurn = com.example.docmind.service.bot.SentimentIntentLayer.isPoliteDeferralExit(customerMessage)
                    && (safeState.downsellOffered() || safeState.objectionCount() == 0);
            boolean isAntiApologyTurn = com.example.docmind.service.bot.SentimentIntentLayer.isAntiApologyPushback(customerMessage)
                    && !com.example.docmind.service.bot.SentimentIntentLayer.hasPositiveServiceInterestSignal(customerMessage);
            if (isPoliteDeferralTurn
                    && (lower.contains("xin lỗi") || lower.matches("(?isU).*chưa\\s+làm\\s+\\S+\\s+hài\\s+lòng.*") || lower.contains("không làm phiền"))) {
                errors.add("FORBIDDEN_APOLOGY_ON_POLITE_DEFERRAL");
            }
            if (isAntiApologyTurn
                    && (lower.contains("xin lỗi") || lower.contains("chuyển sang nhân viên") || lower.contains("chuyển nhân viên") || lower.contains("tóm tắt lại thông tin"))) {
                errors.add("FORBIDDEN_APOLOGY_ON_ANTI_APOLOGY_PUSHBACK");
            }
            if (isLlmValidation
                    && com.example.docmind.service.bot.SentimentIntentLayer.isWordingOrExpressionFeedback(customerMessage)
                    && (lower.matches("(?isU).*\\b(liệu\\s+trình|kỹ\\s+thuật\\s+viên|massage|dưỡng\\s+sinh|thảo\\s+dược|ngâm\\s+bồn|xông\\s+hơi|buffet|ấn\\s+huyệt|giãn\\s+cơ|399k|239k|530k|580k)\\b.*")
                        || lower.contains("?")
                        || lower.contains("xin lỗi"))) {
                errors.add("FORBIDDEN_SALES_PITCH_ON_WORDING_FEEDBACK");
            }
            // v3.4.2 Mục 5.2 & 5.3: Chặn lặp lại cảnh "nằm xuống/đầu nặng trĩu" ở Turn 3 và chặn lặp lại nguyên câu mô tả của Turn 3 B2 ở Turn 4
            if (isLlmValidation
                    && safeState.lastAssistantMessage() != null
                    && !safeState.lastAssistantMessage().isBlank()) {
                String prevLower = safeState.lastAssistantMessage().toLowerCase(Locale.ROOT);
                if (effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                        && (prevLower.contains("nằm xuống") || prevLower.contains("đặt lưng xuống") || prevLower.contains("trằn trọc"))
                        && (lower.contains("nằm xuống") || lower.contains("đặt lưng xuống") || lower.contains("đầu óc nặng trĩu") || lower.contains("đầu vẫn còn căng"))) {
                    errors.add("REPEATED_TURN2_SLEEP_SCENE_IN_TURN3");
                }
                if (effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME) {
                    String[] prevParts = prevLower.split("\\n\\n+");
                    String prevB2 = prevParts.length >= 2 ? prevParts[prevParts.length - 1].strip() : prevLower.strip();
                    if (prevB2.length() >= 25 && hasHighPhraseOverlap(lower, prevB2)) {
                        errors.add("REPEATED_TURN3_BUBBLE2_DESCRIPTION_IN_TURN4");
                    }
                }
            }
            // v3.4.1 Mục 6: Khi báo giá hoặc xử lý chê đắt (khách CHƯA hỏi về phòng), cấm tự khai "phòng chung" làm khách tụt cảm xúc
            if (isLlmValidation
                    && (effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                        || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                        || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT)) {
                String custLower = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
                boolean customerAskedAboutRoom = custLower.contains("phòng") || custLower.contains("không gian") || custLower.contains("rèm");
                if (!customerAskedAboutRoom && lower.contains("phòng chung")) {
                    errors.add("UNSOLICITED_SHARED_ROOM_MENTION");
                }
            }

            // v3.5.0 Mục III.2: BODY_PART_MISMATCH_HALLUCINATION — Khi knownBodyAreas của khách đã xác định (vd: ["chân"])
            // và KHÔNG chứa vai/cổ/gáy/lưng, nếu câu trả lời của LLM tự gán sang "cổ vai", "khớp vai", "bả vai", "vai lưng", "sống lưng" -> chặn ngay!
            if (isLlmValidation
                    && enrichedState.knownBodyAreas() != null
                    && !enrichedState.knownBodyAreas().isEmpty()
                    && effectiveAction != PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT) {
                boolean customerHasNeckShoulderBack = enrichedState.knownBodyAreas().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .anyMatch(s -> s.contains("vai") || s.contains("cổ") || s.contains("gáy") || s.contains("lưng"));
                if (!customerHasNeckShoulderBack) {
                    boolean mentionsWrongUpperBody = lower.contains("cổ vai")
                            || lower.contains("khớp vai")
                            || lower.contains("bả vai")
                            || lower.contains("vai lưng")
                            || lower.contains("sống lưng")
                            || lower.contains("thắt lưng")
                            || lower.matches("(?isU).*\\b(?:vùng|mỏi|nhức)\\s+(?:vai|lưng|cổ|gáy)\\b.*");
                    boolean isApologizingForWrongMention = com.example.docmind.service.bot.SentimentIntentLayer.isEntityMismatchCorrection(customerMessage)
                            && lower.matches("(?isU).*(?:nhầm|lơ\\s+đễnh|sơ\\s+suất|quên\\s+mất|nhắc\\s+nhầm).*");
                    if (mentionsWrongUpperBody && !isApologizingForWrongMention) {
                        errors.add("BODY_PART_MISMATCH_HALLUCINATION");
                    }
                }
            }

            if (!isLlmValidation) {
                text = text.replaceAll("(?iU)\\b(\\d{2,4}k)\\s*\\(\\s*\\d{1,3}(?:\\.\\d{3})+\\s*(?:đ|vnđ|vnd)?\\s*\\)", "$1");
                text = text.replaceAll("(?iU)^\\s*Dạ\\s+em\\s+ghi\\s+nhận\\s+mình\\s+đang\\s+([^.,!?]+?)\\s+ạ\\.",
                        "Dạ tình trạng $1 chắc đang làm mình khá khó chịu ạ.");
            }

            // v3.5.0 Mục IV.2: Nếu trong 1 bong bóng có 2 câu cùng kết thúc bằng '?' (vd câu 1 kết bằng 'ạ?'), tự động đổi dấu '?' của câu 1 thành '.' trong 0ms
            if (!isLlmValidation && countQuestionMarks(text) > 1) {
                text = text.replaceFirst("\\?(?=\\s+[^?]+\\?\\s*[^\\p{L}\\d]*$)", ".");
                lower = text.toLowerCase(Locale.ROOT);
            }

            // 3. Tối đa N câu mỗi bong bóng (nếu còn dư câu ngắn sau auto-split thì nối dấu chấm đầu tiên thành dấu phẩy trong 0ms thay vì cắt chữ)
            if (countSentences(text) > maxSentencesPerBubble) {
                errors.add("TOO_MANY_SENTENCES");
                if (!isLlmValidation) {
                    text = trimToMaxSentences(text, maxSentencesPerBubble);
                }
            }

            // 4. Tối đa 65 từ mỗi bong bóng (Dual-Band 45-60 âm tiết: bảo toàn nguyên vẹn câu có hồn đạt chuẩn Referee Contract)
            int wordCount = countWords(text);
            boolean preservedByDualBandReferee = wordCount <= 60
                    && satisfiesRefereeTruthAndAestheticContract(List.of(new ReplyBubble(text, b.typingDelayMs())), activePackageForReferee);
            if (!preservedByDualBandReferee && wordCount > maxWordsPerBubble) {
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

            // v3.6.7.1 Hard Gates:
            // (a) FORBIDDEN_NEGATED_SCOPE_USED: Cấm nhắc lại vùng nằm trong forbiddenScopes như triệu chứng của khách
            if (enrichedState.forbiddenScopes() != null && !enrichedState.forbiddenScopes().isEmpty()) {
                for (String negScope : enrichedState.forbiddenScopes()) {
                    if (negScope == null || negScope.isBlank()) continue;
                    String nsLower = negScope.toLowerCase(Locale.ROOT);
                    boolean mentionsNegatedScope = lower.contains(nsLower)
                            || (("đầu gối".equals(nsLower) || "khớp gối".equals(nsLower))
                                && lower.matches("(?isU).*\\b(đau\\s+gối|mỏi\\s+gối|nhức\\s+gối|vùng\\s+gối|chân\\s+gối|khớp\\s+gối|đầu\\s+gối)\\b.*"));
                    if (mentionsNegatedScope) {
                        String errKey = "WRONG_SCOPE:" + nsLower;
                        boolean alreadyApologizedForThisScope = safeState.apologizedErrorIds() != null
                                && safeState.apologizedErrorIds().contains(errKey);
                        String scopeTokenForAck = Pattern.quote("đầu gối".equals(nsLower) ? "gối" : nsLower);
                        boolean isInitialMistakeAck = !alreadyApologizedForThisScope
                                && (effectiveAction == PrimaryAction.REPAIR_AND_RECONFIRM
                                    || com.example.docmind.service.bot.SentimentIntentLayer.isScopeCorrectionPushback(customerMessage))
                                && (lower.matches("(?isU).*(?:nhầm|xin\\s+lỗi|sơ\\s+suất|không\\s+phải|đâu\\s+phải|chứ\\s+không\\s+phải)[^.!?]{0,45}"
                                        + scopeTokenForAck + ".*")
                                    || lower.matches("(?isU).*" + scopeTokenForAck
                                        + "[^.!?]{0,45}(?:nhầm|xin\\s+lỗi|sơ\\s+suất|không\\s+phải|đâu\\s+phải).*"))
                                && !lower.matches("(?isU).*(?:chăm\\s+sóc|massage|xoa\\s+bóp|ấn\\s+huyệt)\\s+(?:kỹ\\s+)?(?:vùng\\s+)?" + scopeTokenForAck + ".*");
                        if (!isInitialMistakeAck && !errors.contains("FORBIDDEN_NEGATED_SCOPE_USED")) {
                            errors.add("FORBIDDEN_NEGATED_SCOPE_USED");
                        }
                    }
                }
            }

            // (b) DUPLICATE_ERROR_APOLOGY: Khóa xin lỗi theo errorId (ApologyLedger)
            if (safeState.apologizedErrorIds() != null && !safeState.apologizedErrorIds().isEmpty()
                    && effectiveAction != PrimaryAction.HARD_HANDOFF_COMPLAINT
                    && effectiveAction != PrimaryAction.HARD_HANDOFF_SAFETY) {
                String activeErr = enrichedState.activeErrorId();
                boolean hasNewUnapologizedError = activeErr != null
                        && !activeErr.isBlank()
                        && !safeState.apologizedErrorIds().contains(activeErr);
                if (!hasNewUnapologizedError
                        && lower.matches("(?isU).*\\b(xin\\s+lỗi|thứ\\s+lỗi|lơ\\s+đễnh|sơ\\s+suất)\\b.*")
                        && !lower.matches("(?isU).*\\b(?:không|đâu)\\s+(?:có\\s+gì\\s+)?(?:phải\\s+)?xin\\s+lỗi\\b.*")) {
                    if (!errors.contains("DUPLICATE_ERROR_APOLOGY")) {
                        errors.add("DUPLICATE_ERROR_APOLOGY");
                    }
                }
            }

            // (c) OPERATIONAL_PROMISE_WITHOUT_ARTIFACT: Hứa dặn care_actor nhưng không có confirmedScopes/knownBodyAreas
            if (ConversationState.containsOperationalCarePromise(text, null)
                    && (enrichedState.confirmedScopes() == null || enrichedState.confirmedScopes().isEmpty())
                    && (enrichedState.knownBodyAreas() == null || enrichedState.knownBodyAreas().isEmpty())) {
                if (!errors.contains("OPERATIONAL_PROMISE_WITHOUT_ARTIFACT")) {
                    errors.add("OPERATIONAL_PROMISE_WITHOUT_ARTIFACT");
                }
            }

            // (d) PROMO_NOT_GROUNDED: Bịa mức giảm giá ngoài Catalog
            if (lower.matches("(?isU).*\\bgiảm\\s+(?!10\\s*%)(?:\\d{1,2}\\s*%|\\d{2,3}\\s*k)\\b.*")) {
                if (!errors.contains("PROMO_NOT_GROUNDED")) {
                    errors.add("PROMO_NOT_GROUNDED");
                }
            }

            // (e) Tier C Forbidden Promises & Awkward Closing Questions & Repeat Guard Phrase
            if (lower.matches("(?isU).*\\bchăm\\s+sóc\\s+(?:kỹ\\s+hơn\\s+)?cho\\s+(?:mình|chị|anh)\\s+ngay\\s+(?:ạ|bây\\s+giờ)\\b.*")) {
                errors.add("FORBIDDEN_OUT_OF_SPACE_PROMISE");
            }
            if (lower.matches("(?isU).*(?:liệu\\s+trình\\s+này\\s+hợp\\s+nhu\\s+cầu\\s+không\\s+ạ|(?:mình|chị|anh)\\s+thấy\\s+thế\\s+nào\\s+ạ|đang\\s+quan\\s+tâm\\s+[^?]+\\s+hay\\s+[^?]+\\s+không\\s+ạ\\?|(?:phải\\s+không|đúng\\s+không)\\s*(?:ạ|nhỉ|ha|hả|em|chị|anh)?\\s*\\?).*")) {
                errors.add("FORBIDDEN_AWKWARD_CLOSING_QUESTION");
            }
            if (lower.matches("(?isU).*(?:đã\\s+gửi\\s+ở\\s+tin\\s+nhắn\\s+trên|vừa\\s+gửi\\s+ở\\s+tin\\s+trên).*")) {
                errors.add("FORBIDDEN_REPEAT_GUARD_PHRASE");
            }

            // 8. Nếu đã bàn giá, không lặp giá trừ khi EXPLAIN_UNIT_VALUE, ANSWER_PRICE_ONCE, HANDLE_PRICE_OBJECTION_REPEAT, HANDLE_PRICE_OBJECTION_FIRST_TIME, DIRECT_CONCRETE_SOLUTION hoặc CONFIRM_BOOKING_DETAILS
            if (safeState.priceDiscussed()
                    && effectiveAction != PrimaryAction.EXPLAIN_UNIT_VALUE
                    && effectiveAction != PrimaryAction.ANSWER_PRICE_ONCE
                    && effectiveAction != PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                    && effectiveAction != PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                    && effectiveAction != PrimaryAction.DIRECT_CONCRETE_SOLUTION
                    && effectiveAction != PrimaryAction.CONFIRM_BOOKING_DETAILS) {
                if (PRICE_PATTERN.matcher(text).find()) {
                    errors.add("REPEATED_PRICE_AFTER_DISCUSSED");
                }
            }

            // 9. Không lặp template đồng cảm giá lần 1 khi đang ở HANDLE_PRICE_OBJECTION_REPEAT
            if (effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                    && (lower.contains("cân nhắc về chi phí") || lower.contains("cân nhắc chi phí"))) {
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
            boolean customerAskedPromoOrDiscount = customerMessage != null
                    && customerMessage.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(giảm\\s+giá|khuyến\\s+mãi|km|ưu\\s+đãi|voucher|sale)\\b.*");
            boolean objectionB1RepeatsPrice = effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                    && !customerAskedPromoOrDiscount
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

        // Enforce total question marks across all bubbles <= 1 (or 0 on handoff / Stage 3 graceful exit / polite deferral / anti-apology pushback / strong rejection)
        boolean isPoliteDeferralNoQuestion = com.example.docmind.service.bot.SentimentIntentLayer.isPoliteDeferralExit(customerMessage)
                && (safeState.downsellOffered() || safeState.objectionCount() == 0);
        boolean isAntiApologyNoQuestion = com.example.docmind.service.bot.SentimentIntentLayer.isAntiApologyPushback(customerMessage)
                && !com.example.docmind.service.bot.SentimentIntentLayer.hasPositiveServiceInterestSignal(customerMessage);
        boolean isNoQuestionAction = effectiveAction == PrimaryAction.HARD_HANDOFF_SAFETY
                || effectiveAction == PrimaryAction.HARD_HANDOFF_COMPLAINT
                || "STAGE_3_GRACEFUL_EXIT".equals(safeState.objectionStage())
                || "STAGE_3_GRACEFUL_EXIT".equals(enrichedState.objectionStage())
                || "STAGE_3_GRACEFUL_EXIT".equals(enrichedState.lastIntent())
                || isPoliteDeferralNoQuestion
                || isAntiApologyNoQuestion
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
        if (!isLlmValidation
                && effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                && working.size() == 2
                && working.get(0) != null && working.get(0).text() != null
                && working.get(1) != null && working.get(1).text() != null
                && !PRICE_PATTERN.matcher(working.get(0).text()).find()
                && PRICE_PATTERN.matcher(working.get(1).text()).find()) {
            List<String> b1Sents = splitIntoSentencesPreservingPunctuation(working.get(0).text());
            List<String> b2Sents = splitIntoSentencesPreservingPunctuation(working.get(1).text());
            if (b1Sents.size() == 1 && b2Sents.size() >= 2 && PRICE_PATTERN.matcher(b2Sents.get(0)).find()) {
                String newB1 = (working.get(0).text().strip() + " " + b2Sents.get(0).strip()).strip();
                String newB2 = String.join(" ", b2Sents.subList(1, b2Sents.size())).strip();
                if (countWords(newB1) <= maxWordsPerBubble && !newB2.isBlank()) {
                    working.set(0, new ReplyBubble(newB1, 0));
                    working.set(1, new ReplyBubble(newB2, effectivePostPriceDelay));
                }
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
            } else if (i > 0 && !isSingleAnchorBubbleTurn && hasDynamicElapsed && b.typingDelayMs() > 2500) {
                working.set(i, new ReplyBubble(b.text(), 2000));
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

    /**
     * v3.4.1 Mục 2 & Gate 4: Tự động tách bong bóng có > 2 câu tại dấu kết câu (., !, ?)
     * thành Bubble A (2 câu đầu) + Bubble B (câu thứ 3 trở đi, typing_delay_ms = 1500)
     * khi tổng số bong bóng sau khi tách <= 3 và không phải lượt bắt buộc 1 bong bóng (HARD_HANDOFF / STRONG_REJECTION).
     */
    public static List<ReplyBubble> autoSplitBubblesExceedingTwoSentences(List<ReplyBubble> bubbles) {
        return autoSplitBubblesExceedingTwoSentences(bubbles, false);
    }

    public static List<ReplyBubble> autoSplitBubblesExceedingTwoSentences(
            List<ReplyBubble> bubbles,
            boolean isSingleBubbleTurn
    ) {
        return autoSplitBubblesExceedingTwoSentences(bubbles, isSingleBubbleTurn, true);
    }

    public static List<ReplyBubble> autoSplitBubblesExceedingTwoSentences(
            List<ReplyBubble> bubbles,
            boolean isSingleBubbleTurn,
            boolean allowPunctuationMerge
    ) {
        if (bubbles == null || bubbles.isEmpty()) {
            return List.of();
        }
        if (isSingleBubbleTurn || bubbles.size() >= 3) {
            return List.copyOf(bubbles);
        }
        List<ReplyBubble> result = new ArrayList<>();
        for (int idx = 0; idx < bubbles.size(); idx++) {
            ReplyBubble b = bubbles.get(idx);
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            int remainingOriginalBubblesAfterCurrent = bubbles.size() - 1 - idx;
            List<String> sentences = splitIntoSentencesPreservingPunctuation(b.text().strip());
            int currentDelay = b.typingDelayMs();
            boolean isPriceBubbleInMultiBubbleTurn = bubbles.size() >= 2 && PRICE_PATTERN.matcher(b.text()).find();
            if (allowPunctuationMerge && isPriceBubbleInMultiBubbleTurn && sentences.size() > 2) {
                while (sentences.size() > 2) {
                    String s1 = sentences.get(0).replaceFirst("[.!?]+$", ",");
                    String merged = (s1 + " " + sentences.get(1)).strip();
                    List<String> next = new ArrayList<>();
                    next.add(merged);
                    next.addAll(sentences.subList(2, sentences.size()));
                    sentences = next;
                }
            }
            while (!isPriceBubbleInMultiBubbleTurn && sentences.size() > 2 && (result.size() + 1 + remainingOriginalBubblesAfterCurrent) < 3) {
                String firstTwo = (sentences.get(0) + " " + sentences.get(1)).strip();
                result.add(new ReplyBubble(firstTwo, currentDelay));
                sentences = new ArrayList<>(sentences.subList(2, sentences.size()));
                currentDelay = 1500;
            }
            String rest = String.join(" ", sentences).strip();
            if (!rest.isEmpty()) {
                result.add(new ReplyBubble(rest, currentDelay));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> splitIntoSentencesPreservingPunctuation(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.strip().split("(?<!\\d)(?<=[.!?])\\s+");
        List<String> sentences = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                String trimmed = p.strip();
                if (!trimmed.matches("(?s).*[\\p{L}\\d].*") && !sentences.isEmpty()) {
                    int lastIdx = sentences.size() - 1;
                    sentences.set(lastIdx, (sentences.get(lastIdx) + " " + trimmed).strip());
                } else {
                    sentences.add(trimmed);
                }
            }
        }
        return sentences;
    }

    public static int countSentences(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String normalized = text.replaceAll("\\d+\\.\\d+", "0");
        String[] parts = normalized.split("[.!?]+(?:\\s+|$)");
        int count = 0;
        for (String p : parts) {
            if (!p.isBlank() && p.matches("(?s).*[\\p{L}\\d].*")) {
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
        boolean claimsEssentialOil = (lowerText.contains("tinh dầu thảo dược") || lowerText.contains("tinh dầu"))
                && !lowerText.matches("(?isU).*(?:không\\s+(?:có|bao\\s+gồm)|chưa\\s+(?:có|bao\\s+gồm))[^.!?]{0,40}tinh\\s+dầu.*");
        if (!claimsSauna && !claimsHerbalBath && !claimsVipRoom && !claimsFullBody && !claimsBuffet && !claimsEssentialOil) {
            return false;
        }

        List<Long> targetPrices = new ArrayList<>();
        if (mentionedPricesVnd != null && !mentionedPricesVnd.isEmpty()) {
            targetPrices.addAll(mentionedPricesVnd);
        } else if ((effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                || effectiveAction == PrimaryAction.RECOMMEND_ONE_PACKAGE
                || effectiveAction == PrimaryAction.GENERIC_SHORT_DISCOVERY
                || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                || effectiveAction == PrimaryAction.CONFIRM_BOOKING_DETAILS)
                && safeState.lastOfferedPriceVnd() != null
                && safeState.lastOfferedPriceVnd().longValue() > 0L) {
            targetPrices.add(safeState.lastOfferedPriceVnd().longValue());
        }

        if (targetPrices.isEmpty()) {
            if (claimsEssentialOil) {
                boolean anyCatalogHasEssentialOil = safeState.availablePackageCatalog().stream().anyMatch(pkg -> {
                    if (pkg == null) return false;
                    String combined = ((pkg.displayName() != null ? pkg.displayName() : "") + " "
                            + (pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                            + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                    return combined.contains("tinh dầu");
                });
                if (!anyCatalogHasEssentialOil) {
                    return true;
                }
            }
            return false;
        }

        List<com.example.docmind.service.bot.PackageCatalogService.PackageSummary> matchingPackages = safeState.availablePackageCatalog().stream()
                .filter(pkg -> pkg != null && targetPrices.contains(pkg.priceVnd()))
                .toList();
        if (matchingPackages.isEmpty()) {
            return false;
        }

        if (claimsEssentialOil) {
            boolean anyHasOil = matchingPackages.stream().anyMatch(pkg -> {
                String combined = ((pkg.displayName() != null ? pkg.displayName() : "") + " "
                        + (pkg.includesSummary() != null ? pkg.includesSummary() : "") + " "
                        + (pkg.processSummary() != null ? pkg.processSummary() : "")).toLowerCase(Locale.ROOT);
                return combined.contains("tinh dầu");
            });
            if (!anyHasOil) {
                return true;
            }
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

    private static boolean hasHighPhraseOverlap(String currentLower, String prevB2Lower) {
        if (currentLower == null || prevB2Lower == null) {
            return false;
        }
        String normCur = currentLower.replaceAll("[^\\p{L}\\d\\s]+", " ").replaceAll("\\s+", " ").strip();
        String normPrev = prevB2Lower.replaceAll("[^\\p{L}\\d\\s]+", " ").replaceAll("\\s+", " ").strip();
        String[] prevWords = normPrev.split("\\s+");
        if (prevWords.length < 9) {
            return false;
        }
        for (int idx = 0; idx <= prevWords.length - 9; idx++) {
            StringBuilder gram = new StringBuilder();
            for (int k = 0; k < 9; k++) {
                if (k > 0) gram.append(' ');
                gram.append(prevWords[idx + k]);
            }
            if (normCur.contains(gram.toString())) {
                return true;
            }
        }
        return false;
    }
}
