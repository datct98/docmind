package com.example.docmind.service.salescore;

import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Versioned universal tools, not an utterance router. Selection is provisional until evidence and review agree. */
final class SalesStrategyRegistry {
    record Card(String id, String family, String customerObjective, List<String> eligibleEvidence,
                List<Action> allowedActions, List<String> prohibitedWhen, boolean requiredBusinessEvidence,
                String intendedCustomerValue, List<String> recommendedInteraction, List<String> stopConditions,
                List<String> antiPatterns, List<String> outcomeSignals, int allowedCloseIntensity) {}
    record Catalog(int schemaVersion, int maxSelected, List<Card> cards) {}
    record Application(String id, String objective, String signal, String customerQuote,
                       List<String> businessEvidenceIds, String predictedCustomerBenefit, int closeIntensity) {
        Application { businessEvidenceIds = businessEvidenceIds == null ? List.of() : List.copyOf(businessEvidenceIds); }
    }
    record Assessment(List<Application> accepted, List<String> rejected) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Catalog CATALOG = load();
    private SalesStrategyRegistry() {}

    private static Catalog load() {
        try (var stream = SalesStrategyRegistry.class.getResourceAsStream("/sales/strategies.json")) {
            if (stream == null) throw new IllegalStateException("Strategy catalog missing");
            var catalog = JSON.readValue(stream.readAllBytes(), Catalog.class);
            if (catalog.schemaVersion() != 1 || catalog.maxSelected() != 2 || catalog.cards() == null)
                throw new IllegalArgumentException("Invalid strategy catalog");
            var ids = new HashSet<String>();
            for (var c : catalog.cards()) if (c.id() == null || !ids.add(c.id()) || c.eligibleEvidence().isEmpty()
                    || c.allowedActions().isEmpty() || c.recommendedInteraction().isEmpty() || c.allowedCloseIntensity() > 2)
                throw new IllegalArgumentException("Invalid strategy card");
            return catalog;
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read strategy catalog", e); }
    }

    static String index() {
        // A compact index enables selection; full cards are included only for the selected 0–2 applications at review.
        return JSON.writeValueAsString(CATALOG.cards().stream().map(c -> Map.of("id", c.id(), "family", c.family(),
                "purpose", c.customerObjective(), "signals", c.eligibleEvidence())).toList());
    }
    static List<Card> selectedCards(Strategy strategy) {
        Set<String> ids = new HashSet<>(); strategy.techniques().forEach(a -> ids.add(a.id()));
        return CATALOG.cards().stream().filter(c -> ids.contains(c.id())).limit(2).toList();
    }
    static Assessment assess(Strategy strategy, TurnInput input, Set<String> citedSources) {
        if (strategy.techniques().size() > CATALOG.maxSelected()) return new Assessment(List.of(), List.of("STRATEGY_LIMIT_EXCEEDED"));
        var accepted = new ArrayList<Application>(); var rejected = new ArrayList<String>(); var seen = new HashSet<String>();
        for (var a : strategy.techniques()) {
            var card = a == null ? null : CATALOG.cards().stream().filter(c -> c.id().equals(a.id())).findFirst().orElse(null);
            boolean valid = card != null && seen.add(a.id()) && card.allowedActions().contains(strategy.action())
                    && card.eligibleEvidence().contains(a.signal()) && SalesConsultationPolicy.customerSupports(input, a.customerQuote())
                    && a.objective() != null && !a.objective().isBlank() && a.predictedCustomerBenefit() != null && !a.predictedCustomerBenefit().isBlank()
                    && a.closeIntensity() >= 0 && a.closeIntensity() <= card.allowedCloseIntensity()
                    && citedSources.containsAll(a.businessEvidenceIds())
                    && (!card.requiredBusinessEvidence() || !a.businessEvidenceIds().isEmpty());
            if (valid && a.closeIntensity() > 0) {
                var plan = input.interactionPlan();
                valid = plan != null && SalesConsultationPolicy.customerSupports(input, plan.readinessEvidence())
                        && "NONE".equalsIgnoreCase(plan.unresolvedConcern());
            }
            if (valid && input.interactionPlan() != null && input.interactionPlan().stage() == ConsultationStage.CLOSE)
                valid = a.closeIntensity() == 0 && strategy.action() == Action.PAUSE && card.id().equals("relationship_preservation");
            if (valid) accepted.add(a); else rejected.add("STRATEGY_EVIDENCE_REJECTED:" + (a == null ? "null" : a.id()));
        }
        return new Assessment(List.copyOf(accepted), List.copyOf(rejected));
    }
    static String instructions() {
        return """
            STRATEGY SELECTION: dùng 0–2 công cụ trong index khi thực sự giúp khách; mặc định techniques=[].
            Không chạy đủ bước framework, không nói tên framework với khách. Chọn NONE cho một fact đơn giản.
            APAC: ghi nhận đúng lo ngại, chỉ hỏi nếu chưa rõ, giải bằng bằng chứng, kiểm tra lại khi có ích.
            AIDA là lăng kính tạo quan tâm khi khách cần giới thiệu; không thêm CTA cho mọi câu hỏi.
            SPIN chỉ 0–1 câu hỏi có tác dụng, không hỏi cung hay khuếch đại nỗi sợ.
            Giá trị: một chi tiết có nguồn → ý nghĩa với ưu tiên khách → lời gợi trải nghiệm không hứa hiệu quả.
            Chốt nhẹ khi có tín hiệu muốn thử/chọn/cân nhắc gần quyết định, giải phản đối còn mở trước.
            Trong decision.strategy thêm techniques=[{id,objective,signal,customerQuote,businessEvidenceIds:[],predictedCustomerBenefit,closeIntensity:0}].
            signal phải từ signals của card, customerQuote nguyên văn. closeIntensity 0=không chốt, 1=mời bước nhỏ,
            2=thông tin vận hành khi khách yêu cầu mua. Chỉ dẫn quyền hạn/facts/STOP luôn cao hơn chiến lược.
            INDEX_JSON:
            """ + index();
    }
}
