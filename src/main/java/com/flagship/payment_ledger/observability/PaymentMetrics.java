package com.flagship.payment_ledger.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Centralized metrics for payment operations.
 *
 * Phase 7: Observability
 *
 * Metrics exposed:
 * - payment.created: Counter of created payments
 * - payment.authorized: Counter of authorized payments
 * - payment.settled: Counter of settled payments
 * - payment.failed: Counter of failed payments
 * - payment.settlement.duration: Timer for settlement operations
 * - payment.api.duration: Timer for API response times
 *
 * All metrics are tagged with relevant dimensions for filtering.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    // Counters
    private final Counter paymentsCreated;
    private final Counter paymentsAuthorized;
    private final Counter paymentsSettled;
    private final Counter paymentsFailed;
    private final Counter duplicateRequests;

    // Timers
    private final Timer settlementTimer;
    private final Timer apiTimer;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Payment lifecycle counters
        this.paymentsCreated = Counter.builder("payment.created")
                .description("Number of payments created")
                .register(registry);

        this.paymentsAuthorized = Counter.builder("payment.authorized")
                .description("Number of payments authorized")
                .register(registry);

        this.paymentsSettled = Counter.builder("payment.settled")
                .description("Number of payments settled")
                .register(registry);

        this.paymentsFailed = Counter.builder("payment.failed")
                .description("Number of payments failed")
                .register(registry);

        this.duplicateRequests = Counter.builder("payment.duplicate_requests")
                .description("Number of duplicate payment requests (idempotency hits)")
                .register(registry);

        // Timers
        this.settlementTimer = Timer.builder("payment.settlement.duration")
                .description("Time taken to settle a payment")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.apiTimer = Timer.builder("payment.api.duration")
                .description("API response time for payment operations")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    // ==================== Counter Methods ====================

    public void incrementPaymentsCreated() {
        paymentsCreated.increment();
    }

    public void incrementPaymentsCreated(String currency) {
        Counter.builder("payment.created")
                .tag("currency", currency)
                .register(registry)
                .increment();
    }

    public void incrementPaymentsAuthorized() {
        paymentsAuthorized.increment();
    }

    public void incrementPaymentsSettled() {
        paymentsSettled.increment();
    }

    public void incrementPaymentsFailed(String reason) {
        paymentsFailed.increment();
        Counter.builder("payment.failed")
                .tag("reason", sanitizeTag(reason))
                .register(registry)
                .increment();
    }

    public void incrementDuplicateRequests() {
        duplicateRequests.increment();
    }

    // ==================== Tagged Counter Methods ====================

    /**
     * Records a payment creation with currency and status tags.
     * Uses registry.counter() for efficient meter lookup/creation.
     */
    public void recordPaymentCreated(String currency, String status) {
        registry.counter("payments.created",
                "currency", sanitizeTag(currency),
                "status", sanitizeTag(status)
        ).increment();
    }

    /**
     * Records a payment settlement with currency and status tags.
     * Uses registry.counter() for efficient meter lookup/creation.
     */
    public void recordPaymentSettled(String currency, String status) {
        registry.counter("payments.settled",
                "currency", sanitizeTag(currency),
                "status", sanitizeTag(status)
        ).increment();
    }

    /**
     * Records payment operation latency.
     * Uses registry.timer() for efficient meter lookup/creation.
     */
    public void recordPaymentLatency(String operation, long durationMs) {
        registry.timer("payments.latency",
                "operation", sanitizeTag(operation)
        ).record(Duration.ofMillis(durationMs));
    }

    /**
     * Records an idempotency cache hit (duplicate request).
     */
    public void recordIdempotencyHit() {
        registry.counter("idempotency.cache", "result", "hit").increment();
    }

    /**
     * Records an idempotency cache miss (new request).
     */
    public void recordIdempotencyMiss() {
        registry.counter("idempotency.cache", "result", "miss").increment();
    }

    // ==================== Timer Methods ====================

    /**
     * Records the duration of a settlement operation.
     */
    public void recordSettlementDuration(Duration duration) {
        settlementTimer.record(duration);
    }

    /**
     * Times a settlement operation.
     */
    public <T> T timeSettlement(Supplier<T> operation) {
        return settlementTimer.record(operation);
    }

    /**
     * Records API response time.
     */
    public void recordApiDuration(String endpoint, String method, int statusCode, Duration duration) {
        Timer.builder("payment.api.duration")
                .tag("endpoint", endpoint)
                .tag("method", method)
                .tag("status", String.valueOf(statusCode))
                .register(registry)
                .record(duration);
    }

    /**
     * Times an API operation.
     */
    public <T> T timeApi(String endpoint, Supplier<T> operation) {
        return apiTimer.record(operation);
    }

    // ==================== Gauge Methods ====================

    /**
     * Registers a gauge for outbox backlog size.
     */
    public void registerOutboxBacklogGauge(Supplier<Number> supplier) {
        registry.gauge("outbox.backlog.size", Tags.empty(), supplier, s -> s.get().doubleValue());
    }

    /**
     * Registers a gauge for processed events count.
     */
    public void registerProcessedEventsGauge(String consumerGroup, Supplier<Number> supplier) {
        registry.gauge("consumer.processed.count",
                Tags.of("consumer_group", consumerGroup),
                supplier, s -> s.get().doubleValue());
    }

    // ==================== Event Processing Metrics ====================

    /**
     * Records event processing.
     */
    public void recordEventProcessed(String eventType, boolean wasNew) {
        Counter.builder("event.processed")
                .tag("event_type", eventType)
                .tag("was_new", String.valueOf(wasNew))
                .register(registry)
                .increment();
    }

    /**
     * Records event processing failure.
     */
    public void recordEventProcessingFailure(String eventType, String error) {
        Counter.builder("event.processing.failure")
                .tag("event_type", eventType)
                .tag("error", sanitizeTag(error))
                .register(registry)
                .increment();
    }

    /**
     * Records event publishing.
     */
    public void recordEventPublished(String eventType) {
        Counter.builder("outbox.event.published")
                .tag("event_type", eventType)
                .register(registry)
                .increment();
    }

    /**
     * Records event publishing failure.
     */
    public void recordEventPublishFailure(String eventType) {
        Counter.builder("outbox.event.publish.failure")
                .tag("event_type", eventType)
                .register(registry)
                .increment();
    }

    // ==================== Helper Methods ====================

    /**
     * Sanitizes a tag value to prevent cardinality explosion.
     */
    private String sanitizeTag(String value) {
        if (value == null) {
            return "unknown";
        }
        // Limit length and remove special characters
        String sanitized = value.replaceAll("[^a-zA-Z0-9_]", "_");
        return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
    }
}
