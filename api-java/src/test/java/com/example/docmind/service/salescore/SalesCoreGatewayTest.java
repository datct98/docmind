package com.example.docmind.service.salescore;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.entity.*;
import com.example.docmind.repository.*;
import com.example.docmind.service.bot.FanpageBotService;
import com.example.docmind.service.bot.InboundSessionPersistenceService;
import com.example.docmind.service.bot.SenderEpochRegistry;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SalesCoreGatewayTest {
    @TempDir Path config;
    final UUID pageId = UUID.randomUUID();
    final UUID docId = UUID.randomUUID();
    KnowledgePageRepository pages = mock(KnowledgePageRepository.class);
    DocumentRepository docs = mock(DocumentRepository.class);
    ChunkRepository chunks = mock(ChunkRepository.class);
    RetrievalService retrieval = mock(RetrievalService.class);
    private SalesKnowledgeSource source() {
        when(pages.findById(pageId)).thenReturn(Optional.of(KnowledgePageEntity.builder().id(pageId).name("Test shop")
                .personaConfigJson("{\"industry_code\":\"VEHICLE_RENTAL\",\"self_pronoun\":\"em\"}").build()));
        when(docs.findByPageIdOrderByCreatedAtDesc(pageId)).thenReturn(List.of(DocumentEntity.builder()
                .id(docId).pageId(pageId).status("READY").active(true).build()));
        when(chunks.findByDocumentIdIn(anyList(), any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of(
                ChunkEntity.builder().id(UUID.randomUUID()).documentId(docId).pageId(pageId).text("Xe số: 150000 VND/ngày, cọc 1000000 VND.").headingPath(new String[]{"Bảng giá"}).build()));
        return new SalesKnowledgeSource(pages, docs, chunks, retrieval, config.toString());
    }
    @Test void rolloutCanTargetOnePageWithoutChangingOtherPages() {
        var gateway = new SalesCoreGateway(null, null, true);
        gateway.setPilotPages(pageId.toString());
        assertThat(gateway.enabledFor(pageId)).isTrue();
        assertThat(gateway.enabledFor(UUID.randomUUID())).isFalse();
        assertThat(gateway.enabledFor(null)).isFalse();
        assertThat(new SalesCoreGateway(null, null, false).enabledFor(pageId)).isFalse();
    }
    @Test void rejectsForeignDocumentAndNeverUsesGlobalKnowledge() {
        var source = source();
        assertThatThrownBy(() -> source.load(pageId, List.of(UUID.randomUUID()), List.of(), "giá", ""))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(retrieval);
        assertThatThrownBy(() -> source.load(null, List.of(), List.of(), "giá", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void catalogRemainsAvailableForShortRepliesWithoutVectorSearchHit() {
        var input = source().load(pageId, null, List.of(new SalesContracts.Message("user", "thuê xe hai ngày")), "Đắt", "");
        assertThat(input.industryCode()).isEqualTo("VEHICLE_RENTAL");
        assertThat(input.evidence()).anyMatch(e -> e.text().contains("150000 VND"));
        assertThat(input.history()).hasSize(1);
    }
    @Test void pricingIsNotCrowdedOutByLongProcedureTablesUnderContextBudget() {
        var source = source();
        var procedure = "| Bước | Mô tả |\n" + "a".repeat(7700);
        var price = "| Tên | Giá |\n| Xe số | 150000 VND |\n" + "b".repeat(4000);
        when(chunks.findByDocumentIdIn(anyList(), any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of(
                ChunkEntity.builder().id(UUID.randomUUID()).documentId(docId).text(procedure).build(),
                ChunkEntity.builder().id(UUID.randomUUID()).documentId(docId).text(procedure).build(),
                ChunkEntity.builder().id(UUID.randomUUID()).documentId(docId).text(price).build()));
        var input = source.load(pageId, null, List.of(), "Giá", "");
        assertThat(input.evidence().getFirst().text()).contains("150000 VND");
        assertThat(input.contextTruncated()).isTrue();
    }
    @Test void configuredPageDataIsIsolatedAndExpiredOffersAreNotQuoted() throws Exception {
        Files.writeString(config.resolve(pageId + ".json"), """
           {"industryCode":"VEHICLE_RENTAL","voice":"gọn","businessPolicies":[],"offers":[
           {"id":"old","name":"OLD OFFER","price":"100 VND/ngày","scope":"","details":"","validUntil":"2020-01-01"},
           {"id":"current","name":"Xe hiện tại","price":"180000 VND/ngày","scope":"Hà Nội","details":"Mũ bảo hiểm"}]}
           """);
        var input = source().load(pageId, null, List.of(), "Giá", "");
        assertThat(input.evidence()).anyMatch(e -> e.id().equals("offer:current"));
        assertThat(input.evidence()).noneMatch(e -> e.text().contains("OLD OFFER"));
    }
    @Test void publicFanpageRouteUsesNewCoreAndPreservesBudgetQuestion() {
        var source = source();
        String expected = "Mình dự tính khoảng bao nhiêu cho lần thuê này?";
        String raw = SalesCoreTest.answer(expected, "CLARIFY", "[]")
                .replace("cổ vai gáy chân tay", "thuê xe hai ngày");
        var llm = SalesCoreTest.fake(() -> raw);
        try (var core = new SalesTurnOrchestrator(llm, new IndustryProfileRegistry(), 3000)) {
            var gateway = new SalesCoreGateway(core, source, true);
            var service = new FanpageBotService(retrieval, llm, new ChatProperties(), docs, pages, null, null, null, null, null);
            service.setSalesCoreGateway(gateway);
            var req = new FanpageBotRequest(pageId.toString(), UUID.randomUUID().toString(), "", "Đắt", null,
                    List.of(new FanpageBotRequest.ConversationTurn("user", "thuê xe hai ngày"),
                            new FanpageBotRequest.ConversationTurn("assistant", "Giá theo ngày."),
                            new FanpageBotRequest.ConversationTurn("user", "Đắt")), null, 5, null, null, null, null);
            var response = service.handleCustomerMessage(req);
            assertThat(response.replyText()).isEqualTo(expected);
            assertThat(response.triggeredHooks()).contains("SALES_CORE_V1");
            assertThat(response.decisionTrace().detectedIntent()).isEqualTo("CLARIFY");
            assertThat(response.needsHumanHandoff()).isFalse();
        }
    }
    @Test void enabledCoreRejectsMissingPageRatherThanFallingBackToGlobalKnowledge() {
        var llm = mock(com.example.docmind.service.llm.LlmClient.class);
        var service = new FanpageBotService(retrieval, llm, new ChatProperties(), docs, pages, null, null, null, null, null);
        service.setSalesCoreGateway(new SalesCoreGateway(null, null, true));
        assertThatThrownBy(() -> service.handleCustomerMessage(new FanpageBotRequest(null, "missing-page", "", "Giá", null, null, null, 5)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(retrieval, llm);
    }
    @Test void customerPhoneDoesNotCountAsCompletedSaleInNewRoute() {
        var source = source();
        var learning = mock(com.example.docmind.service.MetaLearningService.class);
        var llm = SalesCoreTest.fake(() -> SalesCoreTest.answer("Em cần nhờ nhân viên xác nhận yêu cầu thuê xe.", "HANDOFF", "[]")
                .replace("cổ vai gáy chân tay", "thuê xe hai ngày")
                .replace("\"handoffSummary\":\"\"", "\"handoffSummary\":\"Cần kiểm tra tồn xe.\""));
        try (var core = new SalesTurnOrchestrator(llm, new IndustryProfileRegistry(), 3000)) {
            var service = new FanpageBotService(retrieval, llm, new ChatProperties(), docs, pages, null, null, learning, null, null);
            service.setSalesCoreGateway(new SalesCoreGateway(core, source, true));
            var response = service.handleCustomerMessage(new FanpageBotRequest(pageId.toString(), UUID.randomUUID().toString(), "", "SĐT 0912345678", null,
                    List.of(new FanpageBotRequest.ConversationTurn("user", "thuê xe hai ngày")), null, 5));
            assertThat(response.triggeredHooks()).contains("SALES_CORE_V1");
            verifyNoInteractions(learning);
        }
    }
    @Test void humanTakeoverBlocksCoreBeforeReadingKnowledge() {
        var core = mock(SalesTurnOrchestrator.class);
        var knowledge = mock(SalesKnowledgeSource.class);
        var gateway = new SalesCoreGateway(core, knowledge, true);
        var service = new FanpageBotService(retrieval, SalesCoreTest.fake(() -> "{}"), new ChatProperties(), docs, pages, null, null, null, null, null);
        service.setSalesCoreGateway(gateway);
        var inbound = mock(InboundSessionPersistenceService.class);
        when(inbound.evaluateEntryAndPersistCustomer(any(), anyString())).thenReturn(new InboundSessionPersistenceService.EntryEvaluation(
                InboundSessionPersistenceService.EntryAction.BLOCK_HUMAN_TAKEOVER, UUID.randomUUID(), "HUMAN_TAKEOVER", null, false));
        service.setInboundSessionPersistenceService(inbound);
        var result = service.handleCustomerMessage(new FanpageBotRequest(pageId.toString(), "blocked", "", "Giá", null, null, null, 5, null, null, null, null));
        assertThat(result.replyBubbles()).isEmpty();
        verifyNoInteractions(core, knowledge);
    }
    @Test void groundedHandoffReturnsActionableInboxSummaryWithoutBookingConfirmation() {
        var source = source();
        String raw = SalesCoreTest.answer("Em cần nhờ nhân viên xác nhận xe và thủ tục cho mình.", "HANDOFF", "[]")
                .replace("cổ vai gáy chân tay", "thuê xe hai ngày")
                .replace("\"handoffSummary\":\"\"", "\"handoffSummary\":\"Khách muốn thuê xe hai ngày. Cần xác nhận tồn xe và thủ tục.\"");
        try (var core = new SalesTurnOrchestrator(SalesCoreTest.fake(() -> raw), new IndustryProfileRegistry(), 3000)) {
            var gateway = new SalesCoreGateway(core, source, true);
            var request = new FanpageBotRequest(pageId.toString(), "handoff-fixture", "", "thuê xe hai ngày", null,
                    List.of(), null, 5, null, null, null, null);
            var response = gateway.respond(pageId, request, request.message(), null);
            assertThat(response.needsHumanHandoff()).isTrue();
            assertThat(response.replyText()).doesNotContain("đã đặt", "đã giữ");
            assertThat(response.triggeredHooks()).contains("SALES_CORE_V1", "SEMANTIC_REVIEW_PASS");
        }
    }
    @Test void takeoverDuringGenerationSuppressesNewCoreOutputAtOuterFence() {
        source();
        var gateway = mock(SalesCoreGateway.class);
        when(gateway.enabledFor(pageId)).thenReturn(true);
        when(gateway.respond(any(), any(), anyString(), any())).thenReturn(new com.example.docmind.dto.bot.FanpageBotResponse(
                "Nội dung phải bị chặn", List.of(new com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble("Nội dung phải bị chặn", 0)),
                List.of(0), "ANSWER", 0, false, null, null, null, List.of(), null, List.of(), List.of("SALES_CORE_V1")));
        var service = new FanpageBotService(retrieval, SalesCoreTest.fake(() -> "{}"), new ChatProperties(), docs, pages, null, null, null, null, null);
        service.setSalesCoreGateway(gateway);
        var inbound = mock(InboundSessionPersistenceService.class);
        when(inbound.evaluateEntryAndPersistCustomer(any(), anyString())).thenReturn(new InboundSessionPersistenceService.EntryEvaluation(
                InboundSessionPersistenceService.EntryAction.PROCEED_TO_BOT, UUID.randomUUID(), "BOT_ACTIVE", null, true));
        when(inbound.verifyOutboundStillPermittedFresh(any(), anyString())).thenReturn(false);
        service.setInboundSessionPersistenceService(inbound);
        var result = service.handleCustomerMessage(new FanpageBotRequest(pageId.toString(), "takeover", "", "Giá", null, null, null, 5, null, null, null, null));
        assertThat(result.replyBubbles()).isEmpty();
        verify(gateway).respond(any(), any(), anyString(), any());
    }
    @Test void newerCustomerEpochSuppressesCompletedModelResponse() {
        var source = source();
        String sender = UUID.randomUUID().toString();
        SenderEpochRegistry.startNewBatchEpoch(pageId.toString(), sender);
        var llm = SalesCoreTest.fake(() -> {
            SenderEpochRegistry.startNewBatchEpoch(pageId.toString(), sender);
            return SalesCoreTest.answer("Mình dự tính thế nào ạ?", "CLARIFY", "[]").replace("cổ vai gáy chân tay", "Giá");
        });
        try (var core = new SalesTurnOrchestrator(llm, new IndustryProfileRegistry(), 5000)) {
            var service = new FanpageBotService(retrieval, llm, new ChatProperties(), docs, pages, null, null, null, null, null);
            service.setSalesCoreGateway(new SalesCoreGateway(core, source, true));
            var result = service.handleCustomerMessage(new FanpageBotRequest(pageId.toString(), sender, "", "Giá", null, null, null, 5, null, null, null, null));
            assertThat(result.replyBubbles()).isEmpty();
            assertThat(result.triggeredHooks()).contains("SUPERSEDED_SALES_CORE_TURN");
        } finally { SenderEpochRegistry.clearCurrentThreadTurn(); }
    }
}
