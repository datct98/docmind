package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACI V3 — Stage P1 forensic regression suite (Chỉ thị triển khai cuối cùng, Mục 5.2, test 1–17).
 *
 * <p>Bối cảnh pháp y: Page Nàng Sen ({@code 2850a8ef-ca4a-40eb-87fa-4f808640efee}), khách nói
 * "hoa mắt, chóng mặt, tê chân" rồi "khó ngủ"; bot tự suy diễn "cổ vai gáy"; khách phản bác
 * "Ơ bị dở à sao lại cổ vai gáy". Mọi tầng (fact grounding, taxonomy, state, scope merge,
 * skill/rule arbitration, apology guard) phải bám đúng lời khách, không bịa fact, không ghi
 * negative preference sai, không xin lỗi về nội dung bot chưa từng nói.
 */
class P38ForensicRegressionTest {

    private static final UUID PAGE_ID = UUID.fromString("2850a8ef-ca4a-40eb-87fa-4f808640efee");
    private static final String SENDER = "p38-forensic-sender";

    /** Taxonomy sau khi V41 áp: tách bạch body-area khỏi domain keyword, KHÔNG còn "cổ vai gáy". */
    private static final List<String> NANG_SEN_TAXONOMY = List.of(
            "chân", "vai", "cổ", "gáy", "lưng", "toàn thân", "đầu");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    // =========================================================================
    // TEST 1 (§3.2): "hoa mắt, chóng mặt, tê chân" — giữ đủ 3 triệu chứng,
    // vùng cơ thể duy nhất là [chân]; tuyệt đối không suy ra "mắt"/"cổ vai gáy".
    // =========================================================================
    @Test
    @DisplayName("P1-T1 §3.2: 'hoa mắt, chóng mặt, tê chân' giữ đủ triệu chứng, body area chỉ [chân], không auto cổ vai gáy/mắt")
    void test01_symptomOnlyTurnKeepsAllSymptomsAndOnlyChanBodyArea() {
        ConversationState state = ConversationState
                .hydrateForTurn(PAGE_ID, SENDER, null, List.of(), NANG_SEN_TAXONOMY, List.of())
                .enrichWithCurrentMessage("hoa mắt, chóng mặt, tê chân", NANG_SEN_TAXONOMY);

        assertThat(state.knownSymptoms())
                .anySatisfy(s -> assertThat(s).containsIgnoringCase("hoa mắt"))
                .anySatisfy(s -> assertThat(s).containsIgnoringCase("chóng mặt"))
                .anySatisfy(s -> assertThat(s).containsIgnoringCase("tê chân"));
        assertThat(state.knownBodyAreas())
                .contains("chân")
                .noneMatch(s -> s.contains("mắt"))
                .noneMatch(s -> s.contains("cổ"))
                .noneMatch(s -> s.contains("vai"))
                .noneMatch(s -> s.contains("gáy"));
    }

    // =========================================================================
    // TEST 2 (§3.2/§5.2-12): "Sao lại cổ vai gáy?" — câu hỏi phản bác phạm vi,
    // phải được phân loại possibleCorrection (REJECTED_BOT_ASSUMPTION), KHÔNG phải
    // bare regex "sao lại" => frustration.
    // =========================================================================
    @Test
    @DisplayName("P1-T2 §3.2: 'sao lại cổ vai gáy?' là scope pushback (possibleCorrection), phạm vi bị phủ định bị chặn")
    void test02_interrogativeScopePushbackClassifiedAsCorrection() {
        VietnameseScopeSignalExtractor.ScopeSignalResult signal =
                VietnameseScopeSignalExtractor.analyze("ơ bị dở à sao lại cổ vai gáy", NANG_SEN_TAXONOMY);

        assertThat(signal.possibleCorrection())
                .as("'sao lại cổ vai gáy' phải là phản bác phạm vi (REJECTED_BOT_ASSUMPTION), không trích scope mới")
                .isTrue();
        assertThat(signal.confirmedScopesToAdd())
                .as("không được xác nhận thêm scope cổ/vai/gáy từ câu phản bác")
                .noneMatch(s -> s.contains("cổ"))
                .noneMatch(s -> s.contains("vai"))
                .noneMatch(s -> s.contains("gáy"));
    }

    // =========================================================================
    // TEST 3 (§3.1): Lượt phản bác — GIỮ vùng khách đã nói ([chân] từ "tê chân"),
    // RÚT giả định sai của bot (cổ/vai/gáy), KHÔNG ghi vào knownBodyAreas.
    // =========================================================================
    @Test
    @DisplayName("P1-T3 §3.1: correction turn giữ [chân], loại cổ/vai/gáy khỏi knownBodyAreas, không ghi negative preference")
    void test03_correctionTurnKeepsStatedAreaAndRetractsBotAssumption() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "dạo này em hay bị tê chân"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ với tình trạng của mình thì em nghĩ gói massage cổ vai gáy sẽ hợp ạ.")
        );

        ConversationState state = ConversationState
                .hydrateForTurn(PAGE_ID, SENDER, null, history, NANG_SEN_TAXONOMY, List.of())
                .enrichWithCurrentMessage("ơ bị dở à sao lại cổ vai gáy", NANG_SEN_TAXONOMY);

        assertThat(state.knownBodyAreas())
                .as("vùng khách tự nói (chân) phải được giữ lại")
                .contains("chân");
        assertThat(state.knownBodyAreas())
                .as("giả định sai của bot (cổ/vai/gáy) bị khách phản bác -> không được nằm trong knownBodyAreas")
                .noneMatch(s -> s.contains("cổ"))
                .noneMatch(s -> s.contains("vai"))
                .noneMatch(s -> s.contains("gáy"));
    }

    // =========================================================================
    // TEST 4 (§3.1): "Đâu có đau cổ vai gáy" — Nhóm D tường minh, phủ định trực tiếp.
    // =========================================================================
    @Test
    @DisplayName("P1-T4 §3.1: 'đâu có đau cổ vai gáy' (Nhóm D) phủ định scope, giữ [chân]")
    void test04_explicitGroupDDenialRemovesForbiddenScopes() {
        VietnameseScopeSignalExtractor.ScopeSignalResult signal =
                VietnameseScopeSignalExtractor.analyze("đâu có đau cổ vai gáy", NANG_SEN_TAXONOMY);
        assertThat(signal.explicitCorrectionGroupD() || signal.possibleCorrection()).isTrue();

        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "dạo này em hay bị tê chân")
        );
        ConversationState state = ConversationState
                .hydrateForTurn(PAGE_ID, SENDER, null, history, NANG_SEN_TAXONOMY, List.of())
                .enrichWithCurrentMessage("đâu có đau cổ vai gáy", NANG_SEN_TAXONOMY);
        assertThat(state.knownBodyAreas()).contains("chân");
        assertThat(state.knownBodyAreas())
                .noneMatch(s -> s.contains("cổ"))
                .noneMatch(s -> s.contains("vai"))
                .noneMatch(s -> s.contains("gáy"));
    }

    // =========================================================================
    // TEST 5 (§3.3): Scope merge — lượt 2 "khó ngủ" KHÔNG được đánh rơi scope lượt 1.
    // =========================================================================
    @Test
    @DisplayName("P1-T5 §3.3: mergeScopes giữ scope lượt trước khi lượt hiện tại chỉ là triệu chứng ('khó ngủ')")
    void test05_scopeMergeRetainsPriorScopesOnSymptomOnlyTurn() {
        List<String> merged = ScopeMatcher.mergeScopes("khó ngủ", List.of("chân"), NANG_SEN_TAXONOMY);
        assertThat(merged).contains("chân");

        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "hoa mắt, chóng mặt, tê chân"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em nghe mình chia sẻ rồi ạ.")
        );
        ConversationState state = ConversationState
                .hydrateForTurn(PAGE_ID, SENDER, null, history, NANG_SEN_TAXONOMY, List.of())
                .enrichWithCurrentMessage("khó ngủ", NANG_SEN_TAXONOMY);
        assertThat(state.knownBodyAreas()).contains("chân");
        assertThat(state.knownSymptoms())
                .anySatisfy(s -> assertThat(s).containsIgnoringCase("khó ngủ"))
                .anySatisfy(s -> assertThat(s).containsIgnoringCase("tê chân"));
    }

    // =========================================================================
    // TEST 6 (§3.2): Taxonomy đồng bộ — domain_keywords KHÔNG được dùng làm từ điển
    // vùng cơ thể; "tê chân" -> "chân" ở mọi tầng.
    // =========================================================================
    @Test
    @DisplayName("P1-T6 §3.2: resolvedScopeTaxonomy không rò rỉ domain_keywords ('cổ vai gáy') làm vùng cơ thể")
    void test06_taxonomyNeverDerivedFromDomainKeywords() {
        PagePersonaConfig leaked = PagePersonaConfig.fromJson("""
                {
                  "industry_code": "BEAUTY_SPA",
                  "domain_keywords": ["cổ vai gáy", "massage body", "chăm sóc da"],
                  "scope_taxonomy": [],
                  "body_area_keywords": [],
                  "symptom_keywords": []
                }
                """);
        assertThat(leaked.resolvedScopeTaxonomy())
                .as("domain_keywords không phải từ điển vùng cơ thể — không được fallback thành scope taxonomy")
                .noneMatch(s -> s.contains("cổ vai gáy"));

        PagePersonaConfig configured = PagePersonaConfig.fromJson("""
                {
                  "industry_code": "BEAUTY_SPA",
                  "domain_keywords": ["cổ vai gáy", "massage body"],
                  "scope_taxonomy": ["chân", "toàn thân"],
                  "body_area_keywords": ["chân", "toàn thân"],
                  "symptom_keywords": ["tê chân", "hoa mắt", "chóng mặt", "khó ngủ"]
                }
                """);
        assertThat(configured.resolvedScopeTaxonomy()).contains("chân");

        // "tê chân" -> vùng "chân" ở tầng ScopeMatcher
        List<String> scopes = ScopeMatcher.extractScopesFromText("hoa mắt, chóng mặt, tê chân", NANG_SEN_TAXONOMY);
        assertThat(scopes).contains("chân");
        assertThat(scopes)
                .noneMatch(s -> s.contains("mắt"))
                .noneMatch(s -> s.contains("cổ"));
    }

    // =========================================================================
    // TEST 7 (§3.1): Fact grounding — không fact nào được ghi CONFIRMED/HIGH với
    // source=CUSTOMER_MESSAGE khi không có bằng chứng từ vựng trong lời khách.
    // =========================================================================
    @Test
    @DisplayName("P1-T7 §3.1: LLM đề xuất fact 'cổ vai gáy' nhưng khách chỉ nói 'tê chân' -> fact bị loại/hạ cấp sau reconcile")
    void test07_factWithoutLexicalEvidenceNeverConfirmedHigh() throws Exception {
        String customerMsg = "dạo này em hay bị tê chân";
        JsonNode rawReflection = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Chia sẻ tình trạng"},
                  "customer_understanding": {
                    "turn_intent": "SHARING_CONDITION_OR_CONTEXT",
                    "conversation_goal": "EXPLORE",
                    "interaction_permission": "OPEN",
                    "buying_signal": "NONE",
                    "emotion": "NEUTRAL",
                    "confirmed_facts_delta": [
                      {"key": "need_scope", "value": "cổ vai gáy", "op": "ADD", "status": "CURRENT"},
                      {"key": "need_scope", "value": "chân", "op": "ADD", "status": "CURRENT"}
                    ]
                  },
                  "priority": "DISCOVERY",
                  "response_plan": {
                    "strategy": "Active Listening",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "ASK_ONE_DISCOVERY_QUESTION",
                    "adaptation": "STAY"
                  }
                }
                """);

        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(rawReflection, customerMsg);

        List<AdaptiveConversationIntelligence.FactDeltaProposal> deltas = reconciled.confirmedFactsDelta();
        assertThat(deltas)
                .as("fact 'cổ vai gáy' không có bằng chứng từ vựng trong lời khách -> không được tồn tại ở trạng thái confirmed")
                .noneMatch(f -> f.value() != null && f.value().toLowerCase(java.util.Locale.ROOT).contains("cổ vai gáy"));
        assertThat(deltas)
                .as("fact 'chân' có bằng chứng ('tê chân') -> được giữ")
                .anyMatch(f -> f.value() != null && f.value().toLowerCase(java.util.Locale.ROOT).contains("chân"));
    }

    // =========================================================================
    // TEST 8 (§3.1): extracted_pains khử trùng lặp — không lưu đồng thời chuỗi
    // tổng hợp "đau cổ vai gáy" và các phần tử nguyên tử của nó.
    // =========================================================================
    @Test
    @DisplayName("P1-T8 §3.1: tách 'đau cổ vai gáy' thành atoms, không lưu composite + children trùng lặp")
    void test08_extractedPainsDedupNoCompositePlusAtomicChildren() {
        List<String> atoms = AdaptiveConversationIntelligence.splitAtomicHealthExpressions("đau cổ vai gáy");
        assertThat(atoms)
                .as("biểu hiện tổng hợp phải tách thành nguyên tử, không giữ chuỗi ghép")
                .noneMatch(s -> s.equalsIgnoreCase("đau cổ vai gáy"));
        assertThat(atoms).hasSize(3);

        // Khi LLM gửi cả composite lẫn atom, kết quả cuối cùng chỉ chứa atoms duy nhất
        java.util.List<AdaptiveConversationIntelligence.FactDeltaProposal> target = new java.util.ArrayList<>();
        String msg = "em bị đau cổ vai gáy, đặc biệt đau cổ";
        JsonNode json = null;
        try {
            json = objectMapper.readTree("""
                    {
                      "conversational_context": {"current_topic": "Chia sẻ triệu chứng"},
                      "customer_understanding": {
                        "turn_intent": "SHARING_CONDITION_OR_CONTEXT",
                        "conversation_goal": "EXPLORE",
                        "interaction_permission": "OPEN",
                        "buying_signal": "NONE",
                        "emotion": "NEUTRAL",
                        "confirmed_facts_delta": [
                          {"key": "symptom", "value": "đau cổ vai gáy", "op": "ADD"},
                          {"key": "symptom", "value": "đau cổ", "op": "ADD"}
                        ]
                      },
                      "priority": "DISCOVERY",
                      "response_plan": {
                        "strategy": "Active Listening", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE",
                        "cta_level": "NO_CTA", "next_action": "ASK_ONE_DISCOVERY_QUESTION", "adaptation": "STAY"
                      }
                    }
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(json, msg);
        List<String> values = reconciled.confirmedFactsDelta().stream()
                .map(AdaptiveConversationIntelligence.FactDeltaProposal::value)
                .map(v -> v.toLowerCase(java.util.Locale.ROOT))
                .toList();
        assertThat(values).doesNotContain("đau cổ vai gáy");
        assertThat(values.stream().filter(v -> v.contains("đau cổ")).count()).isEqualTo(1);
        assertThat(target).isEmpty(); // chỉ để giữ import rõ ràng, logic nằm ở reconciled
    }

    // =========================================================================
    // TEST 9 (§5.2-9): "Sao lại có giá như vậy?" — câu hỏi GIÁ, không phải phản bác scope.
    // =========================================================================
    @Test
    @DisplayName("P1-T9 §5.2: 'sao lại có giá như vậy?' -> price inquiry, KHÔNG phải scope correction")
    void test09_saoLaiCoGiaIsPriceInquiryNotScopeCorrection() {
        String msg = "sao lại có giá như vậy?";
        VietnameseScopeSignalExtractor.ScopeSignalResult signal =
                VietnameseScopeSignalExtractor.analyze(msg, NANG_SEN_TAXONOMY);
        assertThat(signal.possibleCorrection()).isFalse();
        assertThat(signal.explicitCorrectionGroupD()).isFalse();
        assertThat(AdaptiveConversationIntelligence.classifyPriceIntentSubMode(msg))
                .isNotEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);
    }

    // =========================================================================
    // TEST 10 (§5.2-10): "Sao lại massage 60 phút?" — câu hỏi dịch vụ/thời lượng.
    // =========================================================================
    @Test
    @DisplayName("P1-T10 §5.2: 'sao lại massage 60 phút?' -> câu hỏi dịch vụ, KHÔNG phải scope correction")
    void test10_saoLaiMassage60PhutIsServiceQuestionNotCorrection() {
        String msg = "sao lại massage 60 phút?";
        VietnameseScopeSignalExtractor.ScopeSignalResult signal =
                VietnameseScopeSignalExtractor.analyze(msg, NANG_SEN_TAXONOMY);
        assertThat(signal.possibleCorrection()).isFalse();
        assertThat(signal.explicitCorrectionGroupD()).isFalse();
        assertThat(AdaptiveConversationIntelligence.classifyPriceIntentSubMode(msg))
                .as("không có cue giá -> không được xếp price intent")
                .isEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);
    }

    // =========================================================================
    // TEST 11 (§5.2): "Đừng nói vòng vo nữa, báo giá đi" — COMMUNICATION_FRICTION
    // đi kèm yêu cầu giá; không sụp đổ mọi friction thành CONCISE_PREFERRED mù.
    // =========================================================================
    @Test
    @DisplayName("P1-T11 §5.2: 'đừng nói vòng vo nữa, báo giá đi' -> style complaint + yêu cầu giá")
    void test11_dungNoiVongVoIsStyleComplaintWithPriceRequest() {
        String msg = "đừng nói vòng vo nữa, báo giá đi";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(msg)).isTrue();
        // Trên lượt phàn nàn giao tiếp, price sub-mode cố ý trả NONE (style complaint chiếm ưu tiên);
        // yêu cầu giá bên trong được nhận diện qua kênh price inquiry riêng
        assertThat(PrimaryActionRouter.isPriceInquiryMessage(msg))
                .as("'báo giá đi' phải được nhận diện là yêu cầu giá")
                .isTrue();
    }

    // =========================================================================
    // TEST 12 (§5.2): "Viết dài thế đọc thế quái nào được" — yêu cầu NGẮN tường minh.
    // Chỉ khi khách phàn nàn về độ dài mới được ghi CONCISE_PREFERRED.
    // =========================================================================
    @Test
    @DisplayName("P1-T12 §5.2: 'viết dài thế đọc thế quái nào được' -> yêu cầu trả lời ngắn tường minh")
    void test12_vietDaiTheIsExplicitConciseRequest() {
        String msg = "viết dài thế đọc thế quái nào được";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isExplicitConciseReplyRequest(msg))
                .as("phàn nàn về ĐỘ DÀI phải được nhận diện là yêu cầu ngắn gọn tường minh")
                .isTrue();
        // Phản bác scope KHÔNG được tự sụp thành yêu cầu ngắn gọn
        assertThat(CleanAgentOutputRefereeAndSanitizer.isExplicitConciseReplyRequest("ơ bị dở à sao lại cổ vai gáy"))
                .isFalse();
    }

    // =========================================================================
    // TEST 13 (§3.5): False Apology Guard — bot chưa từng nhắc "cổ vai gáy" trong
    // lịch sử assistant thì không được xin lỗi kiểu "vừa nhắc tới cổ vai gáy".
    // =========================================================================
    @Test
    @DisplayName("P1-T13 §3.5: xin lỗi tham chiếu nội dung bot chưa từng nói -> viết lại thành lời xin lỗi trung thực")
    void test13_falseApologyRewrittenWhenBotNeverMentionedContent() {
        List<String> priorAssistantTexts = List.of(
                "Dạ em nghe mình chia sẻ rồi ạ.",
                "Dạ mình đang bị hoa mắt, chóng mặt, tê chân và khó ngủ đúng không ạ?"
        );
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em xin lỗi vì vừa nhắc tới cổ vai gáy trong khi mình không hề nói vậy ạ.", 0)
        );
        List<FanpageBotResponse.ReplyBubble> guarded =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        bubbles, priorAssistantTexts, "em", "mình");
        assertThat(guarded).isNotEmpty();
        assertThat(guarded.get(0).text())
                .as("bot chưa từng nhắc 'cổ vai gáy' -> không được xin lỗi vì điều chưa nói")
                .doesNotContainIgnoringCase("cổ vai gáy");
        assertThat(guarded.get(0).text())
                .as("vẫn phải là lời xin lỗi trung thực bám tình trạng khách")
                .containsIgnoringCase("xin lỗi");
    }

    // =========================================================================
    // TEST 14 (§3.5): Nếu bot ĐÃ nói "cổ vai gáy" trong lịch sử — lời xin lỗi
    // tham chiếu nội dung đó là trung thực, phải được giữ nguyên.
    // =========================================================================
    @Test
    @DisplayName("P1-T14 §3.5: xin lỗi tham chiếu nội dung bot ĐÃ nói -> giữ nguyên")
    void test14_groundedApologyPreservedWhenBotDidMentionContent() {
        List<String> priorAssistantTexts = List.of(
                "Dạ với tình trạng của mình thì em nghĩ gói massage cổ vai gáy sẽ hợp ạ."
        );
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em xin lỗi vì vừa nhắc tới cổ vai gáy trong khi mình không hề nói vậy ạ.", 0)
        );
        List<FanpageBotResponse.ReplyBubble> guarded =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        bubbles, priorAssistantTexts, "em", "mình");
        assertThat(guarded).isNotEmpty();
        assertThat(guarded.get(0).text())
                .as("bot đã thật sự nhắc cổ vai gáy -> lời xin lỗi có căn cứ, không bị viết lại")
                .containsIgnoringCase("cổ vai gáy");
    }

    // =========================================================================
    // TEST 15 (§3.5): Cụm đồng cảm rỗng "Em rất thương khi nghe mình..." phải bị
    // phát hiện và thay thế ở tầng sanitizer.
    // =========================================================================
    @Test
    @DisplayName("P1-T15 §3.5: 'Em rất thương khi nghe mình...' bị phát hiện cliché và sanitize")
    void test15_cannedEmptyEmpathyDetectedAndSanitized() {
        String cliche = "Dạ em rất thương khi nghe mình chia sẻ vậy ạ.";
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectForbiddenThaoMaiEmpathyCliche(cliche))
                .as("biến thể 'rất thương khi nghe mình' phải bị bắt như các cliché 'thương quá/xót xa'")
                .isTrue();

        List<FanpageBotResponse.ReplyBubble> sanitized =
                CleanAgentOutputRefereeAndSanitizer.sanitizePatronizingEmpathyPhrasesInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(cliche, 0)), "em", "mình");
        assertThat(sanitized).isNotEmpty();
        assertThat(sanitized.get(0).text()).doesNotContainIgnoringCase("rất thương");
    }

    // =========================================================================
    // TEST 16 (§5.2-16): Đại từ — "t là nam" -> "Anh"; mặc định trung lập "mình".
    // =========================================================================
    @Test
    @DisplayName("P1-T16 §5.2: 't là nam' -> xưng hô Anh; chưa biết thì giữ trung lập 'mình'")
    void test16_pronounInferenceAndNeutralDefault() {
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("t là nam"))
                .isEqualTo("Anh");
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("khó ngủ"))
                .as("không có tín hiệu -> không suy diễn đại từ")
                .isNull();

        PagePersonaConfig nangSen = PagePersonaConfig.fromJson("""
                {
                  "industry_code": "BEAUTY_SPA",
                  "self_pronoun": "em",
                  "default_customer_pronoun": "mình"
                }
                """);
        assertThat(nangSen.resolvedDefaultCustomerPronoun()).isEqualTo("mình");
    }

    // =========================================================================
    // TEST 17 (§5.2-17): Tối đa 3 bong bóng mỗi phản hồi.
    // =========================================================================
    @Test
    @DisplayName("P1-T17 §5.2: splitIntoBubbles không bao giờ vượt quá 3 bong bóng")
    void test17_bubbleCapNeverExceedsThree() {
        String longReply = String.join("\n\n",
                "Dạ em nghe mình chia sẻ rồi ạ.",
                "Tình trạng hoa mắt chóng mặt kèm tê chân cần được nghỉ ngơi hợp lý.",
                "Mình cũng hay khó ngủ nên giấc ngủ chưa sâu.",
                "Bên em có liệu trình thư giãn đầu giúp dễ ngủ hơn.",
                "Mình muốn tìm hiểu thêm gói nào không ạ?");
        List<FanpageBotResponse.ReplyBubble> bubbles =
                CleanAgentOutputRefereeAndSanitizer.splitIntoBubbles(longReply);
        assertThat(bubbles.size()).isLessThanOrEqualTo(3);
    }

    // =========================================================================
    // TEST 18 (§3.4): Skill arbitration — lượt phản bác/phàn nàn giao tiếp không
    // được để discovery_empathy_and_scope chi phối, loại trừ skill chốt lịch/bán hàng.
    // =========================================================================
    @Test
    @DisplayName("P1-T18 §3.4: lượt CUSTOMER_CORRECTION loại discovery/closing skill khỏi pre-LLM selection")
    void test18_correctionTurnExcludesDiscoveryAndClosingSkills() {
        List<AgentSkillDto> sixSkills = buildNangSenSixSkills();
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "hoa mắt, chóng mặt, tê chân"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ với tình trạng của mình thì em nghĩ gói massage cổ vai gáy sẽ hợp ạ."),
                new FanpageBotRequest.ConversationTurn("user", "khó ngủ")
        );

        List<AgentSkillDto> selected = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                sixSkills, "ơ bị dở à sao lại cổ vai gáy", history, null);

        assertThat(selected)
                .as("lượt phản bác giả định sai: discovery skill không được chi phối (tránh gợi ý vùng cơ thể giả định)")
                .noneMatch(s -> s.code() != null && s.code().contains("discovery_empathy_and_scope"));
        assertThat(selected)
                .as("không chèn skill chốt lịch/bán hàng vào lượt phản bác")
                .noneMatch(s -> s.code() != null && s.code().contains("booking_careful_preparation"))
                .noneMatch(s -> s.code() != null && s.code().contains("warm_nod_to_gentle_scheduling"));
    }

    // =========================================================================
    // ======================= STAGE P2 (Directive §4.1–4.8) ====================
    // =========================================================================

    private PagePersonaConfig personaNangSen() throws Exception {
        return objectMapper.readValue("""
                {
                  "botName": "Nàng Sen",
                  "selfPronoun": "em",
                  "customerPronoun": "mình"
                }
                """, PagePersonaConfig.class);
    }

    /** Catalog đa chi nhánh theo số liệu thật Page Nàng Sen: NS01 (HN 399k/60p, SG 570k/70p), NS04 (HN 670k/120p, SG 970k/120p). */
    private static List<PackageCatalogService.PackageSummary> buildMultiBranchCatalog() {
        return List.of(
                new PackageCatalogService.PackageSummary(
                        "HN:NS01:399000", "NS01", "HN", "Massage body tinh dầu", "MASSAGE",
                        List.of("toàn thân"), 60, "60 phút", 399_000L, "399k", "Phòng chung",
                        "Massage toàn thân thư giãn", "Đau mỏi cơ bản", "Quy trình 60 phút", false),
                new PackageCatalogService.PackageSummary(
                        "SG:NS01:570000", "NS01", "SG", "Massage body tinh dầu", "MASSAGE",
                        List.of("toàn thân"), 70, "70 phút", 570_000L, "570k", "Phòng chung",
                        "Massage toàn thân thư giãn", "Đau mỏi cơ bản", "Quy trình 70 phút", false),
                new PackageCatalogService.PackageSummary(
                        "HN:NS04:670000", "NS04", "HN", "Massage chuyên sâu kết hợp gội đầu", "MASSAGE",
                        List.of("toàn thân"), 120, "120 phút", 670_000L, "670k", "Phòng chung",
                        "Ngâm bồn + massage + gội đầu", "Đau mỏi nặng", "Quy trình 120 phút", false),
                new PackageCatalogService.PackageSummary(
                        "SG:NS04:970000", "NS04", "SG", "Massage chuyên sâu kết hợp gội đầu", "MASSAGE",
                        List.of("toàn thân"), 120, "120 phút", 970_000L, "970k", "Phòng chung",
                        "Ngâm bồn + massage + gội đầu", "Đau mỏi nặng", "Quy trình 120 phút", false)
        );
    }

    private static String joinBubbleTexts(List<FanpageBotResponse.ReplyBubble> bubbles) {
        return bubbles.stream().map(FanpageBotResponse.ReplyBubble::text)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // =========================================================================
    // TEST 19 (§4.1): Phân loại 4 tiểu loại phản ứng tiêu cực — tuyệt đối không
    // sụp mọi COMMUNICATION_FRICTION thành CONCISE_PREFERRED.
    // =========================================================================
    @Test
    @DisplayName("P2-T19 §4.1: phân loại đúng 4 tiểu loại negative reaction (correction/complaint/over-intrusion/concise-request)")
    void test19_negativeReactionSubtypeFourWayClassification() {
        // CUSTOMER_CORRECTION — khách đính chính fact bot nói sai
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "ơ bị dở à sao lại cổ vai gáy", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.CUSTOMER_CORRECTION);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "em có nói đau lưng đâu", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.CUSTOMER_CORRECTION);

        // COMMUNICATION_COMPLAINT — phàn nàn phong cách/phản hồi (KHÔNG khóa CONCISE dài hạn)
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "nói chuyện máy móc quá", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.COMMUNICATION_COMPLAINT);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "tư vấn lòng vòng thế", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.COMMUNICATION_COMPLAINT);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "đừng nói vòng vo nữa, báo giá đi", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.COMMUNICATION_COMPLAINT);

        // OVER_INTRUSION — phàn nàn bị hỏi dồn/ép chốt (dừng hỏi sâu, dừng chốt)
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "hỏi lắm thế", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.OVER_INTRUSION);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "đừng ép chốt nữa", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.OVER_INTRUSION);

        // EXPLICIT_CONCISE_REQUEST — CHỈ loại này mới được set CONCISE_PREFERRED
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "nói ngắn thôi", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.EXPLICIT_CONCISE_REQUEST);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "viết ngắn gọn giúp mình", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.EXPLICIT_CONCISE_REQUEST);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "trả lời thẳng vào ý chính giúp mình", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.EXPLICIT_CONCISE_REQUEST);

        // NONE — câu hỏi giá bình thường không phải phản ứng tiêu cực
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "giá bao nhiêu vậy", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.NONE);
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(
                "cho mình hỏi gói massage", NANG_SEN_TAXONOMY))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.NONE);
    }

    // =========================================================================
    // TEST 20 (§4.2): Lượt friction KHÔNG kèm hỏi giá -> fallback xin lỗi gọn,
    // 0 gói mới, 0 giá, 0 CTA đặt lịch/xin SĐT.
    // =========================================================================
    @Test
    @DisplayName("P2-T20 §4.2: lượt phàn nàn thuần (không hỏi giá) -> 0 giá/0 gói/0 CTA trong fallback")
    void test20_pureFrictionFallbackApologyWithoutPriceOrCta() throws Exception {
        List<PackageCatalogService.PackageSummary> catalog = buildMultiBranchCatalog();
        PagePersonaConfig persona = personaNangSen();

        List<FanpageBotResponse.ReplyBubble> bubbles = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", null, catalog, catalog, persona,
                "nói chuyện máy móc quá", "em", "mình", null, List.of());
        String joined = joinBubbleTexts(bubbles);
        assertThat(joined).containsIgnoringCase("xin lỗi");
        assertThat(joined)
                .doesNotContain("399").doesNotContain("570").doesNotContain("670").doesNotContain("970");
        assertThat(joined).doesNotContainIgnoringCase("đặt lịch");
        assertThat(joined).doesNotContain("NS01").doesNotContain("NS04");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                "nói chuyện máy móc quá", ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), null))
                .isEqualTo("FALLBACK_SERVICE_RECOVERY");

        // Lượt đính chính fact (không hỏi giá) cũng không được xả gói/giá/CTA
        List<FanpageBotResponse.ReplyBubble> correctionBubbles = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", null, catalog, catalog, persona,
                "em có nói đau lưng đâu", "em", "mình", null, List.of());
        String correctionJoined = joinBubbleTexts(correctionBubbles);
        assertThat(correctionJoined)
                .doesNotContain("399").doesNotContain("570").doesNotContain("670").doesNotContain("970");
        assertThat(correctionJoined).doesNotContainIgnoringCase("đặt lịch");
        assertThat(correctionJoined).doesNotContain("NS01").doesNotContain("NS04");
    }

    // =========================================================================
    // TEST 21 (§4.2 NGOẠI LỆ BẮT BUỘC): "Đừng nói vòng vo nữa, báo giá đi" —
    // phàn nàn + hỏi giá trong CÙNG lượt -> PHẢI trả lời đúng giá grounded.
    // =========================================================================
    @Test
    @DisplayName("P2-T21 §4.2: friction + yêu cầu giá tường minh -> PHẢI trả giá grounded, không bị chặn")
    void test21_frictionWithExplicitPriceDemandMustAnswerGroundedPrice() throws Exception {
        String msg = "đừng nói vòng vo nữa, báo giá đi";
        String msg2 = "không phải, tôi đang hỏi giá gói đó cơ";

        assertThat(CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(msg2)).isTrue();
        // Guard âm: "đừng báo giá nữa" là YÊU CẦU NGỪNG báo giá, không phải yêu cầu giá
        assertThat(CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage("đừng báo giá nữa")).isFalse();

        // Tầng price sub-mode không được trả NONE khi khách đang đòi giá
        assertThat(AdaptiveConversationIntelligence.classifyPriceIntentSubMode(msg))
                .as("phàn nàn vòng vo KÈM 'báo giá đi' vẫn phải nhận diện price intent")
                .isNotEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);
        assertThat(AdaptiveConversationIntelligence.classifyPriceIntentSubMode(msg2))
                .isNotEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);
        // Phàn nàn thuần vẫn NONE
        assertThat(AdaptiveConversationIntelligence.classifyPriceIntentSubMode("nói chuyện máy móc quá"))
                .isEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);

        // Tầng fallback: speech act phải là PRICE_INFO, bong bóng chứa giá grounded từ catalog
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                msg, ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), null))
                .isEqualTo("FALLBACK_PRICE_INFO");
        List<PackageCatalogService.PackageSummary> catalog = buildMultiBranchCatalog();
        List<FanpageBotResponse.ReplyBubble> bubbles = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", null, catalog, catalog, personaNangSen(),
                msg, "em", "mình", null, List.of());
        assertThat(joinBubbleTexts(bubbles))
                .as("khách đòi giá trong lượt phàn nàn -> fallback phải chứa giá grounded từ Catalog")
                .contains("399");

        // Planner: directive FEEDBACK_RECOVERY kèm giá phải CHO PHÉP báo giá
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        msg, List.of(
                                new FanpageBotRequest.ConversationTurn("user", "em bị đau mỏi cổ vai gáy"),
                                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em xin phép chia sẻ chi tiết liệu trình ạ...")
                        ), null, ConversationState.StructuredBookingState.none());
        assertThat(plan.priceIntentSubMode())
                .isNotEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);
        assertThat(plan.actionExecutionDirective())
                .as("directive lượt friction+hỏi giá phải CHO PHÉP trả lời giá, không ghi '0 báo giá' mù")
                .containsIgnoringCase("giá");
    }

    // =========================================================================
    // TEST 22 (§5.2-20): previous_outcome=REJECTED / friction trước đó KHÔNG được
    // khóa vĩnh viễn các lượt sau — lượt hỏi giá mới vẫn được trả lời giá.
    // =========================================================================
    @Test
    @DisplayName("P2-T22 §5.2-20: lượt hỏi giá SAU lượt bị phản bác vẫn được planner trả lời giá (0 khóa vĩnh viễn)")
    void test22_rejectedPriorTurnDoesNotLockLaterPriceTurn() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "dạo này em hay bị tê chân"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ với tình trạng của mình thì em nghĩ gói massage cổ vai gáy sẽ hợp ạ."),
                new FanpageBotRequest.ConversationTurn("user", "ơ bị dở à sao lại cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ em xin lỗi mình vì hiểu nhầm ạ.")
        );
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        "gói massage toàn thân giá bao nhiêu", history, null,
                        ConversationState.StructuredBookingState.none());
        assertThat(plan.priceIntentSubMode())
                .as("lượt hỏi giá sau khi correction không được bị khóa về NONE")
                .isNotEqualTo(AdaptiveConversationIntelligence.PriceIntentSubMode.NONE);
        assertThat(plan.recommendedAction())
                .as("hành động phải là trả lời giá, không phải FEEDBACK_RECOVERY/DEESCALATION từ lượt trước")
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.SPECIFIC_PRICE_ANSWER);
    }

    // =========================================================================
    // TEST 23 (§4.7, cấm #6): KHÔNG mặc định preferredRegion="HN" khi khách
    // chưa nêu chi nhánh — phải trả null (UNSPECIFIED) để giữ cả 2 miền giá.
    // =========================================================================
    @Test
    @DisplayName("P2-T23 §4.7: detectPreferredRegion trả null khi khách chưa nêu chi nhánh (0 default HN)")
    void test23_noDefaultHanoiRegionWhenCustomerNeverStatedBranch() {
        assertThat(ConversationState.detectPreferredRegion("giá bao nhiêu vậy", null))
                .as("không có cue chi nhánh + chưa có vùng hiện tại -> null, KHÔNG default HN")
                .isNull();
        assertThat(ConversationState.detectPreferredRegion("", null)).isNull();
        assertThat(ConversationState.detectPreferredRegion(null, null)).isNull();
        assertThat(ConversationState.detectPreferredRegion("em ở hà nội", null)).isEqualTo("HN");
        assertThat(ConversationState.detectPreferredRegion("mình ở quận 1", null)).isEqualTo("SG");
        assertThat(ConversationState.detectPreferredRegion("ok em", "SG"))
                .as("đã có vùng từ trước -> giữ nguyên")
                .isEqualTo("SG");

        // Helper phân giải vùng cho prompt: tin nhắn -> lịch sử (user) -> vùng hiện tại -> null
        assertThat(ConversationState.resolveExplicitRegionAcrossTurns("giá bao nhiêu", List.of(), null)).isNull();
        assertThat(ConversationState.resolveExplicitRegionAcrossTurns("em ở quận 1", List.of(), null)).isEqualTo("SG");
        assertThat(ConversationState.resolveExplicitRegionAcrossTurns(
                "ok em", List.of(new FanpageBotRequest.ConversationTurn("user", "em ở hà nội")), null))
                .isEqualTo("HN");
        assertThat(ConversationState.resolveExplicitRegionAcrossTurns("ok em", List.of(), "SG")).isEqualTo("SG");
    }

    // =========================================================================
    // TEST 24 (§4.7, §5.2-21/22): Catalog đa chi nhánh — không lọc mất biến thể
    // khi chưa biết chi nhánh; dedup KHÔNG được gộp mất biến thể cùng mã/thời
    // lượng nhưng khác chi nhánh (NS04 670k HN vs 970k SG).
    // =========================================================================
    @Test
    @DisplayName("P2-T24 §4.7: filterForRegion(null) giữ đủ biến thể; dedup region-aware giữ cả NS04 HN 670k lẫn SG 970k")
    void test24_multiBranchVariantsPreservedAndDedupRegionAware() {
        List<PackageCatalogService.PackageSummary> all = buildMultiBranchCatalog();

        assertThat(PackageCatalogService.filterForRegion(all, null))
                .as("chưa biết chi nhánh -> giữ đủ 4 biến thể, không mặc định HN")
                .hasSize(4);
        assertThat(PackageCatalogService.filterForRegion(all, "")).hasSize(4);

        List<PackageCatalogService.PackageSummary> sg = PackageCatalogService.filterForRegion(all, "SG");
        assertThat(sg).hasSize(2);
        assertThat(sg).allMatch(p -> "SG".equals(p.region()));
        assertThat(sg).anySatisfy(p -> {
            assertThat(p.code()).isEqualTo("NS04");
            assertThat(p.priceAmountVnd()).isEqualTo(970_000L);
        });

        List<PackageCatalogService.PackageSummary> dedup =
                CleanAgentCatalogAndPromptBuilder.deduplicateCatalogByCanonicalSignature(all);
        assertThat(dedup)
                .as("dedup phải region-aware: NS04 HN 670k/120p và NS04 SG 970k/120p là 2 biến thể khác nhau")
                .hasSize(4);
        assertThat(dedup).anySatisfy(p -> {
            assertThat(p.code()).isEqualTo("NS04");
            assertThat(p.priceAmountVnd()).isEqualTo(670_000L);
        });
        assertThat(dedup).anySatisfy(p -> {
            assertThat(p.code()).isEqualTo("NS04");
            assertThat(p.priceAmountVnd()).isEqualTo(970_000L);
        });
    }

    // =========================================================================
    // TEST 25 (§4.7): Dòng catalog trong prompt phải gắn nhãn chi nhánh cho gói
    // BRANCH_SPECIFIC để Brain báo đúng giá theo chi nhánh.
    // =========================================================================
    @Test
    @DisplayName("P2-T25 §4.7: catalog line mang nhãn branch cho gói BRANCH_SPECIFIC")
    void test25_catalogLineCarriesBranchLabelForBranchSpecificPackages() {
        List<PackageCatalogService.PackageSummary> all = buildMultiBranchCatalog();
        StringBuilder sbHn = new StringBuilder();
        CleanAgentCatalogAndPromptBuilder.appendAciUnblindedCatalogLine(
                sbHn, 1, "PRIMARY", all.get(0),
                CleanAgentCatalogAndPromptBuilder.CatalogViewMode.NEED_MATCHED_RECOMMENDATION, false);
        assertThat(sbHn.toString()).contains("branch=\"HN\"");

        StringBuilder sbSg = new StringBuilder();
        CleanAgentCatalogAndPromptBuilder.appendAciUnblindedCatalogLine(
                sbSg, 2, "PRIMARY", all.get(1),
                CleanAgentCatalogAndPromptBuilder.CatalogViewMode.NEED_MATCHED_RECOMMENDATION, false);
        assertThat(sbSg.toString()).contains("branch=\"SG\"");
    }

    // =========================================================================
    // TEST 26 (§4.5/§4.6, §5.2-24): TRIAL_COMMITMENT_LOCK — khóa đúng gói khách
    // chọn, CẤM upsell, và directive KHÔNG được hardcode giá lịch sử.
    // =========================================================================
    @Test
    @DisplayName("P2-T26 §4.6: trial lock giữ gói khách chọn, 0 upsell, 0 giá hardcode trong directive")
    void test26_trialCommitmentLockNoUpsellNoHardcodedPrice() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "em bị đau đầu khó ngủ"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ bên em có gói massage thư giãn cơ bản giúp dễ ngủ ạ.")
        );
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        "tôi muốn vừa đủ để thử", history, null, ConversationState.StructuredBookingState.none());
        assertThat(plan.recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.TRIAL_COMMITMENT_LOCK);
        assertThat(plan.actionExecutionDirective())
                .as("directive trial lock phải cấm upsell")
                .containsIgnoringCase("upsell");
        assertThat(plan.actionExecutionDirective())
                .as("directive không được hardcode mức giá lịch sử (cấm #8)")
                .doesNotMatch("(?is).*\\d{2,4}\\s*k\\b.*");
    }

    // =========================================================================
    // TEST 27 (§4.5): CUSTOMER_CORRECTION_PIVOT — chuyển thẳng theo yêu cầu
    // khách, 0 hỏi lại vòng vo, 0 giá hardcode trong directive.
    // =========================================================================
    @Test
    @DisplayName("P2-T27 §4.5: correction pivot đi thẳng theo yêu cầu khách, directive 0 hardcode giá")
    void test27_correctionPivotDirectiveHasNoHardcodedPrice() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "em bị đau đầu khó ngủ"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ bên em có gói gội đầu dưỡng sinh phục hồi tóc ạ.")
        );
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        "tôi không muốn chăm sóc tóc, chỉ muốn massage", history, null,
                        ConversationState.StructuredBookingState.none());
        assertThat(plan.recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.CUSTOMER_CORRECTION_PIVOT);
        assertThat(plan.actionExecutionDirective())
                .doesNotMatch("(?is).*\\d{2,4}\\s*k\\b.*");
    }

    // =========================================================================
    // TEST 28 (§5.2-26): Hỏi khuyến mãi/ưu đãi -> KHÔNG bị xử lý như phản đối
    // giá, KHÔNG tự bịa voucher/giảm giá ngoài dữ liệu.
    // =========================================================================
    @Test
    @DisplayName("P2-T28 §5.2-26: hỏi khuyến mãi không phải price objection, fallback không bịa voucher/giảm giá")
    void test28_promoInquiryNotPriceObjectionAndNoFabricatedVoucher() throws Exception {
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        "hiện có khuyến mãi gì không", List.of(), null,
                        ConversationState.StructuredBookingState.none());
        assertThat(plan.recommendedAction())
                .as("hỏi khuyến mãi không được xếp thành xử lý phản đối giá")
                .isNotIn(AdaptiveConversationIntelligence.TurnAction.RESPECTFUL_DEESCALATION,
                        AdaptiveConversationIntelligence.TurnAction.ALTERNATIVE_OFFER);

        List<PackageCatalogService.PackageSummary> catalog = buildMultiBranchCatalog();
        List<FanpageBotResponse.ReplyBubble> bubbles = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", null, catalog, catalog, personaNangSen(),
                "hiện có khuyến mãi gì không", "em", "mình", null, List.of());
        String joined = joinBubbleTexts(bubbles);
        assertThat(joined)
                .as("catalog không có khuyến mãi -> tuyệt đối không bịa voucher/%/giảm giá")
                .doesNotContainIgnoringCase("voucher")
                .doesNotMatch("(?is).*giảm\\s*\\d+.*")
                .doesNotMatch("(?is).*\\d+\\s*%.*");
    }

    // =========================================================================
    // TEST 29 (§5.2-28): "Để xem đã" — tôn trọng tạm dừng, 0 hỏi vặn, 0 giá,
    // 0 ép đặt lịch.
    // =========================================================================
    @Test
    @DisplayName("P2-T29 §5.2-28: 'để xem đã' -> respectful pause, 0 câu hỏi, 0 giá, 0 ép lịch")
    void test29_deferralRespectfulPauseNoQuestionNoPrice() throws Exception {
        String msg = "để xem đã";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isExplicitConversationClosingDeferral(msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                msg, ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), null))
                .isEqualTo("FALLBACK_RESPECTED_PAUSE");

        List<PackageCatalogService.PackageSummary> catalog = buildMultiBranchCatalog();
        List<FanpageBotResponse.ReplyBubble> bubbles = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", null, catalog, catalog, personaNangSen(),
                msg, "em", "mình", null, List.of());
        String joined = joinBubbleTexts(bubbles);
        assertThat(joined).doesNotContain("?");
        assertThat(joined)
                .doesNotContain("399").doesNotContain("570").doesNotContain("670").doesNotContain("970");
        assertThat(joined).doesNotContainIgnoringCase("đặt lịch");
    }

    // =========================================================================
    // TEST 30 (§4.8): Prompt (file ngoài + template in-code) không còn hardcode
    // giá lịch sử; quy tắc subtype §4.1 / ngoại lệ giá §4.2 / nhãn chi nhánh
    // §4.7 phải hiện diện.
    // =========================================================================
    @Test
    @DisplayName("P2-T30 §4.8: prompt 0 hardcode giá lịch sử + có quy tắc subtype/ngoại lệ giá/đa chi nhánh")
    void test30_promptHasNoHardcodedHistoricalPricesAndCarriesP2Rules() throws Exception {
        String md = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "config", "aci_v2_system_prompt.md"));
        String template = CleanAgentCatalogAndPromptBuilder.getInCodeAciPromptTemplate();
        for (String hardcode : new String[]{"239k", "339k", "399k", "350k", "530k", "570k", "580k", "670k", "970k", "990k"}) {
            assertThat(md).as("prompt md không được hardcode " + hardcode).doesNotContain(hardcode);
            assertThat(template).as("template in-code không được hardcode " + hardcode).doesNotContain(hardcode);
        }
        // §4.1: CONCISE_PREFERRED chỉ khi khách yêu cầu ngắn gọn tường minh
        assertThat(md).contains("chỉ khi khách yêu cầu ngắn gọn");
        assertThat(template).contains("chỉ khi khách yêu cầu ngắn gọn");
        // §4.2: ngoại lệ bắt buộc — khách vừa phàn nàn vừa đòi giá thì PHẢI báo giá
        assertThat(md).containsIgnoringCase("báo giá đi");
        assertThat(template).containsIgnoringCase("báo giá đi");
        // §4.7: quy tắc đa chi nhánh trong prompt
        assertThat(md).containsIgnoringCase("branch=");
        assertThat(template).containsIgnoringCase("branch=");
    }

    // =========================================================================
    // TEST 31 (P3 §4.9/§5.1): decision_trace phải hiện diện với ĐỦ trường bắt
    // buộc trên lượt correction — và tuyệt đối không rò rỉ raw message (PII).
    // =========================================================================
    @Test
    @DisplayName("P3-T31 §4.9: decision_trace đủ trường trên lượt correction, hash SHA-256 rút gọn, 0 rò rỉ raw message")
    void test31_decisionTraceCompletenessOnCorrectionTurnAndPiiRedaction() throws Exception {
        String msg = "ơ bị dở à sao lại cổ vai gáy";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "dạo này em hay bị tê chân"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ với tình trạng của mình thì em nghĩ gói massage cổ vai gáy sẽ hợp ạ.")
        );
        ConversationState prior = ConversationState
                .hydrateForTurn(PAGE_ID, SENDER, null, history, NANG_SEN_TAXONOMY, List.of());
        ConversationState post = prior.enrichWithCurrentMessage(msg, NANG_SEN_TAXONOMY);

        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection =
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty()
                        .withConfirmedFactsDelta(List.of(
                                new AdaptiveConversationIntelligence.FactDeltaProposal(
                                        "self_reported_symptom", "khó ngủ",
                                        AdaptiveConversationIntelligence.FactMutationOp.ADD, "HIGH")));

        List<AgentSkillDto> sixSkills = buildNangSenSixSkills();
        List<PackageCatalogService.PackageSummary> catalog = buildMultiBranchCatalog();
        List<FanpageBotResponse.ReplyBubble> finalBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em xin lỗi vì câu trả lời trước chưa bám sát những gì mình chia sẻ ạ.", 0));

        com.example.docmind.dto.bot.DecisionTrace trace = CleanAgentOrchestratorService.buildAciV3DecisionTrace(
                "T3", msg, reflection,
                AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial(),
                prior, post, null,
                sixSkills, List.of(),           // correction turn -> selected rỗng (gate §3.4)
                List.of(), List.of(),
                catalog.subList(0, 2), catalog.subList(2, 4),
                List.of("Dạ em rất thương khi nghe mình vất vả vậy ạ."),  // raw LLM có cliché bị cấm
                finalBubbles,
                false,
                NANG_SEN_TAXONOMY);

        assertThat(trace).isNotNull();
        assertThat(trace.turnId()).isEqualTo("T3");
        assertThat(trace.customerMessageHash())
                .as("hash SHA-256 rút gọn 16 hex, deterministic")
                .isEqualTo(com.example.docmind.dto.bot.DecisionTrace.sha256Short(msg))
                .hasSize(16)
                .matches("[0-9a-f]{16}");
        assertThat(trace.negativeReactionSubtype())
                .as("'sao lại cổ vai gáy' phải là CUSTOMER_CORRECTION (§4.1)")
                .isEqualTo("CUSTOMER_CORRECTION");
        assertThat(trace.factChanges().extractedPains()).contains("khó ngủ");
        assertThat(trace.factChanges().knownBodyAreas())
                .contains("chân")
                .noneMatch(s -> s.contains("cổ"))
                .noneMatch(s -> s.contains("vai"))
                .noneMatch(s -> s.contains("gáy"));
        assertThat(trace.scopesBefore()).contains("chân");
        assertThat(trace.scopesAfter()).contains("chân");
        assertThat(trace.selectedSkills()).isEmpty();
        assertThat(trace.excludedSkillsOrRulesOnCorrection())
                .as("lượt correction phải ghi rõ 6 skill bị loại bởi correction-turn gate")
                .hasSize(6)
                .allSatisfy(e -> assertThat(e).startsWith("skill:").contains("CORRECTION_TURN_GATE"));
        assertThat(trace.catalogCandidates())
                .anySatisfy(c -> {
                    assertThat(c.productId()).isEqualTo("HN:NS01:399000");
                    assertThat(c.branch()).isEqualTo("HN");
                    assertThat(c.priceDisplay()).isEqualTo("399k");
                    assertThat(c.durationLabel()).isEqualTo("60 phút");
                });
        assertThat(trace.responsePlan()).isNotNull();
        assertThat(trace.responsePlan().explicitPriceDemand()).isFalse();
        assertThat(trace.sanitizerMutations().falseApologyRewritten())
                .as("bubble cuối chứa văn bản rewrite của False Apology Guard -> mutation được ghi nhận")
                .isTrue();
        assertThat(trace.sanitizerMutations().clicheEmpathyStripped())
                .as("raw LLM có 'em rất thương khi nghe mình...' nhưng output cuối không còn -> stripped")
                .isTrue();
        assertThat(trace.sanitizerMutations().priceGroundingApplied()).isFalse();

        // PII redaction: serialize ra JSON — tuyệt đối không chứa raw message, đủ khóa bắt buộc.
        JsonNode json = objectMapper.valueToTree(trace);
        for (String key : new String[]{"turn_id", "customer_message_hash", "detected_intent",
                "negative_reaction_subtype", "fact_changes", "scopes_before", "scopes_after",
                "selected_skills", "excluded_skills_or_rules_on_correction", "catalog_candidates",
                "response_plan", "sanitizer_mutations"}) {
            assertThat(json.has(key)).as("decision_trace phải có khóa " + key).isTrue();
        }
        assertThat(json.get("fact_changes").has("retracted_bot_assumptions")).isTrue();
        assertThat(json.get("response_plan").has("explicit_price_demand")).isTrue();
        assertThat(json.toString())
                .as("raw customer message tuyệt đối không xuất hiện trong trace (PII)")
                .doesNotContain("ơ bị dở");
    }

    // =========================================================================
    // TEST 32 (P3 §4.9/§4.2): lượt complaint kèm yêu cầu giá tường minh — trace
    // ghi explicit_price_demand=true, price_grounding_applied=true khi final
    // bubbles báo giá, và KHÔNG đổ correction exclusions (subtype ≠ correction).
    // =========================================================================
    @Test
    @DisplayName("P3-T32 §4.9/§4.2: lượt 'đừng nói vòng vo, báo giá đi' -> price demand + grounding được trace, 0 exclusion")
    void test32_decisionTraceOnStyleComplaintWithExplicitPriceDemand() throws Exception {
        String msg = "đừng nói vòng vo nữa, báo giá đi";
        ConversationState prior = ConversationState
                .hydrateForTurn(PAGE_ID, SENDER, null, List.of(
                        new FanpageBotRequest.ConversationTurn("user", "đau mỏi chân và khó ngủ quá")),
                        NANG_SEN_TAXONOMY, List.of());
        ConversationState post = prior.enrichWithCurrentMessage(msg, NANG_SEN_TAXONOMY);

        List<PackageCatalogService.PackageSummary> catalog = buildMultiBranchCatalog();
        List<FanpageBotResponse.ReplyBubble> finalBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ gói NS01 ở Hà Nội là 399k/60 phút ạ.", 0));

        com.example.docmind.dto.bot.DecisionTrace trace = CleanAgentOrchestratorService.buildAciV3DecisionTrace(
                "T2", msg,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial(),
                prior, post, "HN",
                buildNangSenSixSkills(), buildNangSenSixSkills().subList(0, 1),
                List.of(), List.of(),
                catalog.subList(0, 1), List.of(),
                List.of("Dạ gói NS01 ở Hà Nội là 399k/60 phút ạ."),
                finalBubbles,
                false,
                NANG_SEN_TAXONOMY);

        assertThat(trace.negativeReactionSubtype())
                .as("phàn nàn phong cách kèm đòi giá -> COMMUNICATION_COMPLAINT, không phải correction")
                .isEqualTo("COMMUNICATION_COMPLAINT");
        assertThat(trace.responsePlan().explicitPriceDemand())
                .as("'báo giá đi' là yêu cầu giá tường minh (§4.2)")
                .isTrue();
        assertThat(trace.sanitizerMutations().priceGroundingApplied())
                .as("final bubble có giá 399k và không safe-handoff -> grounding đã áp")
                .isTrue();
        assertThat(trace.sanitizerMutations().falseApologyRewritten()).isFalse();
        assertThat(trace.excludedSkillsOrRulesOnCorrection())
                .as("không phải correction turn -> không đổ correction exclusions")
                .isEmpty();
        assertThat(trace.selectedSkills())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.code()).isEqualTo("discovery_empathy_and_scope");
                    assertThat(s.reason()).isNotBlank();
                });
        assertThat(trace.factChanges().preferredRegion()).isEqualTo("HN");

        // wither gắn trace vào response và serialize ra đúng khóa decision_trace
        FanpageBotResponse resp = new FanpageBotResponse(
                "Dạ gói NS01 ở Hà Nội là 399k/60 phút ạ.", finalBubbles, "VALUE_CONSULTATION", 1.0,
                false, null, null, null, List.of(), null, List.of())
                .withDecisionTrace(trace);
        JsonNode respJson = objectMapper.valueToTree(resp);
        assertThat(respJson.has("decision_trace")).isTrue();
        assertThat(respJson.get("decision_trace").get("negative_reaction_subtype").asText())
                .isEqualTo("COMMUNICATION_COMPLAINT");
    }

    // =========================================================================
    // TEST 33 (P3 Fix-A): đại từ đứng giữa "xin lỗi" và "vì" + động từ
    // "nhắc sang" — biến thể thực tế từ stability run 9.
    // =========================================================================
    @Test
    @DisplayName("P3-T33 Fix-A: 'Em xin lỗi mình vì đã nhắc sang cổ vai gáy' (đại từ giữa xin lỗi-vì) bị viết lại khi bot chưa từng nhắc")
    void test33_falseApologyPronounGapAndNhacSangVariantRewritten() {
        List<String> priorAssistantTexts = List.of(
                "Dạ em nghe mình chia sẻ rồi ạ.",
                "Dạ mình đang bị hoa mắt, chóng mặt, tê chân và khó ngủ đúng không ạ?"
        );
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi mình vì đã nhắc sang cổ vai gáy, trong khi mình chỉ kể hoa mắt chóng mặt, tê chân và khó ngủ thôi ạ.", 0),
                new FanpageBotResponse.ReplyBubble(
                        "Mình kể em nghe thêm một chút nhé, lúc hoa mắt chóng mặt ấy thì mình thường đang làm gì ạ?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> guarded =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        bubbles, priorAssistantTexts, List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ"),
                        "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        assertThat(guarded.get(0).text())
                .as("bot chưa từng nhắc cổ vai gáy -> câu tự nhận 'đã nhắc sang' bị viết lại")
                .containsIgnoringCase("xin lỗi")
                .doesNotContainIgnoringCase("cổ vai gáy")
                .doesNotContainIgnoringCase("nhắc sang");
        assertThat(guarded.get(0).text())
                .as("vế ghi nhận đúng triệu chứng khách đã chia sẻ được nối vào lời xin lỗi")
                .containsIgnoringCase("hoa mắt")
                .containsIgnoringCase("chóng mặt")
                .containsIgnoringCase("tê chân")
                .containsIgnoringCase("khó ngủ");
        assertThat(guarded.get(1).text())
                .as("bubble hỏi khám phá thuần của LLM được giữ nguyên, không sinh thêm câu hỏi thứ hai")
                .contains("lúc hoa mắt chóng mặt");
        assertThat(guarded).hasSize(2);
    }

    // =========================================================================
    // TEST 34 (P3 Fix-A): các biến thể thực tế từ stability runs 2/11/12 —
    // "nhắc tới vùng", "nhắc chưa đúng ý ... chứ không phải", "đoán nhầm sang".
    // =========================================================================
    @Test
    @DisplayName("P3-T34 Fix-A: 'nhắc tới vùng cổ vai gáy' / 'nhắc chưa đúng ý... chứ không phải cổ vai gáy' / 'đoán nhầm sang' đều bị viết lại")
    void test34_falseApologyNhacToiChuaDungYDoanNhamVariantsRewritten() {
        List<String> priorAssistantTexts = List.of("Dạ em nghe mình chia sẻ rồi ạ.");
        List<String> symptoms = List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ");

        List<FanpageBotResponse.ReplyBubble> g1 =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Dạ em xin lỗi mình vì đã nhắc tới vùng cổ vai gáy khi mình chưa hề nói tới, là em nói vội quá ạ.", 0)),
                        priorAssistantTexts, symptoms, "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        assertThat(g1.get(0).text())
                .containsIgnoringCase("xin lỗi")
                .doesNotContainIgnoringCase("cổ vai gáy")
                .doesNotContainIgnoringCase("đã nhắc");

        List<FanpageBotResponse.ReplyBubble> g2 =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Dạ em xin lỗi mình vì đã nhắc chưa đúng ý, mình đang chia sẻ hoa mắt chóng mặt, tê chân và khó ngủ chứ không phải cổ vai gáy, em ghi nhận lại đúng như vậy ạ.", 0)),
                        priorAssistantTexts, symptoms, "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        assertThat(g2.get(0).text())
                .containsIgnoringCase("xin lỗi")
                .doesNotContainIgnoringCase("cổ vai gáy")
                .doesNotContainIgnoringCase("đã nhắc");

        List<FanpageBotResponse.ReplyBubble> g3 =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Dạ em xin lỗi mình, em đoán nhầm sang cổ vai gáy trong khi mình chỉ kể hoa mắt, chóng mặt, tê chân và khó ngủ.", 0)),
                        priorAssistantTexts, symptoms, "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        assertThat(g3.get(0).text())
                .containsIgnoringCase("xin lỗi")
                .doesNotContainIgnoringCase("cổ vai gáy");
    }

    // =========================================================================
    // TEST 35 (P3 Fix-A): KHÔNG được over-trigger — xin lỗi có căn cứ (bot đã
    // nói thật) và tham chiếu mô tả phía khách ("vùng mình không cần chăm sóc")
    // đều phải giữ nguyên.
    // =========================================================================
    @Test
    @DisplayName("P3-T35 Fix-A: xin lỗi grounded (bot ĐÃ nói) và tham chiếu phía khách đều được giữ nguyên")
    void test35_groundedOrCustomerRelativeApologyPreserved() {
        List<String> priorAssistantTexts = List.of(
                "Dạ với tình trạng của mình thì em nghĩ gói massage cổ vai gáy sẽ hợp ạ.");

        // Grounded: bot đã thật sự nhắc cổ vai gáy -> biến thể "nhắc sang" cũng được giữ
        List<FanpageBotResponse.ReplyBubble> grounded =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Em xin lỗi mình vì đã nhắc sang cổ vai gáy trong khi mình chưa hỏi về vùng đó ạ.", 0)),
                        priorAssistantTexts, List.of(), null, "em", "mình");
        assertThat(grounded.get(0).text())
                .as("bot đã nhắc cổ vai gáy thật -> lời xin lỗi có căn cứ, không bị viết lại")
                .containsIgnoringCase("cổ vai gáy")
                .containsIgnoringCase("nhắc sang");

        // Customer-relative: "vùng mình không cần chăm sóc" là trạng thái của khách, không phải
        // nội dung bot bị quy kết đã nói -> không viết lại (tránh over-trigger run 10/18)
        List<FanpageBotResponse.ReplyBubble> customerRelative =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Em xin lỗi mình vì đã nhắc tới vùng mình không cần chăm sóc ạ.", 0)),
                        List.of("Dạ em nghe mình chia sẻ rồi ạ."), List.of(), null, "em", "mình");
        assertThat(customerRelative.get(0).text())
                .as("tham chiếu 'vùng mình không cần chăm sóc' là phía khách -> giữ nguyên, không rewrite mù")
                .containsIgnoringCase("chăm sóc")
                .containsIgnoringCase("nhắc tới");
    }

    // =========================================================================
    // TEST 36 (P3 Fix-B): lượt CUSTOMER_CORRECTION không đòi giá -> strip sạch
    // câu giá/gói/câu hỏi chốt chi nhánh, nối câu hỏi mở bám triệu chứng.
    // =========================================================================
    @Test
    @DisplayName("P3-T36 Fix-B: lượt correction (0 explicit price demand) -> strip pitch giá/gói/chốt chi nhánh, nối câu hỏi mở bám triệu chứng")
    void test36_correctionTurnStripsUnsolicitedPricePitchAndAppendsSymptomQuestion() {
        List<String> symptoms = List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ");
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em xin lỗi vì câu trả lời trước chưa bám sát những gì mình chia sẻ ạ.", 0),
                new FanpageBotResponse.ReplyBubble(
                        "Bên em có gói massage thư giãn toàn thân 60 phút, khởi điểm 399k ở Hà Nội và 570k cho bản 70 phút ở Sài Gòn, không gian yên tĩnh ạ. Mình đang ở khu vực nào để em tư vấn đúng cơ sở ạ?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> cleaned =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        bubbles, "ơ bị dở à sao lại cổ vai gáy", symptoms, NANG_SEN_TAXONOMY, "em", "mình");
        String joined = joinBubbleTexts(cleaned);
        assertThat(joined)
                .doesNotContain("399").doesNotContain("570")
                .doesNotContainIgnoringCase("gói massage")
                .doesNotContainIgnoringCase("khu vực nào");
        assertThat(joined)
                .as("câu hỏi mở bám đúng 4 triệu chứng khách đã chia sẻ (gate tc5)")
                .containsIgnoringCase("hoa mắt")
                .containsIgnoringCase("chóng mặt")
                .containsIgnoringCase("tê chân")
                .containsIgnoringCase("khó ngủ");
        assertThat(joined).contains("?");
    }

    // =========================================================================
    // TEST 37 (P3 Fix-B, §4.2): ngoại lệ bắt buộc — friction nhưng khách ĐÒI GIÁ
    // tường minh -> tuyệt đối không cắt nội dung giá grounded.
    // =========================================================================
    @Test
    @DisplayName("P3-T37 Fix-B §4.2: friction + 'báo giá đi' (explicit price demand) -> KHÔNG cắt nội dung giá")
    void test37_frictionWithExplicitPriceDemandKeepsGroundedPriceAnswer() {
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ gói NS01 ở Hà Nội là 399k/60 phút ạ.", 0)
        );
        List<FanpageBotResponse.ReplyBubble> kept =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        bubbles, "đừng nói vòng vo nữa, báo giá đi", List.of(), NANG_SEN_TAXONOMY, "em", "mình");
        assertThat(kept).hasSize(1);
        assertThat(joinBubbleTexts(kept))
                .as("§4.2: explicit price demand phải được trả lời giá grounded, guard không can thiệp")
                .contains("399");
    }

    // =========================================================================
    // TEST 38 (P3 Fix-B): lượt hỏi giá/gói bình thường (subtype NONE) -> guard
    // hoàn toàn không can thiệp; lượt OVER_INTRUSION -> strip pitch nhưng giữ
    // nguyên chính sách 0 câu hỏi.
    // =========================================================================
    @Test
    @DisplayName("P3-T38 Fix-B: lượt thường (NONE) không can thiệp; OVER_INTRUSION strip pitch nhưng 0 câu hỏi được thêm")
    void test38_normalTurnUntouchedAndOverIntrusionStripsWithoutQuestion() {
        List<FanpageBotResponse.ReplyBubble> priceAnswer = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ gói massage body tinh dầu ở Hà Nội là 399k/60 phút ạ.", 0)
        );
        List<FanpageBotResponse.ReplyBubble> untouched =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        priceAnswer, "giá bao nhiêu vậy", List.of(), NANG_SEN_TAXONOMY, "em", "mình");
        assertThat(untouched).hasSize(1);
        assertThat(joinBubbleTexts(untouched)).contains("399");

        List<FanpageBotResponse.ReplyBubble> intrusionBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em xin lỗi vì đã làm mình khó chịu ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Gói massage cơ bản bên em từ 399k ạ. Mình đặt lịch luôn nhé?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> intrusionCleaned =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        intrusionBubbles, "đừng ép chốt nữa", List.of("đau lưng"), NANG_SEN_TAXONOMY, "em", "mình");
        String intrusionJoined = joinBubbleTexts(intrusionCleaned);
        assertThat(intrusionJoined)
                .doesNotContain("399")
                .doesNotContainIgnoringCase("đặt lịch")
                .doesNotContainIgnoringCase("gói massage");
        assertThat(intrusionJoined)
                .as("nhánh OVER_INTRUSION giữ nguyên chính sách 0 câu hỏi")
                .doesNotContain("?");
    }

    // =========================================================================
    // TEST 39 (P3 Fix-B): toàn bộ output đều là pitch -> thay bằng lời xin lỗi
    // trung thực kèm ghi nhận triệu chứng + câu hỏi mở (không để lượt rỗng).
    // =========================================================================
    @Test
    @DisplayName("P3-T39 Fix-B: mọi bubble đều là pitch -> fallback xin lỗi trung thực + ghi nhận triệu chứng + câu hỏi mở")
    void test39_allPitchOutputReplacedByHonestApologyAndQuestion() {
        List<String> symptoms = List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ");
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Gói Massage cơ bản toàn thân 60 phút bên em khởi điểm từ 399k ở Hà Nội và 570k ở Sài Gòn ạ.", 0)
        );
        List<FanpageBotResponse.ReplyBubble> cleaned =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        bubbles, "ơ bị dở à sao lại cổ vai gáy", symptoms, NANG_SEN_TAXONOMY, "em", "mình");
        String joined = joinBubbleTexts(cleaned);
        assertThat(cleaned).isNotEmpty();
        assertThat(joined)
                .doesNotContain("399").doesNotContain("570")
                .doesNotContainIgnoringCase("gói massage");
        assertThat(joined)
                .containsIgnoringCase("xin lỗi")
                .containsIgnoringCase("hoa mắt")
                .containsIgnoringCase("khó ngủ");
        assertThat(joined).contains("?");
    }

    // =========================================================================
    // TEST 40 (P3 Fix-A+B phối hợp): trên cùng lượt correction — rewrite xin lỗi
    // sai lịch sử rồi strip pitch; kết quả cuối chỉ còn lời xin lỗi trung thực
    // + ghi nhận triệu chứng + câu hỏi mở.
    // =========================================================================
    @Test
    @DisplayName("P3-T40 Fix-A+B: rewrite apology + strip pitch trên cùng lượt correction -> output cuối sạch giá/gói, bám triệu chứng")
    void test40_falseApologyRewriteThenPitchStripCombined() {
        List<String> priorAssistantTexts = List.of(
                "Dạ em nghe mình chia sẻ rồi ạ.",
                "Dạ mình đang bị hoa mắt, chóng mặt, tê chân và khó ngủ đúng không ạ?"
        );
        List<String> symptoms = List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ");
        List<FanpageBotResponse.ReplyBubble> llmDraft = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi mình vì đã nhắc sang cổ vai gáy, trong khi mình chỉ kể hoa mắt chóng mặt, tê chân và khó ngủ thôi ạ.", 0),
                new FanpageBotResponse.ReplyBubble(
                        "Với những gì mình chia sẻ thì một buổi massage toàn thân nhẹ nhàng sẽ dễ chịu hơn, gói Massage cơ bản 60 phút bên em từ 399k ạ. Mình muốn buổi vừa đủ để thử trước, hay muốn dài hơn một chút ạ?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> afterApology =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        llmDraft, priorAssistantTexts, symptoms, "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        List<FanpageBotResponse.ReplyBubble> finalOut =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        afterApology, "ơ bị dở à sao lại cổ vai gáy", symptoms, NANG_SEN_TAXONOMY, "em", "mình");
        String joined = joinBubbleTexts(finalOut);
        assertThat(joined).containsIgnoringCase("xin lỗi");
        assertThat(joined)
                .doesNotContainIgnoringCase("cổ vai gáy")
                .doesNotContain("399")
                .doesNotContainIgnoringCase("gói massage")
                .doesNotContainIgnoringCase("buổi vừa đủ");
        assertThat(joined)
                .containsIgnoringCase("hoa mắt")
                .containsIgnoringCase("chóng mặt")
                .containsIgnoringCase("tê chân")
                .containsIgnoringCase("khó ngủ");
        assertThat(joined).contains("?");
    }

    // =========================================================================
    // TEST 41 (P3 Fix-A, tc5): lượt correction mà lời xin lỗi GROUNDED (bot đã
    // nhắc thật) nhưng cả lượt không nhắc lại triệu chứng nào -> chèn vế ghi
    // nhận triệu chứng ngay sau câu xin lỗi, trước câu hỏi cuối.
    // =========================================================================
    @Test
    @DisplayName("P3-T41 Fix-A: xin lỗi grounded thiếu echo triệu chứng -> chèn vế ghi nhận sau câu xin lỗi, giữ nguyên câu hỏi")
    void test41_groundedApologyWithoutSymptomEchoGetsSymptomReconnect() {
        List<String> priorAssistantTexts = List.of(
                "Dạ em nghe mình chia sẻ rồi ạ.",
                "Dạ mình muốn ưu tiên thư giãn nhẹ nhàng vùng đầu cổ vai gáy hay dành thời gian cho toàn thân ạ?"
        );
        List<String> symptoms = List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ");
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi mình vì đã tự thêm vùng cổ vai gáy trong khi mình chưa hề nhắc tới ạ. Mình kể em nghe lúc này cơ thể mình đang khó chịu nhất ở đâu để em hiểu đúng ý mình không ạ?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> guarded =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        bubbles, priorAssistantTexts, symptoms, "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        assertThat(guarded).hasSize(1);
        String text = guarded.get(0).text();
        assertThat(text)
                .as("lời xin lỗi grounded được giữ nguyên (bot đã nhắc cổ vai gáy thật ở lượt trước)")
                .containsIgnoringCase("tự thêm vùng cổ vai gáy");
        assertThat(text)
                .as("vế ghi nhận triệu chứng được chèn để lượt sửa sai nối lại đúng lời khách (gate tc5)")
                .containsIgnoringCase("hoa mắt")
                .containsIgnoringCase("chóng mặt")
                .containsIgnoringCase("tê chân")
                .containsIgnoringCase("khó ngủ");
        assertThat(text.indexOf("ghi nhận"))
                .as("vế ghi nhận đứng sau câu xin lỗi và trước câu hỏi cuối của LLM")
                .isGreaterThan(text.indexOf("xin lỗi"))
                .isLessThan(text.indexOf("để em hiểu đúng ý mình không ạ?"));
    }

    // =========================================================================
    // TEST 42 (P3 Fix-A/B): nguồn triệu chứng fallback cho guard — luồng
    // LLM_COGNITIVE_V2 hydrate state tối giản (knownSymptoms rỗng) nên guard
    // trích triệu chứng trực tiếp từ lịch sử user; clause phủ định bị loại.
    // =========================================================================
    @Test
    @DisplayName("P3-T42 Fix-A/B: extractSymptomsMentionedInText bắt đúng triệu chứng khách kể, bỏ clause phủ định và scope bị correction")
    void test42_symptomExtractionFromFreeTextWithNegationFiltering() {
        assertThat(ConversationState.extractSymptomsMentionedInText("hoa mắt, chóng mặt, tê chân"))
                .containsExactly("hoa mắt", "chóng mặt", "tê chân");
        assertThat(ConversationState.extractSymptomsMentionedInText("khó ngủ"))
                .containsExactly("khó ngủ");
        assertThat(ConversationState.extractSymptomsMentionedInText("đau mỏi chân và khó ngủ quá"))
                .contains("khó ngủ");
        // Clause phủ định: triệu chứng khách PHỦ NHẬN không được echo
        assertThat(ConversationState.extractSymptomsMentionedInText("tôi không bị đau đầu, chỉ hơi chóng mặt thôi"))
                .doesNotContain("đau đầu")
                .contains("chóng mặt");
        // Câu correction nhắc scope không có tiền tố triệu chứng -> không sinh triệu chứng ảo
        assertThat(ConversationState.extractSymptomsMentionedInText("ơ bị dở à sao lại cổ vai gáy"))
                .isEmpty();
        assertThat(ConversationState.extractSymptomsMentionedInText(null)).isEmpty();
        assertThat(ConversationState.extractSymptomsMentionedInText("  ")).isEmpty();
    }

    // =========================================================================
    // TEST 43 (P3 Fix-A): biến thể "nhắc nhầm sang" (stability run 1 lần 2) —
    // trạng từ "nhầm/sai" chen giữa động từ nhắc/nói/hỏi và từ chỉ hướng.
    // =========================================================================
    @Test
    @DisplayName("P3-T43 Fix-A: 'xin lỗi mình vì đã nhắc nhầm sang vùng cổ vai gáy' bị viết lại khi bot chưa từng nhắc")
    void test43_falseApologyNhacNhamSangVariantRewritten() {
        List<String> priorAssistantTexts = List.of(
                "Dạ em nghe mình chia sẻ rồi ạ.",
                "Dạ mình đang bị hoa mắt, chóng mặt, tê chân và khó ngủ đúng không ạ?"
        );
        List<String> symptoms = List.of("hoa mắt", "chóng mặt", "tê chân", "khó ngủ");
        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em xin lỗi mình vì đã nhắc nhầm sang vùng cổ vai gáy, mình chưa hề nói tới vùng đó ạ.", 0),
                new FanpageBotResponse.ReplyBubble(
                        "Điều mình chia sẻ là hoa mắt, chóng mặt, tê chân và khó ngủ, em xin ghi nhận đúng như vậy, mình thấy khó chịu nhất ở đâu trước ạ?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> guarded =
                CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        bubbles, priorAssistantTexts, symptoms, "ơ bị dở à sao lại cổ vai gáy", "em", "mình");
        assertThat(guarded.get(0).text())
                .as("biến thể 'nhắc nhầm sang' tham chiếu nội dung bot chưa nói -> bị viết lại")
                .containsIgnoringCase("xin lỗi")
                .doesNotContainIgnoringCase("cổ vai gáy")
                .doesNotContainIgnoringCase("nhắc nhầm");
        assertThat(guarded.get(0).text())
                .containsIgnoringCase("hoa mắt")
                .containsIgnoringCase("khó ngủ");
        assertThat(guarded.get(1).text())
                .as("bubble hỏi khám phá của LLM giữ nguyên")
                .contains("khó chịu nhất ở đâu");
    }

    private static List<AgentSkillDto> buildNangSenSixSkills() {
        Instant now = Instant.now();
        String p = PAGE_ID.toString();
        return List.of(
                new AgentSkillDto(UUID.fromString("24000001-0000-4000-8000-000000000001"),
                        "discovery_empathy_and_scope", "Đồng cảm & Khám phá phạm vi", "PSYCHOLOGY", "",
                        "SHARING_CONDITION_OR_CONTEXT",
                        "Lắng nghe tình trạng khách chia sẻ rồi hỏi nhẹ một câu khám phá.",
                        "", "", 98, true, p, 0, 0, 0.96, now, now),
                new AgentSkillDto(UUID.fromString("24000001-0000-4000-8000-000000000002"),
                        "price_objection_sensory_value", "Thấu cảm giá & Giá trị trải nghiệm", "PSYCHOLOGY", "",
                        "PRICE_OBJECTION_FIRST_TIME,PRICE_OBJECTION",
                        "Không lặp thông số phụ, khắc họa giá trị trải nghiệm.",
                        "", "", 97, true, p, 0, 0, 0.96, now, now),
                new AgentSkillDto(UUID.fromString("24000001-0000-4000-8000-000000000003"),
                        "soft_economical_bridge_and_compare", "Nhịp cầu lựa chọn vừa vặn", "SALES_STRATEGY", "",
                        "PRICE_HESITATION_AFTER_VALUE_DEFENSE",
                        "Bắc nhịp cầu sang lựa chọn vừa ngân sách.",
                        "", "", 96, true, p, 0, 0, 0.95, now, now),
                new AgentSkillDto(UUID.fromString("24000001-0000-4000-8000-000000000004"),
                        "warm_nod_to_gentle_scheduling", "Đón nhận cái gật đầu ấm áp & Mời chọn giờ", "COMMUNICATION", "",
                        "POSITIVE_CONFIRMATION_SIGNAL",
                        "Đón nhận ấm áp và hỏi khung giờ thuận tiện.",
                        "", "", 95, true, p, 0, 0, 0.97, now, now),
                new AgentSkillDto(UUID.fromString("24000001-0000-4000-8000-000000000005"),
                        "booking_careful_preparation", "Chuẩn bị chu đáo & Kiểm tra lịch trống", "SALES_STRATEGY", "",
                        "ACCEPTED_PROPOSED_TIME_SLOT",
                        "Ghi nhận chu đáo và hẹn kiểm tra lịch trống.",
                        "", "", 94, true, p, 0, 0, 0.96, now, now),
                new AgentSkillDto(UUID.fromString("24000001-0000-4000-8000-000000000006"),
                        "graceful_pause_keep_warmth", "Tôn trọng khoảng nghỉ & Giữ hơi ấm", "COMMUNICATION", "",
                        "HESITATION_OR_DEFERRAL",
                        "Thoải mái không áp lực.",
                        "", "", 93, true, p, 0, 0, 0.96, now, now)
        );
    }
}
