package com.example.docmind.service.bot;

import com.example.docmind.client.AgentBrainClient;
import com.example.docmind.client.AgentBrainClient.RecallAndAdviseRequest;
import com.example.docmind.client.AgentBrainClient.RecallAndAdviseResponse;
import com.example.docmind.client.AgentBrainClient.ReflectRequest;
import com.example.docmind.client.AgentBrainClient.ReflectResponse;
import com.example.docmind.client.AgentBrainClient.RetainTurnRequest;
import com.example.docmind.client.AgentBrainClient.RetainTurnResponse;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.service.retrieval.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Kết nối trực tiếp {@code docmind-api} với bộ não độc lập {@code agent-brain} (cổng 8095):
 * <ul>
 *   <li><b>Hot Path ({@code recallAndAdvise}, &lt; 10ms):</b> Truy hồi {@code customer_memory_block}
 *       (các sự thật đã kiểm chứng, lịch sử bác bỏ {@code superseded_by}, rào cản {@code objections})
 *       và chiến thuật kỹ năng tâm lý {@code active_skill}.</li>
 *   <li><b>Async Cold Path ({@code retainTurnAsync}):</b> Bóc tách thực thể mới, giải quyết xung đột
 *       ({@code Current Explicit Message > Verified Fact > Recent Summary > Inferred Old Fact})
 *       và cập nhật bộ nhớ khách hàng bất đồng bộ sau mỗi lượt hội thoại.</li>
 * </ul>
 */
@Service
public class AgentBrainService {

    private static final Logger log = LoggerFactory.getLogger(AgentBrainService.class);

    private final AgentBrainClient client;
    private final String baseUrl;

    @Autowired
    public AgentBrainService(
            @Value("${agent.brain.base-url:${AGENT_BRAIN_BASE_URL:http://localhost:8095}}") String configuredBaseUrl) {
        this.baseUrl = (configuredBaseUrl != null && !configuredBaseUrl.isBlank())
                ? configuredBaseUrl.strip()
                : "http://localhost:8095";

        HttpClient jdkHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttp);
        requestFactory.setReadTimeout(Duration.ofMillis(1500));

        RestClient restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requestFactory)
                .build();

        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
        this.client = factory.createClient(AgentBrainClient.class);
    }

    public AgentBrainService(AgentBrainClient customClient) {
        this.client = customClient;
        this.baseUrl = "custom";
    }

    public RecallAndAdviseResponse recallAndAdvise(
            UUID pageId,
            String senderId,
            String customerName,
            String message,
            double retrievalQuality,
            double groundedness) {
        return recallAndAdvise(pageId, senderId, customerName, message, retrievalQuality, groundedness, false, null);
    }

    public RecallAndAdviseResponse recallAndAdvise(
            UUID pageId,
            String senderId,
            String customerName,
            String message,
            double retrievalQuality,
            double groundedness,
            boolean isFirstTurn,
            PromptBuilder.PagePersona pagePersona) {
        if (client == null || message == null || message.isBlank()) {
            return null;
        }
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;
        java.util.Map<String, Object> domainMeta = new java.util.LinkedHashMap<>();
        domainMeta.put("is_first_turn", isFirstTurn);
        if (pagePersona != null) {
            if (pagePersona.name() != null && !pagePersona.name().isBlank()) {
                domainMeta.put("page_name", pagePersona.name().strip());
            }
            if (pagePersona.industry() != null && !pagePersona.industry().isBlank()) {
                domainMeta.put("industry", pagePersona.industry().strip());
            }
            if (pagePersona.targetAudience() != null && !pagePersona.targetAudience().isBlank()) {
                domainMeta.put("target_audience", pagePersona.targetAudience().strip());
            }
            if (pagePersona.personaConfig() != null && pagePersona.personaConfig().toneStyle() != null) {
                domainMeta.put("tone_style", pagePersona.personaConfig().toneStyle().strip());
            } else if (pagePersona.description() != null && !pagePersona.description().isBlank()) {
                domainMeta.put("tone_style", pagePersona.description().strip());
            }
        }
        try {
            RecallAndAdviseResponse res = client.recallAndAdvise(new RecallAndAdviseRequest(
                    tenantId,
                    customerId,
                    conversationId,
                    message,
                    customerName,
                    Math.max(0.0, Math.min(1.0, retrievalQuality)),
                    Math.max(0.0, Math.min(1.0, groundedness)),
                    domainMeta
            ));
            if (res != null) {
                log.info("[AgentBrain] RecallAndAdvise ok in {} ms (tenant={}, customer={}, stage={}, archetype={}, skill={}, facts={}, confidence={})",
                        res.latencyMs(),
                        tenantId,
                        customerId,
                        res.purchaseStage(),
                        res.buyerArchetype(),
                        res.activeSkill() != null ? res.activeSkill().skillCode() : "none",
                        res.activeFacts() != null ? res.activeFacts().size() : 0,
                        res.finalConfidence());
            }
            return res;
        } catch (Exception ex) {
            log.debug("[AgentBrain] recallAndAdvise unreachable at {} ({}), continuing with in-JVM insight engine",
                    baseUrl, ex.getMessage());
            return null;
        }
    }

    public void retainTurnAsync(
            UUID pageId,
            String senderId,
            String customerName,
            String userMessage,
            String assistantReply) {
        if (client == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;

        CompletableFuture.runAsync(() -> {
            try {
                RetainTurnResponse retained = client.retainTurn(new RetainTurnRequest(
                        tenantId,
                        customerId,
                        conversationId,
                        userMessage,
                        assistantReply,
                        customerName,
                        true
                ));
                if (retained != null) {
                    log.info("[AgentBrain] Retained turn (tenant={}, customer={}, extractedFacts={}, supersededFacts={}, stage={})",
                            tenantId,
                            customerId,
                            retained.extractedFacts() != null ? retained.extractedFacts().size() : 0,
                            retained.supersededFactIds() != null ? retained.supersededFactIds().size() : 0,
                            retained.purchaseStage());
                }
            } catch (Exception ex) {
                log.debug("[AgentBrain] retainTurnAsync skipped ({})", ex.getMessage());
            }
        });
    }

    public ReflectResponse reflectCustomer(UUID pageId, String senderId) {
        if (client == null) {
            return null;
        }
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;
        try {
            return client.reflect(new ReflectRequest(tenantId, customerId, conversationId));
        } catch (Exception ex) {
            log.debug("[AgentBrain] reflectCustomer unreachable ({})", ex.getMessage());
            return null;
        }
    }

    /**
     * Đồng bộ kỹ năng chủ đạo do {@code agent-brain} chọn với danh sách {@code activeSkills} của Page (tối đa 1 skill).
     */
    public List<AgentSkillDto> synchronizeActiveSkills(
            List<AgentSkillDto> currentActiveSkills,
            List<AgentSkillDto> candidateSkills,
            RecallAndAdviseResponse brainAdvice) {
        if (brainAdvice == null || brainAdvice.activeSkill() == null
                || brainAdvice.activeSkill().skillCode() == null) {
            if (currentActiveSkills == null || currentActiveSkills.isEmpty()) {
                return List.of();
            }
            return List.of(currentActiveSkills.get(0));
        }
        String targetCode = brainAdvice.activeSkill().skillCode().strip();
        List<AgentSkillDto> working = currentActiveSkills != null
                ? new ArrayList<>(currentActiveSkills)
                : new ArrayList<>();

        if (!working.isEmpty() && targetCode.equalsIgnoreCase(working.get(0).code())) {
            return List.of(working.get(0));
        }

        // Tìm trong working hoặc candidateSkills
        AgentSkillDto matched = null;
        for (AgentSkillDto s : working) {
            if (s != null && targetCode.equalsIgnoreCase(s.code())) {
                matched = s;
                break;
            }
        }
        if (matched == null && candidateSkills != null) {
            for (AgentSkillDto s : candidateSkills) {
                if (s != null && targetCode.equalsIgnoreCase(s.code())) {
                    matched = s;
                    break;
                }
            }
        }
        if (matched != null) {
            return List.of(matched);
        }
        return working.isEmpty() ? List.of() : List.of(working.get(0));
    }

    /**
     * Bổ sung các sự thật đã kiểm chứng ({@code active_facts}) và rào cản ({@code objections})
     * từ bộ nhớ {@code agent-brain} vào {@code CustomerInsightDto}.
     */
    public CustomerInsightDto enrichCustomerInsight(
            CustomerInsightDto currentInsight,
            RecallAndAdviseResponse brainAdvice) {
        if (currentInsight == null || brainAdvice == null) {
            return currentInsight;
        }
        List<String> mergedPains = new ArrayList<>(currentInsight.extractedPains());
        if (brainAdvice.activeFacts() != null) {
            for (AgentBrainClient.MemoryFactDto f : brainAdvice.activeFacts()) {
                if (f != null && f.isActive() && f.factKey() != null && f.factValue() != null) {
                    String factSummary = f.factKey() + "=" + f.factValue();
                    if (mergedPains.stream().noneMatch(p -> p.toLowerCase().contains(f.factValue().toLowerCase()))) {
                        mergedPains.add(factSummary);
                    }
                }
            }
        }
        List<String> mergedConcerns = new ArrayList<>(currentInsight.hiddenConcerns());
        if (brainAdvice.objections() != null) {
            for (AgentBrainClient.ObjectionItemDto obj : brainAdvice.objections()) {
                if (obj != null && obj.type() != null) {
                    String objLabel = "Rào cản [" + obj.type() + "]: " + (obj.quote() != null ? obj.quote() : "");
                    if (mergedConcerns.stream().noneMatch(c -> c.contains("[" + obj.type() + "]"))) {
                        mergedConcerns.add(objLabel);
                    }
                }
            }
        }
        String enrichedStrategy = currentInsight.recommendedStrategy();
        if (brainAdvice.activeSkill() != null) {
            String cadence = brainAdvice.activeSkill().audienceVoiceCadence();
            String directive = brainAdvice.activeSkill().promptDirective();
            if ("WITHHOLD_NUMBERS_HOOK_FIRST".equalsIgnoreCase(brainAdvice.activeSkill().priceDisclosureGate())
                    && directive != null && !directive.isBlank()) {
                enrichedStrategy = (cadence != null && !cadence.isBlank() ? "[" + cadence + "] " : "") + directive;
            } else if (cadence != null && !cadence.isBlank() && enrichedStrategy != null && !enrichedStrategy.contains(cadence)) {
                enrichedStrategy = enrichedStrategy + " | Âm hưởng tệp KH: " + cadence;
            }
        }
        return new CustomerInsightDto(
                currentInsight.buyerArchetype(),
                currentInsight.archetypeLabel(),
                currentInsight.buyingTemperature(),
                currentInsight.previousTemperature(),
                currentInsight.temperatureDelta(),
                currentInsight.momentum(),
                currentInsight.temperatureLabel(),
                currentInsight.decayReason(),
                currentInsight.honorific(),
                mergedPains,
                mergedConcerns,
                enrichedStrategy
        );
    }
}
