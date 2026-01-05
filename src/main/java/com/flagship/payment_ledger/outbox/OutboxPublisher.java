package com.flagship.payment_ledger.outbox;

import com.flagship.payment_ledger.observability.OutboxMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Background publisher that reads events from the outbox and publishes to Kafka.
 *
 * Phase 5: Transactional Outbox Pattern
 *
 * This component:
 * 1. Polls the outbox_events table for unpublished events
 * 2. Publishes each event to Kafka
 * 3. Marks events as published on success
 * 4. Handles failures with retry logic
 *
 * Key design decisions:
 * - Uses SELECT FOR UPDATE SKIP LOCKED for concurrent publishers
 * - Publishes synchronously for ordering guarantees within aggregate
 * - Uses aggregate ID as Kafka key for partition affinity
 * - Configurable poll interval and batch size
 *
 * Failure handling:
 * - Failed publishes increment retry count
 * - Events exceeding max retries become "dead letter" events
 * - Dead letter events need manual intervention
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxService outboxService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxMetrics outboxMetrics;

    @Value("${kafka.topic.payments:payments}")
    private String paymentsTopic;

    @Value("${outbox.publisher.batch-size:100}")
    private int batchSize;

    @Value("${outbox.publisher.max-retries:5}")
    private int maxRetries;

    /**
     * Scheduled task that polls for and publishes unpublished events.
     *
     * Runs at a fixed rate (configurable via outbox.publisher.poll-interval-ms).
     * Each execution fetches a batch of events and publishes them.
     */
    @Scheduled(fixedRateString = "${outbox.publisher.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        try {
            List<OutboxEvent> events = outboxService.findUnpublishedEvents(batchSize);

            if (events.isEmpty()) {
                return;
            }

            log.debug("Found {} unpublished events to process", events.size());

            for (OutboxEvent event : events) {
                publishEvent(event);
            }

        } catch (Exception e) {
            log.error("Error in outbox publisher polling loop", e);
        }
    }

    /**
     * Publishes a single event to Kafka.
     *
     * Uses aggregate ID as partition key to ensure ordering within aggregate.
     * Waits for acknowledgment before marking as published.
     */
    private void publishEvent(OutboxEvent event) {
        if (event.getRetryCount() >= maxRetries) {
            log.warn("Event {} has exceeded max retries ({}), moving to dead letter. eventType={}, aggregateId={}",
                    event.getId(), maxRetries, event.getEventType(), event.getAggregateId());
            outboxMetrics.recordEventDeadLettered(event.getEventType());
            return;
        }

        String topic = getTopicForEvent(event);
        String key = event.getAggregateId().toString();
        String value = event.getPayload();

        try {
            // Send synchronously to ensure ordering and proper error handling
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, key, value);

            // Wait for the send to complete
            SendResult<String, String> result = future.get();

            log.debug("Published event: eventId={}, topic={}, partition={}, offset={}, eventType={}",
                    event.getId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    event.getEventType());

            // Mark as published
            outboxService.markPublished(event.getId());

            // Record success metric
            outboxMetrics.recordEventPublished(event.getEventType());

        } catch (Exception e) {
            log.error("Failed to publish event: eventId={}, eventType={}, error={}",
                    event.getId(), event.getEventType(), e.getMessage());
            outboxService.markFailed(event.getId(), e.getMessage());

            // Record failure metric
            outboxMetrics.recordEventPublishFailed(event.getEventType());
        }
    }

    /**
     * Determines the Kafka topic for an event based on its aggregate type.
     *
     * Currently routes all payment events to the payments topic.
     * Can be extended for different aggregate types in the future.
     */
    private String getTopicForEvent(OutboxEvent event) {
        return switch (event.getAggregateType()) {
            case "Payment" -> paymentsTopic;
            default -> paymentsTopic; // Default to payments topic
        };
    }

    /**
     * Manually triggers publishing (useful for testing).
     */
    public void triggerPublish() {
        publishPendingEvents();
    }

    /**
     * Gets the count of unpublished events (for monitoring).
     */
    public long getUnpublishedCount() {
        return outboxService.countUnpublished();
    }
}
