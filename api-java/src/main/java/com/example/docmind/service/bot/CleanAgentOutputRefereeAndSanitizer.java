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
            "(?iU)^\\s*(?:dạ[,\\s]+)?(?:mình\\s+(?:đã\\s+|rất\\s+|cũng\\s+)?(?:hiểu|biết|ghi\\s+nhận|nắm\\s+rõ|xin\\s+lỗi)|(?:đã\\s+)?hiểu\\s+rồi|em\\s+hiểu\\s+mình\\s+đang\\s+cân\\s+nhắc)\\b.*"
    );

    private static final Pattern CATALOG_COMPONENT_DURATION_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{1,3})\\s*(?:phút|p)\\b"
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
            + "chuyên\\s+viên\\s+lành\\s+nghề|ấn\\s+miết\\s+sâu|mang\\s+lại\\s+cảm\\s+nhận|em\\s+gợi\\s+ý|bên\\s+em\\s+(?:có|cam\\s+kết)|"
            + "mức\\s+giá\\s+đã\\s+bao\\s+gồm|đã\\s+lưu\\s+lại\\s+thông\\s+tin|cứ\\s+cân\\s+nhắc\\s+thêm|"
            + "cam\\s+kết\\s+chính\\s+hãng|chính\\s+hãng\\s+loại\\s+1|độ\\s+bền\\s+vượt\\s+trội|chống\\s+thấm\\s+tuyệt\\s+đối|"
            + "cam\\s+kết\\s+đầu\\s+ra|lộ\\s+trình\\s+chuẩn\\s+quốc\\s+tế|giao\\s+hàng\\s+nhanh(?:\\s+toàn\\s+quốc)?|"
            + "giá\\s+tốt\\s+nhất\\s+thị\\s+trường|chất\\s+lượng\\s+hàng\\s+đầu|ưu\\s+đãi\\s+hấp\\s+dẫn|nâng\\s+tầm\\s+đẳng\\s+cấp)\\b"
    );

    private static final Pattern UNCONFIRMED_BOT_SPECULATION_CARE_NOTE_PATTERN = Pattern.compile(
            "(?iU)\\b(có\\s+thể\\s+khách|chắc\\s+(?:là\\s+)?khách|suy\\s+đoán\\s+khách|đoán\\s+(?:là\\s+)?khách|khả\\s+năng\\s+cao\\s+khách|nghi\\s+ngờ\\s+khách)\\b"
    );

    private static final Pattern PROVENANCE_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}xX.]+");

    private static final Set<String> PROVENANCE_STOPWORDS = Set.of(
            "khách", "hàng", "cần", "muốn", "đang", "được", "cho", "với", "của", "và", "là", "có", "không",
            "này", "đó", "các", "những", "một", "bên", "shop", "mình", "anh", "chị", "bạn", "em", "rất", "hơn"
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
        boolean hasMsg = customerMessage != null && !customerMessage.isBlank();
        boolean inferredCurrentTurnHasPriceObjection = !hasMsg
                ? (priceObjectionCount >= 1 || explicitDownsellRequested)
                : (CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(customerMessage, true)
                    || CleanAgentOrchestratorService.isExplicitDownsellOrLowBudgetRequest(customerMessage, null));
        boolean inferredHigherPriorityIntent = hasMsg
                && (CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(customerMessage)
                    || (CleanAgentOrchestratorService.isNormalPriceOrDetailInquiryMessage(customerMessage)
                        && !inferredCurrentTurnHasPriceObjection)
                    || (CleanAgentOrchestratorService.isPromoOrDiscountInquiryMessage(customerMessage)
                        && !inferredCurrentTurnHasPriceObjection));
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
                customerMessage,
                inferredCurrentTurnHasPriceObjection,
                false,
                inferredHigherPriorityIntent
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
            String bookingStatus,
            String customerMessage,
            boolean currentTurnHasPriceObjection,
            boolean downsellAlreadyOfferedInHistory,
            boolean hasHigherPriorityIntent
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
                allowedDurations.addAll(extractComponentDurationsFromCatalogPackage(p));
            }
            if (activePackage != null) {
                allowedDurations.addAll(extractComponentDurationsFromCatalogPackage(activePackage));
            }
            if (excludedCatalog != null) {
                for (PackageCatalogService.PackageSummary ex : excludedCatalog) {
                    if (ex != null && ex.priceVnd() > 0L && !allowedPrices.contains(ex.priceVnd())) {
                        excludedPrices.add(ex.priceVnd());
                    }
                }
            }
            for (PackageCatalogService.PackageSummary p : allCatalog) {
                if (p != null) {
                    allowedDurations.addAll(extractComponentDurationsFromCatalogPackage(p));
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

        Pattern durPat = Pattern.compile("(?iU)\\b(\\d{2,3})\\s*(?:phút|p)\\b");
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

            if (!catalogEmpty) {
                List<String> entityMismatchFlags = detectPriceEntityMismatchesInBubble(text, allCatalog);
                for (String flag : entityMismatchFlags) {
                    if (!violations.contains(flag)) {
                        violations.add(flag);
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
                String maskedForExcl = lower;
                if (scopeFilteredCatalog != null && !scopeFilteredCatalog.isEmpty()) {
                    List<PackageCatalogService.PackageSummary> sortedAllowed = scopeFilteredCatalog.stream()
                            .filter(p -> p != null && p.displayName() != null && !p.displayName().isBlank())
                            .sorted((a, b2) -> Integer.compare(b2.displayName().strip().length(), a.displayName().strip().length()))
                            .toList();
                    for (PackageCatalogService.PackageSummary allowPkg : sortedAllowed) {
                        String allowLow = allowPkg.displayName().strip().toLowerCase(Locale.ROOT);
                        if (allowLow.length() >= 5) {
                            maskedForExcl = maskedForExcl.replace(allowLow, " ");
                        }
                    }
                }
                for (PackageCatalogService.PackageSummary ex : excludedCatalog) {
                    if (ex == null || ex.displayName() == null || ex.displayName().isBlank()) continue;
                    String exNameLower = ex.displayName().strip().toLowerCase(Locale.ROOT);
                    if (exNameLower.length() >= 5 && maskedForExcl.contains(exNameLower)) {
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
                // Checkpoint 1.2: MISSING_ECONOMICAL_PACKAGE_IN_STAGE_3_DOWNSELL chỉ kích hoạt khi hội đủ cả 4 điều kiện:
                // (1) Lượt hiện tại có phản đối giá hoặc xin gói rẻ hơn (currentTurnHasPriceObjection == true)
                // (2) Quy tắc downsell phù hợp với ý định lượt hiện tại (explicitDownsellRequested == true, priceObjectionCount >= 2)
                // (3) Gói tiết kiệm phù hợp (DS02 - 239k) chưa được giới thiệu trong lịch sử (!downsellAlreadyOfferedInHistory)
                // (4) Không có ý định ưu tiên cao hơn cần trả lời trước (!hasHigherPriorityIntent)
                if (currentTurnHasPriceObjection
                        && explicitDownsellRequested
                        && !downsellAlreadyOfferedInHistory
                        && !hasHigherPriorityIntent
                        && priceObjectionCount >= 2
                        && activePackage != null
                        && activePackage.priceVnd() == minAllowedPrice) {
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

    /**
     * Checkpoint 1.3: Đối chiếu cặp {@code (canonicalName, price, duration)} trong từng mệnh đề với danh sách đối tượng
     * cấu trúc {@code Catalog}. Nếu tên gói và mức giá/thời lượng trong cùng một khẳng định thuộc về 2 gói khác nhau
     * (ví dụ: {@code "gói Massage cơ bản 60 phút bên em đang áp dụng mức giá ưu đãi 239k"}), gắn cờ {@code PRICE_ENTITY_MISMATCH}.
     */
    public static List<String> detectPriceEntityMismatchesInBubble(
            String bubbleText,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (bubbleText == null || bubbleText.isBlank() || allCatalog == null || allCatalog.isEmpty()) {
            return List.of();
        }
        List<PackageCatalogService.PackageSummary> sortedCatalog = allCatalog.stream()
                .filter(p -> p != null && !p.isAddon() && p.displayName() != null && !p.displayName().isBlank())
                .sorted((a, b) -> Integer.compare(b.displayName().strip().length(), a.displayName().strip().length()))
                .toList();
        if (sortedCatalog.isEmpty()) {
            return List.of();
        }

        List<String> flags = new ArrayList<>();
        String[] sentences = bubbleText.split("[.!?;\n]+");
        Pattern clauseSplitPat = Pattern.compile(
                "(?iU)(?:,\\s*(?:còn|trong\\s+khi|nhưng|thay\\s+vào\\s+đó|hiện\\s+tại|tuy\\s+nhiên|bên\\s+cạnh\\s+đó)\\b|\\b(?:còn|trong\\s+khi|và|hoặc|hay|so\\s+với|ngoài\\s+ra\\s+có)\\s+(?=gói\\b))"
        );

        for (String sentence : sentences) {
            if (sentence == null || sentence.isBlank()) continue;
            String[] subClauses = clauseSplitPat.split(sentence);
            for (String rawClause : subClauses) {
                if (rawClause == null || rawClause.isBlank()) continue;
                String clause = rawClause.strip();
                String maskedLower = clause.toLowerCase(Locale.ROOT);

                List<PackageCatalogService.PackageSummary> mentionedPkgs = new ArrayList<>();
                for (PackageCatalogService.PackageSummary p : sortedCatalog) {
                    String dispLower = p.displayName().strip().toLowerCase(Locale.ROOT);
                    String dispNoPrefix = dispLower.replaceFirst("^gói\\s+", "").strip();
                    if (dispLower.length() < 4) continue;
                    if (maskedLower.contains(dispLower)) {
                        mentionedPkgs.add(p);
                        maskedLower = maskedLower.replace(dispLower, " ");
                    } else if (dispNoPrefix.length() >= 6 && maskedLower.contains(dispNoPrefix)) {
                        mentionedPkgs.add(p);
                        maskedLower = maskedLower.replace(dispNoPrefix, " ");
                    }
                }
                if (mentionedPkgs.size() != 1) {
                    continue;
                }
                PackageCatalogService.PackageSummary matchedPkg = mentionedPkgs.get(0);
                String pkgNameLower = matchedPkg.displayName().strip().toLowerCase(Locale.ROOT);
                String pkgNoPrefixLower = pkgNameLower.replaceFirst("^gói\\s+", "").strip();

                // Kiểm tra thời lượng đi liền ngay sau tên gói (nếu có)
                Pattern adjacentDurPat = Pattern.compile(
                        "(?iU)(?:gói\\s+)?" + Pattern.quote(pkgNoPrefixLower) + "\\s+~?\\s*(\\d{2,3})\\s*(?:phút|p)\\b"
                );
                Matcher adjDurMatcher = adjacentDurPat.matcher(clause);
                if (adjDurMatcher.find() && matchedPkg.durationMinutes() != null && matchedPkg.durationMinutes() > 0) {
                    try {
                        int statedMin = Integer.parseInt(adjDurMatcher.group(1));
                        boolean anySameNameWithDur = sortedCatalog.stream()
                                .anyMatch(p -> p.displayName().strip().equalsIgnoreCase(matchedPkg.displayName().strip())
                                        && p.durationMinutes() != null && p.durationMinutes() == statedMin);
                        if (!anySameNameWithDur && statedMin != matchedPkg.durationMinutes()) {
                            if (!flags.contains("PRICE_ENTITY_MISMATCH")) {
                                flags.add("PRICE_ENTITY_MISMATCH");
                            }
                            flags.add("PRICE_ENTITY_MISMATCH:" + matchedPkg.code()
                                    + ":expectedDuration=" + matchedPkg.durationMinutes() + "p,actual=" + statedMin + "p");
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }

                // Kiểm tra mức giá khẳng định trong cùng mệnh đề với tên gói
                Matcher pm = CLEAN_PRICE_MENTION_PATTERN.matcher(clause);
                while (pm.find()) {
                    Long priceVnd = parseCleanPriceMentionToVnd(pm.group());
                    if (priceVnd == null || priceVnd <= 0L) continue;
                    String beforePrice = clause.substring(0, pm.start()).toLowerCase(Locale.ROOT);
                    String afterPrice = clause.substring(pm.end()).toLowerCase(Locale.ROOT);
                    boolean isNegatedOrComparative = beforePrice.matches(
                            "(?isU).*(?:(?:chưa|không|ko|chẳng)\\s+(?:có|hỗ\\s+trợ|triển\\s+khai|áp\\s+dụng|mở)|(?:dưới|thấp\\s+hơn|trên|cao\\s+hơn|so\\s+với)\\s+(?:mức\\s+|gói\\s+)?)[^,]*$")
                            || afterPrice.matches("(?isU)^[^,]{0,40}?\\b(?:chưa|không|ko|chẳng)\\s+(?:có|hỗ\\s+trợ|triển\\s+khai|áp\\s+dụng|mở)\\b.*");
                    if (isNegatedOrComparative) {
                        continue;
                    }
                    boolean anySameNameWithPrice = sortedCatalog.stream()
                            .anyMatch(p -> p.displayName().strip().equalsIgnoreCase(matchedPkg.displayName().strip())
                                    && p.priceVnd() == priceVnd);
                    if (!anySameNameWithPrice && matchedPkg.priceVnd() != priceVnd) {
                        if (!flags.contains("PRICE_ENTITY_MISMATCH")) {
                            flags.add("PRICE_ENTITY_MISMATCH");
                        }
                        flags.add("PRICE_ENTITY_MISMATCH:" + matchedPkg.code()
                                + ":expected=" + matchedPkg.priceDisplay() + ",actual=" + pm.group().strip());
                    }
                }
            }
        }
        return flags;
    }

    /**
     * Checkpoint 1.3 & Nguyên tắc 1 + 3: Dựng lại bong bóng trực tiếp từ đối tượng cấu trúc {@code Catalog}
     * ({@code canonicalName + durationLabel + priceDisplay + includesSummary + customerFacingSpaceNote} lấy từ cùng 1 item)
     * khi đầu ra vi phạm {@code PRICE_ENTITY_MISMATCH} sau tối đa 1 lần gọi lại LLM (tuyệt đối không dùng regex thay thế mù quáng).
     */
    public static List<FanpageBotResponse.ReplyBubble> rebuildBubblesFromStructuredCatalog(
            List<FanpageBotResponse.ReplyBubble> candidateBubbles,
            PackageCatalogService.PackageSummary targetPackage,
            List<PackageCatalogService.PackageSummary> allCatalog,
            String selfPronoun,
            String customerPronoun
    ) {
        if (candidateBubbles == null || candidateBubbles.isEmpty()) {
            return List.of();
        }
        String sp = (selfPronoun != null && !selfPronoun.isBlank()) ? selfPronoun.strip() : "em";
        String cp = (customerPronoun != null && !customerPronoun.isBlank()) ? customerPronoun.strip() : "mình";

        List<FanpageBotResponse.ReplyBubble> rebuilt = new ArrayList<>(candidateBubbles.size());
        for (FanpageBotResponse.ReplyBubble b : candidateBubbles) {
            if (b == null || b.text() == null || b.text().isBlank()) continue;
            List<String> mismatchFlags = detectPriceEntityMismatchesInBubble(b.text(), allCatalog);
            if (mismatchFlags.isEmpty()) {
                rebuilt.add(b);
                continue;
            }
            PackageCatalogService.PackageSummary authoritativePkg = targetPackage;
            if (authoritativePkg == null && allCatalog != null) {
                String low = b.text().toLowerCase(Locale.ROOT);
                List<PackageCatalogService.PackageSummary> sorted = allCatalog.stream()
                        .filter(p -> p != null && !p.isAddon() && p.displayName() != null && !p.displayName().isBlank())
                        .sorted((x, y) -> Integer.compare(y.displayName().strip().length(), x.displayName().strip().length()))
                        .toList();
                for (PackageCatalogService.PackageSummary p : sorted) {
                    if (low.contains(p.displayName().strip().toLowerCase(Locale.ROOT))) {
                        authoritativePkg = p;
                        break;
                    }
                }
            }
            if (authoritativePkg == null) {
                rebuilt.add(b);
                continue;
            }
            String pkgName = authoritativePkg.displayName() != null ? authoritativePkg.displayName().strip() : "gói dịch vụ";
            String displayWithGoi = pkgName.toLowerCase(Locale.ROOT).startsWith("gói ") ? pkgName : ("gói " + pkgName);
            String pkgDur = (authoritativePkg.durationLabel() != null && !authoritativePkg.durationLabel().isBlank())
                    ? (" " + authoritativePkg.durationLabel().strip())
                    : "";
            String pkgPrice = authoritativePkg.priceDisplay() != null ? authoritativePkg.priceDisplay().strip() : "";
            String pkgIncludes = (authoritativePkg.includesSummary() != null && !authoritativePkg.includesSummary().isBlank())
                    ? authoritativePkg.includesSummary().strip()
                    : "";
            String pkgSpace = (authoritativePkg.customerFacingSpaceNote() != null && !authoritativePkg.customerFacingSpaceNote().isBlank())
                    ? authoritativePkg.customerFacingSpaceNote().strip()
                    : "";

            StringBuilder structuredBubble = new StringBuilder();
            structuredBubble.append("Dạ hiện tại ").append(displayWithGoi).append(pkgDur)
                    .append(" bên ").append(sp).append(" đang áp dụng mức giá niêm yết ").append(pkgPrice).append(" trọn gói");
            if (!pkgIncludes.isEmpty()) {
                structuredBubble.append(" (bao gồm ").append(pkgIncludes);
                if (!pkgSpace.isEmpty()) {
                    structuredBubble.append(" tại ").append(pkgSpace);
                }
                structuredBubble.append(")");
            }
            structuredBubble.append(", cam kết không phát sinh phụ phí hay nhận tip ạ.");
            if (b.text().strip().endsWith("?")) {
                structuredBubble.append(" Dạ ").append(cp).append(" thấy ").append(displayWithGoi)
                        .append(" (").append(pkgPrice).append(") này có phù hợp với nhu cầu của ").append(cp).append(" không ạ?");
            }
            rebuilt.add(new FanpageBotResponse.ReplyBubble(structuredBubble.toString(), b.typingDelayMs()));
        }
        return rebuilt;
    }

    private static final Pattern AFFIRMATIVE_PROMO_FABRICATION_PATTERN = Pattern.compile(
            "(?isU).*(?<!(?:chưa|không|ko|chẳng)\\s{1,4}(?:có|áp\\s+dụng|triển\\s+khai)\\s{1,4}(?:chương\\s+trình\\s+)?)"
            + "\\b(?:đang\\s+(?:giảm|sale|ưu\\s+đãi)\\s+\\d{1,2}\\s*%|giảm\\s+(?:ngay\\s+|thêm\\s+|tới\\s+)?\\d{1,2}\\s*%|"
            + "ưu\\s+đãi\\s+giảm\\s+\\d{1,2}\\s*%|mua\\s+\\d+\\s*(?:buổi|gói|vé|suất)\\s+(?:tặng|được\\s+tặng|giảm)\\s+\\d+|"
            + "tặng\\s+(?:kèm\\s+)?voucher\\s+\\d+)"
            + ".*"
    );

    public static boolean hasUngroundedPromoFabrication(
            String combinedText,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (combinedText == null || combinedText.isBlank()) {
            return false;
        }
        if (!AFFIRMATIVE_PROMO_FABRICATION_PATTERN.matcher(combinedText).matches()) {
            return false;
        }
        if (allCatalog != null) {
            for (PackageCatalogService.PackageSummary p : allCatalog) {
                if (p == null) continue;
                String meta = ((p.includesSummary() != null ? p.includesSummary() : "") + " "
                        + (p.processSummary() != null ? p.processSummary() : "")).toLowerCase(Locale.ROOT);
                if (meta.contains("%") || meta.contains("voucher") || meta.contains("tặng 1")) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checkpoint C: Deterministic Guard cho 3 trạng thái {@link CleanAgentCatalogAndPromptBuilder.ReplyResolutionState}
     * ({@code RESOLVED_QUOTED_PACKAGE}, {@code UNRESOLVED_QUOTED_PACKAGE}, {@code AMBIGUOUS_PACKAGE_REFERENCE})
     * và kiểm soát không tự bịa chính sách khuyến mãi ngoài {@link PackageCatalogService}.
     */
    public static List<String> checkReplyResolutionGuard(
            List<FanpageBotResponse.ReplyBubble> candidateBubbles,
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quoteResolution,
            List<PackageCatalogService.PackageSummary> allCatalog,
            String customerMessage
    ) {
        if (candidateBubbles == null || candidateBubbles.isEmpty()) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        for (FanpageBotResponse.ReplyBubble b : candidateBubbles) {
            if (b != null && b.text() != null && !b.text().isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(b.text().strip());
            }
        }
        String combined = sb.toString();
        if (combined.isEmpty()) {
            return List.of();
        }
        String combinedLower = combined.toLowerCase(Locale.ROOT);
        List<String> violations = new ArrayList<>();

        if (hasUngroundedPromoFabrication(combined, allCatalog)) {
            violations.add("UNGROUNDED_PROMO_POLICY_FABRICATION");
        }

        if (quoteResolution == null) {
            return violations;
        }
        CleanAgentCatalogAndPromptBuilder.ReplyResolutionState state = quoteResolution.resolutionState();
        if (state == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.NONE) {
            return violations;
        }

        Set<Long> replyPrices = extractPricesInVndFromText(combined);
        Set<Long> customerExplicitPrices = extractPricesInVndFromText(customerMessage);

        if (state == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.RESOLVED_QUOTED_PACKAGE) {
            PackageCatalogService.PackageSummary qPkg = quoteResolution.quotedPackage();
            if (qPkg != null) {
                boolean mentionsQuotedPkg = isPackageMentionedByNameOrPrice(combinedLower, replyPrices, qPkg);
                boolean mentionsOtherPkgPrice = false;
                for (Long rp : replyPrices) {
                    if (rp != null && rp > 0L && rp != qPkg.priceVnd() && !customerExplicitPrices.contains(rp)) {
                        mentionsOtherPkgPrice = true;
                        break;
                    }
                }
                if (!mentionsQuotedPkg || mentionsOtherPkgPrice) {
                    violations.add("QUOTED_PACKAGE_FOCUS_MISMATCH:" + (qPkg.code() != null ? qPkg.code() : qPkg.priceDisplay()));
                }
            }
            return violations;
        }

        // UNRESOLVED_QUOTED_PACKAGE or AMBIGUOUS_PACKAGE_REFERENCE
        List<PackageCatalogService.PackageSummary> candidates = quoteResolution.candidatePackages();
        boolean hasClarificationQuestion = combined.contains("?");
        if (candidates.size() >= 2) {
            int mentionedCandidateCount = 0;
            for (PackageCatalogService.PackageSummary cand : candidates) {
                if (cand != null && isPackageMentionedByNameOrPrice(combinedLower, replyPrices, cand)) {
                    mentionedCandidateCount++;
                }
            }
            if (!hasClarificationQuestion || mentionedCandidateCount < 2) {
                violations.add("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:TIER_1_MULTI_CANDIDATE");
            }
        } else if (candidates.size() == 1) {
            PackageCatalogService.PackageSummary soleCand = candidates.get(0);
            boolean mentionsSole = isPackageMentionedByNameOrPrice(combinedLower, replyPrices, soleCand);
            boolean asksOrOtherPackage = combinedLower.matches(
                    "(?isU).*\\b(hay\\s+(?:một\\s+|1\\s+|là\\s+)?gói\\s+(?:nào\\s+|dịch\\s+vụ\\s+)?khác|hoặc\\s+(?:một\\s+)?gói\\s+khác|hay\\s+đang\\s+(?:hỏi|quan\\s+tâm)\\s+gói\\s+khác)\\b.*"
            );
            if (!hasClarificationQuestion || !mentionsSole || !asksOrOtherPackage) {
                violations.add("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:TIER_2_SINGLE_CANDIDATE");
            }
        } else {
            boolean mentionsAnySpecificPkgName = false;
            if (allCatalog != null) {
                for (PackageCatalogService.PackageSummary p : allCatalog) {
                    if (p == null || p.isAddon() || p.displayName() == null) continue;
                    String dLow = p.displayName().strip().toLowerCase(Locale.ROOT);
                    String dNoPrefix = dLow.replaceFirst("^gói\\s+", "").strip();
                    if ((dLow.length() >= 4 && combinedLower.contains(dLow))
                            || (dNoPrefix.length() >= 6 && combinedLower.contains(dNoPrefix))) {
                        mentionsAnySpecificPkgName = true;
                        break;
                    }
                }
            }
            if (!hasClarificationQuestion || !replyPrices.isEmpty() || mentionsAnySpecificPkgName) {
                violations.add("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:TIER_3_EMPTY_HISTORY");
            }
        }
        return violations;
    }

    private static boolean isPackageMentionedByNameOrPrice(
            String textLower,
            Set<Long> extractedPrices,
            PackageCatalogService.PackageSummary pkg
    ) {
        if (pkg == null) return false;
        if (pkg.priceVnd() > 0L && extractedPrices != null && extractedPrices.contains(pkg.priceVnd())) {
            return true;
        }
        if (pkg.displayName() != null && !pkg.displayName().isBlank()) {
            String dLow = pkg.displayName().strip().toLowerCase(Locale.ROOT);
            String dNoPrefix = dLow.replaceFirst("^gói\\s+", "").strip();
            if ((dLow.length() >= 4 && textLower.contains(dLow))
                    || (dNoPrefix.length() >= 6 && textLower.contains(dNoPrefix))) {
                return true;
            }
        }
        return false;
    }

    public static List<FanpageBotResponse.ReplyBubble> buildDeterministicQuotedPackageReplyBubbles(
            PackageCatalogService.PackageSummary quotedPkg,
            String customerMessage,
            String selfPronoun,
            String customerPronoun
    ) {
        if (quotedPkg == null) {
            return List.of();
        }
        String sp = (selfPronoun != null && !selfPronoun.isBlank()) ? selfPronoun.strip() : "em";
        String cp = (customerPronoun != null && !customerPronoun.isBlank()) ? customerPronoun.strip() : "mình";
        String rawName = quotedPkg.displayName() != null ? quotedPkg.displayName().strip() : "gói dịch vụ";
        String displayWithGoi = rawName.toLowerCase(Locale.ROOT).startsWith("gói ") ? rawName : ("gói " + rawName);
        String durLabel = (quotedPkg.durationMinutes() != null && quotedPkg.durationMinutes() > 0)
                ? (" " + quotedPkg.durationMinutes() + " phút")
                : ((quotedPkg.durationLabel() != null && !quotedPkg.durationLabel().isBlank()) ? (" " + quotedPkg.durationLabel().strip()) : "");
        String priceDisp = quotedPkg.priceDisplay() != null ? quotedPkg.priceDisplay().strip() : "";
        String includes = quotedPkg.includesSummary() != null ? quotedPkg.includesSummary().strip() : "";
        String spaceNote = quotedPkg.customerFacingSpaceNote() != null ? quotedPkg.customerFacingSpaceNote().strip() : "";
        boolean isPromoQuestion = CleanAgentOrchestratorService.isPromoOrDiscountInquiryMessage(customerMessage);

        List<FanpageBotResponse.ReplyBubble> out = new ArrayList<>(2);
        if (isPromoQuestion) {
            out.add(new FanpageBotResponse.ReplyBubble(
                    "Dạ hiện tại " + displayWithGoi + durLabel + " bên " + sp
                            + " đang áp dụng mức giá niêm yết trọn gói là " + priceDisp
                            + ", hiện chưa có chương trình giảm giá riêng cho gói này ạ.",
                    0
            ));
        } else {
            String areas = (quotedPkg.bodyAreas() != null && !quotedPkg.bodyAreas().isEmpty())
                    ? String.join(", ", quotedPkg.bodyAreas())
                    : "chuyên sâu theo liệu trình";
            out.add(new FanpageBotResponse.ReplyBubble(
                    "Dạ với " + displayWithGoi + durLabel + " (giá niêm yết " + priceDisp
                            + " trọn gói), liệu trình tập trung chăm sóc vùng " + areas + " cho " + cp + " ạ.",
                    0
            ));
        }
        if (!includes.isEmpty()) {
            String spaceClause = !spaceNote.isEmpty() ? (" tại " + spaceNote) : "";
            out.add(new FanpageBotResponse.ReplyBubble(
                    "Mức " + priceDisp + " đã bao gồm trọn gói " + includes + spaceClause
                            + ", cam kết không phát sinh phụ phí hay nhận tip ạ.",
                    2000
            ));
        }
        return out;
    }

    public static List<FanpageBotResponse.ReplyBubble> buildDeterministicReplyClarificationBubbles(
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quoteResolution,
            String customerMessage,
            String selfPronoun,
            String customerPronoun
    ) {
        String sp = (selfPronoun != null && !selfPronoun.isBlank()) ? selfPronoun.strip() : "em";
        String cp = (customerPronoun != null && !customerPronoun.isBlank()) ? customerPronoun.strip() : "mình";
        List<PackageCatalogService.PackageSummary> candidates =
                quoteResolution != null ? quoteResolution.candidatePackages() : List.of();
        boolean isPromo = CleanAgentOrchestratorService.isPromoOrDiscountInquiryMessage(customerMessage);
        String topicPhrase = isPromo ? "ưu đãi cho " : "thông tin cho ";

        if (candidates.size() >= 2) {
            List<String> formattedOptions = new ArrayList<>();
            int limit = Math.min(3, candidates.size());
            for (int i = 0; i < limit; i++) {
                formattedOptions.add(formatPackageClarificationOption(candidates.get(i)));
            }
            String joinedOptions;
            if (formattedOptions.size() == 2) {
                joinedOptions = formattedOptions.get(0) + " hay " + formattedOptions.get(1);
            } else {
                joinedOptions = formattedOptions.get(0) + ", " + formattedOptions.get(1) + " hay " + formattedOptions.get(2);
            }
            String text = "Dạ " + cp + " đang hỏi " + topicPhrase + joinedOptions + " ạ? "
                    + BubbleCapHook.capitalizeFirstLetter(sp) + " xác nhận đúng gói rồi báo " + cp + " chính xác nhé.";
            return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
        }
        if (candidates.size() == 1) {
            String opt = formatPackageClarificationOption(candidates.get(0));
            String text = "Dạ không biết " + cp + " đang muốn hỏi " + topicPhrase + opt
                    + " hay một gói khác trong bảng giá bên " + sp + " ạ? "
                    + BubbleCapHook.capitalizeFirstLetter(sp) + " xác nhận đúng gói rồi báo " + cp + " chính xác nhé.";
            return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
        }
        String openText = "Dạ do tin nhắn " + cp + " trích dẫn ở khá xa nên " + sp
                + " chưa hiển thị lại được nội dung, " + cp + " nhắn giúp " + sp
                + " tên gói hoặc mức giá " + cp + " đang quan tâm để " + sp + " kiểm tra chính xác nhất nhé ạ?";
        return List.of(new FanpageBotResponse.ReplyBubble(openText, 0));
    }

    private static String formatPackageClarificationOption(PackageCatalogService.PackageSummary pkg) {
        if (pkg == null) {
            return "gói dịch vụ";
        }
        String rawName = pkg.displayName() != null ? pkg.displayName().strip() : "dịch vụ";
        String cleanName = BubbleCapHook.capitalizeFirstLetter(rawName.replaceFirst("(?iU)^gói\\s+", "").strip());
        String durPart = (pkg.durationMinutes() != null && pkg.durationMinutes() > 0)
                ? (" " + pkg.durationMinutes() + " phút")
                : ((pkg.durationLabel() != null && !pkg.durationLabel().isBlank())
                    ? (" " + pkg.durationLabel().strip().replace("~", ""))
                    : "");
        String pricePart = (pkg.priceDisplay() != null && !pkg.priceDisplay().isBlank())
                ? (" giá " + pkg.priceDisplay().strip())
                : "";
        return "gói " + cleanName + durPart + pricePart;
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

    public static Set<Integer> extractComponentDurationsFromCatalogPackage(PackageCatalogService.PackageSummary pkg) {
        if (pkg == null) {
            return Set.of();
        }
        Set<Integer> durations = new LinkedHashSet<>();
        if (pkg.durationMinutes() != null && pkg.durationMinutes() > 0) {
            durations.add(pkg.durationMinutes());
        }
        String[] verifiedFields = {
                pkg.displayName(),
                pkg.durationLabel(),
                pkg.includesSummary(),
                pkg.processSummary(),
                pkg.suitableFor()
        };
        for (String field : verifiedFields) {
            if (field == null || field.isBlank()) continue;
            Matcher m = CATALOG_COMPONENT_DURATION_PATTERN.matcher(field);
            while (m.find()) {
                try {
                    int min = Integer.parseInt(m.group(1));
                    if (min > 0) {
                        durations.add(min);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return durations;
    }

    public static boolean detectCleanCrossTurnRepetition(
            List<FanpageBotResponse.ReplyBubble> candidateBubbles,
            String prevBotTurnText,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        boolean inferredDetailOrPromoTurn = false;
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                if (!"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())) {
                    String lastUserMsg = t.content().strip();
                    inferredDetailOrPromoTurn = CleanAgentOrchestratorService.isNormalPriceOrDetailInquiryMessage(lastUserMsg)
                            || CleanAgentOrchestratorService.isPromoOrDiscountInquiryMessage(lastUserMsg)
                            || SentimentIntentLayer.isDetailOrFaqQuestion(lastUserMsg, null);
                    break;
                }
            }
        }
        return detectCleanCrossTurnRepetition(candidateBubbles, prevBotTurnText, history, inferredDetailOrPromoTurn);
    }

    public static boolean detectCleanCrossTurnRepetition(
            List<FanpageBotResponse.ReplyBubble> candidateBubbles,
            String prevBotTurnText,
            List<FanpageBotRequest.ConversationTurn> history,
            boolean isDetailOrPromoQuestionTurn
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

        StringBuilder fullTurnSb = new StringBuilder();
        for (FanpageBotResponse.ReplyBubble b : candidateBubbles) {
            if (b != null && b.text() != null && !b.text().isBlank()) {
                if (!fullTurnSb.isEmpty()) fullTurnSb.append(' ');
                fullTurnSb.append(b.text().strip());
            }
        }
        List<String> fullCandTokens = tokenizeCleanWords(fullTurnSb.toString());
        if (!fullCandTokens.isEmpty()) {
            for (String prevTurn : recentAssistantTurns) {
                List<String> prevTokens = tokenizeCleanWords(prevTurn);
                if (prevTokens.isEmpty()) continue;
                Set<String> prevSet = new LinkedHashSet<>(prevTokens);
                int hits = 0;
                for (String t : fullCandTokens) {
                    if (prevSet.contains(t)) {
                        hits++;
                    }
                }
                double fullOverlap = hits / (double) fullCandTokens.size();
                if (fullOverlap >= 0.85d && (fullCandTokens.size() - hits) <= 3) {
                    return true;
                }
            }
        }

        if (isDetailOrPromoQuestionTurn) {
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
        List<String> fullCandTokens = tokenizeCleanWords(candidateBubble);
        List<String> fullPrevTokens = tokenizeCleanWords(previousBotTurnText);
        if (!fullCandTokens.isEmpty() && !fullPrevTokens.isEmpty()) {
            Set<String> fullPrevSet = new LinkedHashSet<>(fullPrevTokens);
            int fullHits = 0;
            for (String t : fullCandTokens) {
                if (fullPrevSet.contains(t)) {
                    fullHits++;
                }
            }
            double fullRatio = fullHits / (double) fullCandTokens.size();
            int newWords = fullCandTokens.size() - fullHits;
            String candLow = candidateBubble.toLowerCase(Locale.ROOT);
            boolean answersDetailOrPromo = candLow.matches("(?isU).*\\b(khuyến\\s+mãi|ưu\\s+đãi|giảm\\s+giá|voucher|không\\s+phát\\s+sinh|đã\\s+là\\s+mức\\s+giá|gồm\\s+có|quy\\s+trình)\\b.*");
            if (answersDetailOrPromo && fullRatio < 0.85d && newWords >= 5) {
                return false;
            }
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
        if (violations.stream().anyMatch(v -> v.startsWith("QUOTED_PACKAGE_FOCUS_MISMATCH:"))) {
            String qHint = activePackage != null
                    ? (activePackage.displayName() + " " + activePackage.durationLabel() + " (" + activePackage.priceDisplay() + ")")
                    : "đúng gói trong bong bóng được trích dẫn";
            sb.append("- KHÁCH ĐANG QUOTE-REPLY VÀO GÓI ").append(qHint)
              .append(": BẮT BUỘC chỉ trả lời đúng thông tin/ưu đãi niêm yết của gói ").append(qHint)
              .append(", TUYỆT ĐỐI CẤM lôi gói khác (như 239k) vào câu trả lời!\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:"))) {
            sb.append("- CHƯA XÁC ĐỊNH RÕ GÓI TRONG TIN NHẮN REPLY: TUYỆT ĐỐI CẤM tự mặc định trả lời cho 1 gói đơn lẻ. BẮT BUỘC đặt câu hỏi xác nhận lịch sự theo đúng hướng dẫn trong <AGENT_BRAIN_INSIGHT> (nếu lịch sử có >=2 gói thì hỏi xác nhận giữa các gói đó; nếu có 1 gói thì hỏi gói đó hay gói khác; nếu lịch sử trống thì hỏi mở không đoán mò giá).\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("UNGROUNDED_PROMO_POLICY_FABRICATION"))) {
            sb.append("- TUYỆT ĐỐI CẤM tự bịa chương trình giảm giá %, voucher hay combo tặng buổi ngoài dữ liệu <SCOPE_FILTERED_CATALOG>. Hãy báo trung thực mức giá hiện tại là giá niêm yết trọn gói, không phát sinh phụ phí.\n");
        }
        if (violations.stream().anyMatch(v -> v.startsWith("PRICE_ENTITY_MISMATCH:"))) {
            sb.append("- PHÁT HIỆN RÂU ÔNG NỌ CẮM CẰM BÀ KIA (`PRICE_ENTITY_MISMATCH`): Tuyệt đối KHÔNG ghép tên gói này với mức giá/thời lượng của gói khác (ví dụ: cấm nói 'Massage cơ bản 60 phút' có giá '239k' vì 239k là 'Gói dưỡng sinh cơ bản 60 phút', còn 'Massage cơ bản 60 phút' có giá 399k). BẮT BUỘC đối chiếu đúng từng bộ (tên gói, thời lượng, mức giá) trong <SCOPE_FILTERED_CATALOG>.\n");
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
                "(?iU)^(\\s*(?:dạ[,\\s]+)?)mình\\s+((?:đã\\s+|rất\\s+|cũng\\s+)?(?:biết|ghi\\s+nhận|nắm\\s+rõ|xin\\s+lỗi))\\b",
                "Dạ " + sp + " $2"
        );
        out = out.replaceAll(
                "(?iU)\\b(dạ[,\\s]+)" + Pattern.quote(sp) + "\\s+thấy\\s+([^.!?]*?\\b(?:có\\s+phù\\s+hợp|phù\\s+hợp\\s+với|như\\s+thế\\s+nào|thế\\s+nào|ra\\s+sao)[^.!?]*?\\?)",
                "$1" + cp + " thấy $2"
        );
        out = out.replaceAll(
                "(?iU)(?<=^|[.!?]\\s+)" + Pattern.quote(sp) + "\\s+thấy\\s+([^.!?]*?\\b(?:có\\s+phù\\s+hợp|phù\\s+hợp\\s+với|như\\s+thế\\s+nào|thế\\s+nào|ra\\s+sao)[^.!?]*?\\?)",
                BubbleCapHook.capitalizeFirstLetter(cp) + " thấy $1"
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
        String[] clauses = trimmed.split("\\s*(?:(?<!\\d)[,;.]+|[,;.]+(?!\\d)|[–—])\\s*");
        List<String> keptClauses = new ArrayList<>();
        boolean anyClauseRemoved = false;
        String custLower = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        String botLower = botReply != null ? botReply.toLowerCase(Locale.ROOT) : "";
        Set<String> custTokens = extractProvenanceContentTokens(custLower);
        Set<String> botTokens = extractProvenanceContentTokens(botLower);
        for (String clause : clauses) {
            if (clause == null || clause.isBlank()) continue;
            String c = clause.strip();
            String cLow = c.toLowerCase(Locale.ROOT);
            if (UNCONFIRMED_BOT_SPECULATION_CARE_NOTE_PATTERN.matcher(c).find()) {
                if (custLower.isEmpty() || !custLower.contains(cLow)) {
                    anyClauseRemoved = true;
                    continue;
                }
            }
            if (BOT_PROMOTIONAL_OR_EFFICACY_CARE_NOTE_CLAUSE_PATTERN.matcher(c).find()) {
                if (custLower.isEmpty() || !custLower.contains(cLow)) {
                    anyClauseRemoved = true;
                    continue;
                }
            }
            if (!botLower.isEmpty()) {
                Set<String> cTokens = extractProvenanceContentTokens(cLow);
                int custOverlap = countTokenOverlap(cTokens, custTokens);
                int botOverlap = countTokenOverlap(cTokens, botTokens);
                boolean echoedFromBotOnly = (botLower.contains(cLow)
                        || (cTokens.size() >= 3 && botOverlap >= Math.ceil(cTokens.size() * 0.75)))
                        && custOverlap == 0
                        && !custLower.contains(cLow);
                if (echoedFromBotOnly) {
                    anyClauseRemoved = true;
                    continue;
                }
            }
            keptClauses.add(c);
        }
        if (keptClauses.isEmpty()) {
            return "";
        }
        if (anyClauseRemoved) {
            trimmed = String.join(", ", keptClauses).strip();
        }
        Set<String> remainingTokens = extractProvenanceContentTokens(trimmed.toLowerCase(Locale.ROOT));
        if (remainingTokens.isEmpty()) {
            return "";
        }
        if (!custTokens.isEmpty() && !custLower.startsWith("tin nhắn bổ sung thứ")) {
            if (countTokenOverlap(remainingTokens, custTokens) <= 0) {
                return "";
            }
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

    private static Set<String> extractProvenanceContentTokens(String lowerText) {
        if (lowerText == null || lowerText.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : PROVENANCE_TOKEN_SPLIT_PATTERN.split(lowerText)) {
            if (raw == null || raw.isBlank()) continue;
            String tok = raw.strip().replaceAll("^\\.+|\\.+$", "");
            if (tok.length() < 2) continue;
            if (PROVENANCE_STOPWORDS.contains(tok)) continue;
            tokens.add(tok);
        }
        return tokens;
    }

    private static int countTokenOverlap(Set<String> noteTokens, Set<String> referenceTokens) {
        if (noteTokens == null || noteTokens.isEmpty() || referenceTokens == null || referenceTokens.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String nt : noteTokens) {
            if (referenceTokens.contains(nt)) {
                overlap++;
                continue;
            }
            if (nt.length() >= 3) {
                for (String rt : referenceTokens) {
                    if (rt.length() >= 3 && (nt.contains(rt) || rt.contains(nt))) {
                        overlap++;
                        break;
                    }
                }
            }
        }
        return overlap;
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
