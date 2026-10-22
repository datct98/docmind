package com.example.docmind.service.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseQueryNormalizerChallengerR2Test {

    @Test
    @DisplayName("Mandatory Round 1 Dispatch Tokens: sp01, dv01, cs01, k01, PROMO_K10, NV02_CS01, 100k01")
    void verifyMandatoryDispatchTokens() {
        // Dispatch tokens check
        assertThat(VietnameseQueryNormalizer.normalize("sp01")).isEqualTo("sp01");
        assertThat(VietnameseQueryNormalizer.normalize("dv01")).isEqualTo("dv01");
        assertThat(VietnameseQueryNormalizer.normalize("cs01")).isEqualTo("cs01");
        assertThat(VietnameseQueryNormalizer.normalize("k01")).isEqualTo("k01");
        assertThat(VietnameseQueryNormalizer.normalize("PROMO_K10")).isEqualTo("PROMO_K10");
        assertThat(VietnameseQueryNormalizer.normalize("NV02_CS01")).isEqualTo("NV02_CS01");
        assertThat(VietnameseQueryNormalizer.normalize("100k01")).isEqualTo("100k01");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sp01", "SP01", "Sp01", "sP01",
            "dv02", "DV02", "cs03", "CS03",
            "lt01", "LT01", "nv99", "NV99",
            "ktv05", "KTV05", "cn02", "CN02",
            "k01", "K01", "k99", "K99",
            "PROMO_K10", "promo_k10", "PROMO_K01",
            "NV02_CS01", "nv02_cs01", "SP01_VIP",
            "100k01", "200k02", "500k99"
    })
    @DisplayName("Alphanumeric token preservation across mixed cases and patterns")
    void alphanumericTokensMustNeverBeCorrupted(String code) {
        String input = "Khách hỏi thông tin mã " + code + " có ưu đãi gì không";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        assertThat(normalized)
                .as("Code %s must be preserved verbatim in normalized query", code)
                .contains(code);
    }

    @Test
    @DisplayName("Code vs Currency discrimination: 100k (currency) vs 100k01 (code)")
    void codeVsCurrencyDiscrimination() {
        // 100k -> 100.000 VNĐ
        assertThat(VietnameseQueryNormalizer.normalize("giá 100k một buổi"))
                .isEqualTo("giá 100.000 VNĐ một buổi");

        // 100k01 -> 100k01 (must not become 100.000 VNĐ01 or 100không01)
        assertThat(VietnameseQueryNormalizer.normalize("mã gói 100k01 một buổi"))
                .isEqualTo("mã gói 100k01 một buổi");

        // 670k -> 670.000 VNĐ
        assertThat(VietnameseQueryNormalizer.normalize("gói massage 670k"))
                .isEqualTo("gói massage 670.000 VNĐ");

        // 670k02 -> 670k02
        assertThat(VietnameseQueryNormalizer.normalize("gói massage 670k02"))
                .isEqualTo("gói massage 670k02");
    }

    @Test
    @DisplayName("Messenger abbreviations vs alphanumeric tokens coexistence")
    void abbreviationsCoexistenceWithCodes() {
        String query = "ad oi mik muosn xem goi sp01 gia bn tien k shop";
        String normalized = VietnameseQueryNormalizer.normalize(query);

        // 'sp01' must remain 'sp01', NOT 'sản phẩm01'
        assertThat(normalized).contains("sp01");
        // Slang expansions
        assertThat(normalized).contains("tư vấn viên");
        assertThat(normalized).contains("mình");
        assertThat(normalized).contains("muốn");
        assertThat(normalized).contains("bao nhiêu");
        assertThat(normalized).contains("không");
    }

    @Test
    @DisplayName("Blank, null, and Unicode whitespace inputs return empty string")
    void blankInputsReturnEmpty() {
        assertThat(VietnameseQueryNormalizer.normalize(null)).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("   ")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("   \t\n  ")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize(" \t \r\n \f ")).isEmpty();
        // Unicode NBSP & special spaces
        assertThat(VietnameseQueryNormalizer.normalize("\u00A0")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("   \u00A0  \t\n  ")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize(" \u00A0 \t \r\n \f ")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u00A0\u00A0\u00A0")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u2007 \u202F \u3000")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("\u200B\u200C\u200D\uFEFF")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize(" \u00A0 \t \r\n \f \u200B \uFEFF \u202F \u2007 \u3000 ")).isEmpty();
    }

    @Test
    @DisplayName("Unicode non-breaking and zero-width spaces within normal queries are properly sanitized and collapsed")
    void unicodeWhitespaceWithinQueriesNormalized() {
        // NBSP between words
        assertThat(VietnameseQueryNormalizer.normalize("gói\u00A0670k\u00A0một\u00A0buổi"))
                .isEqualTo("gói 670.000 VNĐ một buổi");

        // NBSP in multi-word slang pattern
        assertThat(VietnameseQueryNormalizer.normalize("bao\u00A0nhiu\u00A0tiền\u00A0k\u00A0shop"))
                .isEqualTo("bao nhiêu tiền không shop");

        // Zero-width space and BOM around text
        assertThat(VietnameseQueryNormalizer.normalize("\uFEFFtôi\u200Bmuosn\u200Btư vấn"))
                .isEqualTo("tôi muốn tư vấn");

        // Mixed Unicode spaces and padding
        assertThat(VietnameseQueryNormalizer.normalize(" \u00A0 xem \u200B gói \uFEFF 100k \u00A0 "))
                .isEqualTo("xem gói 100.000 VNĐ");
    }

    @Test
    @DisplayName("Surrounding punctuation around codes")
    void punctuationAroundCodes() {
        assertThat(VietnameseQueryNormalizer.normalize("(sp01)")).isEqualTo("(sp01)");
        assertThat(VietnameseQueryNormalizer.normalize("[dv01]")).isEqualTo("[dv01]");
        assertThat(VietnameseQueryNormalizer.normalize("\"cs01\"")).isEqualTo("\"cs01\"");
        assertThat(VietnameseQueryNormalizer.normalize("sp01, dv01 và cs01")).isEqualTo("sp01, dv01 và cs01");
        assertThat(VietnameseQueryNormalizer.normalize("sp01?")).isEqualTo("sp01?");
        assertThat(VietnameseQueryNormalizer.normalize("mã:sp01")).isEqualTo("mã:sp01");
    }

    @Test
    @DisplayName("Valid URLs and emails must NEVER be corrupted by slang, telex, price, or duration patterns")
    void urlsAndEmailsMustNeverBeCorrupted() {
        // 1. Standalone URLs
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/sp"))
                .isEqualTo("https://nangsen.vn/sp");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/dv/massage"))
                .isEqualTo("https://nangsen.vn/dv/massage");
        assertThat(VietnameseQueryNormalizer.normalize("https://facebook.com/ad/12345"))
                .isEqualTo("https://facebook.com/ad/12345");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/cs/hanoi"))
                .isEqualTo("https://nangsen.vn/cs/hanoi");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/cn/saigon"))
                .isEqualTo("https://nangsen.vn/cn/saigon");
        assertThat(VietnameseQueryNormalizer.normalize("https://example.com/search?k=da"))
                .isEqualTo("https://example.com/search?k=da");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/package/60p"))
                .isEqualTo("https://nangsen.vn/package/60p");
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/promo/100k"))
                .isEqualTo("https://nangsen.vn/promo/100k");
        assertThat(VietnameseQueryNormalizer.normalize("www.nangsen.vn/sp/ds01"))
                .isEqualTo("www.nangsen.vn/sp/ds01");

        // 2. Email preservation
        assertThat(VietnameseQueryNormalizer.normalize("ad@nangsen.vn"))
                .isEqualTo("ad@nangsen.vn");
        assertThat(VietnameseQueryNormalizer.normalize("Gửi mail tới ad@nangsen.vn nhé ad"))
                .isEqualTo("Gửi mail tới ad@nangsen.vn nhé tư vấn viên");

        // 3. URL + Vietnamese query mixing
        assertThat(VietnameseQueryNormalizer.normalize("Xem gói https://nangsen.vn/sp/ds01 nhé ad, gia bn vay"))
                .isEqualTo("Xem gói https://nangsen.vn/sp/ds01 nhé tư vấn viên, giá bao nhiêu vậy");

        // 4. Multiple URLs
        assertThat(VietnameseQueryNormalizer.normalize("So sánh https://nangsen.vn/sp/ds01 và https://nangsen.vn/sp/ds02 xem gia bn"))
                .isEqualTo("So sánh https://nangsen.vn/sp/ds01 và https://nangsen.vn/sp/ds02 xem giá bao nhiêu");

        // 5. Trailing punctuation & brackets
        assertThat(VietnameseQueryNormalizer.normalize("Vào https://nangsen.vn/sp. Giá bn?"))
                .isEqualTo("Vào https://nangsen.vn/sp. Giá bao nhiêu?");
        assertThat(VietnameseQueryNormalizer.normalize("Xem https://nangsen.vn/sp, tư vấn em với"))
                .isEqualTo("Xem https://nangsen.vn/sp, tư vấn em với");
        assertThat(VietnameseQueryNormalizer.normalize("Vào https://nangsen.vn/sp! Gói tốt k?"))
                .isEqualTo("Vào https://nangsen.vn/sp! Gói tốt không?");
        assertThat(VietnameseQueryNormalizer.normalize("(xem https://nangsen.vn/sp/ds01)"))
                .isEqualTo("(xem https://nangsen.vn/sp/ds01)");
        assertThat(VietnameseQueryNormalizer.normalize("[https://nangsen.vn/sp]"))
                .isEqualTo("[https://nangsen.vn/sp]");

        // 6. Query parameters and hash fragments
        assertThat(VietnameseQueryNormalizer.normalize("Vào https://nangsen.vn/search?k=massage&sp=1#section2 xem giar"))
                .isEqualTo("Vào https://nangsen.vn/search?k=massage&sp=1#section2 xem giá");

        // 7. Phone number + URL coexistence
        assertThat(VietnameseQueryNormalizer.normalize("sdt 0912345678 truy cập https://nangsen.vn/dv/massage"))
                .isEqualTo("số điện thoại 0912345678 truy cập https://nangsen.vn/dv/massage");

        // 8. Alphanumeric codes, prices, durations alongside URLs
        assertThat(VietnameseQueryNormalizer.normalize("Gói sp01 tại https://nangsen.vn/promo/100k có giá 239k làm trong 60p ko ad"))
                .isEqualTo("Gói sp01 tại https://nangsen.vn/promo/100k có giá 239.000 VNĐ làm trong 60 phút không tư vấn viên");
        assertThat(VietnameseQueryNormalizer.normalize("Mã 100k01 khác gói 100k ở https://nangsen.vn/sp/100k"))
                .isEqualTo("Mã 100k01 khác gói 100.000 VNĐ ở https://nangsen.vn/sp/100k");
    }
}
