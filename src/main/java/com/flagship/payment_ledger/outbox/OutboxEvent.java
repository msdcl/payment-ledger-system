package com.flagship.payment_ledger.outbox;

import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model for an outbox event.
 *
 * Phase 5: Transactional Outbox Pattern
 *
 * An outbox event represents a domain event that needs to be published to Kafka.
 * Events are written to the database atomically with business data, then
 * published asynchronously by a background process.
 *
 * Key properties:
 * - Immutable value object
 * - Contains all information needed for publishing
 * - Tracks publishing status and retry information
 */
@Value
public class OutboxEvent {
    UUID id;
    String aggregateType;      // e.g., "Payment"
    UUID aggregateId;          // e.g., payment ID
    String eventType;          // e.g., "PaymentCreated"
    String payload;            // JSON payload
    Instant createdAt;
    Instant publishedAt;       // null if not yet published
    int retryCount;
    String lastError;
    Long sequenceNumber;

    /**
     * Creates a new unpublished outbox event.
     */
    public static OutboxEvent create(String aggregateType, UUID aggregateId,
                                     String eventType, String payload) {
        return new OutboxEvent(
            UUID.randomUUID(),
            aggregateType,
            aggregateId,
            eventType,
            payload,
            Instant.now(),
            null,  // not published yet
            0,     // no retries yet
            null,  // no errors yet
            null   // sequence assigned by database
        );
    }

    /**
     * Checks if this event has been published.
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * Creates a new event marked as published.
     */
    public OutboxEvent markPublished() {
        return new OutboxEvent(
            this.id,
            this.aggregateType,
            this.aggregateId,
            this.eventType,
            this.payload,
            this.createdAt,
            Instant.now(),
            this.retryCount,
            null,  // clear error on success
            this.sequenceNumber
        );
    }

    /**
     * Creates a new event with incremented retry count and error message.
     */
    public OutboxEvent markRetry(String errorMessage) {
        return new OutboxEvent(
            this.id,
            this.aggregateType,
            this.aggregateId,
            this.eventType,
            this.payload,
            this.createdAt,
            this.publishedAt,
            this.retryCount + 1,
            errorMessage,
            this.sequenceNumber
        );
    }
}
