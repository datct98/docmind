package com.example.docmind.service.bot;

import com.docmind.guardrail.BubbleCapHook;
import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.service.sales.PriceLadderTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuyên trách Đầu ra của CLEAN_AGENT_MODE:
 * Parse JSON chịu lỗi, Holistic Grounding Referee, Sanitizer tại chỗ,
 * Xác thực nguồn gốc CareNotes, Xác thực Speech Act sau gửi & Tách bong bóng tự nhiên.
 */
public final class CleanAgentOutputRefereeAndSanitizer {

    private static final Logger log = LoggerFactory.getLogger(CleanAgentOutputRefereeAndSanitizer.class);
    private static final ObjectMapper LLM_JSON_MAPPER = new ObjectMapper();

    public static final String SPEECH_ACT_RESPECTED_PAUSE = "RESPECTED_PAUSE_WITH_SOFT_OPTION";
    public static final String SPEECH_ACT_OFFERED_DETAILS = "OFFERED_PACKAGE_DETAILS";
    public static final String SPEECH_ACT_INVITED_TRIAL_OR_TIME = "INVITED_TRIAL_OR_ASKED_TIME";
    public static final String SPEECH_ACT_BOOKING_CONFIRMED = "BOOKING_CONFIRMED_ACK";
    public static final String SPEECH_ACT_GENERAL_CONSULTATION = "GENERAL_CONSULTATION";

    private static final Pattern FORBIDDEN_MEDICAL_CLAIM_CLEAN_PATTERN = Pattern.compile(
            "(?iU)\\b(chữa\\s+khỏi(?:\\s+hẳn|\\s+hoàn\\s+toàn)?\\s+bệnh|điều\\s+trị\\s+dứt\\s+điểm\\s+bệnh|trị\\s+khỏi\\s+hẳn\\s+bệnh|cam\\s+kết\\s+khỏi\\s+bệnh)\\b"
    );

    private static final Pattern FORBIDDEN_WRONG_PRONOUN_OR_SUBJECTLESS_OPENER_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:dạ[,\\s]+)?(?:mình\\s+(?:đã\\s+|rất\\s+|cũng\\s+)?(?:hiểu|biết|thấy|ghi\\s+nhận|nắm\\s+rõ|xin\\s+lỗi)|(?:đã\\s+)?hiểu\\s+rồi|em\\s+hiểu\\s+mình\\s+đang\\s+cân\\s+nhắc)\\b.*"
    );

    private static final Pattern FORBIDDEN_SELF_CONFESSION_REPETITION_PATTERN = Pattern.compile(
            "(?isU).*\\b(lỡ\\s+miệng\\s+nhắc\\s+lại|nhắc\\s+lại\\s+thông\\s+tin\\s+cũ|lặp\\s+lại\\s+(?:thông\\s+tin|câu\\s+trước|lời\\s+cũ)|xin\\s+lỗi\\s+vì\\s+(?:đã\\s+)?(?:lặp\\s+lại|nhắc\\s+lại))\\b.*"
    );

    private static final Pattern DOWNSELL_OFFER_PHRASE_PATTERN = Pattern.compile(
            "(?isU).*\\b(gói\\s+(?:dịch\\s+vụ\\s+)?(?:tiết\\s+kiệm|rẻ|mềm)\\s+hơn|phương\\s+án\\s+(?:tiết\\s+kiệm|rẻ)\\s+hơn|gợi\\s+ý\\s+gói\\s+(?:tiết\\s+kiệm|rẻ))\\b.*"
    );

    static final Pattern BOOKING_TIME_QUESTION_PHRASE_PATTERN = Pattern.compile(
            "(?isU).*\\b(khung\\s+giờ\\s+nào|mấy\\s+giờ|giờ\\s+nào\\s+(?:để\\s+em|tiện|ạ|nhỉ)|tiện\\s+ghé\\s+(?:vào\\s+)?(?:khung\\s+giờ|lúc\\s+nào))\\b.*"
    );

    private static final Pattern BOT_PROMOTIONAL_OR_EFFICACY_CARE_NOTE_CLAUSE_PATTERN = Pattern.compile(
            "(?iU)\\b(xử\\s+lý\\s+sớm\\s+sẽ|sẽ\\s+hiệu\\s+quả\\s+nhanh|hiệu\\s+quả\\s+nhanh|cải\\s+thiện\\s+rõ\\s+rệt|"
            + "nhẹ\\s+người\\s+rõ\\s+rệt|không\\s+(?:chỉ\\s+)?làm\\s+qua\\s+loa|qua\\s+loa\\s+bề\\s+mặt|thư\\s+giãn\\s+hời\\s+hợt|"
            + "chuyên\\s+viên\\s+lành\\s+nghề|ấn\\s+miết\\s+sâu|mang\\s+lại\\s+cảm\\s+nhận|em\\s+gợi\\s+ý|bên\\s+em\\s+có|"
            + "mức\\s+giá\\s+đã\\s+bao\\s+gồm|đã\\s+lưu\\s+lại\\s+thông\\s+tin|cứ\\s+cân\\s+nhắc\\s+thêm)\\b"
    );

    private static final Pattern CUSTOMER_CARE_PROVENANCE_SIGNAL_PATTERN = Pattern.compile(
            "(?iU)\\b(đau|mỏi|nhức|tê|căng|cứng|buốt|rát|ngứa|sưng|khó\\s+chịu|mất\\s+ngủ|nặng\\s+đầu|chóng\\s+mặt|chuột\\s+rút|"
            + "đầu|cổ|vai|gáy|lưng|thắt\\s+lưng|eo|chân|tay|bắp\\s+chân|da\\s+đầu|tai|da\\s+mặt|toàn\\s+thân|"
            + "gần\\s+đây|mới\\s+bị|mấy\\s+hôm|lâu\\s+rồi|kéo\\s+dài|ngồi\\s+nhiều|văn\\s+phòng|vận\\s+động|"
            + "lực\\s+nhẹ|lực\\s+vừa|lực\\s+mạnh|kín\\s+đáo|yên\\s+tĩnh|dị\\s+ứng|nhạy\\s+cảm|lưu\\s+ý|tránh)\\b"
    );

    private static final Pattern STAGE6_CLOSING_WISH_SPLIT_PATTERN = Pattern.compile(
            "(?isU)^(.+[,.;])\\s*(chúc\\s+(?:mình|anh|chị|bạn)\\s+(?:một\\s+ngày|ngày\\s+mới|buổi\\s+tối|buổi\\s+chiều|buổi\\s+sáng|cuối\\s+tuần|luôn|thật\\s+nhiều)[^?!.]*[.!]?)\\s*$"
    );

    private CleanAgentOutputRefereeAndSanitizer() {
    }

    public record BrainReflection(
            String observedFact,
            String careNoteDelta,
            String workingHypothesis,
            String intendedSpeechAct,
            String detectedCustomerIntent
    ) {
        public BrainReflection {
            observedFact = observedFact != null ? observedFact.strip() : "";
            careNoteDelta = careNoteDelta != null ? careNoteDelta.strip() : "";
            workingHypothesis = workingHypothesis != null ? workingHypothesis.strip() : "";
            intendedSpeechAct = intendedSpeechAct != null ? intendedSpeechAct.strip() : "";
            detectedCustomerIntent = detectedCustomerIntent != null ? detectedCustomerIntent.strip() : "";
        }

        public BrainReflection(
                String observedFact,
                String careNoteDelta,
                String workingHypothesis,
                String intendedSpeechAct
        ) {
            this(observedFact, careNoteDelta, workingHypothesis, intendedSpeechAct, "");
        }

        public BrainReflection(
                String observedFact,
                String careNoteDelta,
                String workingHypothesis
        ) {
            this(observedFact, careNoteDelta, workingHypothesis, "", "");
        }

        public static BrainReflection empty() {
            return new BrainReflection("", "", "", "", "");
        }

        public boolean hasAnyContent() {
            return !observedFact.isEmpty()
                    || !careNoteDelta.isEmpty()
                    || !workingHypothesis.isEmpty()
                    || !intendedSpeechAct.isEmpty()
                    || !detectedCustomerIntent.isEmpty();
        }
    }

    public record StructuredLlmOutput(
            boolean parsedFromJson,
            List<FanpageBotResponse.ReplyBubble> bubbles,
            Boolean shouldHandoff,
            String handoffReason,
            String updatedSalesStage,
            ConversationState.GroundingCheck groundingCheck,
            String groundingCheckText,
            BrainReflection brainReflection
    ) {
        public StructuredLlmOutput {
            if (brainReflection == null) {
                brainReflection = BrainReflection.empty();
            }
        }

        public StructuredLlmOutput(
                boolean parsedFromJson,
                List<FanpageBotResponse.ReplyBubble> bubbles,
                Boolean shouldHandoff,
                String handoffReason,
                String updatedSalesStage,
                ConversationState.GroundingCheck groundingCheck,
                String groundingCheckText
        ) {
            this(parsedFromJson, bubbles, shouldHandoff, handoffReason, updatedSalesStage, groundingCheck, groundingCheckText, BrainReflection.empty());
        }

        public StructuredLlmOutput(
                boolean parsedFromJson,
                List<FanpageBotResponse.ReplyBubble> bubbles,
                Boolean shouldHandoff,
                String handoffReason,
                String updatedSalesStage
        ) {
            this(parsedFromJson, bubbles, shouldHandoff, handoffReason, updatedSalesStage, null, null, BrainReflection.empty());
        }
    }

    public static StructuredLlmOutput parseStructuredLlmJsonOutput(String rawText, int minPostPriceDelayMs) {
        if (rawText == null || rawText.isBlank()) {
            return new StructuredLlmOutput(false, List.of(), null, null, null);
        }
        String jsonCandidate = stripMarkdownCodeFencesAndExtractJson(rawText);
        if (jsonCandidate == null || !jsonCandidate.startsWith("{")) {
            return salvageReplyBubblesFromPartialJson(rawText.strip(), minPostPriceDelayMs);
        }
        try {
            JsonNode root = LLM_JSON_MAPPER.readTree(jsonCandidate);
            if (root == null || !root.isObject()) {
                return salvageReplyBubblesFromPartialJson(rawText.strip(), minPostPriceDelayMs);
            }
            List<FanpageBotResponse.ReplyBubble> bubbles = extractBubblesFromRootNode(root, minPostPriceDelayMs);
            if (bubbles.isEmpty()) {
                return salvageReplyBubblesFromPartialJson(rawText.strip(), minPostPriceDelayMs);
            }

            Boolean shouldHandoff = null;
            if (root.hasNonNull("should_handoff") && root.get("should_handoff").isBoolean()) {
                shouldHandoff = root.get("should_handoff").asBoolean(false);
            } else if (root.hasNonNull("needs_human_handoff") && root.get("needs_human_handoff").isBoolean()) {
                shouldHandoff = root.get("needs_human_handoff").asBoolean(false);
            }
            String handoffReason = root.hasNonNull("handoff_reason") && root.get("handoff_reason").isTextual()
                    ? root.get("handoff_reason").asText(null)
                    : null;
            String updatedSalesStage = null;
            JsonNode stateUpdates = root.get("state_updates");
            if (stateUpdates != null && stateUpdates.isObject()) {
                if (stateUpdates.hasNonNull("sales_stage")) {
                    updatedSalesStage = stateUpdates.get("sales_stage").asText(null);
                } else if (stateUpdates.hasNonNull("stage")) {
                    updatedSalesStage = stateUpdates.get("stage").asText(null);
                }
            }

            ConversationState.GroundingCheck groundingCheck = null;
            String groundingCheckText = null;
            if (root.hasNonNull("grounding_check")) {
                JsonNode gcNode = root.get("grounding_check");
                if (gcNode.isObject()) {
                    List<String> confirmed = new ArrayList<>();
                    JsonNode confNode = gcNode.get("customer_confirmed_scopes");
                    if (confNode != null && confNode.isArray()) {
                        for (JsonNode n : confNode) {
                            if (n != null && !n.asText("").isBlank()) {
                                confirmed.add(n.asText().strip());
                            }
                        }
                    }
                    List<String> negated = new ArrayList<>();
                    JsonNode negNode = gcNode.get("customer_negated_scopes");
                    if (negNode != null && negNode.isArray()) {
                        for (JsonNode n : negNode) {
                            if (n != null && !n.asText("").isBlank()) {
                                negated.add(n.asText().strip());
                            }
                        }
                    }
                    String activeIntent = gcNode.hasNonNull("active_intent") ? gcNode.get("active_intent").asText(null) : null;
                    String errApologize = gcNode.hasNonNull("error_to_apologize") ? gcNode.get("error_to_apologize").asText(null) : null;
                    groundingCheck = new ConversationState.GroundingCheck(confirmed, negated, activeIntent, errApologize);
                    groundingCheckText = gcNode.toString();
                } else if (gcNode.isTextual()) {
                    groundingCheckText = gcNode.asText("").strip();
                    List<String> negated = new ArrayList<>();
                    Matcher negMatcher = Pattern.compile("(?iU)(?:phủ\\s+định|bác\\s+bỏ|không\\s+phải|chứ\\s+không\\s+phải|loại\\s+bỏ|nhầm\\s+sang)\\s+([^.,;!\\n]+)")
                            .matcher(groundingCheckText);
                    while (negMatcher.find()) {
                        String[] parts = negMatcher.group(1).split("(?iU)(?:,|\\bva\\b|\\bvà\\b|\\bhoặc\\b)");
                        for (String p : parts) {
                            String clean = p != null ? p.replaceAll("(?iU)^(?:vùng|bộ\\s+phận|dịch\\s+vụ)\\s+", "").strip() : "";
                            if (!clean.isBlank() && clean.length() <= 35) {
                                negated.add(clean);
                            }
                        }
                    }
                    groundingCheck = new ConversationState.GroundingCheck(List.of(), negated, null, null);
                }
            }

            BrainReflection brainReflection = extractSafeBrainReflection(root.get("brain_reflection"));
            return new StructuredLlmOutput(
                    true,
                    bubbles,
                    shouldHandoff,
                    handoffReason,
                    updatedSalesStage,
                    groundingCheck,
                    groundingCheckText,
                    brainReflection
            );
        } catch (Exception e) {
            log.debug("[LLM-JSON] Non-fatal JSON parse fallback: {}", e.getMessage());
            return salvageReplyBubblesFromPartialJson(rawText.strip(), minPostPriceDelayMs);
        }
    }

    private static List<FanpageBotResponse.ReplyBubble> extractBubblesFromRootNode(JsonNode root, int minPostPriceDelayMs) {
        List<FanpageBotResponse.ReplyBubble> bubbles = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return bubbles;
        }
        JsonNode bubblesNode = root.has("reply_bubbles") ? root.get("reply_bubbles")
                : (root.has("bubbles") ? root.get("bubbles") : null);
        if (bubblesNode != null && bubblesNode.isArray()) {
            int idx = 0;
            boolean prevContainsPrice = false;
            for (JsonNode item : bubblesNode) {
                if (item == null || item.isNull()) continue;
                String text = null;
                int delayMs = idx == 0 ? 900 : 2800;
                if (item.isTextual()) {
                    text = item.asText();
                } else if (item.isObject()) {
                    if (item.hasNonNull("text")) {
                        text = item.get("text").asText();
                    } else if (item.hasNonNull("content")) {
                        text = item.get("content").asText();
                    }
                    if (item.hasNonNull("delay_ms")) {
                        delayMs = item.get("delay_ms").asInt(delayMs);
                    } else if (item.hasNonNull("delay_before_ms")) {
                        delayMs = item.get("delay_before_ms").asInt(delayMs);
                    } else if (item.hasNonNull("typing_delay_ms")) {
                        delayMs = item.get("typing_delay_ms").asInt(delayMs);
                    } else if (item.hasNonNull("typingDelayMs")) {
                        delayMs = item.get("typingDelayMs").asInt(delayMs);
                    }
                }
                if (text != null && !text.isBlank()) {
                    String cleanBubbleText = BubbleCapHook.capitalizeFirstLetter(text.strip());
                    if (idx > 0 && prevContainsPrice && minPostPriceDelayMs > 0) {
                        delayMs = Math.max(delayMs, minPostPriceDelayMs);
                    }
                    bubbles.add(new FanpageBotResponse.ReplyBubble(cleanBubbleText, Math.max(0, delayMs)));
                    prevContainsPrice = hasQuotedPriceInText(cleanBubbleText);
                    idx++;
                }
            }
        } else if (root.hasNonNull("reply_text") || root.hasNonNull("reply")) {
            String singleText = root.hasNonNull("reply_text") ? root.get("reply_text").asText() : root.get("reply").asText();
            if (singleText != null && !singleText.isBlank()) {
                bubbles.addAll(splitIntoBubbles(singleText.strip()));
            }
        }
        return bubbles;
    }

    private static BrainReflection extractSafeBrainReflection(JsonNode brNode) {
        if (brNode == null || brNode.isNull() || !brNode.isObject()) {
            return BrainReflection.empty();
        }
        try {
            String observedFact = extractSafeTextualField(brNode, "observed_fact");
            String careNoteDelta = extractSafeTextualField(brNode, "care_note_delta");
            String workingHypothesis = extractSafeTextualField(brNode, "working_hypothesis");
            String intendedSpeechAct = extractSafeTextualField(brNode, "intended_speech_act");
            String detectedCustomerIntent = extractSafeTextualField(brNode, "detected_customer_intent");
            return new BrainReflection(observedFact, careNoteDelta, workingHypothesis, intendedSpeechAct, detectedCustomerIntent);
        } catch (Exception ignored) {
            return BrainReflection.empty();
        }
    }

    private static String extractSafeTextualField(JsonNode parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.hasNonNull(fieldName)) {
            return "";
        }
        JsonNode field = parent.get(fieldName);
        if (field == null || field.isNull() || field.isObject() || field.isArray() || field.isBoolean() || field.isNumber()) {
            return "";
        }
        String val = field.asText("");
        return val != null ? val.strip() : "";
    }

    public static StructuredLlmOutput salvageReplyBubblesFromPartialJson(String jsonCandidate, int minPostPriceDelayMs) {
        if (jsonCandidate == null || jsonCandidate.isBlank()) {
            return new StructuredLlmOutput(false, List.of(), null, null, null);
        }
        try {
            int brIdx = jsonCandidate.indexOf("\"brain_reflection\"");
            if (brIdx > 0) {
                int commaBefore = jsonCandidate.lastIndexOf(',', brIdx);
                if (commaBefore > 0) {
                    String salvagedJson = jsonCandidate.substring(0, commaBefore).strip() + "}";
                    JsonNode salvagedRoot = LLM_JSON_MAPPER.readTree(salvagedJson);
                    List<FanpageBotResponse.ReplyBubble> salvagedBubbles = extractBubblesFromRootNode(salvagedRoot, minPostPriceDelayMs);
                    if (!salvagedBubbles.isEmpty()) {
                        return new StructuredLlmOutput(true, salvagedBubbles, false, null, null, null, null, BrainReflection.empty());
                    }
                }
            }
            Matcher m = Pattern.compile("(?s)\"(?:reply_bubbles|bubbles)\"\\s*:\\s*(\\[.*?\\])").matcher(jsonCandidate);
            if (m.find()) {
                String arrJson = "{\"reply_bubbles\":" + m.group(1) + "}";
                JsonNode arrRoot = LLM_JSON_MAPPER.readTree(arrJson);
                List<FanpageBotResponse.ReplyBubble> arrBubbles = extractBubblesFromRootNode(arrRoot, minPostPriceDelayMs);
                if (!arrBubbles.isEmpty()) {
                    return new StructuredLlmOutput(true, arrBubbles, false, null, null, null, null, BrainReflection.empty());
                }
            }
        } catch (Exception ignored) {
        }
        return new StructuredLlmOutput(false, List.of(), null, null, null);
    }

    public static String stripMarkdownCodeFencesAndExtractJson(String rawText) {
        if (rawText == null) {
            return null;
        }
        String trimmed = rawText.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("(?is)^```(?:json|javascript|js)?\\s*", "");
            trimmed = trimmed.replaceFirst("(?is)\\s*```\\s*$", "").strip();
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String candidate = trimmed.substring(firstBrace, lastBrace + 1).strip();
            if (candidate.contains("\"reply_bubbles\"")
                    || candidate.contains("\"bubbles\"")
                    || candidate.contains("\"reply_text\"")
                    || candidate.contains("\"should_handoff\"")
                    || candidate.contains("\"state_updates\"")
                    || candidate.contains("\"grounding_check\"")
                    || candidate.contains("\"reasoning_brief\"")) {
                return candidate;
            }
        }
        return null;
    }

    public static List<FanpageBotResponse.ReplyBubble> splitIntoBubbles(String cleanText) {
        return splitIntoBubbles(cleanText, false);
    }

    public static List<FanpageBotResponse.ReplyBubble> splitIntoBubbles(String cleanText, boolean enablePostPriceBubble2Thinking) {
        if (cleanText == null || cleanText.isBlank()) {
            return List.of();
        }
        String[] rawParagraphs = cleanText.split("\\n\\s*\\n");
        List<String> bubbleTexts = new ArrayList<>();
        for (String p : rawParagraphs) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                bubbleTexts.add(trimmed);
            }
        }

        if (bubbleTexts.size() >= 2 && bubbleTexts.get(0).length() <= 35
                && bubbleTexts.get(0).matches("(?isU)^(?:dạ[,\\s]*)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\b[^?]*$")) {
            String greeting = bubbleTexts.remove(0);
            bubbleTexts.set(0, greeting + " " + bubbleTexts.get(0));
        }

        if (bubbleTexts.size() == 1 && (bubbleTexts.get(0).length() > 220 || (enablePostPriceBubble2Thinking && bubbleTexts.get(0).length() > 80))) {
            String single = bubbleTexts.get(0);
            int splitIdx = findLastSentenceBoundaryBeforeQuestion(single);
            if (splitIdx <= 0 && enablePostPriceBubble2Thinking) {
                int firstDot = single.indexOf(". ");
                if (firstDot > 30 && firstDot < single.length() - 25) {
                    splitIdx = firstDot + 2;
                }
            }
            if (splitIdx > 30 && splitIdx < single.length() - 20) {
                bubbleTexts.clear();
                bubbleTexts.add(single.substring(0, splitIdx).strip());
                bubbleTexts.add(single.substring(splitIdx).strip());
            }
        }

        while (bubbleTexts.size() > 3) {
            String last = bubbleTexts.remove(bubbleTexts.size() - 1);
            int prevIdx = bubbleTexts.size() - 1;
            bubbleTexts.set(prevIdx, bubbleTexts.get(prevIdx) + "\n" + last);
        }

        int targetPostPriceBubbleIndex = -1;
        if (enablePostPriceBubble2Thinking && bubbleTexts.size() >= 2) {
            for (int i = 0; i < bubbleTexts.size() - 1; i++) {
                if (hasQuotedPriceInText(bubbleTexts.get(i))) {
                    targetPostPriceBubbleIndex = i + 1;
                    break;
                }
            }
            if (targetPostPriceBubbleIndex < 0) {
                targetPostPriceBubbleIndex = 1;
            }
        }

        List<FanpageBotResponse.ReplyBubble> bubbles = new ArrayList<>(bubbleTexts.size());
        for (int i = 0; i < bubbleTexts.size(); i++) {
            String text = bubbleTexts.get(i);
            int delayMs;
            if (i == 0) {
                delayMs = Math.min(1200, Math.max(800, text.length() * 10));
            } else if (i == targetPostPriceBubbleIndex && enablePostPriceBubble2Thinking) {
                delayMs = 15_000;
            } else {
                delayMs = Math.min(3000, Math.max(2500, text.length() * 15));
            }
            bubbles.add(new FanpageBotResponse.ReplyBubble(text, delayMs));
        }
        return bubbles;
    }

    private static int findLastSentenceBoundaryBeforeQuestion(String text) {
        if (!text.endsWith("?") && !text.endsWith("ạ?") && !text.endsWith("nhé!")) {
            return -1;
        }
        int lastDot = Math.max(text.lastIndexOf(". "), text.lastIndexOf("! "));
        return lastDot > 0 ? lastDot + 2 : -1;
    }

    public static List<FanpageBotResponse.ReplyBubble> extractCleanBubblesFromOutput(
            StructuredLlmOutput structured,
            String sanitizedReply,
            int minDelayMs
    ) {
        if (structured != null && structured.parsedFromJson() && structured.bubbles() != null && !structured.bubbles().isEmpty()) {
            List<FanpageBotResponse.ReplyBubble> res = new ArrayList<>();
            for (FanpageBotResponse.ReplyBubble b : structured.bubbles()) {
                if (b != null && b.text() != null && !b.text().isBlank()) {
                    res.add(b);
                }
                if (res.size() >= 3) {
                    break;
                }
            }
            if (!res.isEmpty()) {
                return res;
            }
        }
        if (sanitizedReply == null || sanitizedReply.isBlank()) {
            return List.of();
        }
        List<FanpageBotResponse.ReplyBubble> split = splitIntoBubbles(sanitizedReply, true);
        if (split.size() > 3) {
            return new ArrayList<>(split.subList(0, 3));
        }
        return new ArrayList<>(split);
    }

    public static boolean hasQuotedPriceInText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<Long> prices = PriceLadderTracker.extractPricesFromText(text);
        if (prices != null && !prices.isEmpty()) {
            return true;
        }
        return text.matches("(?isU).*\\b(?:\\d{1,3}(?:\\.\\d{3})+\\s*(?:vnđ|đ|vnd)|\\d{2,4}k)\\b.*");
    }

    public static List<String> checkCleanCatalogGrounding(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<PackageCatalogService.PackageSummary> excludedCatalog
    ) {
        return checkCleanCatalogGrounding(
                bubbles,
                allCatalog,
                scopeFilteredCatalog,
                excludedCatalog,
                0,
                false,
                null,
                true,
                "NONE"
        );
    }

    public static List<String> checkCleanCatalogGrounding(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            int priceObjectionCount,
            boolean explicitDownsellRequested
    ) {
        return checkCleanCatalogGrounding(
                bubbles,
                allCatalog,
                scopeFilteredCatalog,
                excludedCatalog,
                priceObjectionCount,
                explicitDownsellRequested,
                null,
                true,
                "NONE"
        );
    }

    public static List<String> checkCleanCatalogGrounding(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            int priceObjectionCount,
            boolean explicitDownsellRequested,
            PackageCatalogService.PackageSummary activePackage,
            boolean heldPkgIsNull
    ) {
        return checkCleanCatalogGrounding(
                bubbles,
                allCatalog,
                scopeFilteredCatalog,
                excludedCatalog,
                priceObjectionCount,
                explicitDownsellRequested,
                activePackage,
                heldPkgIsNull,
                "NONE"
        );
    }

    public static List<String> checkCleanCatalogGrounding(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            int priceObjectionCount,
            boolean explicitDownsellRequested,
            PackageCatalogService.PackageSummary activePackage,
            boolean heldPkgIsNull,
            String bookingStatus
    ) {
        return checkCleanCatalogGrounding(
                bubbles,
                allCatalog,
                scopeFilteredCatalog,
                excludedCatalog,
                priceObjectionCount,
                explicitDownsellRequested,
                activePackage,
                heldPkgIsNull,
                bookingStatus,
                null
        );
    }

    public enum PriceMentionSemanticRole {
        OFFERED_PRICE,
        USER_ASKED_PRICE,
        NEGATED_PRICE,
        COMPARATIVE_PRICE,
        UNGROUNDED_PRICE
    }

    public static final Pattern CLEAN_PRICE_MENTION_PATTERN = Pattern.compile(
            "(?iU)(?<!\\d)(?:\\d{1,3}(?:[.,]\\d{3})+\\s*(?:đồng|vnđ|vnd|đ)?|\\d{5,7}\\s*(?:đồng|vnđ|vnd|đ)|\\d{2,4}\\s*(?:k|nghìn|ngàn)(?:\\s*(?:đồng|vnđ|vnd|đ))?)(?!\\p{L}|\\d)"
    );

    public static Long parseCleanPriceMentionToVnd(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) {
            return null;
        }
        String lower = rawPrice.toLowerCase(Locale.ROOT).strip();
        String digits = lower.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            long val = Long.parseLong(digits);
            if (val < 10_000L && (lower.contains("k") || lower.contains("nghìn") || lower.contains("ngàn"))) {
                val *= 1_000L;
            }
            return val > 0L ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Set<Long> extractPricesInVndFromText(String text) {
        Set<Long> prices = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return prices;
        }
        Matcher m = CLEAN_PRICE_MENTION_PATTERN.matcher(text);
        while (m.find()) {
            Long vnd = parseCleanPriceMentionToVnd(m.group());
            if (vnd != null && vnd > 0L) {
                prices.add(vnd);
            }
        }
        return prices;
    }

    public static PriceMentionSemanticRole classifyPriceMentionSemanticRole(
            String bubbleText,
            int matchStart,
            int matchEnd,
            long priceVnd,
            Set<Long> allowedPrices,
            Set<Long> excludedPrices,
            Set<Long> userAskedPrices,
            boolean hasCustomerMessageContext,
            boolean turnMentionsAllowedPrice
    ) {
        if (allowedPrices != null && allowedPrices.contains(priceVnd)) {
            String prefix = bubbleText.substring(Math.max(0, matchStart - 30), matchStart).toLowerCase(Locale.ROOT);
            if (prefix.matches("(?isU).*(?:dưới|thấp\\s+hơn|trên|cao\\s+hơn|so\\s+với)\\s+(?:mức\\s+|gói\\s+)?\\s*$")) {
                return PriceMentionSemanticRole.COMPARATIVE_PRICE;
            }
            return PriceMentionSemanticRole.OFFERED_PRICE;
        }

        if (excludedPrices != null && excludedPrices.contains(priceVnd)
                && (userAskedPrices == null || !userAskedPrices.contains(priceVnd))) {
            return PriceMentionSemanticRole.UNGROUNDED_PRICE;
        }

        if (hasCustomerMessageContext && (userAskedPrices == null || !userAskedPrices.contains(priceVnd))) {
            return PriceMentionSemanticRole.UNGROUNDED_PRICE;
        }

        if (!turnMentionsAllowedPrice) {
            return PriceMentionSemanticRole.UNGROUNDED_PRICE;
        }

        int clauseStart = 0;
        for (int i = matchStart - 1; i >= 0; i--) {
            char c = bubbleText.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ';' || c == '\n') {
                clauseStart = i + 1;
                break;
            }
        }
        int clauseEnd = bubbleText.length();
        for (int i = matchEnd; i < bubbleText.length(); i++) {
            char c = bubbleText.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ';' || c == '\n') {
                clauseEnd = i;
                break;
            }
        }

        String beforeInClause = bubbleText.substring(clauseStart, matchStart).toLowerCase(Locale.ROOT);
        String afterInClause = bubbleText.substring(matchEnd, clauseEnd).toLowerCase(Locale.ROOT);

        Matcher contrastAfter = Pattern.compile(
                "(?isU)\\b(?:nhưng|tuy\\s+nhiên|thay\\s+vào\\s+đó|hiện\\s+tại\\s+bên\\s+em\\s+có|bên\\s+em\\s+đang\\s+có|trong\\s+nhóm|mức\\s+tiết\\s+kiệm\\s+nhất|gói\\s+tiết\\s+kiệm\\s+nhất|lựa\\s+chọn\\s+tiết\\s+kiệm\\s+nhất|mình\\s+có\\s+thể\\s+tham\\s+khảo|em\\s+gợi\\s+ý)\\b"
        ).matcher(afterInClause);
        if (contrastAfter.find()) {
            afterInClause = afterInClause.substring(0, contrastAfter.start());
        }
        int commaIdxAfter = afterInClause.indexOf(',');
        String immediateAfterPrice = commaIdxAfter >= 0 ? afterInClause.substring(0, commaIdxAfter) : afterInClause;

        String localSubClause = (beforeInClause + " " + bubbleText.substring(matchStart, matchEnd) + " " + immediateAfterPrice)
                .toLowerCase(Locale.ROOT);

        // Chặn bịa thời lượng hoặc quyền lợi gắn cho mức giá không có trong Catalog (VD: "Gói 100k có thời lượng 60 phút")
        Pattern fabricatedAttributeForPricePat = Pattern.compile(
                "(?isU)(?:\\b\\d{2,3}\\s*(?:phút|p)\\b|\\bthời\\s+lượng\\b|\\bkéo\\s+dài\\b|\\bbao\\s+gồm\\b|\\bgồm\\s+có\\b|\\btặng\\s+kèm\\b|\\băn\\s+nhẹ\\b)"
        );
        if (fabricatedAttributeForPricePat.matcher(localSubClause).find()) {
            return PriceMentionSemanticRole.UNGROUNDED_PRICE;
        }

        boolean negatedBefore = Pattern.compile(
                "(?isU)(?:chưa|không|ko|chẳng)\\s+(?:có|hỗ\\s+trợ|triển\\s+khai|áp\\s+dụng|mở)\\b[^.!?;\\n]*$"
        ).matcher(beforeInClause).find();

        boolean negatedAfter = Pattern.compile(
                "(?isU)^[^.!?;\\n]{0,55}?\\b(?:chưa|không|ko|chẳng)\\s+(?:có|hỗ\\s+trợ|triển\\s+khai|áp\\s+dụng|mở)\\b"
        ).matcher(immediateAfterPrice).find();

        boolean affirmedExistence = Pattern.compile(
                "(?isU)\\b(?:bên\\s+em\\s+có|hiện\\s+có|đang\\s+có|sẵn\\s+có)\\s+(?:gói|dịch\\s+vụ|liệu\\s+trình|mức\\s+giá|mức)\\s*$"
        ).matcher(beforeInClause).find();

        if (!affirmedExistence && (negatedBefore || negatedAfter)) {
            return PriceMentionSemanticRole.NEGATED_PRICE;
        }

        return PriceMentionSemanticRole.UNGROUNDED_PRICE;
    }

    public static List<String> checkCleanCatalogGrounding(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            int priceObjectionCount,
            boolean explicitDownsellRequested,
            PackageCatalogService.PackageSummary activePackage,
            boolean heldPkgIsNull,
            String bookingStatus,
            String customerMessage
    ) {
        if (bubbles == null || bubbles.isEmpty()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        boolean catalogEmpty = (allCatalog == null || allCatalog.isEmpty()
                || scopeFilteredCatalog == null || scopeFilteredCatalog.isEmpty());

        Set<Long> allowedPrices = new LinkedHashSet<>();
        Set<Long> excludedPrices = new LinkedHashSet<>();
        Set<Integer> allowedDurations = new LinkedHashSet<>(List.of(5, 10, 15, 20, 30));
        if (!catalogEmpty) {
            for (PackageCatalogService.PackageSummary p : scopeFilteredCatalog) {
                if (p == null) continue;
                if (p.priceVnd() > 0L) {
                    allowedPrices.add(p.priceVnd());
                }
                if (p.durationMinutes() != null && p.durationMinutes() > 0) {
                    allowedDurations.add(p.durationMinutes());
                }
            }
            if (excludedCatalog != null) {
                for (PackageCatalogService.PackageSummary ex : excludedCatalog) {
                    if (ex != null && ex.priceVnd() > 0L && !allowedPrices.contains(ex.priceVnd())) {
                        excludedPrices.add(ex.priceVnd());
                    }
                }
            }
            for (PackageCatalogService.PackageSummary p : allCatalog) {
                if (p != null && p.durationMinutes() != null && p.durationMinutes() > 0) {
                    allowedDurations.add(p.durationMinutes());
                }
            }
        }

        boolean hasCustomerMessageContext = customerMessage != null && !customerMessage.isBlank();
        Set<Long> userAskedPrices = extractPricesInVndFromText(customerMessage);

        StringBuilder preCombinedSb = new StringBuilder();
        for (FanpageBotResponse.ReplyBubble b : bubbles) {
            if (b != null && b.text() != null) {
                if (!preCombinedSb.isEmpty()) preCombinedSb.append(' ');
                preCombinedSb.append(b.text().strip());
            }
        }
        Set<Long> allMentionedPricesInTurn = extractPricesInVndFromText(preCombinedSb.toString());
        boolean turnMentionsAllowedPrice = !allowedPrices.isEmpty()
                && allMentionedPricesInTurn.stream().anyMatch(allowedPrices::contains);

        Pattern durPat = Pattern.compile("(?iU)\\b(\\d{2,3})\\s*phút\\b");
        Pattern privateOrVipRoomPat = Pattern.compile("(?iU)\\bphòng\\s+(?:vip|riêng)\\b");
        Pattern falsePriorPkgInterestPat = Pattern.compile("(?iU).*\\b(mình|anh|chị|bạn)\\s+đang\\s+quan\\s+tâm\\s+(?:đến\\s+|tới\\s+)?gói\\b.*");
        Pattern pastTenseInstructStaffPat = Pattern.compile(
                "(?iU)\\bem\\s+đã\\s+(?:dặn|báo|nhắc|yêu\\s+cầu)(?:\\s+trước)?\\s+(?:các\\s+|bên\\s+)?(?:chuyên\\s+viên|kỹ\\s+thuật\\s+viên|nhân\\s+viên)\\b"
        );
        boolean isBookingConfirmed = bookingStatus != null && "CONFIRMED".equalsIgnoreCase(bookingStatus.strip());
        StringBuilder combinedTurnSb = new StringBuilder();
        for (FanpageBotResponse.ReplyBubble b : bubbles) {
            if (b == null || b.text() == null) continue;
            String text = b.text().strip();
            String lower = text.toLowerCase(Locale.ROOT);
            if (!combinedTurnSb.isEmpty()) {
                combinedTurnSb.append(' ');
            }
            combinedTurnSb.append(text);

            Matcher pm = CLEAN_PRICE_MENTION_PATTERN.matcher(text);
            while (pm.find()) {
                if (catalogEmpty) {
                    violations.add("UNGROUNDED_PRICE_WHEN_CATALOG_EMPTY:" + pm.group().strip());
                } else {
                    Long priceVnd = parseCleanPriceMentionToVnd(pm.group());
                    if (priceVnd != null && !allowedPrices.isEmpty() && !allowedPrices.contains(priceVnd)) {
                        PriceMentionSemanticRole semanticRole = classifyPriceMentionSemanticRole(
                                text,
                                pm.start(),
                                pm.end(),
                                priceVnd,
                                allowedPrices,
                                excludedPrices,
                                userAskedPrices,
                                hasCustomerMessageContext,
                                turnMentionsAllowedPrice
                        );
                        if (semanticRole == PriceMentionSemanticRole.UNGROUNDED_PRICE) {
                            violations.add("UNGROUNDED_OR_EXCLUDED_PRICE:" + pm.group().strip());
                        }
                    }
                }
            }

            Matcher dm = durPat.matcher(text);
            while (dm.find()) {
                try {
                    int min = Integer.parseInt(dm.group(1));
                    if (catalogEmpty) {
                        violations.add("UNGROUNDED_DURATION_WHEN_CATALOG_EMPTY:" + min + "p");
                    } else if (!allowedDurations.contains(min)) {
                        violations.add("UNGROUNDED_DURATION:" + min + "p");
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            if (excludedCatalog != null) {
                for (PackageCatalogService.PackageSummary ex : excludedCatalog) {
                    if (ex == null || ex.displayName() == null || ex.displayName().isBlank()) continue;
                    String exNameLower = ex.displayName().strip().toLowerCase(Locale.ROOT);
                    if (exNameLower.length() >= 5 && lower.contains(exNameLower)) {
                        violations.add("EXCLUDED_PACKAGE_MENTIONED:" + ex.displayName());
                    }
                }
            }

            Matcher medMatcher = FORBIDDEN_MEDICAL_CLAIM_CLEAN_PATTERN.matcher(text);
            while (medMatcher.find()) {
                violations.add("FORBIDDEN_MEDICAL_CLAIM:" + medMatcher.group().strip());
            }

            if (FORBIDDEN_WRONG_PRONOUN_OR_SUBJECTLESS_OPENER_PATTERN.matcher(text).matches()) {
                violations.add("FORBIDDEN_WRONG_PRONOUN_OR_SUBJECTLESS_OPENER");
            }

            if (FORBIDDEN_SELF_CONFESSION_REPETITION_PATTERN.matcher(text).matches()) {
                violations.add("FORBIDDEN_INTERNAL_REPAIR_LEAK_SELF_CONFESSION");
            }

            PackageCatalogService.PackageSummary refPkg = resolveReferencedPackageForBubble(
                    text, activePackage, scopeFilteredCatalog, allCatalog);
            if (refPkg != null) {
                String spaceLower = refPkg.customerFacingSpaceNote() != null
                        ? refPkg.customerFacingSpaceNote().toLowerCase(Locale.ROOT)
                        : "";
                boolean pkgAllowsPrivateOrVip = spaceLower.contains("phòng riêng")
                        || spaceLower.contains("phòng vip")
                        || spaceLower.contains("vip");
                if (!pkgAllowsPrivateOrVip && privateOrVipRoomPat.matcher(text).find()) {
                    violations.add("UNGROUNDED_PRIVATE_ROOM:" + refPkg.code());
                }

                String incLower = refPkg.includesSummary() != null
                        ? refPkg.includesSummary().toLowerCase(Locale.ROOT)
                        : "";
                if (!incLower.contains("thực dưỡng") && lower.contains("thực dưỡng")) {
                    violations.add("CROSS_TIER_AMENITY_MISMATCH:" + refPkg.code());
                }
                if (!incLower.contains("buffet") && lower.contains("buffet")) {
                    violations.add("UNGROUNDED_BUFFET_AMENITY:" + refPkg.code());
                }
            }

            if (heldPkgIsNull && falsePriorPkgInterestPat.matcher(text).matches()) {
                violations.add("FALSE_PRIOR_PACKAGE_INTEREST_ASSUMPTION");
            }

            if (!isBookingConfirmed && pastTenseInstructStaffPat.matcher(text).find()) {
                violations.add("UNCONFIRMED_BOOKING_PAST_TENSE_STAFF_INSTRUCTION");
            }
        }

        String combinedTurnText = combinedTurnSb.toString();
        boolean hasDownsellOfferPhrase = DOWNSELL_OFFER_PHRASE_PATTERN.matcher(combinedTurnText).matches();
        boolean hasBookingTimeQuestionPhrase = BOOKING_TIME_QUESTION_PHRASE_PATTERN.matcher(combinedTurnText).matches();

        if (hasDownsellOfferPhrase && hasBookingTimeQuestionPhrase) {
            violations.add("CONTRADICTORY_DOWNSELL_AND_BOOKING_CLOSE_IN_SAME_TURN");
        }

        if (priceObjectionCount == 1 && !explicitDownsellRequested && hasDownsellOfferPhrase) {
            violations.add("PREMATURE_DOWNSELL_ON_FIRST_OBJECTION");
        }

        if (!catalogEmpty && allowedPrices.size() >= 2) {
            long minAllowedPrice = allowedPrices.stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
            long maxAllowedPrice = allowedPrices.stream().mapToLong(Long::longValue).max().orElse(0L);
            if (minAllowedPrice < maxAllowedPrice) {
                Pattern falseLowestOrDisparagePat = Pattern.compile(
                        "(?isU).*(?:mức\\s+(?:giá\\s+|chi\\s+phí\\s+)?thấp\\s+nhất\\s+(?:bên\\s+em|toàn\\s+spa|hiện\\s+tại|cho)|"
                        + "(?:gói|mức(?:\\s+giá)?)\\s+(?:thấp|rẻ)\\s+nhất\\s+(?:toàn\\s+spa|bên\\s+em)|"
                        + "đã\\s+là\\s+(?:gói|mức(?:\\s+giá)?)\\s+(?:tiêu\\s+chuẩn\\s+)?thấp\\s+nhất|"
                        + "dưới\\s+mức\\s+(?:giá\\s+)?này\\s+chỉ\\s+làm(?:\\s+được)?\\s+(?:thư\\s+giãn\\s+)?(?:hời\\s+hợt|qua\\s+loa)|"
                        + "mức\\s+(?:giá\\s+)?thấp\\s+hơn\\s+chỉ\\s+làm(?:\\s+được)?\\s+(?:thư\\s+giãn\\s+)?(?:hời\\s+hợt|qua\\s+loa)).*"
                );
                if (falseLowestOrDisparagePat.matcher(combinedTurnText).matches()) {
                    violations.add("CONTRADICTORY_LOWEST_PRICE_CLAIM_WHEN_DOWNSELL_EXISTS");
                }
                if (priceObjectionCount >= 2 && activePackage != null && activePackage.priceVnd() == minAllowedPrice) {
                    boolean mentionedDownsell = CleanAgentCatalogAndPromptBuilder.isAnyPackagePresentedInBotText(
                            combinedTurnText, List.of(activePackage));
                    if (!mentionedDownsell) {
                        violations.add("MISSING_ECONOMICAL_PACKAGE_IN_STAGE_3_DOWNSELL:" + activePackage.code());
                    }
                }
            }
        }

        return violations;
    }

    private static PackageCatalogService.PackageSummary resolveReferencedPackageForBubble(
            String bubbleText,
            PackageCatalogService.PackageSummary activePackage,
            List<PackageCatalogService.PackageSummary> scopeFilteredCatalog,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        List<PackageCatalogService.PackageSummary> searchPool = (scopeFilteredCatalog != null && !scopeFilteredCatalog.isEmpty())
                ? scopeFilteredCatalog
                : (allCatalog != null ? allCatalog : List.of());
        if (bubbleText != null && !bubbleText.isBlank() && !searchPool.isEmpty()) {
            String low = bubbleText.toLowerCase(Locale.ROOT);
            Matcher pm = ReplyContractValidator.PRICE_PATTERN.matcher(bubbleText);
            while (pm.find()) {
                java.math.BigDecimal bd = ConversationState.parsePriceToBigDecimal(pm.group());
                if (bd != null) {
                    long vnd = bd.longValue();
                    for (PackageCatalogService.PackageSummary p : searchPool) {
                        if (p != null && p.priceVnd() == vnd) {
                            return p;
                        }
                    }
                }
            }
            for (PackageCatalogService.PackageSummary p : searchPool) {
                if (p != null && p.displayName() != null && !p.displayName().isBlank()
                        && low.contains(p.displayName().strip().toLowerCase(Locale.ROOT))) {
                    return p;
                }
            }
        }
        if (activePackage != null) {
            return activePackage;
        }
        return !searchPool.isEmpty() ? searchPool.get(0) : null;
    }

    public static boolean detectCleanCrossTurnRepetition(
            List<FanpageBotResponse.ReplyBubble> candidateBubbles,
            String prevBotTurnText,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        if (candidateBubbles == null || candidateBubbles.isEmpty()) {
            return false;
        }
        List<String> recentAssistantTurns = new ArrayList<>();
        if (prevBotTurnText != null && !prevBotTurnText.isBlank()) {
            recentAssistantTurns.add(prevBotTurnText.strip());
        }
        if (history != null && !history.isEmpty()) {
            int count = 0;
            for (int i = history.size() - 1; i >= 0 && count < 3; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t != null && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                        && t.content() != null && !t.content().isBlank()) {
                    String c = t.content().strip();
                    if (!recentAssistantTurns.contains(c)) {
                        recentAssistantTurns.add(c);
                    }
                    count++;
                }
            }
        }
        if (recentAssistantTurns.isEmpty()) {
            return false;
        }

        for (FanpageBotResponse.ReplyBubble b : candidateBubbles) {
            if (b == null || b.text() == null || b.text().isBlank()) continue;
            String bubbleText = b.text().strip();
            for (String prevTurn : recentAssistantTurns) {
                if (hasCleanBubbleTokenOverlapExceeding75Percent(bubbleText, prevTurn)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasCleanBubbleTokenOverlapExceeding75Percent(String candidateBubble, String previousBotTurnText) {
        if (candidateBubble == null || candidateBubble.isBlank() || previousBotTurnText == null || previousBotTurnText.isBlank()) {
            return false;
        }
        List<String> candParts = new ArrayList<>();
        candParts.add(candidateBubble.strip());
        for (String s : candidateBubble.split("(?<=[.!?])\\s+")) {
            if (s != null && tokenizeCleanWords(s).size() >= 6) {
                candParts.add(s.strip());
            }
        }

        List<String> prevParts = new ArrayList<>();
        prevParts.add(previousBotTurnText.strip());
        for (String part : previousBotTurnText.split("\\n\\n+")) {
            if (part != null && !part.isBlank()) {
                prevParts.add(part.strip());
                for (String s : part.split("(?<=[.!?])\\s+")) {
                    if (s != null && tokenizeCleanWords(s).size() >= 6) {
                        prevParts.add(s.strip());
                    }
                }
            }
        }

        for (String candPart : candParts) {
            List<String> candTokens = tokenizeCleanWords(candPart);
            if (candTokens.isEmpty()) continue;
            for (String prevPart : prevParts) {
                List<String> prevTokens = tokenizeCleanWords(prevPart);
                if (prevTokens.isEmpty()) continue;
                if (candTokens.size() >= 6 && prevTokens.size() < 5) continue;
                Set<String> prevSet = new LinkedHashSet<>(prevTokens);
                int hits = 0;
                for (String t : candTokens) {
                    if (prevSet.contains(t)) {
                        hits++;
                    }
                }
                double overlapRatio = hits / (double) candTokens.size();
                if (overlapRatio > 0.75d) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> tokenizeCleanWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String norm = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\d\\s]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (norm.isEmpty()) {
            return List.of();
        }
        return List.of(norm.split("\\s+"));
    }

    public static String buildCleanRepairPrompt(
            String originalPrompt,
            List<String> violations,
            String selfPronoun,
            String customerPronoun
    ) {
        return buildCleanRepairPrompt(originalPrompt, violations, selfPronoun, customerPronoun, null);
    }

    public static String buildCleanRepairPrompt(
            String originalPrompt,
            List<String> violations,
            String selfPronoun,
            String customerPronoun,
            PackageCatalogService.PackageSummary activePackage
    ) {
        StringBuilder sb = new StringBuilder(originalPrompt.length() + 512);
        sb.append(originalPrompt).append("\n\n<REPAIR_INSTRUCTION>\n");
        sb.append("FAST_TARGETED_REPAIR_HINT: Bản nháp vừa rồi vi phạm các quy tắc kiểm định: ").append(violations).append(".\n");
        if (violations.stream().anyMatch(v -> v.startsWith("CROSS_TURN_REPETITION") || v.startsWith("FORBIDDEN_INTERNAL_REPETITION") || v.startsWith("FORBIDDEN_INTERNAL_REPAIR_LEAK"))) {
            sb.append("- CHỈ DẪN KỸ THUẬT NỘI BỘ: Khách hàng KHÔNG phàn nàn về việc lặp lời. Tuyệt đối CẤM viết câu xin lỗi kiểu 'tại em lỡ miệng nhắc lại thông tin cũ'. Hãy trả lời thẳng vào tin nhắn hiện tại của khách bằng cách diễn đạt mới.\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("FORBIDDEN_WRONG_PRONOUN_OR_SUBJECTLESS_OPENER"))) {
            sb.append("- Bắt buộc tự xưng là \"").append(selfPronoun).append("\" và gọi khách là \"").append(customerPronoun)
              .append("\". Tuyệt đối KHÔNG viết \"Dạ mình hiểu\", \"Dạ mình đã hiểu rồi ạ\" hay \"Dạ hiểu rồi ạ\".\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("UNGROUNDED_PRIVATE_ROOM") || v.startsWith("CROSS_TIER_AMENITY_MISMATCH") || v.startsWith("UNGROUNDED_BUFFET_AMENITY"))) {
            String spaceInfo = activePackage != null ? activePackage.customerFacingSpaceNote() : "đúng mục Không gian/Hình thức của gói";
            String incInfo = activePackage != null ? activePackage.includesSummary() : "đúng mục Quyền lợi đi kèm của gói";
            sb.append("- BẮT BUỘC chỉ nêu đúng Không gian/Hình thức (\"").append(spaceInfo)
              .append("\") và Quyền lợi đi kèm (\"").append(incInfo)
              .append("\") của gói đang tư vấn; tuyệt đối KHÔNG bịa 'phòng riêng/VIP', 'buffet' hay tiện ích của gói khác.\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("UNCONFIRMED_BOOKING_PAST_TENSE_STAFF_INSTRUCTION"))) {
            sb.append("- Khi khách chưa chốt khung giờ đặt lịch (bookingStatus != CONFIRMED), TUYỆT ĐỐI CẤM nói quá khứ \"").append(selfPronoun)
              .append(" đã dặn chuyên viên\". Hãy nói \"").append(selfPronoun).append(" lưu lại để khi mình ghé sẽ dặn chuyên viên\".\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("FALSE_PRIOR_PACKAGE_INTEREST_ASSUMPTION"))) {
            sb.append("- Khách chưa biết tên gói dịch vụ, tuyệt đối CẤM viết \"").append(customerPronoun)
              .append(" đang quan tâm đến gói...\". Hãy chủ động đề xuất bằng cụm \"").append(selfPronoun)
              .append(" gợi ý ").append(customerPronoun).append(" tham khảo gói...\".\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("PREMATURE_DOWNSELL_ON_FIRST_OBJECTION"))) {
            sb.append("- Ở lần băn khoăn giá đầu tiên (priceObjectionCount == 1), TUYỆT ĐỐI CẤM tự gạ 'gói tiết kiệm hơn / gói rẻ hơn'. Hãy giữ nguyên giá trị gói đang tư vấn và mời khách thử trải nghiệm 1 lần.\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("CONTRADICTORY_DOWNSELL_AND_BOOKING_CLOSE_IN_SAME_TURN"))) {
            sb.append("- Tuyệt đối CẤM vừa gạ đổi gói tiết kiệm hơn vừa hỏi khung giờ đặt lịch trong cùng một lượt trả lời.\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("FORBIDDEN_MEDICAL_CLAIM"))) {
            sb.append("- Tuyệt đối KHÔNG cam kết chữa khỏi bệnh lý y khoa lâm sàng (\"chữa khỏi bệnh\", \"điều trị dứt điểm bệnh\").\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("CONTRADICTORY_LOWEST_PRICE_CLAIM_WHEN_DOWNSELL_EXISTS") || v.startsWith("MISSING_ECONOMICAL_PACKAGE_IN_STAGE_3_DOWNSELL"))) {
            String econHint = activePackage != null
                    ? (activePackage.displayName() + " " + activePackage.durationLabel() + " (" + activePackage.priceDisplay() + ")")
                    : "gói tiết kiệm trong [NHÓM 2]";
            sb.append("- Khi khách phản đối giá lần 2 và trong [NHÓM 2] có sẵn gói tiết kiệm (").append(econHint)
              .append("), BẮT BUỘC phải giới thiệu gói ").append(econHint)
              .append(" và giải thích trung thực sự khác biệt phạm vi vùng chăm sóc. TUYỆT ĐỐI CẤM nói gói 399k là 'mức thấp nhất bên em có' và CẤM chê mức giá thấp hơn 399k là 'chỉ làm được thư giãn hời hợt'.\n");
        }
        if (violations.stream().anyMatch(v -> v.contains("PRICE") || v.contains("DURATION") || v.contains("EXCLUDED_PACKAGE"))) {
            sb.append("- BẮT BUỘC chỉ sử dụng đúng tên gói, thời lượng và mức giá nằm trong <SCOPE_FILTERED_CATALOG>, tuyệt đối không bịa giá khi Catalog trống và không nhắc tới gói đã bị loại.\n");
        }
        sb.append("Trả về JSON hợp lệ.\n</REPAIR_INSTRUCTION>");
        return sb.toString();
    }

    public static String sanitizeMinorCleanAgentWordingInPlace(String text, String selfPronoun) {
        return sanitizeMinorCleanAgentWordingInPlace(text, selfPronoun, "mình", null, true, "NONE");
    }

    public static String sanitizeMinorCleanAgentWordingInPlace(
            String text,
            String selfPronoun,
            PackageCatalogService.PackageSummary activePackage
    ) {
        return sanitizeMinorCleanAgentWordingInPlace(text, selfPronoun, "mình", activePackage, true, "NONE");
    }

    public static String sanitizeMinorCleanAgentWordingInPlace(
            String text,
            String selfPronoun,
            String customerPronoun,
            PackageCatalogService.PackageSummary activePackage,
            boolean heldPkgIsNull
    ) {
        return sanitizeMinorCleanAgentWordingInPlace(text, selfPronoun, customerPronoun, activePackage, heldPkgIsNull, "NONE");
    }

    public static String sanitizeMinorCleanAgentWordingInPlace(
            String text,
            String selfPronoun,
            String customerPronoun,
            PackageCatalogService.PackageSummary activePackage,
            boolean heldPkgIsNull,
            String bookingStatus
    ) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sp = (selfPronoun != null && !selfPronoun.isBlank()) ? selfPronoun.strip() : "em";
        String cp = (customerPronoun != null && !customerPronoun.isBlank()) ? customerPronoun.strip() : "mình";
        String out = text.strip();
        out = out.replaceAll(
                "(?iU)^\\s*(?:dạ[,\\s]+)?(?:mình\\s+(?:đã\\s+|rất\\s+|cũng\\s+)?hiểu(?:\\s+rồi)?|(?:đã\\s+)?hiểu\\s+rồi)(?:\\s+ạ)?(?=\\s*[.,!?;:]|$)",
                "Dạ " + sp + " hiểu ạ"
        );
        out = out.replaceAll(
                "(?iU)^\\s*(?:dạ[,\\s]+)?(?:mình\\s+(?:đã\\s+|rất\\s+|cũng\\s+)?hiểu(?:\\s+rồi)?|(?:đã\\s+)?hiểu\\s+rồi)\\b",
                "Dạ " + sp + " hiểu"
        );
        out = out.replaceAll(
                "(?iU)^(\\s*(?:dạ[,\\s]+)?)mình\\s+((?:đã\\s+|rất\\s+|cũng\\s+)?(?:biết|thấy|ghi\\s+nhận|nắm\\s+rõ|xin\\s+lỗi))\\b",
                "Dạ " + sp + " $2"
        );

        if (heldPkgIsNull) {
            out = out.replaceAll(
                    "(?iU)\\b(mình|anh|chị|bạn)\\s+đang\\s+quan\\s+tâm\\s+(?:đến\\s+|tới\\s+)?gói\\b",
                    sp + " gợi ý " + cp + " tham khảo gói"
            );
        }

        if (bookingStatus == null || !"CONFIRMED".equalsIgnoreCase(bookingStatus.strip())) {
            Pattern pastStaffInstructPat = Pattern.compile(
                    "(?iU)\\b(em)\\s+đã\\s+(?:dặn|báo|nhắc|yêu\\s+cầu)(?:\\s+trước)?\\s+(?:các\\s+|bên\\s+)?(?:chuyên\\s+viên|kỹ\\s+thuật\\s+viên|nhân\\s+viên)\\b"
            );
            Matcher mStaff = pastStaffInstructPat.matcher(out);
            StringBuilder sbStaff = new StringBuilder();
            while (mStaff.find()) {
                String matchedEm = mStaff.group(1);
                boolean isUpper = matchedEm != null && !matchedEm.isEmpty() && Character.isUpperCase(matchedEm.charAt(0));
                String rep = (isUpper ? "Em" : "em") + " lưu lại để khi mình ghé sẽ dặn chuyên viên";
                mStaff.appendReplacement(sbStaff, Matcher.quoteReplacement(rep));
            }
            mStaff.appendTail(sbStaff);
            out = sbStaff.toString();
        }

        if (activePackage != null) {
            if (activePackage.priceVnd() > 0L && activePackage.priceVnd() < 350_000L
                    && activePackage.displayName() != null && !activePackage.displayName().isBlank()) {
                Pattern falseLowestSentencePat = Pattern.compile(
                        "(?isU)[^.!?]*(?:mức\\s+(?:giá\\s+|chi\\s+phí\\s+)?thấp\\s+nhất\\s+(?:bên\\s+em|hiện\\s+tại|cho)|"
                        + "dưới\\s+mức\\s+(?:giá\\s+)?này\\s+chỉ\\s+làm(?:\\s+được)?\\s+(?:thư\\s+giãn\\s+)?(?:hời\\s+hợt|qua\\s+loa))[^.!?]*[.!?]?"
                );
                if (falseLowestSentencePat.matcher(out).find()) {
                    String honestDownsellSentence = "Nếu " + cp + " muốn ưu tiên phương án tiết kiệm hơn tập trung chăm sóc vùng đầu – cổ – vai – gáy thì bên "
                            + sp + " cũng có " + activePackage.displayName().strip() + " "
                            + (activePackage.durationLabel() != null ? activePackage.durationLabel().strip() + " " : "")
                            + "(" + activePackage.priceDisplay() + " trọn gói"
                            + (activePackage.includesSummary() != null && !activePackage.includesSummary().isBlank()
                                ? ", bao gồm " + activePackage.includesSummary().strip()
                                : "")
                            + ") ạ.";
                    out = falseLowestSentencePat.matcher(out).replaceAll(Matcher.quoteReplacement(honestDownsellSentence));
                }
            }
            String spaceNote = activePackage.customerFacingSpaceNote() != null
                    ? activePackage.customerFacingSpaceNote().strip()
                    : "";
            String spaceLower = spaceNote.toLowerCase(Locale.ROOT);
            boolean allowsPrivateOrVip = spaceLower.contains("phòng riêng")
                    || spaceLower.contains("phòng vip")
                    || spaceLower.contains("vip");
            if (!spaceNote.isEmpty() && !allowsPrivateOrVip) {
                out = out.replaceAll(
                        "(?iU)\\bphòng\\s+(?:vip(?:\\s+riêng(?:\\s+tư)?)?|riêng(?:\\s+tư|\\s+kín\\s+đáo)?)\\b",
                        Matcher.quoteReplacement(spaceNote)
                );
            }

            String incSummary = activePackage.includesSummary() != null
                    ? activePackage.includesSummary().strip()
                    : "";
            String incLower = incSummary.toLowerCase(Locale.ROOT);
            if (!incSummary.isEmpty() && !incLower.contains("thực dưỡng") && out.toLowerCase(Locale.ROOT).contains("thực dưỡng")) {
                out = out.replaceAll(
                        "(?iU)\\b(?:buffet\\s+thực\\s+dưỡng|buffet\\s+ăn\\s+nhẹ|bữa\\s+ăn\\s+nhẹ\\s+thực\\s+dưỡng|phần\\s+ăn\\s+nhẹ\\s+thực\\s+dưỡng|ăn\\s+nhẹ\\s+thực\\s+dưỡng|thực\\s+dưỡng)\\b",
                        Matcher.quoteReplacement(incSummary)
                );
            } else if (!incSummary.isEmpty() && !incLower.contains("buffet")
                    && out.toLowerCase(Locale.ROOT).contains("buffet")) {
                String catalogFoodWording = incLower.contains("ăn nhẹ thực dưỡng")
                        ? "bữa ăn nhẹ thực dưỡng"
                        : incSummary;
                out = out.replaceAll(
                        "(?iU)\\b(?:quầy\\s+|bữa\\s+|tiệc\\s+)?buffet(?:\\s+ăn\\s+nhẹ)?(?:\\s+thực\\s+dưỡng)?\\b",
                        Matcher.quoteReplacement(catalogFoodWording)
                );
            }
        }
        return out.strip();
    }

    public static List<FanpageBotResponse.ReplyBubble> splitNaturalTrialInviteBubbleIfApplicable(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            String cleanStage,
            int minDelayMs
    ) {
        if (bubbles == null || bubbles.isEmpty() || bubbles.size() >= 3) {
            return bubbles != null ? bubbles : List.of();
        }
        boolean isStage1Or2 = "STAGE_1_DISCOVERY_AND_FIRST_QUOTE".equals(cleanStage)
                || "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(cleanStage);
        if (!isStage1Or2) {
            return bubbles;
        }
        FanpageBotResponse.ReplyBubble lastBubble = bubbles.get(bubbles.size() - 1);
        if (lastBubble == null || lastBubble.text() == null) {
            return bubbles;
        }
        String text = lastBubble.text().strip();
        if (!text.endsWith("?")) {
            return bubbles;
        }
        String[] totalWords = text.split("\\s+");
        if (totalWords.length < 16) {
            return bubbles;
        }
        int lastDotIdx = text.lastIndexOf(". ");
        if (lastDotIdx <= 0) {
            return bubbles;
        }
        String descPart = text.substring(0, lastDotIdx + 1).strip();
        String questionPart = text.substring(lastDotIdx + 2).strip();
        if (descPart.split("\\s+").length < 6 || questionPart.split("\\s+").length < 5) {
            return bubbles;
        }
        String qLow = questionPart.toLowerCase(Locale.ROOT);
        boolean isConditionalOrDependent = qLow.startsWith("nếu ")
                || qLow.startsWith("không biết ")
                || qLow.startsWith("trường hợp ")
                || qLow.startsWith("vì ")
                || qLow.startsWith("do ")
                || (qLow.contains("không biết") && qLow.contains(" hay "));
        if (isConditionalOrDependent) {
            return bubbles;
        }
        boolean isTrialOrFeedbackInvite = qLow.contains("thử")
                || qLow.contains("trải nghiệm")
                || qLow.contains("cảm nhận")
                || qLow.contains("kiểm chứng")
                || qLow.contains("thế nào")
                || qLow.contains("sao ạ")
                || qLow.endsWith("nhé?")
                || qLow.endsWith("nha?")
                || qLow.endsWith("không ạ?")
                || qLow.endsWith("chưa ạ?");
        if (!isTrialOrFeedbackInvite) {
            return bubbles;
        }
        List<FanpageBotResponse.ReplyBubble> updated = new ArrayList<>(bubbles.subList(0, bubbles.size() - 1));
        updated.add(new FanpageBotResponse.ReplyBubble(descPart, lastBubble.typingDelayMs()));
        updated.add(new FanpageBotResponse.ReplyBubble(questionPart, Math.max(minDelayMs, 1200)));
        return updated;
    }

    public static List<FanpageBotResponse.ReplyBubble> splitClosingWishBubbleIfApplicable(
            List<FanpageBotResponse.ReplyBubble> bubbles,
            String cleanStage,
            int minDelayMs
    ) {
        if (bubbles == null || bubbles.size() != 1) {
            return bubbles != null ? bubbles : List.of();
        }
        if (!"STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(cleanStage)) {
            return bubbles;
        }
        FanpageBotResponse.ReplyBubble onlyBubble = bubbles.get(0);
        if (onlyBubble == null || onlyBubble.text() == null || onlyBubble.text().isBlank()) {
            return bubbles;
        }
        String text = onlyBubble.text().strip();
        Matcher m = STAGE6_CLOSING_WISH_SPLIT_PATTERN.matcher(text);
        if (!m.matches()) {
            return bubbles;
        }
        String rawFirstPart = m.group(1).strip();
        String rawWishPart = m.group(2).strip();
        String firstPartBody = rawFirstPart.replaceAll("[,.;]+\\s*$", "").strip();
        if (firstPartBody.split("\\s+").length < 4 || rawWishPart.split("\\s+").length < 4) {
            return bubbles;
        }
        String wishLower = rawWishPart.toLowerCase(Locale.ROOT);
        if (wishLower.matches("(?isU).*\\b(đã\\s+lưu|cứ\\s+nhắn|liệu\\s+trình|gói\\s+dịch\\s+vụ|đặt\\s+lịch|tư\\s+vấn|hỗ\\s+trợ\\s+(?:mình|anh|chị|bạn))\\b.*")) {
            return bubbles;
        }
        String firstBubbleText = (firstPartBody.endsWith(".") || firstPartBody.endsWith("!"))
                ? firstPartBody
                : (firstPartBody + ".");
        String secondBubbleText = BubbleCapHook.capitalizeFirstLetter(rawWishPart);
        if (!secondBubbleText.endsWith(".") && !secondBubbleText.endsWith("!")) {
            secondBubbleText = secondBubbleText + ".";
        }
        int secondDelay = Math.max(minDelayMs > 0 ? minDelayMs : 1200, 1200);
        return List.of(
                new FanpageBotResponse.ReplyBubble(firstBubbleText, onlyBubble.typingDelayMs()),
                new FanpageBotResponse.ReplyBubble(secondBubbleText, secondDelay)
        );
    }

    public static String validateCleanCareNoteDelta(
            String candidateNote,
            String customerMessage,
            String botReply
    ) {
        return validateCleanCareNoteDelta(candidateNote, null, null, customerMessage, botReply);
    }

    public static String validateCleanCareNoteDelta(
            String candidateNote,
            List<PackageCatalogService.PackageSummary> excludedCatalog
    ) {
        return validateCleanCareNoteDelta(candidateNote, excludedCatalog, null, null, null);
    }

    public static String validateCleanCareNoteDelta(
            String candidateNote,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            PackageCatalogService.PackageSummary activePackage
    ) {
        return validateCleanCareNoteDelta(candidateNote, excludedCatalog, activePackage, null, null);
    }

    public static String validateCleanCareNoteDelta(
            String candidateNote,
            List<PackageCatalogService.PackageSummary> excludedCatalog,
            PackageCatalogService.PackageSummary activePackage,
            String customerMessage,
            String botReply
    ) {
        if (candidateNote == null || candidateNote.isBlank()) {
            return "";
        }
        String trimmed = candidateNote.strip();
        if (FORBIDDEN_MEDICAL_CLAIM_CLEAN_PATTERN.matcher(trimmed).find()) {
            return "";
        }
        String[] clauses = trimmed.split("\\s*[,;.–—]\\s*");
        List<String> keptClauses = new ArrayList<>();
        boolean anyClauseRemoved = false;
        String custLower = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        String botLower = botReply != null ? botReply.toLowerCase(Locale.ROOT) : "";
        for (String clause : clauses) {
            if (clause == null || clause.isBlank()) continue;
            String c = clause.strip();
            String cLow = c.toLowerCase(Locale.ROOT);
            if (BOT_PROMOTIONAL_OR_EFFICACY_CARE_NOTE_CLAUSE_PATTERN.matcher(c).find()) {
                if (custLower.isEmpty() || !custLower.contains(cLow)) {
                    anyClauseRemoved = true;
                    continue;
                }
            }
            if (!botLower.isEmpty() && botLower.contains(cLow) && !custLower.contains(cLow)
                    && !CUSTOMER_CARE_PROVENANCE_SIGNAL_PATTERN.matcher(c).find()) {
                anyClauseRemoved = true;
                continue;
            }
            keptClauses.add(c);
        }
        if (keptClauses.isEmpty()) {
            return "";
        }
        if (anyClauseRemoved) {
            trimmed = String.join(", ", keptClauses).strip();
        }
        if (!CUSTOMER_CARE_PROVENANCE_SIGNAL_PATTERN.matcher(trimmed).find()) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (excludedCatalog != null) {
            for (PackageCatalogService.PackageSummary ex : excludedCatalog) {
                if (ex == null) continue;
                if (ex.displayName() != null && ex.displayName().strip().length() >= 5
                        && lower.contains(ex.displayName().strip().toLowerCase(Locale.ROOT))) {
                    return "";
                }
                if (ex.code() != null && !ex.code().isBlank()
                        && Pattern.compile("(?iU)\\b" + Pattern.quote(ex.code().strip()) + "\\b").matcher(trimmed).find()) {
                    return "";
                }
            }
        }
        if (activePackage != null) {
            String spaceLower = activePackage.customerFacingSpaceNote() != null
                    ? activePackage.customerFacingSpaceNote().toLowerCase(Locale.ROOT)
                    : "";
            boolean allowsPrivateOrVip = spaceLower.contains("phòng riêng")
                    || spaceLower.contains("phòng vip")
                    || spaceLower.contains("vip");
            if (!allowsPrivateOrVip && Pattern.compile("(?iU)\\bphòng\\s+(?:vip|riêng)\\b").matcher(trimmed).find()) {
                return "";
            }
        }
        return trimmed;
    }

    public static String resolveActualSentSpeechAct(
            String turnStage,
            List<FanpageBotResponse.ReplyBubble> finalBubbles,
            String bookingStatus,
            String llmIntendedSpeechAct
    ) {
        String combinedText = finalBubbles != null
                ? String.join(" ", finalBubbles.stream()
                        .filter(Objects::nonNull)
                        .map(FanpageBotResponse.ReplyBubble::text)
                        .filter(Objects::nonNull)
                        .toList()).strip()
                : "";
        if ("CONFIRMED".equalsIgnoreCase(bookingStatus)
                && !"STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equalsIgnoreCase(turnStage)
                && !"STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equalsIgnoreCase(turnStage)) {
            return CleanAgentOrchestratorService.SPEECH_ACT_BOOKING_CONFIRMED;
        }
        if ("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equalsIgnoreCase(turnStage)
                || "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equalsIgnoreCase(turnStage)
                || CleanAgentOrchestratorService.isPauseOrDeferralBotReply(combinedText)) {
            return CleanAgentOrchestratorService.SPEECH_ACT_RESPECTED_PAUSE;
        }
        if (CleanAgentOrchestratorService.isOfferPackageDetailsBotReply(combinedText)) {
            return CleanAgentOrchestratorService.SPEECH_ACT_OFFERED_DETAILS;
        }
        if (CleanAgentOrchestratorService.isInviteTrialOrAskTimeBotReply(combinedText)
                || "STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equalsIgnoreCase(turnStage)) {
            return CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME;
        }
        if (llmIntendedSpeechAct != null && !llmIntendedSpeechAct.isBlank()) {
            String upper = llmIntendedSpeechAct.strip().toUpperCase(Locale.ROOT);
            if (Set.of(
                    CleanAgentOrchestratorService.SPEECH_ACT_RESPECTED_PAUSE,
                    CleanAgentOrchestratorService.SPEECH_ACT_OFFERED_DETAILS,
                    CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                    CleanAgentOrchestratorService.SPEECH_ACT_BOOKING_CONFIRMED,
                    CleanAgentOrchestratorService.SPEECH_ACT_GENERAL_CONSULTATION
            ).contains(upper)) {
                return upper;
            }
        }
        return CleanAgentOrchestratorService.SPEECH_ACT_GENERAL_CONSULTATION;
    }
}
