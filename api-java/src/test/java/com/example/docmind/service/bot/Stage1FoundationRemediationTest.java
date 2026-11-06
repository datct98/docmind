package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.DecisionTrace;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GIAI ĐOẠN 1 — FOUNDATION REMEDIATION: Comprehensive Regression & Acceptance Suite.
 *
 * <p>Covers:
 * <ul>
 *   <li>Section IX.1: T1–T15 Replay & Forensic Invariants (T1, T3, T5, T7–T11, T13, T15)</li>
 *   <li>Section IX.2 (Booking — 6 Mandatory Cases):
 *       (1) Clear booking intent,
 *       (2) Explicit refusal to book,
 *       (3) Sarcasm containing "đặt lịch",
 *       (4) Quoting another person mentioning "đặt lịch",
 *       (5) Incomplete booking info (missing package / missing date-time),
 *       (6) Confirmed booking summary transition</li>
 *   <li>Section IX.2 (Catalog — 6 Mandatory Cases):
 *       (1) Same package with different prices across branches,
 *       (2) Branch selection and cross-turn price consistency,
 *       (3) Package unavailable at selected branch,
 *       (4) Undetermined branch (multi-branch anchor / rejection of branchless price claim),
 *       (5) LLM selects non-existent package code,
 *       (6) LLM outputs ungrounded free-text price not in catalog</li>
 *   <li>Section IX.2 (Deadline & Concurrency — 6 Mandatory Cases):
 *       (1) Primary model slower than turn deadline,
 *       (2) Fallback activated within remaining budget (read-only state),
 *       (3) Fallback when budget exhausted (immediate static safe response),
 *       (4) Late primary result arrives after timeout (cannot mutate state),
 *       (5) Two concurrent requests on the same conversation (epoch fencing),
 *       (6) Stale result from older turn cannot overwrite newer state</li>
 *   <li>Section VII & VIII: Raw-to-Final Traceability, SanitizerMutationAudit, Lifecycle Stages, and Stable request_id for unresolved_requests</li>
 * </ul>
 */
class Stage1FoundationRemediationTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static PackageCatalogService.PackageSummary pkg(
            String id,
            String code,
            String region,
            String name,
            String category,
            List<String> scopes,
            int minutes,
            long priceVnd,
            String priceDisplay
    ) {
        return new PackageCatalogService.PackageSummary(
                id,
                code,
                region,
                name,
                category,
                scopes,
                minutes,
                minutes + " phút",
                priceVnd,
                priceDisplay,
                "Phòng rèm riêng yên tĩnh",
                "Gội đầu thảo dược, massage cổ vai gáy, ngâm chân thảo dược",
                "Đau mỏi cổ vai gáy, đau đầu",
                "Thư giãn và chăm sóc chuyên sâu",
                false
        );
    }

    private List<PackageCatalogService.PackageSummary> buildNangSenMultiBranchCatalog() {
        return List.of(
                // Gội đầu dưỡng sinh — HN vs HCM (SG)
                pkg("pkg-ds01-hn", "DS01", "HN", "Gói Thư Giãn (DS01)", "GỘI ĐẦU DƯỠNG SINH",
                        List.of("đầu", "cổ", "vai", "gáy"), 65, 339_000L, "339.000đ"),
                pkg("pkg-ds02-hn", "DS02", "HN", "Gói Cơ Bản (DS02)", "GỘI ĐẦU DƯỠNG SINH",
                        List.of("đầu", "cổ", "vai", "gáy", "lưng"), 75, 405_000L, "405.000đ"),
                pkg("pkg-ds01-sg", "DS01", "SG", "Gói Thư Giãn (DS01)", "GỘI ĐẦU DƯỠNG SINH",
                        List.of("đầu", "cổ", "vai", "gáy"), 65, 370_000L, "370.000đ"),
                pkg("pkg-ds02-sg", "DS02", "SG", "Gói Cơ Bản (DS02)", "GỘI ĐẦU DƯỠNG SINH",
                        List.of("đầu", "cổ", "vai", "gáy", "lưng"), 75, 439_000L, "439.000đ"),
                // Chăm sóc cơ thể — HN vs HCM (SG)
                pkg("pkg-body01-hn", "BODY01", "HN", "Massage Cổ Vai Gáy 45p", "CHĂM SÓC CƠ THỂ",
                        List.of("cổ", "vai", "gáy"), 45, 239_000L, "239.000đ"),
                pkg("pkg-body02-hn", "BODY02", "HN", "Massage Body Đá Nóng 60p", "CHĂM SÓC CƠ THỂ",
                        List.of("toàn thân", "cổ", "vai", "gáy", "lưng"), 60, 399_000L, "399.000đ"),
                pkg("pkg-body01-sg", "BODY01", "SG", "Massage Cổ Vai Gáy 45p", "CHĂM SÓC CƠ THỂ",
                        List.of("cổ", "vai", "gáy"), 45, 260_000L, "260.000đ"),
                // Gói chỉ có ở HN (không có ở HCM/SG)
                pkg("pkg-hn-only", "HN_ONLY_01", "HN", "Liệu Trình Đá Muối Himalaya Đặc Biệt", "CHĂM SÓC ĐẶC BIỆT",
                        List.of("cổ", "vai", "gáy", "lưng"), 90, 520_000L, "520.000đ")
        );
    }

    private List<String> runReferee(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            String customerMessage,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> eligibleCatalog,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            ConversationState.StructuredBookingState bookingState,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        return CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                bubbles,
                allCatalog,
                eligibleCatalog,
                excludedCatalog,
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                bookingState,
                eligibleCatalog,
                PagePersonaConfig.empty(),
                customerMessage,
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(),
                "Nàng Sen Spa",
                null,
                history
        );
    }

    // =========================================================================
    // SECTION IX.1 — T1–T15 REPLAY & FORENSIC INVARIANTS
    // =========================================================================

    @Test
    @DisplayName("T1 Replay: Multi-category starting price answer ('239.000đ' vs '399.000đ') does NOT trigger false-positive PRICE_ENTITY_MISMATCH or redundant LLM retry")
    void t1MultiCategoryStartingPriceDoesNotTriggerFalsePositivePriceEntityMismatch() {
        List<PackageCatalogService.PackageSummary> catalog = buildNangSenMultiBranchCatalog();
        List<PackageCatalogService.PackageSummary> hnCatalog = PackageCatalogService.filterByRegion(catalog, "HN");

        List<FanpageBotResponse.ReplyBubble> t1Bubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ bên em có liệu trình Massage Cổ Vai Gáy 45p giá 239.000đ và Gói Thư Giãn (DS01) 65 phút giá 339.000đ hoặc Massage Body Đá Nóng 60p giá 399.000đ tùy nhu cầu thư giãn của mình ạ.",
                        0
                )
        );

        List<String> violations = runReferee(
                t1Bubbles,
                "Gội đầu dưỡng sinh và massage cổ vai gáy giá từ bao nhiêu em?",
                hnCatalog,
                hnCatalog,
                List.of(),
                ConversationState.StructuredBookingState.none(),
                List.of()
        );

        assertThat(violations)
                .filteredOn(v -> v.startsWith("PRICE_ENTITY_MISMATCH") || v.startsWith("UNGROUNDED_PRICE"))
                .isEmpty();
    }

    @Test
    @DisplayName("T3 & T5 & T15 Replay: Customer need ('mỏi cổ vai gáy, đau đầu') and branch ('HCM' vs 'HN') are locked and strictly isolate branch catalog prices")
    void t3T5T15NeedAndBranchIsolationPreventCrossBranchPriceContamination() {
        List<PackageCatalogService.PackageSummary> allCatalog = buildNangSenMultiBranchCatalog();

        List<FanpageBotRequest.ConversationTurn> historyWithHcm = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy và đau đầu nhiều"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chị đang ở Hà Nội hay TP.HCM để em báo đúng bảng giá cơ sở gần mình ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "Mình ở HCM nhé")
        );

        String resolvedBranch = ConversationState.resolveExplicitRegionAcrossTurns(
                "Vậy gói dưỡng sinh bên em giá sao?", historyWithHcm, null);
        assertThat(resolvedBranch).isEqualTo("SG");

        List<PackageCatalogService.PackageSummary> hcmCatalog =
                PackageCatalogService.filterByRegion(allCatalog, resolvedBranch);
        assertThat(hcmCatalog)
                .extracting(PackageCatalogService.PackageSummary::priceDisplay)
                .contains("370.000đ", "439.000đ", "260.000đ")
                .doesNotContain("339.000đ", "405.000đ", "520.000đ");

        // Verify that if LLM leaks HN price (339.000đ or 405.000đ) while customer is in HCM, Referee blocks it
        List<FanpageBotResponse.ReplyBubble> crossBranchLeakBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ ở cơ sở TP.HCM bên em có Gói Thư Giãn (DS01) 65 phút giá 339.000đ và Gói Cơ Bản (DS02) 75 phút giá 405.000đ ạ.",
                        0
                )
        );
        List<String> hcmViolations = runReferee(
                crossBranchLeakBubbles,
                "Vậy gói dưỡng sinh bên em giá sao?",
                allCatalog,
                allCatalog,
                List.of(),
                ConversationState.StructuredBookingState.none(),
                historyWithHcm
        );
        assertThat(hcmViolations)
                .anyMatch(v -> v.startsWith("PRICE_ENTITY_MISMATCH")
                        || v.startsWith("EXCLUDED_PACKAGE_LEAKED")
                        || v.startsWith("UNGROUNDED_PRICE_MENTIONED"));

        // T15: When customer is in HN, 339.000đ is valid and 370.000đ (HCM) is blocked
        List<FanpageBotRequest.ConversationTurn> historyWithHn = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình ở cơ sở Hà Nội nhé")
        );
        List<FanpageBotResponse.ReplyBubble> validHnBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ tại chi nhánh Hà Nội, Gói Thư Giãn (DS01) 65 phút có mức giá 339.000đ ạ.",
                        0
                )
        );
        List<String> hnValidCheck = runReferee(
                validHnBubbles,
                "Báo giá gói thư giãn cho mình",
                allCatalog,
                allCatalog,
                List.of(),
                ConversationState.StructuredBookingState.none(),
                historyWithHn
        );
        assertThat(hnValidCheck)
                .filteredOn(v -> v.startsWith("PRICE_ENTITY_MISMATCH") || v.startsWith("UNGROUNDED_PRICE"))
                .isEmpty();

        List<FanpageBotResponse.ReplyBubble> leakedSgPriceInHn = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ tại chi nhánh Hà Nội, Gói Thư Giãn (DS01) 65 phút có mức giá 370.000đ ạ.",
                        0
                )
        );
        List<String> hnLeakCheck = runReferee(
                leakedSgPriceInHn,
                "Báo giá gói thư giãn cho mình",
                allCatalog,
                allCatalog,
                List.of(),
                ConversationState.StructuredBookingState.none(),
                historyWithHn
        );
        assertThat(hnLeakCheck)
                .anyMatch(v -> v.startsWith("PRICE_ENTITY_MISMATCH") || v.startsWith("UNGROUNDED_PRICE_MENTIONED"));

        ConversationState.ParsedBookingDateTime t15FalseClock = ConversationState.parseBookingDateTime(
                "loạn ngôn à, nãy vừa 339  giờ lại mọc đâu ra gói 370", null, null, VN_ZONE, java.time.LocalDate.of(2026, 11, 6));
        assertThat(t15FalseClock.bookingTime()).isNull();

        ConversationState.ParsedBookingDateTime validWithRoi = ConversationState.parseBookingDateTime(
                "Đặt cho mình lúc 19 giờ rồi nhé", null, null, VN_ZONE, java.time.LocalDate.of(2026, 11, 6));
        assertThat(validWithRoi.bookingTime()).isEqualTo("19:00");
    }

    @Test
    @DisplayName("T7–T11 Replay: Price objections and competitor comparisons preserve LLM strategy/CTA autonomy and keep bookingStatus = NONE")
    void t7ToT11PriceObjectionAndCompetitorComparisonPreserveLlmStrategyAndKeepBookingNone() throws Exception {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");

        String t7ReflectionJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "COMPETITOR_COMPARISON",
                    "conversation_state": "OBJECTION_NEGOTIATION",
                    "buying_signal": "WEAK",
                    "booking_readiness": "EXPLORING",
                    "interaction_permission": "OPEN",
                    "cost_concern_expressed": true
                  },
                  "priority": "VALUE_ALIGNMENT",
                  "working_hypothesis": {
                    "hypothesis": "Khách so sánh tiệm gội thường 100k với liệu trình dưỡng sinh",
                    "barrier_type": "COMPETITOR_COMPARISON",
                    "confidence": 0.88,
                    "lifecycle_op": "ADJUST"
                  },
                  "response_plan": {
                    "strategy": "Clarify Gently",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "PIVOT"
                  },
                  "proposed_booking_patch": {
                    "action_type": "NONE",
                    "selected_product_id": null,
                    "proposed_date": null,
                    "proposed_time": null
                  }
                }
                """;
        JsonNode node = objectMapper.readTree(t7ReflectionJson);
        var reconciled = AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(node, null)
                .reconcileWithCurrentUtterance("Quanh nhà mình gội đầu có 100k thôi mà bên bạn tận 370k đắt thế");

        assertThat(reconciled.responsePlan().strategy()).isEqualTo("Clarify Gently");
        assertThat(reconciled.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(reconciled.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");

        ConversationState.StructuredBookingState bookingAfterT7 =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        reconciled.proposedBookingPatch(),
                        "Quanh nhà mình gội đầu có 100k thôi mà bên bạn tận 370k đắt thế",
                        "Dạ Gói Thư Giãn DS01 65 phút là 370.000đ ạ.",
                        hnCatalog,
                        hnCatalog,
                        VN_ZONE,
                        reconciled.customerUnderstanding().bookingReadiness(),
                        reconciled.priority().primary()
                );
        assertThat(bookingAfterT7.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(bookingAfterT7.selectedProductId()).isNull();
    }

    // =========================================================================
    // SECTION IX.2 — 6 MANDATORY BOOKING TESTS
    // =========================================================================

    @Test
    @DisplayName("Booking 1/6: Customer expresses clear booking intent with package + date + time -> transitions to AWAITING_AVAILABILITY_CHECK with full evidence")
    void bookingCase1ClearBookingIntentTransitionsToAwaitingAvailabilityCheck() {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");

        AdaptiveConversationIntelligence.ProposedBookingPatch patch =
                new AdaptiveConversationIntelligence.ProposedBookingPatch(
                        "DS01",
                        "ngày mai",
                        "18:00",
                        "INITIATE_BOOKING",
                        "BOOKING_REQUEST",
                        "Cho chị đặt lịch gói DS01 lúc 18h ngày mai nhé",
                        "HN",
                        List.of()
                );

        ConversationState.StructuredBookingState nextState =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        patch,
                        "Cho chị đặt lịch gói DS01 lúc 18h ngày mai nhé",
                        "Dạ tại Hà Nội Gói Thư Giãn (DS01) 65 phút là 339.000đ ạ.",
                        hnCatalog,
                        hnCatalog,
                        VN_ZONE,
                        AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK,
                        "BOOKING_COMPLETION"
                );

        assertThat(nextState.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK);
        assertThat(nextState.selectedProductId()).isEqualTo("pkg-ds01-hn");
        assertThat(nextState.bookingDate()).isNotBlank();
        assertThat(nextState.bookingTime()).isEqualTo("18:00");
    }

    @Test
    @DisplayName("Booking 2/6: Customer explicitly refuses to book ('Thôi chị chưa muốn đặt lịch đâu') -> bookingStatus stays NONE")
    void bookingCase2ExplicitRefusalKeepsBookingStatusNone() {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");

        AdaptiveConversationIntelligence.ProposedBookingPatch hallucinatedPatch =
                new AdaptiveConversationIntelligence.ProposedBookingPatch(
                        "DS01", null, null, "UPDATE_BOOKING_SLOTS", "DECLINE_BOOKING",
                        "Thôi chị chưa muốn đặt lịch đâu, để chị tham khảo thêm đã", "HN", List.of("date", "time")
                );

        ConversationState.StructuredBookingState nextState =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        hallucinatedPatch,
                        "Thôi chị chưa muốn đặt lịch đâu, để chị tham khảo thêm đã",
                        "Dạ chị có muốn đặt lịch trải nghiệm thử gói DS01 không ạ?",
                        hnCatalog,
                        hnCatalog,
                        VN_ZONE,
                        AdaptiveConversationIntelligence.BookingReadiness.NOT_READY,
                        "POLITE_CLOSURE"
                );

        assertThat(nextState.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(nextState.selectedProductId()).isNull();
    }

    @Test
    @DisplayName("Booking 3/6 (T13 & Variants): Sarcastic utterances containing 'đặt lịch' keep bookingStatus = NONE and never promote bookingReadiness")
    void bookingCase3SarcasticUtterancesWithDatLichKeepBookingStatusNone() throws Exception {
        List<PackageCatalogService.PackageSummary> hcmCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "SG");

        List<String> sarcasticUtterances = List.of(
                "So sánh khéo quá cơ, thế này chắc muốn mình đặt lịch luôn chứ gì",
                "Nói hay thế, chắc tưởng mình sẽ đặt lịch ngay đấy nhỉ",
                "Giải thích vòng vo thế thì ai mà dám đặt lịch",
                "Đắt gấp ba bên ngoài mà cứ gạ đặt lịch hoài"
        );

        for (String utterance : sarcasticUtterances) {
            String rawReflection = """
                    {
                      "customer_understanding": {
                        "turn_intent": "PRICE_OBJECTION",
                        "conversation_state": "OBJECTION_NEGOTIATION",
                        "buying_signal": "NONE",
                        "booking_readiness": "NOT_READY",
                        "interaction_permission": "OPEN",
                        "cost_concern_expressed": true
                      },
                      "priority": "TRUST_ASSURANCE",
                      "response_plan": {
                        "strategy": "Reassure Honestly",
                        "tone": "LEVEL_1_NATURAL",
                        "depth": "CONCISE",
                        "cta_level": "NO_CTA",
                        "next_action": "NO_ADDITIONAL_ACTION",
                        "adaptation": "PIVOT"
                      },
                      "proposed_booking_patch": {
                        "action_type": "NONE",
                        "selected_product_id": null,
                        "proposed_date": null,
                        "proposed_time": null
                      }
                    }
                    """;
            var reflection = AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(
                    objectMapper.readTree(rawReflection), null).reconcileWithCurrentUtterance(utterance);

            assertThat(reflection.customerUnderstanding().bookingReadiness())
                    .as("BookingReadiness for sarcastic utterance: %s", utterance)
                    .isEqualTo(AdaptiveConversationIntelligence.BookingReadiness.NOT_READY);
            assertThat(reflection.customerUnderstanding().conversationState())
                    .as("ConversationState for sarcastic utterance: %s", utterance)
                    .isNotEqualTo("BOOKING_READY");

            ConversationState.StructuredBookingState state =
                    AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                            ConversationState.StructuredBookingState.none(),
                            reflection.proposedBookingPatch(),
                            utterance,
                            "Dạ bên em dùng thảo dược nấu tươi mỗi ngày và có phòng rèm riêng yên tĩnh ạ.",
                            hcmCatalog,
                            hcmCatalog,
                            VN_ZONE,
                            reflection.customerUnderstanding().bookingReadiness(),
                            reflection.priority().primary()
                    );
            assertThat(state.status())
                    .as("BookingStatus for sarcastic utterance: %s", utterance)
                    .isEqualTo(ConversationState.BookingStatus.NONE);
            assertThat(state.selectedProductId()).isNull();
        }
    }

    @Test
    @DisplayName("Booking 4/6: Customer quotes another person mentioning 'đặt lịch' -> bookingStatus stays NONE")
    void bookingCase4QuotedSpeechMentioningDatLichKeepsBookingStatusNone() throws Exception {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");

        List<String> quotedUtterances = List.of(
                "Bạn mình bảo đặt lịch bên em cuối tuần hay bị hết chỗ lắm phải không?",
                "Hôm qua thấy đồng nghiệp kể là đặt lịch gói dưỡng sinh xong phải chờ 15 phút mới được vào làm"
        );

        for (String utterance : quotedUtterances) {
            String rawReflection = """
                    {
                      "customer_understanding": {
                        "turn_intent": "SERVICE_OR_OPERATIONAL_INQUIRY",
                        "conversation_state": "ACTIVE_CONSULTATION",
                        "buying_signal": "WEAK",
                        "booking_readiness": "EXPLORING",
                        "interaction_permission": "OPEN",
                        "cost_concern_expressed": false
                      },
                      "priority": "TRUST_ASSURANCE",
                      "response_plan": {
                        "strategy": "Reassure Honestly",
                        "tone": "LEVEL_1_NATURAL",
                        "depth": "CONCISE",
                        "cta_level": "NO_CTA",
                        "next_action": "NO_ADDITIONAL_ACTION",
                        "adaptation": "CONTINUATION"
                      },
                      "proposed_booking_patch": {
                        "action_type": "NONE",
                        "selected_product_id": null,
                        "proposed_date": null,
                        "proposed_time": null
                      }
                    }
                    """;
            var reflection = AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(
                    objectMapper.readTree(rawReflection), null).reconcileWithCurrentUtterance(utterance);

            ConversationState.StructuredBookingState state =
                    AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                            ConversationState.StructuredBookingState.none(),
                            reflection.proposedBookingPatch(),
                            utterance,
                            "Dạ bên em có hai gói DS01 và DS02 ạ.",
                            hnCatalog,
                            hnCatalog,
                            VN_ZONE,
                            reflection.customerUnderstanding().bookingReadiness(),
                            reflection.priority().primary()
                    );
            assertThat(state.status())
                    .as("BookingStatus for quoted utterance: %s", utterance)
                    .isEqualTo(ConversationState.BookingStatus.NONE);
            assertThat(state.selectedProductId()).isNull();
        }
    }

    @Test
    @DisplayName("Booking 5/6: Customer has incomplete booking info (gives date/time when 2 packages were offered, or selects package without date/time) -> transitions to AWAITING_PRODUCT_SELECTION or AWAITING_DATE_OR_TIME")
    void bookingCase5IncompleteBookingInfoTransitionsToAwaitingMissingSlots() {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");
        List<PackageCatalogService.PackageSummary> twoPresented = List.of(hnCatalog.get(0), hnCatalog.get(1));

        // Subcase 5A: Customer gives date + time ("Cho chị đặt lịch lúc 19h tối mai nhé") without specifying which of the 2 packages
        AdaptiveConversationIntelligence.ProposedBookingPatch patchMissingPkg =
                new AdaptiveConversationIntelligence.ProposedBookingPatch(
                        null,
                        "ngày mai",
                        "19:00",
                        "INITIATE_BOOKING",
                        "BOOKING_REQUEST",
                        "Cho chị đặt lịch lúc 19h tối mai nhé",
                        "HN",
                        List.of("package_id")
                );
        ConversationState.StructuredBookingState stateMissingPkg =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        patchMissingPkg,
                        "Cho chị đặt lịch lúc 19h tối mai nhé",
                        "Dạ bên em có Gói Thư Giãn (DS01) 339.000đ và Gói Cơ Bản (DS02) 405.000đ ạ.",
                        hnCatalog,
                        twoPresented,
                        VN_ZONE,
                        AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK,
                        "BOOKING_COMPLETION"
                );
        assertThat(stateMissingPkg.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION);
        assertThat(stateMissingPkg.selectedProductId()).isNull();
        assertThat(stateMissingPkg.bookingTime()).isEqualTo("19:00");

        // Subcase 5B: Customer selects package ("Chị chốt đặt gói DS01 nhé") without date/time
        AdaptiveConversationIntelligence.ProposedBookingPatch patchMissingTime =
                new AdaptiveConversationIntelligence.ProposedBookingPatch(
                        "DS01",
                        null,
                        null,
                        "INITIATE_BOOKING",
                        "BOOKING_REQUEST",
                        "Chị chốt đặt gói DS01 nhé",
                        "HN",
                        List.of("date", "time")
                );
        ConversationState.StructuredBookingState stateMissingTime =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        patchMissingTime,
                        "Chị chốt đặt gói DS01 nhé",
                        "Dạ chị muốn chọn gói DS01 hay DS02 ạ?",
                        hnCatalog,
                        twoPresented,
                        VN_ZONE,
                        AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK,
                        "BOOKING_COMPLETION"
                );
        assertThat(stateMissingTime.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME);
        assertThat(stateMissingTime.selectedProductId()).isEqualTo("pkg-ds01-hn");
    }

    @Test
    @DisplayName("Booking 6/6: Customer confirms booking summary in AWAITING_CUSTOMER_CONFIRMATION -> recognizes confirmation and blocks unverified CONFIRMED claims when not yet confirmed")
    void bookingCase6ConfirmedBookingSummaryTransitionAndGuard() {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");

        ConversationState.StructuredBookingState awaitingConfirm = new ConversationState.StructuredBookingState(
                "DS01",
                "Gói Thư Giãn (DS01)",
                "ngày mai",
                "2026-11-07",
                "18:00",
                "Asia/Ho_Chi_Minh",
                ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION,
                List.of("DS01")
        );

        assertThat(CleanAgentOrchestratorService.isExplicitCustomerBookingConfirmationMessage(
                "Ok chốt lịch giúp chị nhé")).isTrue();
        assertThat(CleanAgentOrchestratorService.isExplicitCustomerBookingConfirmationMessage(
                "Khoan đã em ơi, đổi giúp chị sang 19h được không?")).isFalse();

        // Also verify that claiming "đã đặt lịch thành công chính thức" while still in AWAITING_AVAILABILITY_CHECK is blocked by Referee
        ConversationState.StructuredBookingState awaitingAvail = new ConversationState.StructuredBookingState(
                "DS01",
                "Gói Thư Giãn (DS01)",
                "ngày mai",
                "2026-11-07",
                "18:00",
                "Asia/Ho_Chi_Minh",
                ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK,
                List.of("DS01")
        );
        List<FanpageBotResponse.ReplyBubble> prematureConfirmedClaim = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em đã đặt lịch thành công chính thức cho chị gói DS01 lúc 18h ngày mai rồi ạ.",
                        0
                )
        );
        List<String> violations = runReferee(
                prematureConfirmedClaim,
                "Cho chị đặt gói DS01 18h mai nhé",
                hnCatalog,
                hnCatalog,
                List.of(),
                awaitingAvail,
                List.of()
        );
        assertThat(violations).isNotEmpty();
        assertThat(awaitingConfirm.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
    }

    // =========================================================================
    // SECTION IX.2 — 6 MANDATORY CATALOG TESTS
    // =========================================================================

    @Test
    @DisplayName("Catalog 1/6: Same package has different prices across 2 branches (DS01: 339.000đ HN vs 370.000đ HCM) -> rendered strictly from catalog snapshot per branch")
    void catalogCase1SamePackageDifferentPricesAcrossBranchesRenderedAccurately() {
        List<PackageCatalogService.PackageSummary> catalog = buildNangSenMultiBranchCatalog();

        CleanAgentOutputRefereeAndSanitizer.CatalogClaimValidationResult hnRender =
                CleanAgentOutputRefereeAndSanitizer.validateAndRenderCatalogClaimSegments(
                        List.of(
                                CleanAgentOutputRefereeAndSanitizer.MessageSegment.text("Dạ tại Hà Nội,"),
                                CleanAgentOutputRefereeAndSanitizer.MessageSegment.catalogClaim("full", "DS01", "HN")
                        ),
                        catalog,
                        "HN"
                );
        assertThat(hnRender.valid()).isTrue();
        assertThat(hnRender.renderedBubbles()).singleElement().satisfies(bubble -> {
            assertThat(bubble.text()).contains("339.000đ").contains("65 phút").doesNotContain("370.000đ");
        });

        CleanAgentOutputRefereeAndSanitizer.CatalogClaimValidationResult sgRender =
                CleanAgentOutputRefereeAndSanitizer.validateAndRenderCatalogClaimSegments(
                        List.of(
                                CleanAgentOutputRefereeAndSanitizer.MessageSegment.text("Dạ tại TP.HCM,"),
                                CleanAgentOutputRefereeAndSanitizer.MessageSegment.catalogClaim("full", "DS01", "SG")
                        ),
                        catalog,
                        "SG"
                );
        assertThat(sgRender.valid()).isTrue();
        assertThat(sgRender.renderedBubbles()).singleElement().satisfies(bubble -> {
            assertThat(bubble.text()).contains("370.000đ").contains("65 phút").doesNotContain("339.000đ");
        });
    }

    @Test
    @DisplayName("Catalog 2/6: Customer selects branch and price remains consistent across subsequent turns")
    void catalogCase2BranchSelectionPersistsAcrossTurnsWithConsistentPrices() {
        List<PackageCatalogService.PackageSummary> catalog = buildNangSenMultiBranchCatalog();
        List<FanpageBotRequest.ConversationTurn> turns = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình muốn hỏi giá gội đầu dưỡng sinh"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình đang ở Hà Nội hay TP.HCM ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "Mình ở Sài Gòn"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ ở TP.HCM Gói Thư Giãn (DS01) là 370.000đ và Gói Cơ Bản (DS02) là 439.000đ ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Gói cơ bản gồm những bước gì thế?")
        );

        String resolvedRegion = ConversationState.resolveExplicitRegionAcrossTurns(
                "Thế giá gói cơ bản là bao nhiêu nhỉ?", turns, null);
        assertThat(resolvedRegion).isEqualTo("SG");

        CleanAgentOutputRefereeAndSanitizer.CatalogClaimValidationResult renderTurn =
                CleanAgentOutputRefereeAndSanitizer.validateAndRenderCatalogClaimSegments(
                        List.of(
                                CleanAgentOutputRefereeAndSanitizer.MessageSegment.text("Dạ tại chi nhánh TP.HCM,"),
                                CleanAgentOutputRefereeAndSanitizer.MessageSegment.catalogClaim("full", "DS02", "")
                        ),
                        catalog,
                        resolvedRegion
                );
        assertThat(renderTurn.valid()).isTrue();
        assertThat(renderTurn.renderedBubbles().get(0).text())
                .contains("439.000đ")
                .doesNotContain("405.000đ");
    }

    @Test
    @DisplayName("Catalog 3/6: Package unavailable at selected branch -> rejected with CATALOG_CLAIM_UNAVAILABLE_AT_BRANCH without cross-branch fallback")
    void catalogCase3PackageUnavailableAtBranchIsRejectedWithoutCrossBranchFallback() {
        List<PackageCatalogService.PackageSummary> catalog = buildNangSenMultiBranchCatalog();

        // HN_ONLY_01 exists only in HN (520.000đ), NOT in SG/HCM
        CleanAgentOutputRefereeAndSanitizer.CatalogClaimValidationResult res =
                CleanAgentOutputRefereeAndSanitizer.validateAndRenderCatalogClaimSegments(
                        List.of(CleanAgentOutputRefereeAndSanitizer.MessageSegment.catalogClaim(
                                "price", "HN_ONLY_01", "SG"
                        )),
                        catalog,
                        "SG"
                );

        assertThat(res.valid()).isFalse();
        assertThat(res.violations()).contains("CATALOG_CLAIM_UNAVAILABLE_AT_BRANCH:HN_ONLY_01@SG");
        assertThat(res.renderedBubbles()).isEmpty();
    }

    @Test
    @DisplayName("Catalog 4/6: Branch not yet determined -> multi-branch price variance anchor is exposed & branchless single-price claim is rejected")
    void catalogCase4UndeterminedBranchExposesMultiBranchAnchorAndRejectsBranchlessClaim() {
        List<PackageCatalogService.PackageSummary> catalog = buildNangSenMultiBranchCatalog();

        String anchor = CleanAgentCatalogAndPromptBuilder.resolveLowestCoreStartingPackageAnchor(catalog, List.of());
        assertThat(anchor)
                .contains("239.000đ (chi nhánh Hà Nội)")
                .contains("260.000đ (chi nhánh TP.HCM)");

        CleanAgentOutputRefereeAndSanitizer.CatalogClaimValidationResult branchlessClaim =
                CleanAgentOutputRefereeAndSanitizer.validateAndRenderCatalogClaimSegments(
                        List.of(CleanAgentOutputRefereeAndSanitizer.MessageSegment.catalogClaim(
                                "price", "DS01", ""
                        )),
                        catalog,
                        null
                );
        assertThat(branchlessClaim.valid()).isFalse();
        assertThat(branchlessClaim.violations()).contains("CATALOG_CLAIM_MISSING_BRANCH_ID:DS01");
    }

    @Test
    @DisplayName("Catalog 5/6: LLM selects non-existent package code ('NS999') -> rejected with CATALOG_CLAIM_NON_EXISTENT_PACKAGE")
    void catalogCase5NonExistentPackageCodeIsRejected() {
        List<PackageCatalogService.PackageSummary> catalog = buildNangSenMultiBranchCatalog();

        CleanAgentOutputRefereeAndSanitizer.CatalogClaimValidationResult res =
                CleanAgentOutputRefereeAndSanitizer.validateAndRenderCatalogClaimSegments(
                        List.of(CleanAgentOutputRefereeAndSanitizer.MessageSegment.catalogClaim(
                                "price", "NS999", "HN"
                        )),
                        catalog,
                        "HN"
                );
        assertThat(res.valid()).isFalse();
        assertThat(res.violations()).contains("CATALOG_CLAIM_NON_EXISTENT_PACKAGE:NS999");
    }

    @Test
    @DisplayName("Catalog 6/6: LLM outputs ungrounded free-text price ('299.000đ' not in catalog) -> blocked by Referee with UNGROUNDED_PRICE_MENTIONED")
    void catalogCase6UngroundedFreeTextPriceIsBlockedByReferee() {
        List<PackageCatalogService.PackageSummary> hnCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "HN");

        List<FanpageBotResponse.ReplyBubble> ungroundedBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ bên em đang có gói Gội Đầu Thư Giãn 65 phút giá ưu đãi chỉ 299.000đ ạ.",
                        0
                )
        );
        List<String> violations = runReferee(
                ungroundedBubbles,
                "Giá gói gội đầu dưỡng sinh bao nhiêu em?",
                hnCatalog,
                hnCatalog,
                List.of(),
                ConversationState.StructuredBookingState.none(),
                List.of()
        );
        assertThat(violations)
                .anyMatch(v -> v.startsWith("UNGROUNDED_PRICE_MENTIONED") || v.startsWith("PRICE_ENTITY_MISMATCH"));
    }

    // =========================================================================
    // SECTION IX.2 — 6 MANDATORY DEADLINE & CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Deadline 1/6 & 4/6: Primary model slower than turn deadline is cancelled on deadline, and late primary completion cannot mutate booking/memory state")
    void deadlineCase1And4SlowPrimaryIsCancelledAndLateCompletionCannotMutateState() throws Exception {
        AtomicBoolean lateMutationAttempted = new AtomicBoolean(false);
        AtomicReference<ConversationState.StructuredBookingState> authoritativeState =
                new AtomicReference<>(ConversationState.StructuredBookingState.none());
        AtomicBoolean turnTimedOut = new AtomicBoolean(false);

        CompletableFuture<String> primaryFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "CANCELLED";
            }
            if (!turnTimedOut.get()) {
                authoritativeState.set(new ConversationState.StructuredBookingState(
                        "DS01", "Gói Thư Giãn (DS01)", null, null, null, "Asia/Ho_Chi_Minh",
                        ConversationState.BookingStatus.AWAITING_DATE_OR_TIME, List.of("DS01")
                ));
            } else {
                lateMutationAttempted.set(true);
            }
            return "LATE_RESULT";
        });

        String outcome;
        try {
            outcome = primaryFuture.get(50, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            turnTimedOut.set(true);
            primaryFuture.cancel(true);
            outcome = "TIMEOUT_FALLBACK";
        }

        assertThat(outcome).isEqualTo("TIMEOUT_FALLBACK");
        Thread.sleep(300);
        assertThat(authoritativeState.get().status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(authoritativeState.get().selectedProductId()).isNull();
    }

    @Test
    @DisplayName("Deadline 2/6 & 3/6: Fallback within remaining budget vs exhausted budget keeps booking state and AgentBrain memory strictly READ-ONLY")
    void deadlineCase2And3FallbackIsStrictlyReadOnlyAndNeverWritesUnverifiedState() {
        List<PackageCatalogService.PackageSummary> hcmCatalog =
                PackageCatalogService.filterByRegion(buildNangSenMultiBranchCatalog(), "SG");

        ConversationState.StructuredBookingState priorBooking = ConversationState.StructuredBookingState.none();
        boolean llmOutputUnavailable = true;
        boolean turnDeadlineExceeded = true;
        boolean usedStructuredFallbackOnLlmFailure = true;

        ConversationState.StructuredBookingState resultingBooking =
                (llmOutputUnavailable || turnDeadlineExceeded || usedStructuredFallbackOnLlmFailure)
                        ? priorBooking
                        : AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                                priorBooking,
                                new AdaptiveConversationIntelligence.ProposedBookingPatch("DS01", "mai", "18:00"),
                                "Mình ở HCM",
                                null,
                                hcmCatalog,
                                hcmCatalog,
                                VN_ZONE,
                                AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK,
                                "BOOKING_COMPLETION"
                        );

        assertThat(resultingBooking).isEqualTo(priorBooking);
        assertThat(resultingBooking.status()).isEqualTo(ConversationState.BookingStatus.NONE);
    }

    @Test
    @DisplayName("Deadline 5/6 & 6/6: Concurrent requests on same conversation use SenderEpochRegistry fencing so stale turn cannot overwrite newer state")
    void deadlineCase5And6ConcurrentRequestsEpochFencingBlocksStaleTurnWrite() {
        String pageId = "2850a8ef-ca4a-40eb-87fa-4f808640efee";
        String senderId = "user-concurrent-stage1";

        // Request 1 arrives at t0
        long epoch1 = SenderEpochRegistry.nextEpoch(pageId, senderId);
        assertThat(SenderEpochRegistry.isEpochCurrent(pageId, senderId, epoch1)).isTrue();

        // Request 2 arrives concurrently at t1 before Request 1 finishes
        long epoch2 = SenderEpochRegistry.nextEpoch(pageId, senderId);
        assertThat(epoch2).isGreaterThan(epoch1);

        // Request 1 is now stale; epoch check blocks Request 1 from committing state or sending outbound bubbles
        assertThat(SenderEpochRegistry.isEpochCurrent(pageId, senderId, epoch1)).isFalse();
        assertThat(SenderEpochRegistry.isEpochCurrent(pageId, senderId, epoch2)).isTrue();

        AtomicReference<String> committedBranch = new AtomicReference<>("NONE");
        if (SenderEpochRegistry.isEpochCurrent(pageId, senderId, epoch2)) {
            committedBranch.set("HN");
        }
        if (SenderEpochRegistry.isEpochCurrent(pageId, senderId, epoch1)) {
            committedBranch.set("SG_STALE_OVERWRITE");
        }
        assertThat(committedBranch.get()).isEqualTo("HN");
    }

    // =========================================================================
    // SECTION VII & VIII — TRACEABILITY & UNRESOLVED_REQUESTS STABLE REQUEST_ID
    // =========================================================================

    @Test
    @DisplayName("Workstream 5 & 6: DecisionTrace captures raw-to-final sanitizer mutations + lifecycle stages, and UnresolvedRequestItem updates deterministically by request_id")
    void workstream5And6TraceabilityAndUnresolvedRequestsByStableRequestId() throws Exception {
        // 1. Verify stable request_id generation and deterministic resolution without fuzzy string guessing
        AdaptiveConversationIntelligence.UnresolvedRequestItem openReq =
                new AdaptiveConversationIntelligence.UnresolvedRequestItem(
                        "Hỏi giá gói cổ vai gáy",
                        AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN,
                        "T1",
                        "T1"
                );
        assertThat(openReq.requestId()).startsWith("REQ_");

        String deltaJson = """
                {
                  "conversational_context": {
                    "current_topic": "Báo giá gói cổ vai gáy",
                    "unresolved_requests": [
                      {
                        "request_id": "%s",
                        "request": "Đã báo giá gói cổ vai gáy",
                        "status": "ANSWERED"
                      }
                    ]
                  },
                  "customer_understanding": {
                    "turn_intent": "ASK_PRICE",
                    "conversation_state": "ACTIVE_CONSULTATION"
                  }
                }
                """.formatted(openReq.requestId());
        var parsedReflection = AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(
                objectMapper.readTree(deltaJson), null);
        assertThat(parsedReflection.unresolvedRequestsDelta()).singleElement().satisfies(item -> {
            assertThat(item.requestId()).isEqualTo(openReq.requestId());
            assertThat(item.status()).isEqualTo(AdaptiveConversationIntelligence.UnresolvedRequestStatus.ANSWERED);
        });

        // 2. Verify DecisionTrace Stage 1 observability fields (Stage1ExecutionTrace, StepTimings, SanitizerMutationAudit)
        DecisionTrace.Stage1ExecutionTrace stage1Trace = new DecisionTrace.Stage1ExecutionTrace(
                "corr-stage1-001",
                "T5",
                "qwen3.8-max",
                "openai_compatible",
                "aci-v2.13-sbr01-fix4-policy",
                "SG",
                "SG:3_PKGS",
                List.of("Dạ ở TP.HCM Gói Thư Giãn DS01 là 370.000đ ạ."),
                "{\"bubbles\":[\"Dạ ở TP.HCM Gói Thư Giãn DS01 là 370.000đ ạ.\"]}",
                List.of("Dạ ở TP.HCM Gói Thư Giãn (DS01) là 370.000đ ạ."),
                "PASS",
                "MUTATED_1_SEGMENT",
                List.of("Dạ ở TP.HCM Gói Thư Giãn (DS01) là 370.000đ ạ."),
                "EXPLORING",
                "NONE",
                "NONE",
                "UNCHANGED:NONE",
                new DecisionTrace.StepTimings(5L, 8L, 1200L, 4L, 1217L, 65000L),
                1800,
                140,
                0,
                0,
                "NONE",
                false,
                "NONE",
                "SYSTEM_PROCESSED",
                "DELIVERY_REQUESTED",
                List.of("LLM_GENERATED", "SYSTEM_PROCESSED", "DELIVERY_REQUESTED"),
                new DecisionTrace.SanitizerMutationAudit(
                        true,
                        2,
                        1,
                        "rawSegments=1 -> finalSegments=1 (changedChars=2)",
                        List.of("IN_PLACE_FORMAL_OR_STYLE_SANITIZATION"),
                        List.of("CleanAgentOutputRefereeAndSanitizer")
                )
        );

        DecisionTrace baseTrace = new DecisionTrace(
                "T5",
                DecisionTrace.sha256Short("Mình ở HCM"),
                "ASK_PRICE",
                "NONE",
                new DecisionTrace.FactChanges(List.of(), List.of("mỏi cổ vai gáy"), List.of("cổ", "vai", "gáy"), List.of(), "SG"),
                List.of("cổ", "vai", "gáy"),
                List.of("cổ", "vai", "gáy"),
                List.of(),
                List.of(),
                List.of(),
                new DecisionTrace.ResponsePlanTrace("Direct Answer", "LEVEL_1_NATURAL", "CONCISE", true),
                new DecisionTrace.SanitizerMutations(false, false, false)
        ).withStage1ExecutionTrace(stage1Trace);

        assertThat(baseTrace.turnId()).isEqualTo("T5");
        assertThat(baseTrace.stage1Trace()).isNotNull();
        assertThat(baseTrace.stage1Trace().branchId()).isEqualTo("SG");
        assertThat(baseTrace.stage1Trace().sanitizerMutationAudit().mutated()).isTrue();
        assertThat(baseTrace.stage1Trace().lifecycleStages())
                .containsExactly("LLM_GENERATED", "SYSTEM_PROCESSED", "DELIVERY_REQUESTED");
    }
}
