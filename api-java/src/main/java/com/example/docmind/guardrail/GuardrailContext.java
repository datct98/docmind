package com.example.docmind.guardrail;

import java.util.List;

/**
 * Re-export of {@link com.docmind.guardrail.GuardrailContext} in com.example.docmind.guardrail.
 */
public class GuardrailContext extends com.docmind.guardrail.GuardrailContext {

    public GuardrailContext(
            String customerHonorific,
            String customerName,
            boolean isFirstTurn,
            String salesStage,
            String customerMessage,
            List<String> conversationHistory,
            List<String> knowledgeChunks
    ) {
        super(customerHonorific, customerName, isFirstTurn, salesStage, customerMessage, conversationHistory, knowledgeChunks);
    }
}
