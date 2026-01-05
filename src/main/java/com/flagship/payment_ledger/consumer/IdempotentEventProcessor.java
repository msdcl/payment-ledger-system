package com.flagship.payment_ledger.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Service for idempotent event processing.
 *
 * Phase 6: Consumers & Replay Safety
 *
 * This service ensures that each event is processed exactly once
 * by a given consumer group, even if:
 * - The event is replayed after a consumer crash
 * - Multiple consumer instances receive the same event
 * - Kafka rebalances cause duplicate delivery
 *
 * Usage:
 * <pre>
 * idempotentEventProcessor.processEvent(
 *     eventId, eventType, aggregateType, aggregateId, consumerGroup,
 *     () -> {
 *         // Your event handling logic here
 *         // This will only execute once per event
 *     }
 * );
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotentEventProcessor {

    private final ProcessedEventRepository repository;

    /**
     * Processes an event idempotently.
     *
     * If the event has already been processed by this consumer group,
     * the handler is NOT invoked and we return immediately.
     *
     * If processing succeeds, a record is written to prevent future
     * duplicate processing.
     *
     * @param eventId Unique event ID (from event payload)
     * @param eventType Type of event (e.g., "PaymentCreated")
     * @param aggregateType Type of aggregate (e.g., "Payment")
     * @param aggregateId ID of the aggregate
     * @param consumerGroup Name of the consumer group
     * @param handler The processing logic to execute
     * @return true if event was processed, false if it was a duplicate
     */
    @Transactional
    public boolean processEvent(UUID eventId, String eventType,
                                String aggregateType, UUID aggregateId,
                                String consumerGroup, Runnable handler) {
        // Check if already processed
        if (isAlreadyProcessed(eventId, consumerGroup)) {
            log.info("Event {} already processed by consumer group {}, skipping",
                    eventId, consumerGroup);
            return false;
        }

        try {
            // Execute the handler
            handler.run();

            // Record successful processing
            recordProcessed(ProcessedEvent.success(
                eventId, eventType, aggregateType, aggregateId, consumerGroup
            ));

            log.debug("Successfully processed event {} by consumer group {}",
                    eventId, consumerGroup);
            return true;

        } catch (Exception e) {
            // Record failed processing
            recordProcessed(ProcessedEvent.failed(
                eventId, eventType, aggregateType, aggregateId, consumerGroup,
                e.getMessage()
            ));

            log.error("Failed to process event {} by consumer group {}: {}",
                    eventId, consumerGroup, e.getMessage(), e);

            // Re-throw to trigger Kafka retry or DLQ
            throw e;
        }
    }

    /**
     * Processes an event idempotently with a return value.
     *
     * @param <T> Return type of the handler
     * @param eventId Unique event ID
     * @param eventType Type of event
     * @param aggregateType Type of aggregate
     * @param aggregateId ID of the aggregate
     * @param consumerGroup Name of the consumer group
     * @param handler The processing logic to execute
     * @return Result wrapped in ProcessingResult
     */
    @Transactional
    public <T> ProcessingResult<T> processEventWithResult(
            UUID eventId, String eventType,
            String aggregateType, UUID aggregateId,
            String consumerGroup, Supplier<T> handler) {

        if (isAlreadyProcessed(eventId, consumerGroup)) {
            log.info("Event {} already processed by consumer group {}, skipping",
                    eventId, consumerGroup);
            return ProcessingResult.skipped();
        }

        try {
            T result = handler.get();

            recordProcessed(ProcessedEvent.success(
                eventId, eventType, aggregateType, aggregateId, consumerGroup
            ));

            return ProcessingResult.success(result);

        } catch (Exception e) {
            recordProcessed(ProcessedEvent.failed(
                eventId, eventType, aggregateType, aggregateId, consumerGroup,
                e.getMessage()
            ));

            throw e;
        }
    }

    /**
     * Marks an event as skipped (not relevant to this consumer).
     * This prevents the event from being reprocessed.
     */
    @Transactional
    public void skipEvent(UUID eventId, String eventType,
                          String aggregateType, UUID aggregateId,
                          String consumerGroup, String reason) {
        if (isAlreadyProcessed(eventId, consumerGroup)) {
            return;
        }

        recordProcessed(ProcessedEvent.skipped(
            eventId, eventType, aggregateType, aggregateId, consumerGroup, reason
        ));

        log.debug("Skipped event {} by consumer group {}: {}",
                eventId, consumerGroup, reason);
    }

    /**
     * Checks if an event was already processed by this consumer group.
     */
    public boolean isAlreadyProcessed(UUID eventId, String consumerGroup) {
        return repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
    }

    private void recordProcessed(ProcessedEvent event) {
        ProcessedEventEntity entity = ProcessedEventEntity.fromDomain(event);
        repository.save(entity);
    }

    /**
     * Result wrapper for event processing.
     */
    public static class ProcessingResult<T> {
        private final T value;
        private final boolean processed;

        private ProcessingResult(T value, boolean processed) {
            this.value = value;
            this.processed = processed;
        }

        public static <T> ProcessingResult<T> success(T value) {
            return new ProcessingResult<>(value, true);
        }

        public static <T> ProcessingResult<T> skipped() {
            return new ProcessingResult<>(null, false);
        }

        public T getValue() {
            return value;
        }

        public boolean wasProcessed() {
            return processed;
        }

        public boolean wasSkipped() {
            return !processed;
        }
    }
}
