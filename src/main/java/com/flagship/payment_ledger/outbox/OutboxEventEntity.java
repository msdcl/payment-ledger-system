package com.flagship.payment_ledger.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for outbox events.
 *
 * Phase 5: Transactional Outbox Pattern
 *
 * This entity maps to the outbox_events table and is used for
 * persisting events that need to be published to Kafka.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "sequence_number", insertable = false, updatable = false)
    private Long sequenceNumber;

    /**
     * Creates an entity from a domain object.
     */
    public static OutboxEventEntity fromDomain(OutboxEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setId(event.getId());
        entity.setAggregateType(event.getAggregateType());
        entity.setAggregateId(event.getAggregateId());
        entity.setEventType(event.getEventType());
        entity.setPayload(event.getPayload());
        entity.setCreatedAt(event.getCreatedAt());
        entity.setPublishedAt(event.getPublishedAt());
        entity.setRetryCount(event.getRetryCount());
        entity.setLastError(event.getLastError());
        // sequenceNumber is set by database
        return entity;
    }

    /**
     * Converts this entity to a domain object.
     */
    public OutboxEvent toDomain() {
        return new OutboxEvent(
            this.id,
            this.aggregateType,
            this.aggregateId,
            this.eventType,
            this.payload,
            this.createdAt,
            this.publishedAt,
            this.retryCount,
            this.lastError,
            this.sequenceNumber
        );
    }

    /**
     * Marks this event as published.
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Marks this event as failed with an error message.
     */
    public void markFailed(String errorMessage) {
        this.retryCount++;
        this.lastError = errorMessage;
    }
}
