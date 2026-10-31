package com.example.docmind.controller;

import com.example.docmind.service.bot.InboundPersistenceUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link InboundPersistenceUnavailableException} on synchronous REST/Simulator endpoints
 * to HTTP 503 Service Unavailable with {@code Retry-After: 5}.
 */
@RestControllerAdvice
public class InboundPersistenceExceptionHandler {

    @ExceptionHandler(InboundPersistenceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleInboundPersistenceUnavailable(
            InboundPersistenceUnavailableException ex
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "SERVICE_UNAVAILABLE");
        body.put("message", ex.getMessage() != null
                ? ex.getMessage()
                : "Inbound session persistence is temporarily unavailable. Please retry.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(body);
    }
}
