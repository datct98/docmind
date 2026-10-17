package com.example.docmind.service;

import com.example.docmind.exception.UnsupportedFileTypeException;
import org.apache.tika.Tika;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Validate file đầu vào: nhận diện MIME theo magic-byte (Tika, KHÔNG theo đuôi file)
 * + tính SHA-256. Static helper, không giữ state ngoài 1 Tika instance thread-safe.
 */
public final class FileValidation {

    private static final Tika TIKA = new Tika();

    /** Danh sách MIME được hỗ trợ (khớp các loại parser xử lý). */
    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .docx
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", // .pptx
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
            "text/html",
            "text/markdown",
            "text/x-web-markdown",
            "text/x-markdown",
            "text/plain",
            "text/csv",
            "image/png",
            "image/jpeg"
    );

    private static final Set<String> MARKDOWN_MIME_ALIASES = Set.of(
            "text/x-web-markdown",
            "text/x-markdown"
    );

    private FileValidation() {
    }

    /** Nhận diện MIME theo nội dung (magic-byte), có xét thêm filename như gợi ý phụ. */
    public static String detectMime(byte[] bytes, String filename) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            // Tika.detect dựa chủ yếu vào magic-byte; filename chỉ là gợi ý thêm.
            String mime = TIKA.detect(in, filename);
            if (mime == null) {
                return "application/octet-stream";
            }
            if (MARKDOWN_MIME_ALIASES.contains(mime)) {
                return "text/markdown";
            }
            return mime;
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được bytes để detect MIME", e);
        }
    }

    /** Nhận diện MIME chỉ theo magic-byte (không dùng filename) — dùng cho test đối chiếu. */
    public static String detectMime(byte[] bytes) {
        String mime = TIKA.detect(bytes);
        if (mime != null && MARKDOWN_MIME_ALIASES.contains(mime)) {
            return "text/markdown";
        }
        return mime;
    }

    /** Ném {@link UnsupportedFileTypeException} nếu MIME không nằm trong allow-list. */
    public static void requireSupported(String mime) {
        if (!isSupported(mime)) {
            throw new UnsupportedFileTypeException(
                    "Loại file không được hỗ trợ (magic-byte MIME=" + mime + ")");
        }
    }

    public static boolean isSupported(String mime) {
        return mime != null && ALLOWED_MIME.contains(mime);
    }

    /** SHA-256 hex của raw bytes. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM thiếu SHA-256", e);
        }
    }
}
