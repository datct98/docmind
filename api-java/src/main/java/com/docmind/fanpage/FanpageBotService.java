package com.docmind.fanpage;

import com.docmind.guardrail.GuardrailPipeline;

/**
 * Service in package com.docmind.fanpage exposing FanpageBot operations and guardrails.
 * Acts as a delegate wrapper around com.example.docmind.service.bot.FanpageBotService.
 */
public class FanpageBotService {

    private final com.example.docmind.service.bot.FanpageBotService delegate;
    private final GuardrailPipeline guardrailPipeline;

    public FanpageBotService(
            com.example.docmind.service.bot.FanpageBotService delegate,
            GuardrailPipeline guardrailPipeline
    ) {
        this.delegate = delegate;
        this.guardrailPipeline = guardrailPipeline != null ? guardrailPipeline : new GuardrailPipeline();
    }

    public com.example.docmind.service.bot.FanpageBotService getDelegate() {
        return delegate;
    }

    public GuardrailPipeline getGuardrailPipeline() {
        return guardrailPipeline;
    }
}
