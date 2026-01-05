package com.flagship.payment_ledger.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for refreshing observable metrics.
 *
 * Phase 7: Observability
 *
 * Periodically updates gauge metrics that require database queries.
 * This avoids hitting the database on every Prometheus scrape.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetricsScheduler {

    private final OutboxMetrics outboxMetrics;

    /**
     * Refresh outbox metrics every 15 seconds.
     *
     * This is a good balance between freshness and database load.
     * Adjust based on your Prometheus scrape interval.
     */
    @Scheduled(fixedRateString = "${metrics.refresh.interval:15000}")
    public void refreshOutboxMetrics() {
        outboxMetrics.refreshMetrics();
    }
}
