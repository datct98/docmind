package com.example.docmind.guardrail;

import java.util.List;

/**
 * Re-export of {@link com.docmind.guardrail.GuardrailPipeline}.
 */
public class GuardrailPipeline extends com.docmind.guardrail.GuardrailPipeline {

    public GuardrailPipeline() {
        super();
    }

    public GuardrailPipeline(List<com.docmind.guardrail.GuardrailHook> hooks) {
        super(hooks);
    }
}
