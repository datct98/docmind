package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-Loop Deterministic Guardrail Hook: Zero Syntax Leaks & Syntax Sanitization (Feature F9).
 * <p>
 * Ngân sách thực thi nghiêm ngặt: {@code < 0.2ms} (nằm trong trần 5ms của GuardrailPipeline).
 * Khử triệt để mọi cú pháp kỹ thuật, định dạng Markdown và thẻ nội bộ trước khi gửi khách hàng:
 * <ol>
 *   <li>Obsidian Wikilinks: {@code [[Target|Alias]]} -> {@code Alias}, {@code [[Target]]} -> {@code Target},
 *       {@code [[HANDOFF:...]]} -> xóa bỏ hoàn toàn.</li>
 *   <li>Ký hiệu trích dẫn & mã nội bộ: {@code [1]}, {@code [1, 2]}, {@code [SPA-01]}, {@code (SPA-01)},
 *       {@code [Mục: ...]}, {@code RULE_...}, {@code INV_...}, {@code DOM_...}.</li>
 *   <li>Tiêu đề Markdown: {@code ^#{1,6}\s+} -> loại bỏ dấu thăng, chuẩn hóa thành câu tự nhiên.</li>
 *   <li>Định dạng đậm/nghiêng: {@code **text**} -> {@code text}, {@code ***text***} -> {@code text}.</li>
 *   <li>Bảng biểu Markdown: Chuyển đổi toàn bộ cấu trúc bảng (dấu {@code |}, dòng phân cách {@code |---|})
 *       thành danh sách gạch đầu dòng tự nhiên ({@code • Tên gói (Thời gian): Giá}), triệt tiêu 100% ký tự pipe {@code |}.</li>
 *   <li>Thẻ Prompt & Khối suy nghĩ rò rỉ: {@code <think>...</think>}, {@code <DOCUMENT_CONTEXT>...</DOCUMENT_CONTEXT>},
 *       {@code <CONVERSATION_HISTORY>...}, {@code === MAP OF CONTENT ===}, mở bọc {@code <output>...</output>}.</li>
 * </ol>
 */
public class SyntaxSanitizationHook implements GuardrailHook {

    public static final String HOOK_CODE = "HOOK_SYNTAX_SANITIZATION";
    public static final String CODE = HOOK_CODE;

    // --- 1. PROMPT TAG BLOCKS & REASONING BLOCKS (DOTALL) ---
    private static final Pattern THINK_TAG_BLOCK_PATTERN = Pattern.compile(
            "(?is)<think(?:\\s+[^>]*)?>.*?</think\\s*>"
    );
    private static final Pattern SYSTEM_TAG_BLOCK_PATTERN = Pattern.compile(
            "(?is)<(DOCUMENT_CONTEXT|CONVERSATION_HISTORY|USER_QUESTION|CUSTOMER_MESSAGE|"
            + "LEAD_CONTEXT|CUSTOMER_INSIGHT|EXPERIENCE_LIBRARY|SYSTEM_PROMPT|INSTRUCTION|"
            + "experience|cue_signal|rule_text|contrastive_evidence|context)(?:\\s+[^>]*)?>.*?"
            + "</\\1\\s*>"
    );
    private static final Pattern MOC_BLOCK_PATTERN = Pattern.compile(
            "(?is)===\\s*MAP\\s+OF\\s+CONTENT(?:\\s*\\(MOC\\))?\\s*===.*?={4,}"
    );
    private static final Pattern OUTPUT_TAG_WRAPPER_PATTERN = Pattern.compile(
            "(?is)</?(?:output|response)(?:\\s+[^>]*)?>"
    );
    private static final Pattern STRAY_PROMPT_TAGS_PATTERN = Pattern.compile(
            "(?i)</?(?:DOCUMENT_CONTEXT|CONVERSATION_HISTORY|USER_QUESTION|CUSTOMER_MESSAGE|"
            + "LEAD_CONTEXT|CUSTOMER_INSIGHT|EXPERIENCE_LIBRARY|SYSTEM_PROMPT|INSTRUCTION|"
            + "experience|cue_signal|rule_text|contrastive_evidence|context|think|output|response)(?:\\s+[^>]*)?>"
    );
    private static final Pattern STRAY_DIVIDER_LINES_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:={3,}|-{3,})\\s*$"
    );

    // --- 2. WIKILINKS ---
    private static final Pattern INNER_WIKILINK_PATTERN = Pattern.compile(
            "\\[\\[([^\\[\\]]+)\\]\\]"
    );
    private static final Pattern STRAY_WIKILINK_BRACKET_PATTERN = Pattern.compile(
            "\\[\\[|\\]\\]"
    );

    // --- 3. CITATIONS, FOOTNOTES & INTERNAL RULE CODES ---
    private static final Pattern NUMERIC_CITATION_PATTERN = Pattern.compile(
            "\\s*\\[\\s*\\d{1,5}(?:\\s*[,;-]\\s*\\d{1,5})*\\s*\\]"
    );
    private static final Pattern FOOTNOTE_PATTERN = Pattern.compile(
            "\\s*\\[\\^[^\\]]+\\]"
    );
    private static final Pattern BRACKETED_RULE_CODE_PATTERN = Pattern.compile(
            "\\s*\\[\\s*(?:RULE|INV|DOM)_[A-Z0-9_]+\\s*\\]"
    );
    private static final Pattern PAREN_RULE_CODE_PATTERN = Pattern.compile(
            "\\s*\\(\\s*(?:RULE|INV|DOM)_[A-Z0-9_]+\\s*\\)"
    );
    private static final Pattern TECHNICAL_CITATION_PATTERN = Pattern.compile(
            "(?i)\\s*\\[\\s*(?:SPA|SP|DV)-\\d{1,5}\\s*\\]"
    );
    private static final Pattern PAREN_TECHNICAL_CITATION_PATTERN = Pattern.compile(
            "(?i)\\s*\\(\\s*(?:SPA|SP|DV)-\\d{1,5}\\s*\\)"
    );
    private static final Pattern BARE_TECHNICAL_CODE_PATTERN = Pattern.compile(
            "(?i)\\s+\\b(?:SPA|SP|DV)-\\d{1,5}\\b"
    );
    private static final Pattern SECTION_CITATION_PATTERN = Pattern.compile(
            "(?i)\\s*\\[\\s*mục:[^\\]]*\\]"
    );
    private static final Pattern SOURCE_QUOTE_CITATION_PATTERN = Pattern.compile(
            "(?i)\\s*\\[\\s*trích\\s*dẫn[^\\]]*\\]"
    );
    private static final Pattern PLACEHOLDER_NAME_PATTERN = Pattern.compile(
            "(?i)\\[\\s*(?:Tên\\s+Khách\\s+Hàng|Họ\\s+và\\s+Tên|Tên\\s+Chị|Tên\\s+Anh)\\s*\\]"
    );
    private static final Pattern INTERNAL_RULE_CODE_PATTERN = Pattern.compile(
            "\\b(?:RULE|INV|DOM)_[A-Z0-9_]+\\b"
    );
    private static final Pattern ORPHAN_EMPTY_BRACKETS_PATTERN = Pattern.compile(
            "\\s*\\[\\s*\\]|\\s*\\(\\s*\\)"
    );

    // --- 4. MARKDOWN HEADINGS ---
    private static final Pattern MARKDOWN_HEADING_START_PATTERN = Pattern.compile(
            "(?m)^\\s*#{1,6}\\s+"
    );
    private static final Pattern MARKDOWN_HEADING_END_PATTERN = Pattern.compile(
            "(?m)\\s+#{1,6}\\s*$"
    );

    // --- 5. MARKDOWN BOLD / ITALICS ---
    private static final Pattern BOLD_ITALIC_PATTERN = Pattern.compile(
            "\\*\\*\\*(.*?)\\*\\*\\*"
    );
    private static final Pattern BOLD_PATTERN = Pattern.compile(
            "\\*\\*(.*?)\\*\\*"
    );
    private static final Pattern UNDERSCORE_BOLD_PATTERN = Pattern.compile(
            "__(.*?)__"
    );

    // --- 6. MARKDOWN TABLES & PIPES ---
    private static final Pattern TABLE_DIVIDER_LINE_PATTERN = Pattern.compile(
            "^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*$"
    );
    private static final Pattern STRAY_PIPE_PATTERN = Pattern.compile("\\s*\\|\\s*");
    private static final Pattern TRAILING_NEWLINES_PATTERN = Pattern.compile("\\n+$");

    // --- 7. NORMALIZATION PATTERNS ---
    private static final Pattern SPACE_BEFORE_PUNCTUATION_PATTERN = Pattern.compile(" +([.,;!?])");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");

    @Override
    public String getHookCode() {
        return HOOK_CODE;
    }

    @Override
    public boolean isApplicable(GuardrailContext context, GuardrailDraft draft) {
        if (draft == null) {
            return false;
        }
        String text = draft.getText();
        List<ReplyBubble> bubbles = draft.getBubbles();
        return (text != null && !text.isBlank()) || (bubbles != null && !bubbles.isEmpty());
    }

    @Override
    public boolean apply(GuardrailContext context, GuardrailDraft draft) {
        String originalText = draft.getText();
        if (originalText == null && (draft.getBubbles() == null || draft.getBubbles().isEmpty())) {
            return false;
        }

        String honorific = context != null ? context.getCustomerHonorific() : "Chị";
        String updatedText = sanitize(originalText, honorific);
        boolean changed = originalText != null && !updatedText.equals(originalText);

        // Đồng bộ toàn bộ bong bóng chat nếu có
        List<ReplyBubble> originalBubbles = draft.getBubbles();
        if (originalBubbles != null && !originalBubbles.isEmpty()) {
            List<ReplyBubble> updatedBubbles = new ArrayList<>(originalBubbles.size());
            for (ReplyBubble b : originalBubbles) {
                if (b == null) continue;
                String fixedBubble = sanitize(b.text(), honorific);
                if (b.text() != null && !fixedBubble.equals(b.text())) {
                    changed = true;
                }
                updatedBubbles.add(new ReplyBubble(fixedBubble, b.typingDelayMs()));
            }
            draft.setBubbles(updatedBubbles);
        }

        if (changed) {
            draft.setText(updatedText);
            draft.addTriggeredHook(HOOK_CODE);
            return true;
        }

        return false;
    }

    /**
     * Vệ sinh cú pháp kỹ thuật dùng chung cho FanpageBotService và các component khác.
     */
    public static String sanitize(String text) {
        return sanitize(text, "Chị");
    }

    /**
     * Vệ sinh cú pháp kỹ thuật với danh xưng tương ứng.
     */
    public static String sanitize(String text, String honorific) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }

        // Fast-path: Nếu văn bản hoàn toàn không chứa các ký tự đặc biệt cú pháp thì bỏ qua trong O(1)
        if (!hasSyntaxTriggers(text)) {
            return text;
        }

        String working = text;

        // 1-5. Khử khối suy nghĩ, prompt tags và context blocks rò rỉ (không phân biệt hoa thường)
        if (working.indexOf('<') >= 0 && working.indexOf('>') >= 0) {
            // 1. Khử khối suy nghĩ <think>...</think> của các reasoning models
            working = THINK_TAG_BLOCK_PATTERN.matcher(working).replaceAll("");
            // 2. Mở bọc thẻ <output>...</output> hoặc <response>...</response> nếu LLM bọc câu trả lời
            working = OUTPUT_TAG_WRAPPER_PATTERN.matcher(working).replaceAll("");
            // 3. Khử khối dữ liệu ngữ cảnh hệ thống (<DOCUMENT_CONTEXT>, <SYSTEM_PROMPT>, <INSTRUCTION>, v.v.)
            working = SYSTEM_TAG_BLOCK_PATTERN.matcher(working).replaceAll("");
            // 5. Xóa các thẻ prompt đơn lẻ/chưa đóng
            working = STRAY_PROMPT_TAGS_PATTERN.matcher(working).replaceAll("");
        }

        // 4. Khử khối tóm tắt Map of Content (=== MAP OF CONTENT ... ===)
        if (working.contains("MAP OF CONTENT") || working.contains("map of content")) {
            working = MOC_BLOCK_PATTERN.matcher(working).replaceAll("");
        }

        if (working.contains("===") || working.contains("---")) {
            working = STRAY_DIVIDER_LINES_PATTERN.matcher(working).replaceAll("");
        }

        // 6. Khử Obsidian Wikilinks lồng nhau và rò rỉ cú pháp TRƯỚC khi xử lý bảng
        if (working.contains("[[") || working.contains("]]")) {
            working = stripWikilinksFast(working);
        }

        // 7. Chuyển đổi bảng biểu Markdown (|---| và pipes) thành danh sách gạch đầu dòng tự nhiên
        if (working.contains("|")) {
            working = convertMarkdownTablesToBullets(working);
        }

        // 8. Khử ký hiệu trích dẫn nguồn, footnotes, mã quy tắc đóng ngoặc vuông và placeholder
        if (working.indexOf('[') >= 0) {
            // 8a. Khử mã quy tắc đóng ngoặc vuông [RULE_...], [INV_...], [DOM_...] triệt tiêu ngoặc rỗng []
            if (working.contains("RULE_") || working.contains("INV_") || working.contains("DOM_")) {
                working = BRACKETED_RULE_CODE_PATTERN.matcher(working).replaceAll("");
            }
            // 8b. Khử Markdown Footnotes [^1], [^note]
            if (working.contains("[^")) {
                working = FOOTNOTE_PATTERN.matcher(working).replaceAll("");
            }
            // 8c. Khử trích dẫn số [1], [1, 2], [1001]
            if (hasAnyDigit(working)) {
                working = NUMERIC_CITATION_PATTERN.matcher(working).replaceAll("");
            }
            if (working.contains("mục:") || working.contains("Mục:")) {
                working = SECTION_CITATION_PATTERN.matcher(working).replaceAll("");
            }
            if (working.contains("trích dẫn") || working.contains("Trích dẫn")) {
                working = SOURCE_QUOTE_CITATION_PATTERN.matcher(working).replaceAll("");
            }
            if (working.contains("Tên") || working.contains("tên") || working.contains("Họ") || working.contains("họ")) {
                working = PLACEHOLDER_NAME_PATTERN.matcher(working).replaceAll("");
            }
        }

        if (containsTechPrefix(working)) {
            if (working.indexOf('[') >= 0) {
                working = TECHNICAL_CITATION_PATTERN.matcher(working).replaceAll("");
            }
            if (working.indexOf('(') >= 0 && containsTechPrefix(working)) {
                working = PAREN_TECHNICAL_CITATION_PATTERN.matcher(working).replaceAll("");
            }
            if (containsTechPrefix(working)) {
                working = BARE_TECHNICAL_CODE_PATTERN.matcher(working).replaceAll("");
            }
        }

        // 9. Khử mã quy tắc nội bộ còn lại (dạng đóng ngoặc tròn hoặc bare text)
        if (working.contains("RULE_") || working.contains("INV_") || working.contains("DOM_")) {
            if (working.indexOf('(') >= 0) {
                working = PAREN_RULE_CODE_PATTERN.matcher(working).replaceAll("");
            }
            working = INTERNAL_RULE_CODE_PATTERN.matcher(working).replaceAll("");
        }

        // 9b. Dọn dẹp dấu ngoặc rỗng mồ côi [] hoặc ()
        if (working.contains("[]") || working.contains("[ ]") || working.contains("()") || working.contains("( )")) {
            working = ORPHAN_EMPTY_BRACKETS_PATTERN.matcher(working).replaceAll("");
        }

        // 10. Khử tiêu đề Markdown (#, ##, ###)
        if (working.contains("#")) {
            working = MARKDOWN_HEADING_START_PATTERN.matcher(working).replaceAll("");
            working = MARKDOWN_HEADING_END_PATTERN.matcher(working).replaceAll("");
        }

        // 11. Khử định dạng đậm/nghiêng Markdown (***, **, __)
        if (working.contains("***")) {
            working = working.replace("***", "");
        }
        if (working.contains("**")) {
            working = working.replace("**", "");
        }
        if (working.contains("__")) {
            working = working.replace("__", "");
        }

        // 12. Chuẩn hóa khoảng trắng và dấu câu (với fast containment guards để tránh regex full-scan)
        if (working.indexOf(" .") >= 0 || working.indexOf(" ,") >= 0 || working.indexOf(" ;") >= 0
                || working.indexOf(" !") >= 0 || working.indexOf(" ?") >= 0) {
            working = SPACE_BEFORE_PUNCTUATION_PATTERN.matcher(working).replaceAll("$1");
        }
        if (working.contains("  ") || working.contains("\t")) {
            working = MULTI_SPACE_PATTERN.matcher(working).replaceAll(" ");
        }
        if (working.contains("\n\n\n")) {
            working = MULTI_NEWLINE_PATTERN.matcher(working).replaceAll("\n\n");
        }
        working = working.strip();

        // 13. Fallback an toàn nếu sau khi lọc sạch cú pháp nội bộ văn bản bị rỗng
        if (working.isBlank()) {
            String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
            return "Dạ em xin phép kết nối ngay với Chuyên viên tư vấn của Page để hỗ trợ chi tiết cho " + h + " nhé ạ!";
        }

        return working;
    }

    private static boolean hasSyntaxTriggers(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == ']' || c == '(' || c == ')' || c == '|' || c == '#'
                    || c == '*' || c == '<' || c == '>' || c == '=' || c == '_' || c == '-') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTechPrefix(String s) {
        return s.contains("SPA-") || s.contains("spa-") || s.contains("SP-") || s.contains("DV-");
    }

    private static boolean hasAnyDigit(String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    private static String stripWikilinksFast(String text) {
        if (text == null || (!text.contains("[[") && !text.contains("]]"))) {
            return text;
        }
        if (!text.contains("[[")) return text.replace("]]", "");
        if (!text.contains("]]")) return text.replace("[[", "");

        String current = text;
        int depth = 0;
        while (current.contains("[[") && current.contains("]]") && depth < 5) {
            int openIdx = current.indexOf("[[");
            if (openIdx < 0) break;
            StringBuilder sb = new StringBuilder(current.length());
            int cursor = 0;
            boolean anyReplaced = false;

            while (openIdx >= 0) {
                int closeIdx = current.indexOf("]]", openIdx + 2);
                if (closeIdx < 0) break;

                // Bóc tách ngoặc lồng nhau bên trong: chọn openIdx gần closeIdx nhất
                int nextOpen = current.indexOf("[[", openIdx + 2);
                while (nextOpen >= 0 && nextOpen < closeIdx) {
                    openIdx = nextOpen;
                    nextOpen = current.indexOf("[[", openIdx + 2);
                }

                sb.append(current, cursor, openIdx);
                String raw = current.substring(openIdx + 2, closeIdx);
                String replacement = cleanWikilinkContent(raw);
                sb.append(replacement);
                cursor = closeIdx + 2;
                anyReplaced = true;
                openIdx = current.indexOf("[[", cursor);
            }

            if (!anyReplaced) break;
            sb.append(current, cursor, current.length());
            current = sb.toString();
            depth++;
        }
        if (current.contains("[[") || current.contains("]]")) {
            current = current.replace("[[", "").replace("]]", "");
        }
        return current;
    }

    private static String cleanWikilinkContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String content = raw.trim();
        // Cờ handoff kỹ thuật rò rỉ: xóa trắng hoàn toàn
        if (content.regionMatches(true, 0, "HANDOFF:", 0, 8)) {
            return "";
        }
        // [[Target|Alias]] -> Alias
        if (content.contains("|")) {
            return content.substring(content.lastIndexOf('|') + 1).trim();
        }
        // [[Target#Heading]] -> Target
        if (content.contains("#")) {
            return content.substring(0, content.indexOf('#')).trim();
        }
        return content;
    }

    public static String convertMarkdownTablesToBullets(String text) {
        if (text == null || !text.contains("|")) {
            return text;
        }

        // Fast-path: Nếu văn bản không có chỉ dấu bảng Markdown thì xử lý pipe mồ côi trong O(N)
        boolean couldHaveTable = text.contains("---") || text.startsWith("|") || text.contains("\n|") || text.contains("\r|");
        if (!couldHaveTable) {
            return replaceStrayPipesFast(text);
        }

        String[] lines = text.split("\r?\n");
        StringBuilder out = new StringBuilder(text.length());
        List<String> tableBlock = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isTableLine = (trimmed.startsWith("|") && trimmed.contains("|"))
                    || (trimmed.contains("---") && TABLE_DIVIDER_LINE_PATTERN.matcher(trimmed).matches());

            if (isTableLine) {
                tableBlock.add(trimmed);
            } else {
                if (!tableBlock.isEmpty()) {
                    out.append(formatTableBlock(tableBlock)).append("\n");
                    tableBlock.clear();
                }
                out.append(line).append("\n");
            }
        }
        if (!tableBlock.isEmpty()) {
            out.append(formatTableBlock(tableBlock)).append("\n");
            tableBlock.clear();
        }

        String result = TRAILING_NEWLINES_PATTERN.matcher(out.toString()).replaceAll("");
        // Thay thế triệt để các ký tự pipe '|' còn sót ngoài bảng (VD: "Cơ sở 1 | Cơ sở 2" -> "Cơ sở 1 - Cơ sở 2")
        if (result.indexOf('|') >= 0) {
            result = replaceStrayPipesFast(result);
        }
        return result;
    }

    private static String replaceStrayPipesFast(String text) {
        if (text == null || text.indexOf('|') < 0) return text;
        int len = text.length();
        StringBuilder sb = new StringBuilder(len + 32);
        int cursor = 0;
        int pipeIdx = text.indexOf('|', cursor);
        while (pipeIdx >= 0) {
            int start = pipeIdx;
            while (start > cursor && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) {
                start--;
            }
            sb.append(text, cursor, start);
            sb.append(" - ");
            int end = pipeIdx + 1;
            while (end < len && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
                end++;
            }
            cursor = end;
            pipeIdx = text.indexOf('|', cursor);
        }
        sb.append(text, cursor, len);
        return sb.toString();
    }

    private static String formatTableBlock(List<String> tableLines) {
        if (tableLines.isEmpty()) {
            return "";
        }

        List<List<String>> rows = new ArrayList<>();
        for (String l : tableLines) {
            if (TABLE_DIVIDER_LINE_PATTERN.matcher(l).matches()) {
                continue; // Bỏ qua dòng phân cách |---|---|
            }
            String[] split = l.split("\\|");
            List<String> cells = new ArrayList<>();
            for (String c : split) {
                String ct = c.trim();
                if (!ct.isEmpty()) {
                    cells.add(ct);
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }

        if (rows.isEmpty()) {
            return "";
        }

        // Xác định dòng 0 có phải là tiêu đề cột hay không
        List<String> firstRow = rows.get(0);
        boolean firstIsHeader = isHeaderRow(firstRow);
        int dataStartIndex = firstIsHeader ? 1 : 0;

        StringBuilder sb = new StringBuilder();
        for (int r = dataStartIndex; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String bullet = formatTableRow(row);
            if (!bullet.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(bullet);
            }
        }

        return sb.toString();
    }

    private static boolean isHeaderRow(List<String> cells) {
        for (String c : cells) {
            String lower = c.toLowerCase(Locale.ROOT);
            if (lower.contains("mã") || lower.contains("tên") || lower.contains("dịch vụ")
                    || lower.contains("gói") || lower.contains("giá") || lower.contains("thời gian")
                    || lower.contains("liệu trình") || lower.contains("chi phí") || lower.contains("stt")) {
                return true;
            }
        }
        return false;
    }

    private static String formatTableRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return "";
        }
        if (cells.size() == 1) {
            return "• " + cells.get(0);
        }
        if (cells.size() == 2) {
            return "• " + cells.get(0) + ": " + cells.get(1);
        }
        if (cells.size() == 3) {
            // [Tên gói, Thời gian, Giá] hoặc [Tên gói, Giá, Thời gian]
            String c0 = cells.get(0);
            String c1 = cells.get(1);
            String c2 = cells.get(2);
            if (isDurationToken(c1)) {
                return "• " + c0 + " (" + c1 + "): " + c2;
            } else if (isDurationToken(c2)) {
                return "• " + c0 + " (" + c2 + "): " + c1;
            } else {
                return "• " + c0 + " - " + c1 + ": " + c2;
            }
        }
        if (cells.size() >= 4) {
            // [Mã gói, Tên gói, Giá, Thời gian]
            String name = cells.get(1);
            String val2 = cells.get(2);
            String val3 = cells.get(3);
            String duration = isDurationToken(val3) ? val3 : (isDurationToken(val2) ? val2 : null);
            String price = duration != null ? (duration.equals(val3) ? val2 : val3) : val2 + " - " + val3;

            if (duration != null) {
                return "• " + name + " (" + duration + "): " + price;
            } else {
                return "• " + name + ": " + price;
            }
        }
        return "• " + String.join(" - ", cells);
    }

    private static boolean isDurationToken(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("phút") || l.contains("p") || l.contains("giờ") || l.contains("h");
    }
}
