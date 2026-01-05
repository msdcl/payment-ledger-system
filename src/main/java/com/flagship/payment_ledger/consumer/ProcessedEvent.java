package com.flagship.payment_ledger.consumer;

import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model for a processed event record.
 *
 * Phase 6: Consumers & Replay Safety
 *
 * Tracks which events have been processed to enable:
 * - Idempotent event processing
 * - Safe replay of events after consumer crashes
 * - Multiple consumer groups processing same events independently
 */
@Value
public class ProcessedEvent {
    UUID eventId;
    String eventType;
    String aggregateType;
    UUID aggregateId;
    String consumerGroup;
    Instant processedAt;
    ProcessingResult result;
    String errorMessage;

    public enum ProcessingResult {
        SUCCESS,    // Event processed successfully
        SKIPPED,    // Event skipped (e.g., not relevant to this consumer)
        FAILED      // Event processing failed (will not retry)
    }

    /**
     * Creates a successful processing record.
     */
    public static ProcessedEvent success(UUID eventId, String eventType,
                                         String aggregateType, UUID aggregateId,
                                         String consumerGroup) {
        return new ProcessedEvent(
            eventId,
            eventType,
            aggregateType,
            aggregateId,
            consumerGroup,
            Instant.now(),
            ProcessingResult.SUCCESS,
            null
        );
    }

    /**
     * Creates a skipped processing record.
     */
    public static ProcessedEvent skipped(UUID eventId, String eventType,
                                         String aggregateType, UUID aggregateId,
                                         String consumerGroup, String reason) {
        return new ProcessedEvent(
            eventId,
            eventType,
            aggregateType,
            aggregateId,
            consumerGroup,
            Instant.now(),
            ProcessingResult.SKIPPED,
            reason
        );
    }

    /**
     * Creates a failed processing record.
     */
    public static ProcessedEvent failed(UUID eventId, String eventType,
                                        String aggregateType, UUID aggregateId,
                                        String consumerGroup, String errorMessage) {
        return new ProcessedEvent(
            eventId,
            eventType,
            aggregateType,
            aggregateId,
            consumerGroup,
            Instant.now(),
            ProcessingResult.FAILED,
            errorMessage
        );
    }
}
