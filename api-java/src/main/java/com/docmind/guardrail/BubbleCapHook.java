package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * In-Loop Deterministic Guardrail Hook: Bubble Cap.
 * <p>
 * Đảm bảo phản hồi luôn được chia tối đa 2 bong bóng tin nhắn (bubble count <= 2).
 * Nếu LLM sinh ra 3+ đoạn văn hoặc bong bóng rời rạc, tự động gộp các bong bóng dư thừa
 * một cách mượt mà và gán độ trễ gõ phím chuẩn người thật (~1000ms cho bubble 1, ~2600ms cho bubble 2).
 * Ở lượt đầu tiên (isFirstTurn), tự động gộp câu chào ngắn vào đầu bong bóng chính.
 */
public class BubbleCapHook implements GuardrailHook {

    public static final String HOOK_CODE = "HOOK_BUBBLE_CAP";

    private static final Pattern SHORT_GREETING_PATTERN = Pattern.compile(
            "(?isU)^(?:dạ[,\\s]*)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\b[^?]*$"
    );

    @Override
    public String getHookCode() {
        return HOOK_CODE;
    }

    @Override
    public boolean apply(GuardrailContext context, GuardrailDraft draft) {
        List<ReplyBubble> currentBubbles = draft.getBubbles();
        String currentText = draft.getText();

        List<String> rawParagraphs;
        if (currentBubbles != null && !currentBubbles.isEmpty()) {
            rawParagraphs = new ArrayList<>();
            for (ReplyBubble b : currentBubbles) {
                if (b.text() != null && !b.text().isBlank()) {
                    rawParagraphs.add(b.text().strip());
                }
            }
        } else if (currentText != null && !currentText.isBlank()) {
            rawParagraphs = new ArrayList<>();
            String[] splits = currentText.split("\\n\\s*\\n");
            for (String s : splits) {
                String trimmed = s.strip();
                if (!trimmed.isEmpty()) {
                    rawParagraphs.add(trimmed);
                }
            }
        } else {
            return false;
        }

        int initialCount = rawParagraphs.size();
        boolean modified = false;

        // 1. Ở lượt đầu tiên (isFirstTurn): nếu bong bóng đầu chỉ là câu chào rất ngắn (<= 35 ký tự), gộp vào bong bóng kế tiếp
        if (context.isFirstTurn() && rawParagraphs.size() >= 2) {
            String first = rawParagraphs.get(0);
            if (first.length() <= 35 && SHORT_GREETING_PATTERN.matcher(first).matches()) {
                rawParagraphs.remove(0);
                rawParagraphs.set(0, first + " " + rawParagraphs.get(0));
                modified = true;
            }
        }

        // 2. Nếu chỉ có 1 đoạn văn rất dài (> 220 ký tự), tách nhẹ câu hỏi chốt ở cuối thành bong bóng 2
        if (rawParagraphs.size() == 1 && rawParagraphs.get(0).length() > 220) {
            String single = rawParagraphs.get(0);
            int splitIdx = findSentenceBoundaryBeforeQuestion(single);
            if (splitIdx > 60 && splitIdx < single.length() - 25) {
                String p1 = single.substring(0, splitIdx).strip();
                String p2 = single.substring(splitIdx).strip();
                rawParagraphs.clear();
                rawParagraphs.add(p1);
                rawParagraphs.add(p2);
                modified = true;
            }
        }

        // 2b. Nếu có 2 đoạn văn mà đoạn 2 dài (> 180 ký tự) và kết thúc bằng câu hỏi chốt sau dấu chấm câu / danh sách,
        // tách câu hỏi chốt thành bong bóng 3 để tạo nhịp 3 bong bóng dễ đọc (1: Đồng cảm, 2: Giải pháp/Giá, 3: Câu hỏi chốt)
        if (rawParagraphs.size() == 2 && rawParagraphs.get(1).length() > 180) {
            String second = rawParagraphs.get(1);
            int splitIdx = findSentenceBoundaryBeforeQuestion(second);
            if (splitIdx > 50 && splitIdx < second.length() - 20) {
                String p2 = second.substring(0, splitIdx).strip();
                String p3 = second.substring(splitIdx).strip();
                rawParagraphs.set(1, p2);
                rawParagraphs.add(p3);
                modified = true;
            }
        }

        // 3. Khống chế cứng <= 3 bong bóng (gộp tất cả các bong bóng từ index 2 trở đi vào bong bóng 3)
        if (rawParagraphs.size() > 3) {
            StringBuilder mergedThird = new StringBuilder(rawParagraphs.get(2));
            for (int i = 3; i < rawParagraphs.size(); i++) {
                mergedThird.append("\n\n").append(rawParagraphs.get(i));
            }
            String first = rawParagraphs.get(0);
            String second = rawParagraphs.get(1);
            rawParagraphs.clear();
            rawParagraphs.add(first);
            rawParagraphs.add(second);
            rawParagraphs.add(mergedThird.toString().strip());
            modified = true;
        } else if (initialCount > 3) {
            modified = true;
        }

        // Tạo danh sách ReplyBubble chuẩn với độ trễ gõ phím người thật
        // Nếu phản hồi có chứa báo giá sản phẩm/dịch vụ (được đánh dấu hasPostPriceThinkingDelay >= 9000ms),
        // bong bóng ngay sau bong bóng chứa giá được giữ nguyên nhịp chờ 15.000ms (15s) theo SPA AGENT RUNTIME POLICY v2.0.
        boolean hasPostPriceThinkingDelay = currentBubbles != null
                && currentBubbles.stream().anyMatch(b -> b != null && b.typingDelayMs() >= 9000);
        int targetPostPriceBubbleIndex = -1;
        if (hasPostPriceThinkingDelay) {
            if (currentBubbles.size() == rawParagraphs.size()) {
                for (int i = 1; i < currentBubbles.size(); i++) {
                    if (currentBubbles.get(i) != null && currentBubbles.get(i).typingDelayMs() >= 9000) {
                        targetPostPriceBubbleIndex = i;
                        break;
                    }
                }
            }
            if (targetPostPriceBubbleIndex < 0 && rawParagraphs.size() >= 2) {
                for (int i = 0; i < rawParagraphs.size() - 1; i++) {
                    if (rawParagraphs.get(i).matches("(?isU).*\\d+(?:[.,]\\d+)?\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b|triệu\\b).*")) {
                        targetPostPriceBubbleIndex = i + 1;
                        break;
                    }
                }
                if (targetPostPriceBubbleIndex < 0) {
                    targetPostPriceBubbleIndex = 1;
                }
            }
        }
        List<ReplyBubble> finalBubbles = new ArrayList<>(rawParagraphs.size());
        for (int i = 0; i < rawParagraphs.size(); i++) {
            String text = rawParagraphs.get(i);
            int delayMs;
            if (i == 0) {
                delayMs = Math.min(1200, Math.max(800, text.length() * 10));
            } else if (i == targetPostPriceBubbleIndex && hasPostPriceThinkingDelay) {
                delayMs = 15_000;
            } else {
                delayMs = Math.min(3000, Math.max(2500, text.length() * 15));
            }
            finalBubbles.add(new ReplyBubble(text, delayMs));
        }

        draft.setBubbles(finalBubbles);
        draft.setText(String.join("\n\n", rawParagraphs));

        if (modified || initialCount > 3 || (currentBubbles != null && currentBubbles.size() > 3)
                || draft.getOriginalBubbleCount() > 3
                || (draft.getOriginalBubbleCount() >= 3 && draft.getOriginalBubbleCount() != finalBubbles.size())) {
            draft.addTriggeredHook(HOOK_CODE);
            return true;
        }

        return false;
    }

    private static int findSentenceBoundaryBeforeQuestion(String text) {
        if (!text.endsWith("?") && !text.endsWith("ạ?") && !text.endsWith("nhé!") && !text.endsWith("nha!")) {
            return -1;
        }
        int lastDot = Math.max(text.lastIndexOf(". "), text.lastIndexOf("! "));
        int lastNewline = text.lastIndexOf("\n");
        int boundary = Math.max(lastDot > 0 ? lastDot + 2 : -1, lastNewline > 0 ? lastNewline + 1 : -1);
        return boundary > 0 ? boundary : -1;
    }
}
