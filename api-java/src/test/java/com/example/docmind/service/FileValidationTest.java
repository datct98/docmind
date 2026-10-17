package com.example.docmind.service;

import com.example.docmind.exception.UnsupportedFileTypeException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test SHA-256 tất định + nhận diện MIME theo magic-byte (Tika, KHÔNG theo đuôi). */
class FileValidationTest {

    @Test
    void sha256MatchesKnownVector() {
        // SHA-256 của chuỗi rỗng (vector chuẩn).
        String empty = FileValidation.sha256Hex(new byte[0]);
        assertThat(empty)
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        // SHA-256 của "abc" (vector chuẩn).
        String abc = FileValidation.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII));
        assertThat(abc)
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256IsDeterministic() {
        byte[] data = "docmind".getBytes(StandardCharsets.UTF_8);
        assertThat(FileValidation.sha256Hex(data)).isEqualTo(FileValidation.sha256Hex(data));
    }

    @Test
    void detectsPdfByMagicBytesNotExtension() {
        // Magic byte PDF "%PDF-1.4" nhưng filename có đuôi .txt -> vẫn phải ra application/pdf.
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<< >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

        String byContentOnly = FileValidation.detectMime(pdfBytes);
        assertThat(byContentOnly).isEqualTo("application/pdf");

        String withMisleadingName = FileValidation.detectMime(pdfBytes, "notes.txt");
        assertThat(withMisleadingName).isEqualTo("application/pdf");

        assertThat(FileValidation.isSupported(withMisleadingName)).isTrue();
    }

    @Test
    void detectsPngByMagicBytes() {
        // PNG signature.
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0, 'I', 'H', 'D', 'R'};
        String mime = FileValidation.detectMime(png);
        assertThat(mime).isEqualTo("image/png");
    }

    @Test
    void detectsAndSupportsMarkdownTemplateFile() {
        byte[] mdBytes = "# BỘ TRI THỨC BÁN HÀNG CHUẨN\n\n## 1. BẢNG GIÁ\n| Mã SP | Giá |\n| --- | --- |\n"
                .getBytes(StandardCharsets.UTF_8);
        String mime = FileValidation.detectMime(mdBytes, "Template_Tri_Thuc_Sale_Chuan.md");
        assertThat(mime).isEqualTo("text/markdown");
        assertThat(FileValidation.isSupported(mime)).isTrue();
        assertThat(FileValidation.isSupported("text/x-web-markdown")).isTrue();
        assertThat(FileValidation.isSupported("text/x-markdown")).isTrue();
        FileValidation.requireSupported(mime);
        FileValidation.requireSupported("text/x-web-markdown");
    }

    @Test
    void rejectsUnsupportedType() {
        // ELF binary magic (0x7F 'E' 'L' 'F') -> application/x-elf, không nằm allow-list.
        byte[] elf = new byte[]{0x7F, 'E', 'L', 'F', 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        String mime = FileValidation.detectMime(elf);
        assertThat(FileValidation.isSupported(mime)).isFalse();
        assertThatThrownBy(() -> FileValidation.requireSupported(mime))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }
}
