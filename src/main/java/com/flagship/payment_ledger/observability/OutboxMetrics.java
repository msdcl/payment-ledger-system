package com.flagship.payment_ledger.observability;

import com.flagship.payment_ledger.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the transactional outbox.
 *
 * Phase 7: Observability
 *
 * These metrics provide visibility into the outbox health:
 * - Backlog size: How many events are waiting to be published
 * - Oldest event age: How long the oldest event has been waiting
 * - Publishing rate: Events published per second
 *
 * Use these for:
 * - Alerting when backlog grows too large
 * - Detecting publisher issues
 * - Capacity planning
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxMetrics {

    private final OutboxEventRepository outboxRepository;
    private final MeterRegistry meterRegistry;

    // Cached values updated periodically (avoid hitting DB on every scrape)
    private final AtomicLong backlogSize = new AtomicLong(0);
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong(0);
    private final AtomicLong failedEventCount = new AtomicLong(0);

    @PostConstruct
    public void init() {
        // Register gauges that read from cached values
        Gauge.builder("outbox.backlog.size", backlogSize, AtomicLong::get)
                .description("Number of unpublished events in the outbox")
                .tag("status", "pending")
                .register(meterRegistry);

        Gauge.builder("outbox.backlog.age.seconds", oldestEventAgeSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished event in seconds")
                .register(meterRegistry);

        Gauge.builder("outbox.events.failed", failedEventCount, AtomicLong::get)
                .description("Number of events that exceeded max retry attempts")
                .tag("status", "failed")
                .register(meterRegistry);

        log.info("Outbox metrics registered with Micrometer");
    }

    /**
     * Refreshes the cached metric values.
     * Called periodically by the scheduler.
     * Uses read-only transaction for consistent reads across multiple queries.
     */
    @Transactional(readOnly = true)
    public void refreshMetrics() {
        try {
            // Update backlog size
            long unpublished = outboxRepository.countUnpublished();
            backlogSize.set(unpublished);

            // Update oldest event age
            outboxRepository.findOldestUnpublishedCreatedAt()
                    .ifPresentOrElse(
                            oldest -> {
                                long ageSeconds = Duration.between(oldest, Instant.now()).getSeconds();
                                oldestEventAgeSeconds.set(Math.max(0, ageSeconds));
                            },
                            () -> oldestEventAgeSeconds.set(0)
                    );

            // Update failed event count
            long failed = outboxRepository.countByRetryCountGreaterThanEqual(5);
            failedEventCount.set(failed);

            log.debug("Outbox metrics refreshed: backlog={}, oldestAge={}s, failed={}",
                    unpublished, oldestEventAgeSeconds.get(), failed);

        } catch (Exception e) {
            log.warn("Failed to refresh outbox metrics: {}", e.getMessage());
        }
    }

    /**
     * Records that an event was published successfully.
     */
    public void recordEventPublished(String eventType) {
        meterRegistry.counter("outbox.events.published",
                "event_type", eventType,
                "status", "success"
        ).increment();
    }

    /**
     * Records that an event publication failed.
     */
    public void recordEventPublishFailed(String eventType) {
        meterRegistry.counter("outbox.events.published",
                "event_type", eventType,
                "status", "failure"
        ).increment();
    }

    /**
     * Records that an event was moved to dead letter.
     */
    public void recordEventDeadLettered(String eventType) {
        meterRegistry.counter("outbox.events.dead_lettered",
                "event_type", eventType
        ).increment();
    }
}
