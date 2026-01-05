package com.flagship.payment_ledger.observability;

import java.util.UUID;

/**
 * Thread-local context for correlation ID propagation.
 *
 * Phase 7: Observability
 *
 * The correlation ID flows through:
 * - HTTP requests (from header or generated)
 * - Database operations (in logs)
 * - Kafka messages (as header)
 * - All log statements (via MDC)
 *
 * This enables end-to-end request tracing across distributed systems.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String PAYMENT_ID_MDC_KEY = "paymentId";
    public static final String ACCOUNT_ID_MDC_KEY = "accountId";

    private static final ThreadLocal<String> correlationId = new ThreadLocal<>();

    private CorrelationContext() {
        // Utility class
    }

    /**
     * Gets the current correlation ID, or generates a new one if not set.
     */
    public static String getCorrelationId() {
        String id = correlationId.get();
        if (id == null) {
            id = generateCorrelationId();
            correlationId.set(id);
        }
        return id;
    }

    /**
     * Sets the correlation ID for the current thread.
     */
    public static void setCorrelationId(String id) {
        if (id != null && !id.isBlank()) {
            correlationId.set(id);
        } else {
            correlationId.set(generateCorrelationId());
        }
    }

    /**
     * Clears the correlation ID from the current thread.
     * Should be called at the end of request processing.
     */
    public static void clear() {
        correlationId.remove();
    }

    /**
     * Generates a new correlation ID.
     * Uses a shorter format for readability in logs.
     */
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Checks if a correlation ID is currently set.
     */
    public static boolean hasCorrelationId() {
        return correlationId.get() != null;
    }
}
