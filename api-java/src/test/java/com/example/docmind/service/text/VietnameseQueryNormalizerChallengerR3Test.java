package com.example.docmind.service.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical Challenger Suite (M1-R3-1) for VietnameseQueryNormalizer.
 * Adversarially tests URL shielding, surrounding syntax, trailing punctuation,
 * email formats, Unicode whitespace sanitization, alphanumeric discrimination,
 * and mixed prose stress scenarios.
 */
class VietnameseQueryNormalizerChallengerR3Test {

    // =========================================================================
    // Category 1: Complex Nested URLs & Parameter Resiliency
    // =========================================================================

    @Test
    @DisplayName("Complex nested URL with multiple slang-like query parameters and fragments must be preserved 100% verbatim")
    void complexNestedUrlPreservedVerbatim() {
        String complexUrl = "https://nangsen.vn/sp/ds01?ref=ad&k=da&cs=hanoi&cn=saigon&p=60&tr=1.5#section-dv";
        String input = "Xem chi tiết gói tại " + complexUrl + " để biết thêm";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        assertThat(normalized)
                .as("URL with query params ref=ad, k=da, cs=hanoi, cn=saigon, p=60, tr=1.5 must be preserved verbatim")
                .isEqualTo("Xem chi tiết gói tại " + complexUrl + " để biết thêm");
    }

    @Test
    @DisplayName("URL with port, subdomains, encoded characters, and trailing path segments preserved verbatim")
    void urlWithPortSubdomainsEncodedChars() {
        String url1 = "https://sub.portal.nangsen.vn:8080/api/v1/sp/ds01?q=100k%20sale&filter=60p#dv-details";
        String normalized1 = VietnameseQueryNormalizer.normalize("Link: " + url1);
        assertThat(normalized1).isEqualTo("Link: " + url1);

        String url2 = "http://dev.nangsen.vn/cs/hanoi/sp?ad=true&k=0";
        String normalized2 = VietnameseQueryNormalizer.normalize(url2);
        assertThat(normalized2).isEqualTo(url2);

        String url3 = "www.nangsen.vn/promo/100k?duration=60p&ref=ad";
        String normalized3 = VietnameseQueryNormalizer.normalize("Truy cập " + url3);
        assertThat(normalized3).isEqualTo("Truy cập " + url3);
    }

    @Test
    @DisplayName("Multiple distinct URLs in one sentence are each isolated and restored without cross-contamination")
    void multipleUrlsInSingleSentence() {
        String input = "So sánh https://nangsen.vn/sp/ds01 và https://nangsen.vn/dv/massage với www.nangsen.vn/cs/hanoi xem gia bn";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        assertThat(normalized)
                .isEqualTo("So sánh https://nangsen.vn/sp/ds01 và https://nangsen.vn/dv/massage với www.nangsen.vn/cs/hanoi xem giá bao nhiêu");
    }

    // =========================================================================
    // Category 2: Surrounding Syntax & Enclosing Delimiters
    // =========================================================================

    @Test
    @DisplayName("URLs wrapped in parentheses, brackets, braces, and quotes must be cleanly isolated")
    void surroundingSyntaxPreserved() {
        // Parentheses
        assertThat(VietnameseQueryNormalizer.normalize("(https://nangsen.vn/sp/ds01)"))
                .isEqualTo("(https://nangsen.vn/sp/ds01)");
        assertThat(VietnameseQueryNormalizer.normalize("(xem https://nangsen.vn/sp/ds01)"))
                .isEqualTo("(xem https://nangsen.vn/sp/ds01)");

        // Square brackets
        assertThat(VietnameseQueryNormalizer.normalize("[https://nangsen.vn/sp]"))
                .isEqualTo("[https://nangsen.vn/sp]");
        assertThat(VietnameseQueryNormalizer.normalize("[xem tại https://nangsen.vn/sp]"))
                .isEqualTo("[xem tại https://nangsen.vn/sp]");

        // Curly braces
        assertThat(VietnameseQueryNormalizer.normalize("{https://nangsen.vn/sp}"))
                .isEqualTo("{https://nangsen.vn/sp}");

        // ASCII quotes
        assertThat(VietnameseQueryNormalizer.normalize("\"https://nangsen.vn/sp\""))
                .isEqualTo("\"https://nangsen.vn/sp\"");
        assertThat(VietnameseQueryNormalizer.normalize("'https://nangsen.vn/sp'"))
                .isEqualTo("'https://nangsen.vn/sp'");

        // Typographic / Curly quotes
        assertThat(VietnameseQueryNormalizer.normalize("“https://nangsen.vn/sp”"))
                .isEqualTo("“https://nangsen.vn/sp”");
        assertThat(VietnameseQueryNormalizer.normalize("‘https://nangsen.vn/sp’"))
                .isEqualTo("‘https://nangsen.vn/sp’");

        // Angle brackets
        assertThat(VietnameseQueryNormalizer.normalize("<https://nangsen.vn/sp>"))
                .isEqualTo("<https://nangsen.vn/sp>");

        // Markdown link syntax: text inside bracket normalized, URL protected
        assertThat(VietnameseQueryNormalizer.normalize("[sp](https://nangsen.vn/sp/ds01)"))
                .isEqualTo("[sản phẩm](https://nangsen.vn/sp/ds01)");
        assertThat(VietnameseQueryNormalizer.normalize("[dv](https://nangsen.vn/dv/01)"))
                .isEqualTo("[dịch vụ](https://nangsen.vn/dv/01)");
    }

    @Test
    @DisplayName("URLs with internal balanced parentheses (e.g. Wikipedia) do not prematurely trim")
    void urlWithInternalParentheses() {
        String wikiUrl = "https://vi.wikipedia.org/wiki/Bao_nhiêu_(từ)";
        assertThat(VietnameseQueryNormalizer.normalize(wikiUrl))
                .isEqualTo(wikiUrl);

        // Wrapped in outer parentheses
        assertThat(VietnameseQueryNormalizer.normalize("(" + wikiUrl + ")"))
                .isEqualTo("(" + wikiUrl + ")");
    }

    // =========================================================================
    // Category 3: Trailing Sentence Punctuation on URLs
    // =========================================================================

    @Test
    @DisplayName("Trailing sentence punctuation after URLs is properly separated and preserved")
    void trailingPunctuationDelineation() {
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp."))
                .isEqualTo("https://nangsen.vn/sp.");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp,"))
                .isEqualTo("https://nangsen.vn/sp,");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp!"))
                .isEqualTo("https://nangsen.vn/sp!");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp?"))
                .isEqualTo("https://nangsen.vn/sp?");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp;"))
                .isEqualTo("https://nangsen.vn/sp;");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp:"))
                .isEqualTo("https://nangsen.vn/sp:");

        // Multi-punctuation
        assertThat(VietnameseQueryNormalizer.normalize("Xem https://nangsen.vn/sp... gia bn?"))
                .isEqualTo("Xem https://nangsen.vn/sp... giá bao nhiêu?");
        assertThat(VietnameseQueryNormalizer.normalize("Xem https://nangsen.vn/sp?! Co tot k?"))
                .isEqualTo("Xem https://nangsen.vn/sp?! Co tot không?");
        assertThat(VietnameseQueryNormalizer.normalize("Xem (https://nangsen.vn/sp), cam on!"))
                .isEqualTo("Xem (https://nangsen.vn/sp), cam on!");
    }

    // =========================================================================
    // Category 4: Email Address Variations & Boundaries
    // =========================================================================

    @Test
    @DisplayName("Email variations with slang prefixes and subdomains must be shielded")
    void emailVariationsShielded() {
        // Dispatch test emails
        assertThat(VietnameseQueryNormalizer.normalize("ad@nangsen.vn"))
                .isEqualTo("ad@nangsen.vn");
        assertThat(VietnameseQueryNormalizer.normalize("support-sp@domain.vn"))
                .isEqualTo("support-sp@domain.vn");
        assertThat(VietnameseQueryNormalizer.normalize("ad.min@sub.domain.edu.vn"))
                .isEqualTo("ad.min@sub.domain.edu.vn");

        // Other common patterns
        assertThat(VietnameseQueryNormalizer.normalize("dv_support+booking@spa-nangsen.vn"))
                .isEqualTo("dv_support+booking@spa-nangsen.vn");

        // Surrounding punctuation on emails
        assertThat(VietnameseQueryNormalizer.normalize("Liên hệ (ad@nangsen.vn) để biết thêm."))
                .isEqualTo("Liên hệ (ad@nangsen.vn) để biết thêm.");
        assertThat(VietnameseQueryNormalizer.normalize("Gửi mail ad@nangsen.vn, tư vấn giúp mik!"))
                .isEqualTo("Gửi mail ad@nangsen.vn, tư vấn giúp mình!");
        assertThat(VietnameseQueryNormalizer.normalize("Mail: ad@nangsen.vn?"))
                .isEqualTo("Mail: ad@nangsen.vn?");
    }

    // =========================================================================
    // Category 5: Unicode Whitespace Sanitization & Zero-Width Spaces
    // =========================================================================

    @Test
    @DisplayName("All Unicode whitespace variants and zero-width artifacts evaluate to empty when blank")
    void unicodeWhitespaceBlankInputs() {
        String allBlanks = "\u00A0 \u2007 \u202F \u3000 \u200B \u200C \u200D \uFEFF \u2060 \u00AD \t \r \n \f";
        assertThat(VietnameseQueryNormalizer.normalize(allBlanks)).isEmpty();

        // Individual code points
        assertThat(VietnameseQueryNormalizer.normalize("\u00A0")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u2007")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u202F")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u3000")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u200B")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u200C")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u200D")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\uFEFF")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u2060")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u00AD")).isEmpty();
    }

    @Test
    @DisplayName("Unicode whitespaces inline do not disrupt token boundaries or word replacements")
    void unicodeWhitespaceInlineSanitization() {
        // NBSP and ideographic space separating words
        assertThat(VietnameseQueryNormalizer.normalize("gói\u00A0670k\u3000làm\u202Ftrong\u200760p"))
                .isEqualTo("gói 670.000 VNĐ làm trong 60 phút");

        // Zero-width space embedded around slang
        assertThat(VietnameseQueryNormalizer.normalize("\u200Bmuosn\u200B \uFEFFhoi\uFEFF \u00A0gia\u00A0bn\u00A0"))
                .isEqualTo("muốn hoi giá bao nhiêu");

        // Unicode whitespace adjacent to URLs
        assertThat(VietnameseQueryNormalizer.normalize("xem\u00A0https://nangsen.vn/sp\u00A0nhé\u200Bad"))
                .isEqualTo("xem https://nangsen.vn/sp nhé tư vấn viên");
    }

    // =========================================================================
    // Category 6: Mixed Prose Stress Scenarios
    // =========================================================================

    @Test
    @DisplayName("Stress test: Single query combining slang, price, duration, alphanumeric codes, URL, and email")
    void fullSpectrumMixedProseStressTest() {
        String query = "ad oi! Mik muosn xem goi sp01 tai https://nangsen.vn/sp/ds01?ref=ad&k=da&cs=hanoi&cn=saigon&p=60&tr=1.5#section-dv " +
                       "gia bn vay? Co phai 670k lam 60p ko shop? Gui mail qua ad@nangsen.vn hoac goi sdt 0912345678 nhe!";
        String normalized = VietnameseQueryNormalizer.normalize(query);

        // 1. URL preserved 100%
        assertThat(normalized).contains("https://nangsen.vn/sp/ds01?ref=ad&k=da&cs=hanoi&cn=saigon&p=60&tr=1.5#section-dv");

        // 2. Email preserved 100%
        assertThat(normalized).contains("ad@nangsen.vn");

        // 3. Alphanumeric code preserved
        assertThat(normalized).contains("sp01");

        // 4. Currency expanded
        assertThat(normalized).contains("670.000 VNĐ");

        // 5. Duration expanded
        assertThat(normalized).contains("60 phút");

        // 6. Phone number preserved
        assertThat(normalized).contains("0912345678");

        // 7. Slang expansions
        assertThat(normalized).contains("tư vấn viên");
        assertThat(normalized).contains("mình");
        assertThat(normalized).contains("muốn");
        assertThat(normalized).contains("giá bao nhiêu vậy");
        assertThat(normalized).contains("không shop");
        assertThat(normalized).contains("số điện thoại");
    }

    // =========================================================================
    // Category 7: Zero Regression on Mandatory Tokens, Currency, Duration, Phone
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "sp01", "dv01", "cs01", "k01",
            "PROMO_K10", "NV02_CS01", "100k01",
            "SP01", "DV01", "CS01", "K01"
    })
    @DisplayName("Mandatory dispatch tokens must never be corrupted")
    void mandatoryTokensNeverCorrupted(String token) {
        String input = "Chi tiết mã " + token + " ưu đãi thế nào";
        assertThat(VietnameseQueryNormalizer.normalize(input))
                .contains(token);
    }

    @Test
    @DisplayName("Boundary discrimination: Currency vs alphanumeric code")
    void currencyVsCodeBoundary() {
        // Currency
        assertThat(VietnameseQueryNormalizer.normalize("100k")).isEqualTo("100.000 VNĐ");
        assertThat(VietnameseQueryNormalizer.normalize("gói 100k")).isEqualTo("gói 100.000 VNĐ");

        // Code
        assertThat(VietnameseQueryNormalizer.normalize("100k01")).isEqualTo("100k01");
        assertThat(VietnameseQueryNormalizer.normalize("gói 100k01")).isEqualTo("gói 100k01");

        // Duration
        assertThat(VietnameseQueryNormalizer.normalize("60p")).isEqualTo("60 phút");
        assertThat(VietnameseQueryNormalizer.normalize("gói 60p01")).isEqualTo("gói 60p01");
    }

    @Test
    @DisplayName("All phone number formats preserved verbatim")
    void phoneNumbersPreservedVerbatim() {
        String[] phones = {
                "0912345678",
                "+84912345678",
                "84912345678",
                "0912.345.678",
                "0912 345 678",
                "0912-345-678",
                "(024) 3792 1234"
        };
        for (String phone : phones) {
            String text = "Khách liên hệ " + phone + " đặt lịch";
            assertThat(VietnameseQueryNormalizer.normalize(text))
                    .as("Phone %s must be preserved", phone)
                    .contains(phone);
        }
    }

    // =========================================================================
    // Category 8: Markdown Links & Advanced Delimiters
    // =========================================================================

    @Test
    @DisplayName("Markdown link with currency and codes in text vs URL")
    void markdownLinkWithCurrencyAndCodes() {
        // [gói 100k](https://nangsen.vn/sp/100k) -> [gói 100.000 VNĐ](https://nangsen.vn/sp/100k)
        String input = "Xem [gói 100k sp01](https://nangsen.vn/sp/100k?duration=60p&ref=ad) trong [60p](https://nangsen.vn/time/60p)";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        assertThat(normalized)
                .isEqualTo("Xem [gói 100.000 VNĐ sp01](https://nangsen.vn/sp/100k?duration=60p&ref=ad) trong [60 phút](https://nangsen.vn/time/60p)");
    }

    @Test
    @DisplayName("URLs with IP address and port numbers")
    void urlsWithIpAndPort() {
        assertThat(VietnameseQueryNormalizer.normalize("http://192.168.1.1:8080/sp/ds01?k=1"))
                .isEqualTo("http://192.168.1.1:8080/sp/ds01?k=1");
        assertThat(VietnameseQueryNormalizer.normalize("https://localhost:3000/dv/test"))
                .isEqualTo("https://localhost:3000/dv/test");
    }

    // =========================================================================
    // Category 9: Question Phrases & Telex Typo Normalization
    // =========================================================================

    @Test
    @DisplayName("Common question phrases with variations expand correctly")
    void questionPhrasesExpansion() {
        assertThat(VietnameseQueryNormalizer.normalize("sp này gia bn vay"))
                .isEqualTo("sản phẩm này giá bao nhiêu vậy");
        assertThat(VietnameseQueryNormalizer.normalize("dv nay gia bao nhieu vay shop"))
                .isEqualTo("dịch vụ nay giá bao nhiêu vậy shop");
        assertThat(VietnameseQueryNormalizer.normalize("gois sp01 the nao vay ad"))
                .isEqualTo("gói sp01 thế nào vậy tư vấn viên");
        assertThat(VietnameseQueryNormalizer.normalize("lieeuj trinhf bn vay"))
                .isEqualTo("liệu trình bao nhiêu vậy");
    }

    @Test
    @DisplayName("Extreme Unicode whitespace padding around codes, currencies, and URLs")
    void extremeUnicodeWhitespacePadding() {
        String input = "\u00A0\u00A0sp01\u2007\u2007100k\u202F\u202F60p\u3000\u3000https://nangsen.vn/sp\uFEFF\u200B";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        assertThat(normalized)
                .isEqualTo("sp01 100.000 VNĐ 60 phút https://nangsen.vn/sp");
    }

    // =========================================================================
    // Category 10: Idempotence & Adversarial Injection Resistance
    // =========================================================================

    @Test
    @DisplayName("Normalization is idempotent: normalize(normalize(text)) == normalize(text)")
    void idempotenceVerification() {
        String[] samples = {
                "ad oi mik muosn xem goi sp01 gia 100k tai https://nangsen.vn/sp/ds01?ref=ad&k=da trong 60p k shop",
                "sdt: 0912345678 gui mail ad@nangsen.vn gia bn vay",
                "   \u00A0 gói 670k làm trong 60p \u200B ",
                "Xem [gói 100k](https://nangsen.vn/sp/100k) sp01 gia bn vay ad"
        };
        for (String sample : samples) {
            String once = VietnameseQueryNormalizer.normalize(sample);
            String twice = VietnameseQueryNormalizer.normalize(once);
            assertThat(twice)
                    .as("Normalizing '%s' a second time must produce the identical output", sample)
                    .isEqualTo(once);
        }
    }

    @Test
    @DisplayName("Input containing synthetic placeholder strings does not cause collisions or crashes")
    void syntheticPlaceholderInjectionSafety() {
        String malicious = "___PROTECTED_TOKEN_12345_0___ xem gia bn k shop";
        String normalized = VietnameseQueryNormalizer.normalize(malicious);
        assertThat(normalized).contains("giá bao nhiêu không shop");
        assertThat(normalized).contains("___PROTECTED_TOKEN_12345_0___");
    }
}
