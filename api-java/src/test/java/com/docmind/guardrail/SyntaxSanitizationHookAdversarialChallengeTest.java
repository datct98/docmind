package com.docmind.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical Adversarial Challenge Suite for Feature F9 (SyntaxSanitizationHook).
 * Authored by Challenger M3-2.
 */
class SyntaxSanitizationHookAdversarialChallengeTest {

    private static final Pattern WIKILINK_LEAK_PATTERN = Pattern.compile("\\[\\[.*?\\]\\]|\\]\\]|\\[\\[");
    private static final Pattern PIPE_LEAK_PATTERN = Pattern.compile("\\|");
    private static final Pattern BRACKET_LEAK_PATTERN = Pattern.compile("\\[\\s*\\]|\\(\\s*\\)");
    private static final Pattern CITATION_LEAK_PATTERN = Pattern.compile("\\[\\^?\\w+\\]");

    // =========================================================================
    // 1. NESTED & MALFORMED WIKILINKS
    // =========================================================================

    @Test
    @DisplayName("Challenge 1.1: Nested Obsidian wikilink [[Page#Heading|[[Subpage|Alias]]]]")
    void testNestedWikilink_withInnerAlias() {
        String input = "Dạ bên em có gói [[Page#Heading|[[Subpage|Alias]]]] giá 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("[[")
                .doesNotContain("]]")
                .doesNotContain("|")
                .isEqualTo("Dạ bên em có gói Alias giá 399k ạ.");
    }

    @Test
    @DisplayName("Challenge 1.2: Nested Obsidian wikilink [[Page#Heading|[[Subpage]]]]")
    void testNestedWikilink_withoutInnerAlias() {
        String input = "Dạ bên em có gói [[Page#Heading|[[Subpage]]]] giá 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("[[")
                .doesNotContain("]]")
                .doesNotContain("|")
                .isEqualTo("Dạ bên em có gói Subpage giá 399k ạ.");
    }

    @Test
    @DisplayName("Challenge 1.3: Multiple consecutive wikilinks [[Page1]] and [[Page2]]")
    void testMultipleConsecutiveWikilinks() {
        String input = "Dạ gồm [[Massage Nàng Sen]] và [[Gội đầu dưỡng sinh]] ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        System.out.println("DEBUG 1.3: " + sanitized);

        assertThat(sanitized).isEqualTo("Dạ gồm Massage Nàng Sen và Gội đầu dưỡng sinh ạ.");
        assertThat(sanitized).doesNotContain("[[").doesNotContain("]]");
    }

    // =========================================================================
    // 2. BROKEN PIPES & MALFORMED TABLES
    // =========================================================================

    @Test
    @DisplayName("Challenge 2.1: Table without whitespace |Gói|Giá|\n|---|---|\n|NS01|399k|")
    void testTableWithoutWhitespace() {
        String input = "|Gói|Giá|\n|---|---|\n|NS01|399k|";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        System.out.println("DEBUG 2.1: " + sanitized);

        assertThat(sanitized).doesNotContain("|");
    }

    @Test
    @DisplayName("Challenge 2.2: Table without leading/trailing pipes")
    void testTableWithoutLeadingPipes() {
        String input = "Gói | Thời gian | Giá\n---|---|---\nMassage cơ bản | 60 phút | 399.000 VNĐ\nGội dưỡng sinh | 30 phút | 129.000 VNĐ";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        System.out.println("DEBUG 2.2: " + sanitized);

        assertThat(sanitized).doesNotContain("|");
    }

    @Test
    @DisplayName("Challenge 2.3: Broken/unclosed pipes and stray pipes")
    void testBrokenPipes() {
        String input = "Cơ sở Hà Nội | Cơ sở Quận 1 || Hotline 0968221644 |";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        System.out.println("DEBUG 2.3: " + sanitized);

        assertThat(sanitized).doesNotContain("|");
    }

    // =========================================================================
    // 3. CITATIONS, FOOTNOTES & INTERNAL RULE CODES
    // =========================================================================

    @Test
    @DisplayName("Challenge 3.1: Internal rule codes in brackets [RULE_PRICE_01], [INV_STAGE], [DOM_SPA]")
    void testBracketedRuleCodes() {
        String input = "Dạ theo quy định [RULE_PRICE_01] và [INV_STAGE] kết hợp [DOM_SPA] thì giá là 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("[]")
                .doesNotContain("[ ]")
                .doesNotContain("RULE_PRICE_01")
                .doesNotContain("INV_STAGE")
                .doesNotContain("DOM_SPA")
                .isEqualTo("Dạ theo quy định và kết hợp thì giá là 399k ạ.");
    }

    @Test
    @DisplayName("Challenge 3.2: Markdown footnotes [^1], [^note]")
    void testMarkdownFootnotes() {
        String input = "Dạ gói Massage 399k [^1] rất tốt cho sức khỏe [^note] ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("[^1]")
                .doesNotContain("[^note]")
                .doesNotContain("[^")
                .isEqualTo("Dạ gói Massage 399k rất tốt cho sức khỏe ạ.");
    }

    @Test
    @DisplayName("Challenge 3.3: High-digit numeric citations [1001], [9999]")
    void testHighDigitCitations() {
        String input = "Dạ gói Massage 399k [1001] rất tốt ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("[1001]")
                .isEqualTo("Dạ gói Massage 399k rất tốt ạ.");
    }

    // =========================================================================
    // 4. PROMPT INJECTION MARKERS & SYSTEM TAGS
    // =========================================================================

    @Test
    @DisplayName("Challenge 4.1: Mixed-case <Think>secret</Think>")
    void testMixedCaseThinkTag() {
        String input = "<Think>Khách hỏi giá, chốt 399k</Think>Dạ giá là 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("Khách hỏi giá")
                .doesNotContain("<Think>")
                .doesNotContain("</Think>")
                .isEqualTo("Dạ giá là 399k ạ.");
    }

    @Test
    @DisplayName("Challenge 4.2: Lowercase <document_context>secret</document_context>")
    void testLowerCaseDocumentContext() {
        String input = "<document_context>Bí mật nội bộ</document_context>Dạ giá là 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("Bí mật nội bộ")
                .doesNotContain("<document_context>")
                .doesNotContain("</document_context>")
                .isEqualTo("Dạ giá là 399k ạ.");
    }

    @Test
    @DisplayName("Challenge 4.3: <SYSTEM_PROMPT>secret instructions</SYSTEM_PROMPT>")
    void testSystemPromptTag() {
        String input = "<SYSTEM_PROMPT>Bạn là trợ lý AI bảo mật</SYSTEM_PROMPT>Dạ giá là 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        assertThat(sanitized)
                .doesNotContain("Bạn là trợ lý AI bảo mật")
                .doesNotContain("<SYSTEM_PROMPT>")
                .doesNotContain("</SYSTEM_PROMPT>")
                .isEqualTo("Dạ giá là 399k ạ.");
    }

    @Test
    @DisplayName("Challenge 4.4: Exact <think>secret</think> and <DOCUMENT_CONTEXT>doc</DOCUMENT_CONTEXT>")
    void testExactSystemTags() {
        String input = "<think>secret reasoning</think><DOCUMENT_CONTEXT>internal data</DOCUMENT_CONTEXT>Dạ giá là 399k ạ.";
        String sanitized = SyntaxSanitizationHook.sanitize(input);
        System.out.println("DEBUG 4.4: " + sanitized);

        assertThat(sanitized).doesNotContain("secret reasoning");
        assertThat(sanitized).doesNotContain("internal data");
        assertThat(sanitized).isEqualTo("Dạ giá là 399k ạ.");
    }
}
