package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.entity.LearnedRuleEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bộ kiểm soát Ngân sách Prompt (Prompt Budget Governance v3.2.1-FINAL).
 *
 * <p>Tách biệt rõ 3 tầng:
 * <ul>
 *   <li>{@code CORE_ALWAYS_ON}: Prompt nhân cách & hợp đồng JSON cốt lõi (mục tiêu &lt;= 900 ký tự, trần cứng &lt;= 1200 ký tự).</li>
 *   <li>{@code GLOBAL_CRITICAL_RULES}: Chỉ giữ các quy tắc an toàn bất biến ({@code SAFE-*}, {@code always_on = true}), tổng dung lượng &lt;= 900/1200 ký tự.</li>
 *   <li>{@code CONTEXTUAL_RULES_ON_DEMAND}: Toàn bộ các quy tắc tình huống ({@code PB-*}), {@code always_on = false}, chỉ nạp tối đa 1 quy tắc khớp đúng {@code intent + state} của lượt hiện tại.</li>
 * </ul>
 */
public final class PromptBudgetGuard {

    public static final int HARD_LIMIT_ALWAYS_ON_CHARS = 1200;
    public static final int TARGET_CORE_CHARS = 900;
    public static final int MAX_CONTEXTUAL_RULES_PER_TURN = 1;
    public static final int MAX_SKILLS_PER_TURN = 1;

    private PromptBudgetGuard() {
    }

    public record BudgetCheckResult(
            int alwaysOnRuleChars,
            int corePromptChars,
            int hardLimitChars,
            boolean withinBudget,
            List<LearnedRuleDto> globalCriticalRules,
            List<LearnedRuleDto> contextualCandidates
    ) {
    }

    /**
     * Kiểm tra một mã quy tắc có phải quy tắc an toàn bất biến (Global Critical) được phép bật {@code always_on = true} hay không.
     * Mọi quy tắc tình huống bắt đầu bằng {@code PB-} bắt buộc là {@code always_on = false} (Contextual On-Demand).
     */
    public static boolean isGlobalCriticalRuleCode(String ruleCode, String category) {
        if (ruleCode != null && !ruleCode.isBlank()) {
            String upper = ruleCode.strip().toUpperCase(Locale.ROOT);
            if (upper.startsWith("PB-")) {
                return false;
            }
            if (upper.startsWith("SAFE-") || upper.startsWith("CRIT-") || upper.startsWith("SEC-")) {
                return true;
            }
        }
        if (category != null && !category.isBlank()) {
            String catUpper = category.strip().toUpperCase(Locale.ROOT);
            if ("SAFETY_MEDICAL".equals(catUpper) && (ruleCode == null || !ruleCode.strip().toUpperCase(Locale.ROOT).startsWith("PB-"))) {
                return true;
            }
        }
        return false;
    }

    public static int computeRuleCharCost(LearnedRuleDto rule) {
        if (rule == null) {
            return 0;
        }
        int cueLen = rule.triggerCondition() != null ? rule.triggerCondition().length() : 0;
        int ruleLen = rule.generalizedRule() != null ? rule.generalizedRule().length() : 0;
        int evLen = rule.rootCause() != null ? rule.rootCause().length() : 0;
        return cueLen + ruleLen + evLen;
    }

    public static int computeEntityCharCost(LearnedRuleEntity entity) {
        if (entity == null) {
            return 0;
        }
        int cueLen = entity.getTriggerCondition() != null ? entity.getTriggerCondition().length() : 0;
        int ruleLen = entity.getGeneralizedRule() != null ? entity.getGeneralizedRule().length() : 0;
        int evLen = entity.getRootCause() != null ? entity.getRootCause().length() : 0;
        return cueLen + ruleLen + evLen;
    }

    public static List<LearnedRuleDto> validateAlwaysOnRules(List<LearnedRuleDto> rules) {
        return validateAlwaysOnRules(rules, "").globalCriticalRules();
    }

    public static BudgetCheckResult validateAlwaysOnRules(List<LearnedRuleDto> rules, String activePromptCompact) {
        int coreChars = activePromptCompact != null ? activePromptCompact.strip().length() : 0;
        List<LearnedRuleDto> critical = new ArrayList<>();
        List<LearnedRuleDto> contextual = new ArrayList<>();
        int usedAlwaysOnChars = 0;

        if (rules != null) {
            for (LearnedRuleDto r : rules) {
                if (r == null || !r.active()) {
                    continue;
                }
                boolean isCritical = r.alwaysOn() && isGlobalCriticalRuleCode(r.ruleCode(), r.category());
                if (isCritical) {
                    int cost = computeRuleCharCost(r);
                    if (usedAlwaysOnChars + cost <= HARD_LIMIT_ALWAYS_ON_CHARS) {
                        critical.add(r);
                        usedAlwaysOnChars += cost;
                    } else {
                        contextual.add(r);
                    }
                } else {
                    contextual.add(r);
                }
            }
        }

        boolean withinBudget = usedAlwaysOnChars <= HARD_LIMIT_ALWAYS_ON_CHARS
                && coreChars <= HARD_LIMIT_ALWAYS_ON_CHARS;
        return new BudgetCheckResult(
                usedAlwaysOnChars,
                coreChars,
                HARD_LIMIT_ALWAYS_ON_CHARS,
                withinBudget,
                List.copyOf(critical),
                List.copyOf(contextual)
        );
    }

    public static LearnedRuleDto selectScopedContextualRule(
            List<LearnedRuleDto> candidates,
            String detectedIntent,
            String customerMessage
    ) {
        return selectScopedContextualRule(candidates, detectedIntent, null, customerMessage).stream()
                .findFirst()
                .orElse(null);
    }

    public static LearnedRuleDto selectScopedContextualRule(
            List<LearnedRuleDto> candidates,
            String detectedIntent,
            ConversationState state,
            String customerMessage,
            boolean singleResult
    ) {
        return selectScopedContextualRule(candidates, detectedIntent, state, customerMessage).stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Lọc tối đa 1 quy tắc tình huống (Contextual On-Demand) khớp chặt với {@code detectedIntent} và {@link ConversationState}.
     * Tuyệt đối cấm nạp {@code PRICE_OBJECTION} ở lượt 1 khi khách chỉ mới hỏi {@code "Giá"}!
     */
    public static List<LearnedRuleDto> selectScopedContextualRule(
            List<LearnedRuleDto> candidates,
            String detectedIntent,
            ConversationState state,
            String customerMessage
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String intent = detectedIntent != null ? detectedIntent.strip().toUpperCase(Locale.ROOT) : "";
        boolean isBarePriceInquiry = "PRICE_INQUIRY".equals(intent)
                || "SERVICE_FAMILY_SELECTION".equals(intent)
                || (!SentimentIntentLayer.isPriceObjection(customerMessage)
                    && !SentimentIntentLayer.isExplicitDownsellRequest(customerMessage)
                    && !SentimentIntentLayer.isPackageRejectionMessage(customerMessage)
                    && !SentimentIntentLayer.isTrialAcceptanceMessage(customerMessage)
                    && !SentimentIntentLayer.isEntityMismatchCorrection(customerMessage)
                    && PrimaryActionRouter.isPriceInquiryMessage(customerMessage != null ? customerMessage : ""));
        boolean priceDiscussed = state != null
                ? state.priceDiscussed()
                : !isBarePriceInquiry;
        boolean customerCorrection = SentimentIntentLayer.isEntityMismatchCorrection(customerMessage);

        for (LearnedRuleDto r : candidates) {
            if (r == null || !r.active()) {
                continue;
            }
            if (r.alwaysOn() && isGlobalCriticalRuleCode(r.ruleCode(), r.category())) {
                continue;
            }
            String code = r.ruleCode() != null ? r.ruleCode().strip().toUpperCase(Locale.ROOT) : "";
            String text = ((r.triggerCondition() != null ? r.triggerCondition() : "") + " "
                    + (r.generalizedRule() != null ? r.generalizedRule() : "")).toLowerCase(Locale.ROOT);

            if (matchesIntentScope(code, text, intent, priceDiscussed, customerCorrection, isBarePriceInquiry)) {
                return List.of(r);
            }
        }
        return List.of();
    }

    private static boolean matchesIntentScope(
            String ruleCode,
            String ruleTextLower,
            String intent,
            boolean priceDiscussed,
            boolean customerCorrection,
            boolean isBarePriceInquiry
    ) {
        boolean isPriceObjectionRule = ruleCode.contains("OBJECTION")
                || ruleCode.equals("PB-001")
                || ruleCode.equals("PB-002")
                || ruleTextLower.contains("price_objection")
                || ruleTextLower.contains("chê đắt");
        if (isPriceObjectionRule && (!priceDiscussed || isBarePriceInquiry)) {
            return false;
        }
        if (isBarePriceInquiry && (isPriceObjectionRule
                || ruleCode.contains("TRIAL")
                || ruleCode.contains("CORRECTION")
                || ruleTextLower.contains("thử xem sao")
                || ruleTextLower.contains("customer_correction"))) {
            return false;
        }

        // 1. FRUSTRATION / CUSTOMER CORRECTION
        if ("FRUSTRATION".equals(intent)
                || "FRUSTRATION_REPEAT_HANDOFF".equals(intent)
                || "MISMATCH_CORRECTION_HANDOFF".equals(intent)
                || "POLITE_MISMATCH_PIVOT".equals(intent)
                || customerCorrection) {
            return ruleCode.contains("FRUSTRATION")
                    || ruleCode.contains("CORRECTION")
                    || ruleTextLower.contains("bức xúc")
                    || ruleTextLower.contains("nóng nảy")
                    || ruleTextLower.contains("customer_correction");
        }

        // 2. PRICE OBJECTION (chỉ cho phép khi đã báo giá priceDiscussed == true)
        if ("PRICE_OBJECTION_FIRST".equals(intent) || "PRICE_OBJECTION".equals(intent)) {
            if (!priceDiscussed) {
                return false;
            }
            return ruleCode.contains("OBJECTION-001")
                    || ruleCode.equals("PB-001")
                    || ruleCode.contains("OBJECTION-STEP1")
                    || (ruleCode.contains("OBJECTION") && !ruleCode.contains("002") && !ruleCode.contains("STEP2") && !ruleCode.contains("DOWNSELL"));
        }
        if ("PRICE_OBJECTION_REPEAT".equals(intent)
                || "PRICE_OBJECTION_EXPLICIT_DOWNSELL".equals(intent)
                || "PRICE_FLOOR_NO_LOWER_PACKAGE".equals(intent)
                || "PRICE_OBJECTION_ESCALATION".equals(intent)) {
            if (!priceDiscussed) {
                return false;
            }
            return ruleCode.contains("OBJECTION-002")
                    || ruleCode.equals("PB-002")
                    || ruleCode.contains("OBJECTION-STEP2")
                    || ruleCode.contains("DOWNSELL")
                    || (ruleCode.contains("OBJECTION") && !ruleCode.contains("001") && !ruleCode.contains("STEP1"));
        }

        // 3. TRIAL ACCEPTANCE / BOOKING
        if ("TRIAL_ACCEPTANCE".equals(intent) || "BOOKING_INTENT".equals(intent)) {
            return ruleCode.contains("TRIAL") || ruleCode.contains("BOOKING")
                    || ruleTextLower.contains("thử xem sao") || ruleTextLower.contains("giữ lịch");
        }

        // 4. ALTERNATIVE PACKAGE REQUEST
        if ("ALTERNATIVE_PACKAGE_REQUEST".equals(intent)) {
            return ruleCode.contains("ALTERNATIVE") || ruleCode.contains("MATCH") || ruleCode.contains("RECOMMEND")
                    || ruleTextLower.contains("gói khác") || ruleTextLower.contains("gợi ý");
        }

        // 5. SYMPTOM / CONDITION DISCOVERY
        if ("SYMPTOM_OR_PAIN_SHARE".equals(intent) || "MULTI_ENTITY_CONSULT".equals(intent)) {
            return ruleCode.contains("DISCOVER") || ruleCode.contains("CONDITION");
        }
        if ("CONDITION_FOLLOW_UP".equals(intent)) {
            return ruleCode.contains("MATCH") || ruleCode.contains("RECOMMEND");
        }

        // 6. OPEN PRICE INQUIRY / SERVICE CLARIFY (Turn 1 "Giá"): TUYỆT ĐỐI CẤM nạp PRICE_OBJECTION / TRIAL / CORRECTION
        if ("PRICE_INQUIRY".equals(intent) || "SERVICE_FAMILY_SELECTION".equals(intent)) {
            if (isPriceObjectionRule || ruleCode.contains("TRIAL") || ruleCode.contains("CORRECTION")) {
                return false;
            }
            return ruleCode.contains("PRICE") || ruleCode.contains("CLARIFY")
                    || ruleCode.contains("HERBAL") || ruleCode.contains("EAR")
                    || ruleCode.contains("SERVICE-SHORTCUT");
        }

        // 7. NEUROLOGICAL / SAFETY
        if ("NEUROLOGICAL_RISK".equals(intent) || "SAFETY_CONTRAINDICATION".equals(intent)) {
            return ruleCode.startsWith("SAFE-") || ruleCode.contains("SAFETY");
        }

        // 8. GREETING
        if ("GREETING".equals(intent)) {
            return ruleCode.contains("OPEN");
        }

        return false;
    }
}
