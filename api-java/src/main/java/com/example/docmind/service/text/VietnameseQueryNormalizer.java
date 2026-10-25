package com.example.docmind.service.text;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuẩn hóa tập trung truy vấn và tin nhắn khách hàng tiếng Việt (Messenger abbreviations, Telex typos, Currency shorthand).
 */
public final class VietnameseQueryNormalizer {

    private VietnameseQueryNormalizer() {}

    public static final int MAX_INPUT_LENGTH = 2000;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(?:https?://\\S+|www\\.\\S+)"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    );

    private static final Pattern PRICE_K_PATTERN = Pattern.compile(
            "(?<!\\w)(\\d{1,4})\\s*k(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern PRICE_TRIEU_PATTERN = Pattern.compile(
            "(?<!\\w)(\\d+(?:[.,]\\d+)?)\\s*(?:tr|trieu|triệu)(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(?<!\\w)(\\d+)\\s*(?:p|ph)(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Map<Pattern, String> PHRASE_REPLACEMENTS;
    private static final Map<Pattern, String> WORD_REPLACEMENTS;

    static {
        Map<Pattern, String> phrases = new LinkedHashMap<>();
        // Multi-word Messenger slang & common typos
        addBoundaryPattern(phrases, "gia\\s+bn\\s+vay", "giá bao nhiêu vậy");
        addBoundaryPattern(phrases, "gia\\s+bao\\s+nhieu\\s+vay", "giá bao nhiêu vậy");
        addBoundaryPattern(phrases, "gia\\s+bn", "giá bao nhiêu");
        addBoundaryPattern(phrases, "bn\\s+vay", "bao nhiêu vậy");
        addBoundaryPattern(phrases, "bao\\s+nhieu\\s+vay", "bao nhiêu vậy");
        addBoundaryPattern(phrases, "the\\s+nao\\s+vay", "thế nào vậy");
        addBoundaryPattern(phrases, "bao\\s+nhiu", "bao nhiêu");
        addBoundaryPattern(phrases, "b\\s*nhieu", "bao nhiêu");
        addBoundaryPattern(phrases, "thi\\s+thaorng", "thi thoảng");
        addBoundaryPattern(phrases, "thi\\s+thoang", "thi thoảng");
        addBoundaryPattern(phrases, "thi\\s+thỉang", "thi thoảng");
        addBoundaryPattern(phrases, "thi\\s+thiang", "thi thoảng");
        addBoundaryPattern(phrases, "thỉnh\\s+thỉang", "thỉnh thoảng");
        addBoundaryPattern(phrases, "thinh\\s+thoang", "thỉnh thoảng");
        addBoundaryPattern(phrases, "tụt\\s+hứng\\s+tghooi\\s+rẹp", "tụt hứng thôi dẹp");
        addBoundaryPattern(phrases, "tut\\s+hung\\s+tghooi\\s+rep", "tụt hứng thôi dẹp");
        addBoundaryPattern(phrases, "tụt\\s+hứng\\s+(?:thôi|thooi|tghooi|thoi)\\s+(?:dẹp|rẹp|dep|rep)", "tụt hứng thôi dẹp");
        // v3.3.4 RC-05: tổng quát hóa cho cả "mất/hết hứng", dấu phẩy và gõ không dấu
        addBoundaryPattern(phrases, "(?:tụt|tut|mất|mat|hết|het)\\s+(?:hứng|hung)[,\\s]+(?:thôi|thooi|tghooi|thoi)\\s+(?:dẹp|rẹp|dep|rep)", "tụt hứng thôi dẹp");
        addBoundaryPattern(phrases, "(?:chán|chan)\\s+(?:quá|qua)[,\\s]+(?:thôi|thoi)\\s+(?:nghỉ|nghi|dẹp|dep)", "chán quá thôi nghỉ");
        addBoundaryPattern(phrases, "đau\\s+lưng\\s+chứ\\s+(?:có\\s+phải\\s+)?đau\\s+lòng\\s+đâu", "đau lưng chứ đau lòng đâu");
        addBoundaryPattern(phrases, "(?:thôi|thooi|tghooi|thoi)\\s+(?:dẹp|rẹp|dep|rep)", "thôi dẹp");
        addBoundaryPattern(phrases, "tụt\\s+hứng", "tụt hứng");
        addBoundaryPattern(phrases, "tut\\s+hung", "tụt hứng");
        addBoundaryPattern(phrases, "thoi\\s+luong", "thời lượng");
        addBoundaryPattern(phrases, "quyen\\s+loi", "quyền lợi");
        addBoundaryPattern(phrases, "(\\d{2,3})\\s*p\\b", "$1 phút");
        addBoundaryPattern(phrases, "trai\\s+nghiem", "trải nghiệm");
        addBoundaryPattern(phrases, "nhu\\s+the\\s+nao", "như thế nào");
        addBoundaryPattern(phrases, "bao\\s+lau", "bao lâu");
        addBoundaryPattern(phrases, "bao\\s+nhieu\\s+tien", "bao nhiêu tiền");
        addBoundaryPattern(phrases, "sdt\\s*:", "số điện thoại:");
        addBoundaryPattern(phrases, "thử\\s+trc\\s+(\\d+)\\s*b", "thử trước $1 buổi");
        addBoundaryPattern(phrases, "thử\\s+trước\\s+(\\d+)\\s*b", "thử trước $1 buổi");
        addBoundaryPattern(phrases, "thử\\s+(\\d+)\\s*b", "thử $1 buổi");
        addBoundaryPattern(phrases, "trải\\s+nghiệm\\s+(\\d+)\\s*b", "trải nghiệm $1 buổi");
        addBoundaryPattern(phrases, "lẻ\\s+(\\d+)\\s*b", "lẻ $1 buổi");
        addBoundaryPattern(phrases, "đưng\\s+lưng", "đau lưng");
        addBoundaryPattern(phrases, "dưng\\s+lưng", "đau lưng");
        addBoundaryPattern(phrases, "đưng\\s+gối", "đau gối");
        addBoundaryPattern(phrases, "đưng\\s+vai", "đau vai");
        addBoundaryPattern(phrases, "đưng\\s+cổ", "đau cổ");
        addBoundaryPattern(phrases, "(mỏi|đau|nhức)\\s+goi", "$1 gối");
        addBoundaryPattern(phrases, "te\\s+tay", "tê tay");
        addBoundaryPattern(phrases, "thuc\\s+duong", "thực dưỡng");
        addBoundaryPattern(phrases, "an\\s+nhe", "ăn nhẹ");
        addBoundaryPattern(phrases, "diem\\s+tam", "điểm tâm");
        addBoundaryPattern(phrases, "nuoc\\s+ep", "nước ép");
        addBoundaryPattern(phrases, "no\\s+la\\s+(?:gì|gi|j)", "nó là gì");
        addBoundaryPattern(phrases, "nó\\s+la\\s+(?:gì|gi|j)", "nó là gì");
        addBoundaryPattern(phrases, "la\\s+(?:gì|gi|j)", "là gì");
        addBoundaryPattern(phrases, "co\\s+mon\\s+(?:gì|gi|j)", "có món gì");
        addBoundaryPattern(phrases, "có\\s+mon\\s+(?:gì|gi|j)", "có món gì");
        addBoundaryPattern(phrases, "gom\\s+nhung\\s+(?:gì|gi|j)", "gồm những gì");
        addBoundaryPattern(phrases, "cha\\s+hieu", "chả hiểu");
        addBoundaryPattern(phrases, "chả\\s+hieu", "chả hiểu");
        addBoundaryPattern(phrases, "(?:khong|ko|k)\\s+hieu", "không hiểu");
        addBoundaryPattern(phrases, "khong\\s+can\\s+nua", "không cần nữa");
        addBoundaryPattern(phrases, "toi\\s+thay", "tôi thấy");
        addBoundaryPattern(phrases, "vớ\\s+vânr", "vớ vẩn");
        addBoundaryPattern(phrases, "vớ\\s+vanr", "vớ vẩn");
        addBoundaryPattern(phrases, "vo\\s+vanr", "vớ vẩn");
        addBoundaryPattern(phrases, "vơ\\s+vẩn", "vớ vẩn");
        addBoundaryPattern(phrases, "linh\\s+ta\\s+linh\\s+tỉnh", "linh ta linh tinh");
        addBoundaryPattern(phrases, "linh\\s+ta\\s+linh\\s+tinh", "linh ta linh tinh");
        addBoundaryPattern(phrases, "linh\\s+tỉnh", "linh tinh");
        // Contextual "ns" -> "nói" ONLY before speech/complaint words (preserves standalone "NS" and package codes "NS01".."NS07")
        addBoundaryPattern(
                phrases,
                "ns(?=\\s+(?:linh\\s+ta\\s+linh\\s+tinh|linh\\s+ta\\s+linh\\s+tỉnh|linh\\s+tinh|linh\\s+tỉnh|vớ\\s+vẩn|vớ\\s+vânr|vo\\s+vanr|gì|gi|j|sao|vậy|vay|thế|the|chuyện|chuyen|xàm|xam|nhảm|nham|luyên\\s+thuyên|lung\\s+tung|bậy|điêu|dối)\\b)",
                "nói"
        );
        PHRASE_REPLACEMENTS = Collections.unmodifiableMap(phrases);

        Map<Pattern, String> words = new LinkedHashMap<>();

        // 1. Messenger slang & abbreviations
        addWord(words, "trc", "trước");
        addWord(words, "km", "khuyến mãi");
        addWord(words, "kmai", "khuyến mãi");
        addWord(words, "j", "gì");
        addWord(words, "ko", "không");
        addWord(words, "k", "không");
        addWord(words, "khg", "không");
        addWord(words, "kh", "không");
        addWord(words, "khong", "không");
        addWord(words, "hổng", "không");
        addWord(words, "hong", "không");

        addWord(words, "bn", "bao nhiêu");
        addWord(words, "bnhieu", "bao nhiêu");
        addWord(words, "bnh", "bao nhiêu");

        addWord(words, "sdt", "số điện thoại");
        addWord(words, "sđt", "số điện thoại");

        addWord(words, "dc", "được");
        addWord(words, "đc", "được");
        addWord(words, "duoc", "được");

        addWord(words, "mik", "mình");
        addWord(words, "mk", "mình");

        addWord(words, "tgian", "thời gian");
        addWord(words, "tg", "thời gian");

        addBoundaryPattern(words, "(?<!mã\\s|gói\\s)sp(?!\\s*[-#]?\\d)", "sản phẩm");
        addWord(words, "dv", "dịch vụ");
        addWord(words, "lt", "liệu trình");
        addWord(words, "cs", "cơ sở");
        addWord(words, "cn", "chi nhánh");
        addWord(words, "ib", "inbox");
        addWord(words, "inb", "inbox");
        addWord(words, "ntn", "như thế nào");
        addWord(words, "nv", "nhân viên");
        addWord(words, "ktv", "kỹ thuật viên");
        addWord(words, "ad", "tư vấn viên");
        addWord(words, "mn", "mọi người");
        addWord(words, "mí", "mấy");

        // 2. Telex typing errors
        addWord(words, "muosn", "muốn");
        addWord(words, "muoón", "muốn");
        addWord(words, "muons", "muốn");

        addWord(words, "quẩ", "quả");
        addWord(words, "quar", "quả");

        addWord(words, "hieue", "hiệu");
        addWord(words, "hieeuj", "hiệu");
        addWord(words, "hieuj", "hiệu");
        addWord(words, "hieeur", "hiểu");
        addWord(words, "hieur", "hiểu");

        addWord(words, "trogn", "trong");
        addWord(words, "bieets", "biết");
        addWord(words, "bietes", "biết");

        addWord(words, "ddaauf", "đầu");
        addWord(words, "ddau", "đầu");
        addWord(words, "daauf", "đầu");

        addWord(words, "ddwowjc", "được");
        addWord(words, "dduoc", "được");
        addWord(words, "dduwowjc", "được");

        addWord(words, "ddax", "đã");
        addWord(words, "dax", "đã");
        addWord(words, "ddang", "đang");
        addWord(words, "dduwng", "đừng");
        addWord(words, "dungf", "đừng");

        addWord(words, "khongg", "không");
        addWord(words, "khoong", "không");
        addWord(words, "giar", "giá");
        addWord(words, "gias", "giá");

        addWord(words, "tuw", "từ");
        addWord(words, "tuwf", "từ");
        addWord(words, "vaof", "vào");
        addWord(words, "vafo", "vào");

        addWord(words, "phair", "phải");
        addWord(words, "phari", "phải");
        addWord(words, "trwowsc", "trước");
        addWord(words, "truowcs", "trước");
        addWord(words, "trair", "trải");
        addWord(words, "traie", "trải");

        addWord(words, "nghieejm", "nghiệm");
        addWord(words, "nghiemj", "nghiệm");

        addWord(words, "lafm", "làm");
        addWord(words, "lamf", "làm");
        addWord(words, "chuwr", "chứ");
        addWord(words, "chuws", "chứ");
        addWord(words, "thoif", "thời");
        addWord(words, "thowfi", "thời");
        addWord(words, "giann", "gian");
        addWord(words, "chox", "chỗ");
        addWord(words, "choox", "chỗ");
        addWord(words, "gois", "gói");
        addWord(words, "lieeuj", "liệu");
        addWord(words, "lieuj", "liệu");
        addWord(words, "trinhf", "trình");
        addWord(words, "duowngx", "dưỡng");
        addWord(words, "duongx", "dưỡng");

        addWord(words, "massag", "massage");
        addWord(words, "masage", "massage");
        addWord(words, "matsa", "massage");
        addWord(words, "matxa", "massage");
        addWord(words, "thỉang", "thoảng");
        addWord(words, "thiang", "thoảng");
        addWord(words, "thoangr", "thoảng");
        addWord(words, "tghooi", "thôi");
        addWord(words, "thooi", "thôi");
        addWord(words, "thoii", "thôi");
        addWord(words, "rẹp", "dẹp");
        addWord(words, "tuongr", "tưởng");
        addWord(words, "tưởn", "tưởng");

        WORD_REPLACEMENTS = Collections.unmodifiableMap(words);
    }

    private static void addWord(Map<Pattern, String> map, String word, String replacement) {
        String regex = "(?iU)(?<![\\p{L}\\d_])" + Pattern.quote(word) + "(?![\\p{L}\\d_])";
        map.put(Pattern.compile(regex), replacement);
    }

    private static void addBoundaryPattern(Map<Pattern, String> map, String patternRegex, String replacement) {
        String regex = "(?iU)(?<![\\p{L}\\d_])" + patternRegex + "(?![\\p{L}\\d_])";
        map.put(Pattern.compile(regex), replacement);
    }

    private static String sanitizeUnicodeSpaces(String text) {
        if (text == null) {
            return "";
        }
        // Replace non-breaking, special, and zero-width spaces with standard space
        return text.replace('\u00A0', ' ')
                   .replace('\u2007', ' ')
                   .replace('\u202F', ' ')
                   .replace('\u3000', ' ')
                   .replace('\u200B', ' ')
                   .replace('\u200C', ' ')
                   .replace('\u200D', ' ')
                   .replace('\uFEFF', ' ')
                   .replace('\u2060', ' ')
                   .replace('\u00AD', ' ');
    }

    private static String trimTrailingPunctuation(String url) {
        while (!url.isEmpty()) {
            char last = url.charAt(url.length() - 1);
            if (last == '.' || last == ',' || last == '!' || last == '?' ||
                last == ';' || last == ':' || last == ']' || last == '>' ||
                last == '\'' || last == '"') {
                url = url.substring(0, url.length() - 1);
            } else if (last == ')') {
                int openCount = 0;
                int closeCount = 0;
                for (int i = 0; i < url.length(); i++) {
                    if (url.charAt(i) == '(') openCount++;
                    else if (url.charAt(i) == ')') closeCount++;
                }
                if (closeCount > openCount) {
                    url = url.substring(0, url.length() - 1);
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return url;
    }

    private static final class MaskResult {
        final String maskedText;
        final List<String> placeholders;
        final List<String> originalTokens;

        MaskResult(String maskedText, List<String> placeholders, List<String> originalTokens) {
            this.maskedText = maskedText;
            this.placeholders = placeholders;
            this.originalTokens = originalTokens;
        }

        String restore(String text) {
            if (placeholders.isEmpty()) {
                return text;
            }
            String result = text;
            for (int i = 0; i < placeholders.size(); i++) {
                result = result.replace(placeholders.get(i), originalTokens.get(i));
            }
            return result;
        }
    }

    private static MaskResult maskTokens(String text) {
        Matcher urlMatcher = URL_PATTERN.matcher(text);
        Matcher emailMatcher = EMAIL_PATTERN.matcher(text);

        if (!urlMatcher.find() && !emailMatcher.find()) {
            return new MaskResult(text, Collections.emptyList(), Collections.emptyList());
        }

        urlMatcher.reset();
        List<String> placeholders = new ArrayList<>();
        List<String> originalTokens = new ArrayList<>();
        String prefix = "___PROTECTED_TOKEN_" + UUID.randomUUID().toString().replace("-", "") + "_";
        int counter = 0;

        // 1. Mask URLs
        StringBuffer sb = new StringBuffer();
        while (urlMatcher.find()) {
            String rawMatch = urlMatcher.group();
            String cleanUrl = trimTrailingPunctuation(rawMatch);
            String trailing = rawMatch.substring(cleanUrl.length());
            String placeholder = prefix + (counter++) + "___";
            placeholders.add(placeholder);
            originalTokens.add(cleanUrl);
            urlMatcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder + trailing));
        }
        urlMatcher.appendTail(sb);
        String intermediate = sb.toString();

        // 2. Mask Emails
        Matcher emailMatch = EMAIL_PATTERN.matcher(intermediate);
        sb = new StringBuffer();
        while (emailMatch.find()) {
            String email = emailMatch.group();
            String placeholder = prefix + (counter++) + "___";
            placeholders.add(placeholder);
            originalTokens.add(email);
            emailMatch.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        emailMatch.appendTail(sb);

        return new MaskResult(sb.toString(), placeholders, originalTokens);
    }

    /**
     * Làm sạch nhẹ tin nhắn gốc (chỉ chuẩn hóa khoảng trắng Unicode đặc biệt và cắt chiều dài an toàn),
     * giữ nguyên 100% từ ngữ, mã dịch vụ (NS, NS01, SP-01...) và cách viết gốc của khách hàng.
     */
    public static String sanitizeRawMessage(String text) {
        if (text == null) {
            return "";
        }
        String clipped = text.length() > MAX_INPUT_LENGTH ? text.substring(0, MAX_INPUT_LENGTH) : text;
        String sanitized = sanitizeUnicodeSpaces(clipped);
        if (sanitized.isBlank()) {
            return "";
        }
        return sanitized.replaceAll("\\s+", " ").strip();
    }

    /**
     * Chuẩn hóa toàn diện chuỗi câu hỏi / tin nhắn khách hàng.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            text = text.substring(0, MAX_INPUT_LENGTH);
        }

        // Stage 1: Tiền xử lý Unicode non-breaking & zero-width spaces và kiểm tra rỗng
        String sanitized = sanitizeUnicodeSpaces(text);
        if (sanitized.isBlank()) {
            return "";
        }

        // Stage 2: Bảo vệ (mask) URL và email hợp lệ
        MaskResult maskResult = maskTokens(sanitized);
        String result = maskResult.maskedText;

        // Stage 3: Chuẩn hóa ngôn ngữ và tiền tệ
        // 3a. Chuẩn hóa giá tiền dạng '670k', '129k', '1990k' -> '670.000 VNĐ'
        result = normalizePriceK(result);

        // 3b. Chuẩn hóa giá tiền dạng '1.5tr', '2 triệu' -> '1.500.000 VNĐ'
        result = normalizePriceTrieu(result);

        // 3c. Chuẩn hóa thời lượng dạng '60p', '90ph' -> '60 phút'
        result = normalizeDuration(result);

        // 3d. Chuẩn hóa cụm từ
        for (Map.Entry<Pattern, String> entry : PHRASE_REPLACEMENTS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }

        // 3e. Chuẩn hóa từ đơn (slang & telex)
        for (Map.Entry<Pattern, String> entry : WORD_REPLACEMENTS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }

        // Stage 4: Dọn dẹp khoảng trắng dư thừa
        result = result.replaceAll("\\s+", " ").strip();

        // Stage 5: Khôi phục nguyên vẹn các URL và email đã được bảo vệ
        result = maskResult.restore(result);

        // Stage 6: Trim cuối cùng
        return result.strip();
    }

    public static String normalizeForIntentMatching(String text) {
        String normalized = normalize(text);
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : "";
    }

    public static String normalizeForClassification(String text) {
        return normalizeForIntentMatching(text);
    }

    public static String normalizeForSearch(String text) {
        String normalized = normalize(text);
        return normalized != null ? normalized : "";
    }

    private static String normalizePriceK(String text) {
        Matcher m = PRICE_K_PATTERN.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer();
        do {
            long val = Long.parseLong(m.group(1)) * 1000L;
            m.appendReplacement(sb, Matcher.quoteReplacement(formatVnd(val)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalizePriceTrieu(String text) {
        Matcher m = PRICE_TRIEU_PATTERN.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer();
        do {
            String rawNum = m.group(1).replace(',', '.');
            double val = Double.parseDouble(rawNum) * 1_000_000.0;
            m.appendReplacement(sb, Matcher.quoteReplacement(formatVnd((long) val)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalizeDuration(String text) {
        return DURATION_PATTERN.matcher(text).replaceAll("$1 phút");
    }

    public static String formatVnd(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.GERMANY);
        symbols.setGroupingSeparator('.');
        DecimalFormat df = new DecimalFormat("#,###", symbols);
        return df.format(amount) + " VNĐ";
    }
}
