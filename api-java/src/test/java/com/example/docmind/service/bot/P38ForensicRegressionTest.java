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
