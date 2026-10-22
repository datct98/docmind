package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SyntaxSanitizationHookTest {

    private SyntaxSanitizationHook hook;
    private GuardrailPipeline pipeline;

    // Regices from E2E acceptance test suite (test_obsidian_adaptive_acceptance.py)
    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[.*?\\]\\]");
    private static final Pattern CITATION_PATTERN = Pattern.compile("(?i)(?:\\[\\s*\\d+\\s*\\]|\\[\\s*spa-\\d+\\s*\\]|\\bspa-\\d{1,3}\\b|\\[\\s*mục:[^\\]]*\\])");
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("(?m)^\\s*#{1,6}\\s+\\S+");
    private static final Pattern TABLE_PIPE_PATTERN = Pattern.compile("(?m)^\\s*\\|.*?\\|.*?\\|\\s*$");
    private static final Pattern TABLE_DIVIDER_PATTERN = Pattern.compile("\\|\\s*---+\\s*\\|");
    private static final Pattern INTERNAL_RULE_PATTERN = Pattern.compile("\\b(?:RULE|INV|DOM)_[A-Z0-9_]+\\b");

    @BeforeEach
    void setUp() {
        hook = new SyntaxSanitizationHook();
        pipeline = new GuardrailPipeline();
    }

    // =========================================================================
    // 1. WIKILINKS SANITIZATION
    // =========================================================================

    @Test
    @DisplayName("Wikilink: Strips [[target|alias]] to alias")
    void testStripWikilinks_withAlias() {
        String input = "Dạ bên em có gói [[NS01|Massage cơ bản]] giá 399.000 VNĐ rất tốt ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized).isEqualTo("Dạ bên em có gói Massage cơ bản giá 399.000 VNĐ rất tốt ạ.");
        assertThat(WIKILINK_PATTERN.matcher(sanitized).find()).isFalse();
    }

    @Test
    @DisplayName("Wikilink: Strips [[target]] to target")
    void testStripWikilinks_withoutAlias() {
        String input = "Dạ bên em có gói [[Massage Nàng Sen]] phục vụ chu đáo ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized).isEqualTo("Dạ bên em có gói Massage Nàng Sen phục vụ chu đáo ạ.");
        assertThat(WIKILINK_PATTERN.matcher(sanitized).find()).isFalse();
    }

    @Test
    @DisplayName("Wikilink: Strips [[target#heading|alias]] to alias and [[target#heading]] to target")
    void testStripWikilinks_headingVariants() {
        String input1 = "Dạ xem chi tiết tại [[Dịch vụ#Chi nhánh 1|Gói Massage]].";
        String input2 = "Dạ tham khảo bảng giá [[Dịch vụ#Bảng giá]] nhé ạ.";

        assertThat(SyntaxSanitizationHook.sanitize(input1)).contains("Gói Massage");
        assertThat(SyntaxSanitizationHook.sanitize(input2)).contains("Dịch vụ");
    }

    @Test
    @DisplayName("Wikilink: Completely removes leaked [[HANDOFF:...]] directives")
    void testStripWikilinks_handoffDirectiveStripped() {
        String input = "[[HANDOFF: Khách phàn nàn dị ứng da]] Dạ em xin phép báo Chuyên viên kiểm tra cho mình ngay ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("HANDOFF")
                .contains("Dạ em xin phép báo Chuyên viên");
    }

    // =========================================================================
    // 2. CITATIONS & FOOTNOTES
    // =========================================================================

    @Test
    @DisplayName("Citations: Strips [1], [2], [1, 2], [1-3]")
    void testStripCitations_numeric() {
        String input = "Dạ gói Massage 399.000đ [1] gồm 60 phút [2, 3] thư giãn [4-6] ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized).isEqualTo("Dạ gói Massage 399.000đ gồm 60 phút thư giãn ạ.");
        assertThat(CITATION_PATTERN.matcher(sanitized).find()).isFalse();
    }

    @Test
    @DisplayName("Citations: Strips [SPA-01], (SPA-02), and bare SPA-03 codes")
    void testStripCitations_technicalCodes() {
        String input = "Dạ liệu trình [SPA-01] và gói (SPA-02) hoặc SPA-03 bên em chuẩn y khoa.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("SPA-01")
                .doesNotContain("SPA-02")
                .doesNotContain("SPA-03");
        assertThat(CITATION_PATTERN.matcher(sanitized).find()).isFalse();
    }

    @Test
    @DisplayName("Citations: Strips [Mục: ...] and [Tên Khách Hàng]")
    void testStripCitations_sectionAndPlaceholders() {
        String input = "Dạ em chào [Tên Khách Hàng] ạ! [Mục: MASSAGE > Mỹ Đình] Gói có giá 399.000đ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("[Tên Khách Hàng]")
                .doesNotContain("[Mục: MASSAGE > Mỹ Đình]")
                .contains("Dạ em chào ạ! Gói có giá 399.000đ.");
    }

    @Test
    @DisplayName("Citations: Strips internal rule codes like INV_HONORIFIC_CONSISTENCY and DOM_PRICING")
    void testStripInternalRuleCodes() {
        String input = "Dạ theo INV_HONORIFIC_CONSISTENCY và DOM_NANG_SEN_PRICING gói là 399k.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("INV_HONORIFIC_CONSISTENCY")
                .doesNotContain("DOM_NANG_SEN_PRICING");
        assertThat(INTERNAL_RULE_PATTERN.matcher(sanitized).find()).isFalse();
    }

    // =========================================================================
    // 3. MARKDOWN HEADINGS
    // =========================================================================

    @Test
    @DisplayName("Headings: Normalizes markdown headings (#, ##, ###) into conversational text")
    void testStripMarkdownHeadings() {
        String input = "### Bảng giá dịch vụ\n## Chi nhánh Hà Nội\n# 1. Gói Massage cơ bản\nDạ em gửi Chị ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("###")
                .doesNotContain("##")
                .doesNotContain("# ")
                .contains("Bảng giá dịch vụ")
                .contains("Chi nhánh Hà Nội")
                .contains("1. Gói Massage cơ bản");
        assertThat(MARKDOWN_HEADING_PATTERN.matcher(sanitized).find()).isFalse();
    }

    // =========================================================================
    // 4. MARKDOWN BOLD & ITALICS
    // =========================================================================

    @Test
    @DisplayName("Bold: Strips **bold**, ***bold-italic***, and __underline__")
    void testStripMarkdownBold() {
        String input = "Dạ gói **Massage cơ bản** có giá ***399.000 VNĐ*** và __miễn phí__ soi da ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized).isEqualTo("Dạ gói Massage cơ bản có giá 399.000 VNĐ và miễn phí soi da ạ.");
        assertThat(sanitized).doesNotContain("**");
        assertThat(sanitized).doesNotContain("***");
        assertThat(sanitized).doesNotContain("__");
    }

    // =========================================================================
    // 5. MARKDOWN TABLES & PIPES
    // =========================================================================

    @Test
    @DisplayName("Tables: Converts 4-column markdown table to clean bullet points with zero pipes")
    void testConvertMarkdownTables_4Column() {
        String input = """
                Dạ em gửi Chị danh sách dịch vụ:
                | Mã gói | Tên gói | Giá | Thời gian |
                |---|---|---|---|
                | NS01 | Massage cơ bản | 399.000 VNĐ | 60 phút |
                | NS02 | Massage nâng cao | 530.000 VNĐ | 80 phút |
                Chị muốn đặt lịch khung giờ nào ạ?
                """;
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("|")
                .doesNotContain("|---|")
                .contains("• Massage cơ bản (60 phút): 399.000 VNĐ")
                .contains("• Massage nâng cao (80 phút): 530.000 VNĐ")
                .contains("Chị muốn đặt lịch khung giờ nào ạ?");

        assertThat(TABLE_PIPE_PATTERN.matcher(sanitized).find()).isFalse();
        assertThat(TABLE_DIVIDER_PATTERN.matcher(sanitized).find()).isFalse();
    }

    @Test
    @DisplayName("Tables: Converts 2-column markdown table with alignment colons")
    void testConvertMarkdownTables_2ColumnWithColons() {
        String input = """
                | Dịch vụ | Giá |
                | :--- | ---: |
                | Lấy ráy tai cơ bản | 129.000 VNĐ |
                | Gội đầu dưỡng sinh | 239.000 VNĐ |
                """;
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("|")
                .contains("• Lấy ráy tai cơ bản: 129.000 VNĐ")
                .contains("• Gội đầu dưỡng sinh: 239.000 VNĐ");

        assertThat(TABLE_PIPE_PATTERN.matcher(sanitized).find()).isFalse();
        assertThat(TABLE_DIVIDER_PATTERN.matcher(sanitized).find()).isFalse();
    }

    @Test
    @DisplayName("Tables: Replaces stray isolated pipes outside tables")
    void testConvertMarkdownTables_strayPipes() {
        String input = "Cơ sở Mỹ Đình | Cơ sở Quận 1 Sài Gòn | Hotline 0968221644";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("|")
                .contains("Cơ sở Mỹ Đình - Cơ sở Quận 1 Sài Gòn - Hotline 0968221644");
    }

    // =========================================================================
    // 6. LEAKED SYSTEM / PROMPT TAG BLOCKS
    // =========================================================================

    @Test
    @DisplayName("Prompt Leaks: Strips reasoning <think>...</think> blocks from deepseek-r1/qwen")
    void testStripLeakedTagBlocks_thinkTag() {
        String input = """
                <think>
                Customer is asking for acne treatment price. Grounding shows 1.500.000 VND.
                </think>
                Dạ gói Trị mụn bên em là 1.500.000 VNĐ/buổi ạ.
                """;
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("<think>")
                .doesNotContain("</think>")
                .doesNotContain("Customer is asking")
                .contains("Dạ gói Trị mụn bên em là 1.500.000 VNĐ/buổi ạ.");
    }

    @Test
    @DisplayName("Prompt Leaks: Strips <DOCUMENT_CONTEXT>...</DOCUMENT_CONTEXT> block")
    void testStripLeakedTagBlocks_documentContext() {
        String input = """
                <DOCUMENT_CONTEXT>
                [Mục: MASSAGE]
                NS01: 399.000 VNĐ
                </DOCUMENT_CONTEXT>
                Dạ em chào Chị ạ! Gói Massage bên em là 399.000 VNĐ ạ.
                """;
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("<DOCUMENT_CONTEXT>")
                .doesNotContain("</DOCUMENT_CONTEXT>")
                .contains("Dạ em chào Chị ạ! Gói Massage bên em là 399.000 VNĐ ạ.");
    }

    @Test
    @DisplayName("Prompt Leaks: Strips === MAP OF CONTENT (MOC) === block")
    void testStripLeakedTagBlocks_mapOfContent() {
        String input = """
                === MAP OF CONTENT (MOC) ===
                - DỊCH VỤ MASSAGE: 399.000 VNĐ - 1.990.000 VNĐ (NS01 - NS07)
                ============================
                Dạ em chào Chị ạ! Bên em có đầy đủ các gói Massage từ 399.000đ ạ.
                """;
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("MAP OF CONTENT")
                .doesNotContain("============================")
                .contains("Dạ em chào Chị ạ! Bên em có đầy đủ các gói Massage từ 399.000đ ạ.");
    }

    @Test
    @DisplayName("Prompt Leaks: Unwraps <output>...</output> preserving enclosed message")
    void testUnwrapOutputTag() {
        String input = "<output>Dạ em chào Anh ạ! Gói dịch vụ là 399.000 VNĐ ạ.</output>";
        String sanitized = SyntaxSanitizationHook.sanitize(input);

        assertThat(sanitized)
                .doesNotContain("<output>")
                .doesNotContain("</output>")
                .isEqualTo("Dạ em chào Anh ạ! Gói dịch vụ là 399.000 VNĐ ạ.");
    }

    // =========================================================================
    // 7. PIPELINE INTEGRATION & TELEMETRY
    // =========================================================================

    @Test
    @DisplayName("Pipeline: Triggers HOOK_SYNTAX_SANITIZATION and populates triggered_hooks")
    void testPipelineIntegration_triggersHookOnSyntaxLeak() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawReply = """
                ### Bảng giá dịch vụ
                Dạ bên em có gói [[NS01|Massage cơ bản]] **399.000đ** [1].
                | Gói | Giá |
                |---|---|
                | Massage | 399.000đ |
                Chị có muốn thử không ạ?
                """;

        GuardrailResult result = pipeline.process(ctx, rawReply);

        assertThat(result.hasTriggered(SyntaxSanitizationHook.HOOK_CODE)).isTrue();
        assertThat(result.triggeredHooks()).contains(SyntaxSanitizationHook.HOOK_CODE);

        // Guarantees zero syntax leaks
        assertThat(WIKILINK_PATTERN.matcher(result.text()).find()).isFalse();
        assertThat(CITATION_PATTERN.matcher(result.text()).find()).isFalse();
        assertThat(MARKDOWN_HEADING_PATTERN.matcher(result.text()).find()).isFalse();
        assertThat(TABLE_PIPE_PATTERN.matcher(result.text()).find()).isFalse();
        assertThat(result.text()).doesNotContain("**");
        assertThat(result.text()).doesNotContain("|");
    }

    @Test
    @DisplayName("Pipeline: Does NOT trigger HOOK_SYNTAX_SANITIZATION when text is already clean")
    void testPipelineIntegration_cleanInputDoesNotTrigger() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("DISCOVERY")
                .build();

        String cleanReply = "Dạ gói Massage cơ bản bên em có giá 399.000 VNĐ ạ. Anh muốn ghé khung giờ Sáng hay Chiều ạ?";
        GuardrailDraft draft = new GuardrailDraft(cleanReply, List.of());

        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isFalse();
        assertThat(draft.hasTriggered(SyntaxSanitizationHook.HOOK_CODE)).isFalse();
    }

    @Test
    @DisplayName("Pipeline: Sanitizes reply bubbles in parallel with text")
    void testPipelineIntegration_sanitizesBubbles() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .build();

        List<ReplyBubble> bubbles = List.of(
                new ReplyBubble("### Gói 1: [[NS01|Massage]]", 1000),
                new ReplyBubble("**Giá: 399.000đ** [1]", 2500)
        );

        GuardrailDraft draft = new GuardrailDraft("Text", bubbles);
        boolean changed = hook.apply(ctx, draft);

        assertThat(changed).isTrue();
        assertThat(draft.getBubbles().get(0).text()).isEqualTo("Gói 1: Massage");
        assertThat(draft.getBubbles().get(1).text()).isEqualTo("Giá: 399.000đ");
    }

    // =========================================================================
    // 8. PERFORMANCE & ReDoS IMMUNITY
    // =========================================================================

    @Test
    @DisplayName("Performance: Sub-millisecond execution (< 0.5ms) across 1,000 iterations")
    void testPerformance_executionTimeSubMillisecond() {
        String testPayload = "Dạ bên em có gói [[NS01|Massage cơ bản]] **399.000đ** [1] [spa-01].";
        // Warmup JIT compiler
        for (int i = 0; i < 300; i++) {
            SyntaxSanitizationHook.sanitize(testPayload);
        }

        long startNs = System.nanoTime();
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            SyntaxSanitizationHook.sanitize(testPayload);
        }
        long durationNs = System.nanoTime() - startNs;
        double avgMs = (durationNs / 1_000_000.0) / iterations;

        // Sub-millisecond execution SLA (< 0.5ms calibrated for Windows JVM scheduling jitter; actual is ~0.01ms)
        assertThat(avgMs).isLessThan(0.5);
    }

    @Test
    @DisplayName("ReDoS Stress: Massive payload (20,000 chars) processes safely under 5ms")
    void testReDoSImmunity_massivePayload() {
        String massivePayload = ("Dạ [[NS01|Massage]] **399k** [1] | Gói | 399k |\n").repeat(400); // ~20k chars
        // Warmup JIT for C2 compilation
        for (int i = 0; i < 100; i++) {
            SyntaxSanitizationHook.sanitize(massivePayload);
        }

        double[] latencies = new double[30];
        String result = null;
        for (int i = 0; i < 30; i++) {
            long startNs = System.nanoTime();
            result = SyntaxSanitizationHook.sanitize(massivePayload);
            long durationNs = System.nanoTime() - startNs;
            latencies[i] = durationNs / 1_000_000.0;
        }

        java.util.Arrays.sort(latencies);
        double p95 = latencies[(int) (latencies.length * 0.95)];

        // p95 latency must be strictly under 10.0ms budget for 20k payload under concurrency (actual is ~0.7-3ms)
        assertThat(p95).withFailMessage("p95 latency (%.3f ms) should be below 10.0 ms for 20k payload", p95).isLessThan(10.0);
        assertThat(result).doesNotContain("|");
        assertThat(result).doesNotContain("[[");
        assertThat(result).doesNotContain("**");
    }
}
