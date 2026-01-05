package com.flagship.payment_ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for writing events to the outbox.
 *
 * Phase 5: Transactional Outbox Pattern
 *
 * This service is called within existing business transactions to write
 * events atomically with business data. The key principle is:
 *
 * "If the business operation commits, the event is guaranteed to be written."
 *
 * Events are NOT published directly to Kafka here. That's done by the
 * OutboxPublisher, which runs as a background process.
 *
 * Usage:
 * 1. Call saveEvent() within your @Transactional business method
 * 2. The event is written to the database in the same transaction
 * 3. If the transaction commits, the event will be published to Kafka
 * 4. If the transaction rolls back, the event is also rolled back
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Saves an event to the outbox within the current transaction.
     *
     * IMPORTANT: This method must be called within an existing transaction.
     * It uses MANDATORY propagation to ensure it participates in the
     * caller's transaction.
     *
     * @param aggregateType Type of the aggregate (e.g., "Payment")
     * @param aggregateId ID of the aggregate
     * @param eventType Type of the event (e.g., "PaymentCreated")
     * @param payload Event payload object (will be serialized to JSON)
     * @return The saved event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent saveEvent(String aggregateType, UUID aggregateId,
                                  String eventType, Object payload) {
        String jsonPayload = serializePayload(payload);

        OutboxEvent event = OutboxEvent.create(aggregateType, aggregateId, eventType, jsonPayload);
        OutboxEventEntity entity = OutboxEventEntity.fromDomain(event);

        OutboxEventEntity saved = repository.save(entity);

        log.debug("Saved outbox event: type={}, aggregateType={}, aggregateId={}",
                eventType, aggregateType, aggregateId);

        return saved.toDomain();
    }

    /**
     * Finds unpublished events for the publisher to process.
     * Uses SELECT FOR UPDATE SKIP LOCKED to allow concurrent publishers.
     *
     * @param limit Maximum number of events to fetch
     * @return List of unpublished events
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> findUnpublishedEvents(int limit) {
        return repository.findUnpublishedEventsForUpdate(limit)
                .stream()
                .map(OutboxEventEntity::toDomain)
                .toList();
    }

    /**
     * Marks an event as successfully published.
     *
     * @param eventId ID of the event to mark
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        repository.findById(eventId).ifPresent(entity -> {
            entity.markPublished();
            repository.save(entity);
            log.debug("Marked event {} as published", eventId);
        });
    }

    /**
     * Marks an event as failed with an error message.
     *
     * @param eventId ID of the event
     * @param errorMessage Error message
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, String errorMessage) {
        repository.findById(eventId).ifPresent(entity -> {
            entity.markFailed(errorMessage);
            repository.save(entity);
            log.warn("Marked event {} as failed (retry #{}): {}",
                    eventId, entity.getRetryCount(), errorMessage);
        });
    }

    /**
     * Gets events for a specific aggregate (for debugging/auditing).
     */
    @Transactional(readOnly = true)
    public List<OutboxEvent> getEventsForAggregate(String aggregateType, UUID aggregateId) {
        return repository.findByAggregateTypeAndAggregateIdOrderBySequenceNumberAsc(
                aggregateType, aggregateId)
                .stream()
                .map(OutboxEventEntity::toDomain)
                .toList();
    }

    /**
     * Counts unpublished events (for monitoring).
     */
    @Transactional(readOnly = true)
    public long countUnpublished() {
        return repository.countUnpublished();
    }

    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event payload", e);
        }
    }
}
