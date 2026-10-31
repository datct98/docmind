package com.example.docmind.service.bot;

/**
 * Thrown on synchronous REST/Simulator inbound channels when durable persistence
 * to PostgreSQL is unavailable, enforcing Fail-Closed (0 LLM calls, 0 outbound bubbles)
 * with HTTP 503 Service Unavailable and Retry-After: 5.
 */
public class InboundPersistenceUnavailableException extends RuntimeException {

    public InboundPersistenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public InboundPersistenceUnavailableException(String message) {
        super(message);
    }
}
