package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * In-Loop Deterministic Guardrail Pipeline.
 * <p>
 * Ngân sách thực thi nghiêm ngặt: {@code < 5ms} đo bằng {@code System.nanoTime()}.
 * Điều phối tuần tự các Guardrail Hooks tất định:
 * <ol>
 *   <li>{@link HonorificConsistencyHook}: Khử triệt để pronoun leakage ("Chị" -> "Anh" cho khách nam).</li>
 *   <li>{@link AntiInfoDumpingHook}: Chống xả bảng giá trong giai đoạn DISCOVERY.</li>
 *   <li>{@link ClosingQuestionHook}: Kiểm soát câu hỏi kết thúc (đơn nhất, bảo toàn CTA A/B, không xin phép khô khan).</li>
 *   <li>{@link BubbleCapHook}: Giới hạn tối đa 2 bong bóng tin nhắn (bubble cap <= 2), gộp câu chào lượt 1.</li>
 * </ol>
 * Tích hợp cơ chế Bound Payload và Bound Bubbles tức thì (O(1)) bảo đảm trần độ trễ sub-millisecond (< 1ms)
 * ngay cả trên đầu vào khổng lồ (> 10.000 đến 50.000 ký tự).
 */
@Component
public class GuardrailPipeline {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPipeline.class);

    public static final int MAX_SAFE_PAYLOAD_LENGTH = 4000;
    public static final int HEAD_WINDOW_CHARS = 700;
    public static final int TAIL_WINDOW_CHARS = 400;
    public static final int MAX_SAFE_BUBBLES = 5;
    public static final int MAX_SAFE_BUBBLE_LENGTH = 4000;

    private final List<GuardrailHook> hooks;

    public GuardrailPipeline() {
        this(List.of(
                new SyntaxSanitizationHook(),
                new HonorificConsistencyHook(),
                new ClosingQuestionHook(),
                new AntiInfoDumpingHook(),
                new BubbleCapHook()
        ));
    }

    public GuardrailPipeline(List<GuardrailHook> hooks) {
        this.hooks = (hooks != null) ? new ArrayList<>(hooks) : new ArrayList<>();
    }

    /**
     * Bọc và giới hạn độ dài payload an toàn theo cửa sổ Head + Tail trong O(1).
     * Chỉ kích hoạt trên văn bản thực sự khổng lồ (length >= 4000 ký tự) để bảo đảm < 5ms,
     * tuyệt đối không cắt tỉa hay làm rơi rụng nội dung của các phản hồi thông thường (1000 - 3000 ký tự).
     * Giữ nguyên lời chào/mở bài ở đầu (Head) và câu hỏi chốt/CTA ở cuối (Tail).
     */
    public static String boundPayload(String text) {
        if (text == null || text.length() < MAX_SAFE_PAYLOAD_LENGTH) {
            return text;
        }

        int len = text.length();
        int headCut = Math.min(HEAD_WINDOW_CHARS, len);
        int lastBreakInHead = findLastSentenceBreak(text, headCut - 300, headCut);
        int finalHeadEnd = lastBreakInHead > 0 ? lastBreakInHead : headCut;

        int tailStart = Math.max(0, len - TAIL_WINDOW_CHARS);
        int firstBreakInTail = findFirstSentenceBreak(text, tailStart, Math.min(tailStart + 200, len));
        int finalTailStart = firstBreakInTail > 0 ? firstBreakInTail : tailStart;

        String head = text.substring(0, finalHeadEnd).strip();
        String tail = text.substring(finalTailStart).strip();

        if (tail.isEmpty() || head.equals(tail)) {
            return head;
        }
        return head + "\n\n" + tail;
    }

    private static int findLastSentenceBreak(String text, int start, int end) {
        if (start < 0) start = 0;
        if (end > text.length()) end = text.length();
        for (int i = end - 1; i >= start; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int findFirstSentenceBreak(String text, int start, int end) {
        if (start < 0) start = 0;
        if (end > text.length()) end = text.length();
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && i + 1 < text.length()) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Bọc và giới hạn danh sách bong bóng thô phòng thủ trước flood attack.
     */
    public static List<ReplyBubble> boundBubbles(List<ReplyBubble> bubbles) {
        if (bubbles == null || bubbles.isEmpty()) {
            return bubbles;
        }
        List<ReplyBubble> safe = new ArrayList<>();
        int count = bubbles.size();
        if (count > MAX_SAFE_BUBBLES) {
            safe.add(sanitizeBubble(bubbles.get(0)));
            safe.add(sanitizeBubble(bubbles.get(1)));
            safe.add(sanitizeBubble(bubbles.get(2)));
            safe.add(sanitizeBubble(bubbles.get(count - 1)));
        } else {
            for (ReplyBubble b : bubbles) {
                safe.add(sanitizeBubble(b));
            }
        }
        return safe;
    }

    private static ReplyBubble sanitizeBubble(ReplyBubble b) {
        if (b == null || b.text() == null || b.text().length() <= MAX_SAFE_BUBBLE_LENGTH) {
            return b;
        }
        return new ReplyBubble(boundPayload(b.text()), b.typingDelayMs());
    }

    /**
     * Chạy toàn bộ pipeline trên văn bản và bong bóng chat thô.
     */
    public GuardrailResult process(GuardrailContext context, String initialText, List<ReplyBubble> initialBubbles) {
        long startNs = System.nanoTime();

        // 1. Phân định ranh giới và giới hạn độ dài payload ngay tại cửa ngõ vào để bảo đảm < 5ms
        String safeText = boundPayload(initialText);
        List<ReplyBubble> safeBubbles = boundBubbles(initialBubbles);

        if ((safeText == null || safeText.isBlank()) && safeBubbles != null && !safeBubbles.isEmpty()) {
            List<String> bTexts = safeBubbles.stream()
                    .map(ReplyBubble::text)
                    .filter(t -> t != null && !t.isBlank())
                    .toList();
            safeText = boundPayload(String.join("\n\n", bTexts));
        }

        GuardrailDraft draft = new GuardrailDraft(safeText, safeBubbles);

        for (GuardrailHook hook : hooks) {
            try {
                if (hook.isApplicable(context, draft)) {
                    hook.apply(context, draft);
                }
            } catch (Throwable t) {
                log.warn("[Guardrail] Error applying hook {}: {}", hook.getHookCode(), t.getMessage());
            }
        }

        // Đảm bảo bong bóng được đồng bộ nếu BubbleCapHook chưa chạy hoặc bị null
        if (draft.getBubbles() == null || draft.getBubbles().isEmpty()) {
            new BubbleCapHook().apply(context, draft);
        }

        long durationNs = System.nanoTime() - startNs;
        double durationMs = durationNs / 1_000_000.0;

        if (durationMs >= 5.0) {
            log.warn("[Guardrail] Pipeline execution exceeded 5ms budget: {}ms", durationMs);
        } else {
            log.debug("[Guardrail] Pipeline executed in {}ms ({}ns), triggered_hooks={}",
                    durationMs, durationNs, draft.getTriggeredHooks());
        }

        return new GuardrailResult(
                draft.getText(),
                draft.getBubbles(),
                draft.getTriggeredHooks(),
                durationNs,
                durationMs
        );
    }

    public GuardrailResult process(GuardrailContext context, String initialText) {
        return process(context, initialText, Collections.emptyList());
    }

    /**
     * Interface contract theo PROJECT.md:
     * {@code evaluate(CustomerContext ctx, FanpageBotResponse originalResponse): FanpageBotResponse}
     */
    public FanpageBotResponse evaluate(CustomerContext ctx, FanpageBotResponse originalResponse) {
        return process(ctx, originalResponse);
    }

    /**
     * Áp dụng pipeline trực tiếp lên FanpageBotResponse hiện có, trả về FanpageBotResponse mới
     * đã qua guardrails và bổ sung telemetry triggered_hooks.
     */
    public FanpageBotResponse process(GuardrailContext context, FanpageBotResponse originalResponse) {
        if (originalResponse == null) {
            return null;
        }

        GuardrailResult result = process(
                context,
                originalResponse.replyText(),
                originalResponse.replyBubbles()
        );

        List<String> combinedHooks = new ArrayList<>();
        if (originalResponse.triggeredHooks() != null) {
            combinedHooks.addAll(originalResponse.triggeredHooks());
        }
        for (String h : result.triggeredHooks()) {
            if (!combinedHooks.contains(h)) {
                combinedHooks.add(h);
            }
        }

        boolean hadPostPriceThinkingDelay = originalResponse.replyBubbles() != null
                && originalResponse.replyBubbles().stream().anyMatch(b -> b != null && b.typingDelayMs() >= 9000);
        List<ReplyBubble> finalBubbles = result.bubbles();
        String finalText = result.text();

        com.example.docmind.dto.bot.AgentSkillDto primaryMicroSkill = (originalResponse.activeSkills() != null && !originalResponse.activeSkills().isEmpty())
                ? originalResponse.activeSkills().get(0)
                : null;
        boolean isMicroSkill = primaryMicroSkill != null && primaryMicroSkill.code() != null
                && primaryMicroSkill.code().equals(primaryMicroSkill.code().toLowerCase(java.util.Locale.ROOT));

        if (isMicroSkill) {
            com.example.docmind.dto.bot.AgentSkillDto.SkillOutputContract oc = primaryMicroSkill.resolvedOutputContract();
            String honorific = (context != null && context.getCustomerHonorific() != null && !context.getCustomerHonorific().isBlank())
                    ? context.getCustomerHonorific()
                    : "mình";

            boolean leakedInternal = finalText != null && finalText.toLowerCase(java.util.Locale.ROOT)
                    .matches("(?s).*(?:system\\s+prompt|chunk_id|document_id|<document_context>|<experience_library>).*");
            if (finalText == null || finalText.isBlank() || finalBubbles == null || finalBubbles.isEmpty() || leakedInternal) {
                finalText = primaryMicroSkill.resolvedFallbackReply(honorific);
                String[] parts = finalText.split("\\n\\s*\\n");
                List<ReplyBubble> fbBubbles = new ArrayList<>();
                for (int i = 0; i < parts.length && i < oc.replyMaxBubbles(); i++) {
                    fbBubbles.add(new ReplyBubble(parts[i].strip(), i == 0 ? 1200 : 2500));
                }
                finalBubbles = fbBubbles;
                if (!combinedHooks.contains("HOOK_SKILL_CONTRACT_FALLBACK")) {
                    combinedHooks.add("HOOK_SKILL_CONTRACT_FALLBACK");
                }
            }

            if (oc.maxQuestions() == 0 && finalBubbles != null) {
                List<ReplyBubble> noQuestionBubbles = new ArrayList<>();
                for (ReplyBubble b : finalBubbles) {
                    if (b == null || b.text() == null) continue;
                    String t = b.text().strip();
                    if (t.contains("?")) {
                        t = t.replaceAll("(?isU)(?<=[.!])\\s+[^.!?]*\\?\\s*$", "").strip();
                    }
                    if (!t.isBlank()) {
                        noQuestionBubbles.add(new ReplyBubble(t, b.typingDelayMs()));
                    }
                }
                if (!noQuestionBubbles.isEmpty()) {
                    finalBubbles = noQuestionBubbles;
                    finalText = String.join("\n\n", finalBubbles.stream().map(ReplyBubble::text).toList());
                }
            }

            boolean hasCodeInjectedOpening = originalResponse.replyBubbles() != null
                    && !originalResponse.replyBubbles().isEmpty()
                    && originalResponse.replyBubbles().get(0) != null
                    && originalResponse.replyBubbles().get(0).typingDelayMs() == 0;
            int effectiveMaxBubbles = (hasCodeInjectedOpening || (context != null && context.isFirstTurn()))
                    ? Math.max(3, oc.replyMaxBubbles())
                    : Math.min(2, oc.replyMaxBubbles());
            if (effectiveMaxBubbles > 0 && finalBubbles != null && finalBubbles.size() > effectiveMaxBubbles) {
                int maxB = effectiveMaxBubbles;
                List<ReplyBubble> merged = new ArrayList<>(finalBubbles.subList(0, maxB - 1));
                StringBuilder lastText = new StringBuilder(finalBubbles.get(maxB - 1).text().strip());
                int maxDelay = finalBubbles.get(maxB - 1).typingDelayMs();
                for (int i = maxB; i < finalBubbles.size(); i++) {
                    lastText.append(" ").append(finalBubbles.get(i).text().strip());
                    maxDelay = Math.max(maxDelay, finalBubbles.get(i).typingDelayMs());
                }
                if (hadPostPriceThinkingDelay && maxDelay < 10_000) {
                    maxDelay = 10_000;
                }
                merged.add(new ReplyBubble(lastText.toString().strip(), maxDelay));
                finalBubbles = merged;
                finalText = String.join("\n\n", finalBubbles.stream().map(ReplyBubble::text).toList());
            }
        }

        if (originalResponse.needsHumanHandoff() && finalBubbles != null && finalBubbles.size() > 1) {
            String singleText = finalText != null ? finalText.replaceAll("\\n\\s*\\n", " ").strip() : finalBubbles.get(0).text();
            finalBubbles = List.of(new ReplyBubble(singleText, Math.min(1200, Math.max(800, singleText.length() * 10))));
        } else if (hadPostPriceThinkingDelay && finalBubbles != null && finalBubbles.size() >= 2
                && finalBubbles.stream().noneMatch(b -> b != null && b.typingDelayMs() >= 9000)) {
            List<ReplyBubble> updated = new ArrayList<>(finalBubbles);
            int targetIdx = 1;
            for (int i = 0; i < updated.size() - 1; i++) {
                if (updated.get(i).text() != null
                        && updated.get(i).text().matches("(?isU).*\\d+(?:[.,]\\d+)?\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b|triệu\\b).*")) {
                    targetIdx = i + 1;
                    break;
                }
            }
            updated.set(targetIdx, new ReplyBubble(updated.get(targetIdx).text(), 10_000));
            finalBubbles = Collections.unmodifiableList(updated);
        }

        if (finalBubbles != null && !finalBubbles.isEmpty()) {
            List<ReplyBubble> capitalizedBubbles = new ArrayList<>(finalBubbles.size());
            for (ReplyBubble b : finalBubbles) {
                if (b == null || b.text() == null) {
                    capitalizedBubbles.add(b);
                } else {
                    capitalizedBubbles.add(new ReplyBubble(BubbleCapHook.capitalizeFirstLetter(b.text()), b.typingDelayMs()));
                }
            }
            finalBubbles = Collections.unmodifiableList(capitalizedBubbles);
            finalText = String.join("\n\n", finalBubbles.stream().filter(java.util.Objects::nonNull).map(ReplyBubble::text).toList());
        }

        List<Integer> bubbleDelays = finalBubbles != null
                ? finalBubbles.stream().map(ReplyBubble::typingDelayMs).toList()
                : List.of();

        return new FanpageBotResponse(
                finalText,
                finalBubbles,
                bubbleDelays,
                originalResponse.salesStage(),
                originalResponse.confidenceScore(),
                originalResponse.needsHumanHandoff(),
                originalResponse.handoffCategory(),
                originalResponse.handoffReason(),
                originalResponse.extractedLead(),
                originalResponse.appliedRules(),
                originalResponse.customerInsight(),
                originalResponse.activeSkills(),
                combinedHooks
        );
    }

    public List<GuardrailHook> getHooks() {
        return Collections.unmodifiableList(hooks);
    }
}
