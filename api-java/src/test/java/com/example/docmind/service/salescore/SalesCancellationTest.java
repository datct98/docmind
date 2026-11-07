package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SalesCancellationTest {
    @Test void cancellationDoesNotStartFallbackButOrdinaryProviderFailureDoes() {
        var calls = new AtomicInteger();
        LlmClient fallback = SalesCoreTest.fake(() -> { calls.incrementAndGet(); return "ok"; });
        LlmClient canceled = SalesCoreTest.fake(() -> { Thread.currentThread().interrupt(); return "[test-error] canceled"; });
        try {
            assertThat(new ResilientFallbackLlmClient(canceled, fallback).complete("p")).contains("canceled");
            assertThat(calls).hasValue(0);
        } finally { Thread.interrupted(); }
        var failing = SalesCoreTest.fake(() -> "[test-offline] unavailable");
        assertThat(new ResilientFallbackLlmClient(failing, fallback).complete("p")).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }
}
