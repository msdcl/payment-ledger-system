package com.flagship.payment_ledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for outbox events.
 *
 * Phase 5: Transactional Outbox Pattern
 *
 * Provides methods for:
 * - Saving events (done within business transactions)
 * - Finding unpublished events (done by background publisher)
 * - Marking events as published (done after successful Kafka send)
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Finds unpublished events ordered by creation time.
     * This is the main query used by the background publisher.
     *
     * @param limit Maximum number of events to fetch
     * @return List of unpublished events
     */
    @Query(value = """
        SELECT * FROM outbox_events
        WHERE published_at IS NULL
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedEventsForUpdate(@Param("limit") int limit);

    /**
     * Finds unpublished events (without locking, for read-only queries).
     */
    @Query("""
        SELECT e FROM OutboxEventEntity e
        WHERE e.publishedAt IS NULL
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEventEntity> findUnpublishedEvents();

    /**
     * Finds events for a specific aggregate.
     * Useful for debugging and auditing.
     */
    List<OutboxEventEntity> findByAggregateTypeAndAggregateIdOrderBySequenceNumberAsc(
        String aggregateType, UUID aggregateId);

    /**
     * Counts unpublished events.
     * Useful for monitoring/metrics.
     */
    @Query("SELECT COUNT(e) FROM OutboxEventEntity e WHERE e.publishedAt IS NULL")
    long countUnpublished();

    /**
     * Deletes published events older than the given timestamp.
     * Used for cleanup/retention.
     */
    @Modifying
    @Query("DELETE FROM OutboxEventEntity e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :before")
    int deletePublishedEventsBefore(@Param("before") Instant before);

    /**
     * Finds events that have failed too many times.
     * These may need manual intervention.
     */
    @Query("""
        SELECT e FROM OutboxEventEntity e
        WHERE e.publishedAt IS NULL AND e.retryCount >= :maxRetries
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEventEntity> findDeadLetterEvents(@Param("maxRetries") int maxRetries);

    /**
     * Counts events that have exceeded retry threshold.
     * Used for monitoring failed events.
     */
    long countByRetryCountGreaterThanEqual(int retryCount);

    /**
     * Finds the oldest unpublished event's creation timestamp.
     * Used for lag monitoring.
     */
    @Query("""
        SELECT MIN(e.createdAt) FROM OutboxEventEntity e
        WHERE e.publishedAt IS NULL
        """)
    java.util.Optional<Instant> findOldestUnpublishedCreatedAt();
}
