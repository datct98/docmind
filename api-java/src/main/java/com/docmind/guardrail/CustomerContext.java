package com.docmind.guardrail;

import java.util.List;

/**
 * Interface contract alias representing the Customer Context for the GuardrailPipeline.
 */
public class CustomerContext extends GuardrailContext {

    public CustomerContext(
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

    public static CustomerContext from(GuardrailContext ctx) {
        if (ctx instanceof CustomerContext cc) {
            return cc;
        }
        return new CustomerContext(
                ctx.getCustomerHonorific(),
                ctx.getCustomerName(),
                ctx.isFirstTurn(),
                ctx.getSalesStage(),
                ctx.getCustomerMessage(),
                ctx.getConversationHistory(),
                ctx.getKnowledgeChunks()
        );
    }
}
