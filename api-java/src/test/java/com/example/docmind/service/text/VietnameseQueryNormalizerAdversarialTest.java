package com.example.docmind.service.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VietnameseQueryNormalizerAdversarialTest {

    @Test
    @DisplayName("Rapid sequences of mixed slang and abbreviations")
    void rapidSlangSequence() {
        String input = "ad oi mik muosn hoi gia goi ds01 bn tien sdt 0912345678";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        // Required expansions
        assertThat(normalized).contains("tư vấn viên");
        assertThat(normalized).contains("mình");
        assertThat(normalized).contains("muốn");
        assertThat(normalized).contains("bao nhiêu");
        assertThat(normalized).contains("số điện thoại");
        assertThat(normalized).contains("0912345678");
    }

    @Test
    @DisplayName("Phone numbers in various standard formats must NEVER be corrupted or stripped")
    void phoneNumbersNeverCorrupted() {
        String[] phones = {
                "0912345678",
                "0968123456",
                "+84912345678",
                "84912345678",
                "0912.345.678",
                "0912 345 678",
                "0912-345-678",
                "(024) 3792 1234"
        };

        for (String phone : phones) {
            String text = "Khách liên hệ qua số " + phone + " để đặt lịch";
            String normalized = VietnameseQueryNormalizer.normalize(text);
            assertThat(normalized).as("Phone number %s must be preserved verbatim", phone)
                    .contains(phone);
        }
    }

    @Test
    @DisplayName("Boundary case: Service and Product codes containing abbreviations must not be corrupted")
    void serviceAndProductCodesMustNotBeCorrupted() {
        // e.g. "sp01" should NOT become "sản phẩm01"
        // "dv01" should NOT become "dịch vụ01"
        // "cs01" should NOT become "cơ sở01"
        // "k01" should NOT become "không01"
        assertThat(VietnameseQueryNormalizer.normalize("tư vấn cho mình gói sp01"))
                .isEqualTo("tư vấn cho mình gói sp01");

        assertThat(VietnameseQueryNormalizer.normalize("mình muốn xem chi nhánh cs01"))
                .isEqualTo("mình muốn xem chi nhánh cs01");

        assertThat(VietnameseQueryNormalizer.normalize("báo giá dịch vụ dv01"))
                .isEqualTo("báo giá dịch vụ dv01");

        assertThat(VietnameseQueryNormalizer.normalize("mã voucher PROMO_K10"))
                .isEqualTo("mã voucher PROMO_K10");

        assertThat(VietnameseQueryNormalizer.normalize("mã khách hàng k01"))
                .isEqualTo("mã khách hàng k01");

        assertThat(VietnameseQueryNormalizer.normalize("mã liệu trình 100k01"))
                .isEqualTo("mã liệu trình 100k01");

        assertThat(VietnameseQueryNormalizer.normalize("nhân viên NV02_CS01 phụ trách"))
                .isEqualTo("nhân viên NV02_CS01 phụ trách");

        assertThat(VietnameseQueryNormalizer.normalize("gói liệu trình lt01"))
                .isEqualTo("gói liệu trình lt01");

        assertThat(VietnameseQueryNormalizer.normalize("liên hệ nv01"))
                .isEqualTo("liên hệ nv01");

        assertThat(VietnameseQueryNormalizer.normalize("kỹ thuật viên ktv01"))
                .isEqualTo("kỹ thuật viên ktv01");

        assertThat(VietnameseQueryNormalizer.normalize("chi nhánh cn01"))
                .isEqualTo("chi nhánh cn01");

        assertThat(VietnameseQueryNormalizer.normalize("tư vấn cho mik gói sp01 bn tiền k shop"))
                .isEqualTo("tư vấn cho mình gói sp01 bao nhiêu tiền không shop");

        assertThat(VietnameseQueryNormalizer.normalize("mua 2sp"))
                .isEqualTo("mua 2sp");

        assertThat(VietnameseQueryNormalizer.normalize("mua 2 sp"))
                .isEqualTo("mua 2 sản phẩm");
    }

    @Test
    @DisplayName("Punctuation and emoji resilience")
    void punctuationAndEmojiResilience() {
        String input = "ad oi!!! 🥰 mik muosn hoi goi ds01??? bn tien a? sdt: 0912345678 👍";
        String normalized = VietnameseQueryNormalizer.normalize(input);

        assertThat(normalized).contains("tư vấn viên");
        assertThat(normalized).contains("mình");
        assertThat(normalized).contains("muốn");
        assertThat(normalized).contains("bao nhiêu");
        assertThat(normalized).contains("0912345678");
        assertThat(normalized).contains("🥰");
        assertThat(normalized).contains("👍");
    }

    @Test
    @DisplayName("Empty, null, and whitespace inputs")
    void emptyAndNullInputs() {
        assertThat(VietnameseQueryNormalizer.normalize(null)).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("")).isEmpty();
        assertThat(VietnameseQueryNormalizer.normalize("   \t  \n  ")).isEmpty();
    }
}
