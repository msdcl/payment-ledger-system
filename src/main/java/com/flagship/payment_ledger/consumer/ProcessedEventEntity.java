package com.flagship.payment_ledger.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for processed events.
 *
 * Phase 6: Consumers & Replay Safety
 *
 * This entity maps to the processed_events table and tracks
 * which events have been processed by each consumer group.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_result", length = 50)
    private ProcessedEvent.ProcessingResult processingResult;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Creates an entity from a domain object.
     */
    public static ProcessedEventEntity fromDomain(ProcessedEvent event) {
        return new ProcessedEventEntity(
            event.getEventId(),
            event.getEventType(),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getConsumerGroup(),
            event.getProcessedAt(),
            event.getResult(),
            event.getErrorMessage()
        );
    }

    /**
     * Converts this entity to a domain object.
     */
    public ProcessedEvent toDomain() {
        return new ProcessedEvent(
            this.eventId,
            this.eventType,
            this.aggregateType,
            this.aggregateId,
            this.consumerGroup,
            this.processedAt,
            this.processingResult,
            this.errorMessage
        );
    }
}
