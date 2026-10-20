package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable draft passed through the deterministic guardrail hooks pipeline.
 */
public class GuardrailDraft {

    private String text;
    private List<ReplyBubble> bubbles;
    private final Set<String> triggeredHooks = new LinkedHashSet<>();
    private final int originalBubbleCount;

    public GuardrailDraft(String text, List<ReplyBubble> bubbles) {
        this.text = text != null ? text : "";
        this.bubbles = bubbles != null ? new ArrayList<>(bubbles) : new ArrayList<>();
        int count = this.bubbles.size();
        if (count == 0 && this.text != null && !this.text.isBlank()) {
            count = this.text.split("\\n\\s*\\n").length;
        }
        this.originalBubbleCount = count;
    }

    public int getOriginalBubbleCount() {
        return originalBubbleCount;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public List<ReplyBubble> getBubbles() {
        return Collections.unmodifiableList(bubbles);
    }

    public void setBubbles(List<ReplyBubble> bubbles) {
        this.bubbles = bubbles != null ? new ArrayList<>(bubbles) : new ArrayList<>();
    }

    public void addTriggeredHook(String hookCode) {
        if (hookCode != null && !hookCode.isBlank()) {
            this.triggeredHooks.add(hookCode.strip());
        }
    }

    public List<String> getTriggeredHooks() {
        return new ArrayList<>(triggeredHooks);
    }

    public boolean hasTriggered(String hookCode) {
        return triggeredHooks.contains(hookCode);
    }
}
