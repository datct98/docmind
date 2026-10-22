package com.example.docmind.service.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseQueryNormalizerTest {

    @Test
    @DisplayName("Chuẩn hóa các lỗi gõ Telex phổ biến trên điện thoại")
    void normalizesTelexTypos() {
        assertThat(VietnameseQueryNormalizer.normalize("tôi muosn thử trải nghiệm trước"))
                .isEqualTo("tôi muốn thử trải nghiệm trước");

        assertThat(VietnameseQueryNormalizer.normalize("bao lâu thấy hiệu quẩ"))
                .isEqualTo("bao lâu thấy hiệu quả");

        assertThat(VietnameseQueryNormalizer.normalize("có hieue quả k"))
                .isEqualTo("có hiệu quả không");

        assertThat(VietnameseQueryNormalizer.normalize("ddaauf và cổ vai gáy trogn người"))
                .isEqualTo("đầu và cổ vai gáy trong người");

        assertThat(VietnameseQueryNormalizer.normalize("em bieets thông tin massag này"))
                .isEqualTo("em biết thông tin massage này");

        assertThat(VietnameseQueryNormalizer.normalize("T bị mụn 15 năm thi thaorng viêm"))
                .isEqualTo("T bị mụn 15 năm thi thoảng viêm");
    }

    @Test
    @DisplayName("Chuẩn hóa các từ viết tắt chat Messenger phổ biến")
    void normalizesMessengerAbbreviations() {
        assertThat(VietnameseQueryNormalizer.normalize("gói này bn tiền k shop"))
                .isEqualTo("gói này bao nhiêu tiền không shop");

        assertThat(VietnameseQueryNormalizer.normalize("sdt của c là 0912345678"))
                .isEqualTo("số điện thoại của c là 0912345678");

        assertThat(VietnameseQueryNormalizer.normalize("ib cho mik với ad"))
                .isEqualTo("inbox cho mình với tư vấn viên");

        assertThat(VietnameseQueryNormalizer.normalize("tgian làm là bao lâu và dv có dc giảm giá ko"))
                .isEqualTo("thời gian làm là bao lâu và dịch vụ có được giảm giá không");

        assertThat(VietnameseQueryNormalizer.normalize("sp này ntn vậy mn"))
                .isEqualTo("sản phẩm này như thế nào vậy mọi người");
    }

    @Test
    @DisplayName("Chuẩn hóa tiền tệ viết tắt 'k' và 'triệu/tr'")
    void normalizesCurrencyShorthand() {
        assertThat(VietnameseQueryNormalizer.normalize("gói 670k và 780k khác gì nhau"))
                .isEqualTo("gói 670.000 VNĐ và 780.000 VNĐ khác gì nhau");

        assertThat(VietnameseQueryNormalizer.normalize("giá 129k và 1990k"))
                .isEqualTo("giá 129.000 VNĐ và 1.990.000 VNĐ");

        assertThat(VietnameseQueryNormalizer.normalize("chi phí 1.5tr"))
                .isEqualTo("chi phí 1.500.000 VNĐ");
    }

    @Test
    @DisplayName("Bảo toàn số điện thoại, thời gian và các từ tiếng Anh không phải viết tắt")
    void preservesPiiAndValidEnglishWords() {
        assertThat(VietnameseQueryNormalizer.normalize("Gọi cho anh số 0968221644 nhé"))
                .isEqualTo("Gọi cho anh số 0968221644 nhé");

        assertThat(VietnameseQueryNormalizer.normalize("ok bạn"))
                .isEqualTo("ok bạn");

        assertThat(VietnameseQueryNormalizer.normalize("token link bank"))
                .isEqualTo("token link bank");
    }

    @Test
    @DisplayName("Bảo toàn 100% URL, URI, Email và xử lý non-breaking space")
    void preservesUrlsEmailsAndSanitizesNbsp() {
        // 1. URLs with path abbreviations that match dictionary words (sp, dv, ad, cs, cn)
        assertThat(VietnameseQueryNormalizer.normalize("Xem gói https://nangsen.vn/sp/ds01 nhé"))
                .isEqualTo("Xem gói https://nangsen.vn/sp/ds01 nhé");

        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/dv/massage"))
                .isEqualTo("https://nangsen.vn/dv/massage");

        assertThat(VietnameseQueryNormalizer.normalize("https://facebook.com/ad/12345"))
                .isEqualTo("https://facebook.com/ad/12345");

        assertThat(VietnameseQueryNormalizer.normalize("https://fb.com/ad/123"))
                .isEqualTo("https://fb.com/ad/123");

        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/cs/hanoi và https://nangsen.vn/cn/saigon"))
                .isEqualTo("https://nangsen.vn/cs/hanoi và https://nangsen.vn/cn/saigon");

        // 2. URLs with query parameters that match dictionary words (k, sp, etc.)
        assertThat(VietnameseQueryNormalizer.normalize("https://example.com/search?k=da"))
                .isEqualTo("https://example.com/search?k=da");

        assertThat(VietnameseQueryNormalizer.normalize("https://example.com/search?k=massage"))
                .isEqualTo("https://example.com/search?k=massage");

        // 3. URLs with duration and currency shorthands (60p, 100k, 1.5tr)
        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/promo/100k"))
                .isEqualTo("https://nangsen.vn/promo/100k");

        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/package/60p"))
                .isEqualTo("https://nangsen.vn/package/60p");

        assertThat(VietnameseQueryNormalizer.normalize("https://nangsen.vn/combo/1.5tr"))
                .isEqualTo("https://nangsen.vn/combo/1.5tr");

        // 4. WWW prefix without scheme
        assertThat(VietnameseQueryNormalizer.normalize("Xem gói www.nangsen.vn/sp nhé"))
                .isEqualTo("Xem gói www.nangsen.vn/sp nhé");

        // 5. Emails containing abbreviations (ad, sp, dv)
        assertThat(VietnameseQueryNormalizer.normalize("ad@nangsen.vn"))
                .isEqualTo("ad@nangsen.vn");

        assertThat(VietnameseQueryNormalizer.normalize("Gửi mail qua ad@nangsen.vn giúp mik"))
                .isEqualTo("Gửi mail qua ad@nangsen.vn giúp mình");

        assertThat(VietnameseQueryNormalizer.normalize("Liên hệ sp.support@domain.vn"))
                .isEqualTo("Liên hệ sp.support@domain.vn");

        // 6. Discrimination test: code/currency in URL vs standalone in prose
        assertThat(VietnameseQueryNormalizer.normalize("Xem https://nangsen.vn/promo/100k giá 100k"))
                .isEqualTo("Xem https://nangsen.vn/promo/100k giá 100.000 VNĐ");

        assertThat(VietnameseQueryNormalizer.normalize("gói 60p xem tại https://nangsen.vn/package/60p"))
                .isEqualTo("gói 60 phút xem tại https://nangsen.vn/package/60p");

        assertThat(VietnameseQueryNormalizer.normalize("ad oi xem link https://nangsen.vn/sp/ds01 gia bn k shop"))
                .isEqualTo("tư vấn viên oi xem link https://nangsen.vn/sp/ds01 giá bao nhiêu không shop");

        // 7. Surrounding punctuation and markdown
        assertThat(VietnameseQueryNormalizer.normalize("(https://nangsen.vn/sp/ds01)"))
                .isEqualTo("(https://nangsen.vn/sp/ds01)");

        assertThat(VietnameseQueryNormalizer.normalize("Xem tại https://nangsen.vn/sp/ds01. Cảm ơn"))
                .isEqualTo("Xem tại https://nangsen.vn/sp/ds01. Cảm ơn");

        assertThat(VietnameseQueryNormalizer.normalize("Xem tại https://nangsen.vn/sp/ds01, có k shop?"))
                .isEqualTo("Xem tại https://nangsen.vn/sp/ds01, có không shop?");

        // 8. Adversarial complex URL with multiple parameters
        assertThat(VietnameseQueryNormalizer.normalize("https://example.com/search?k=da&sp=1&dv=2&ad=3&cs=4&cn=5&p=60&tr=1.5"))
                .isEqualTo("https://example.com/search?k=da&sp=1&dv=2&ad=3&cs=4&cn=5&p=60&tr=1.5");

        // 9. Non-breaking space (\u00A0) sanitization
        assertThat(VietnameseQueryNormalizer.normalize("\u00A0")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize(" \u00A0 \t\n \u00A0 ")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("gói\u00A0sp01")).isEqualTo("gói sp01");
    }

    @Test
    @DisplayName("Bảo vệ chống ReDoS và giới hạn độ dài đầu vào 2000 ký tự")
    void clampsLongInputAndProtectsAgainstReDoS() {
        String longInput = "tôi muốn hỏi giá dịch vụ ".repeat(200); // ~5,000 chars
        String normalized = VietnameseQueryNormalizer.normalize(longInput);
        assertThat(normalized.length())
                .as("Output length must be bounded after clamping 2,000 char input")
                .isLessThanOrEqualTo(3000); // 2000 chars + small normalization expansion

        // Null safety
        assertThat(VietnameseQueryNormalizer.normalize(null)).isEmpty();
    }
}
