package com.flagship.payment_ledger.consumer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for processed events.
 *
 * Phase 6: Consumers & Replay Safety
 *
 * Provides methods for:
 * - Checking if an event was already processed (deduplication)
 * - Recording processed events
 * - Querying processing history for debugging
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    /**
     * Checks if an event has already been processed by a specific consumer group.
     * This is the primary deduplication check.
     *
     * @param eventId Event ID to check
     * @param consumerGroup Consumer group to check
     * @return true if event was already processed
     */
    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);

    /**
     * Finds all processed events for a specific aggregate.
     * Useful for debugging and auditing.
     */
    List<ProcessedEventEntity> findByAggregateTypeAndAggregateIdOrderByProcessedAtAsc(
        String aggregateType, UUID aggregateId);

    /**
     * Finds all processed events for a consumer group.
     * Useful for monitoring.
     */
    List<ProcessedEventEntity> findByConsumerGroupOrderByProcessedAtDesc(String consumerGroup);

    /**
     * Counts events processed by a consumer group.
     * Useful for metrics.
     */
    long countByConsumerGroup(String consumerGroup);

    /**
     * Counts failed events for a consumer group.
     * Useful for alerting.
     */
    @Query("""
        SELECT COUNT(e) FROM ProcessedEventEntity e
        WHERE e.consumerGroup = :consumerGroup
        AND e.processingResult = 'FAILED'
        """)
    long countFailedByConsumerGroup(@Param("consumerGroup") String consumerGroup);

    /**
     * Deletes old processed events for cleanup/retention.
     *
     * @param before Delete events processed before this timestamp
     * @return Number of deleted records
     */
    @Modifying
    @Query("DELETE FROM ProcessedEventEntity e WHERE e.processedAt < :before")
    int deleteEventsProcessedBefore(@Param("before") Instant before);

    /**
     * Finds recently failed events for retry or investigation.
     */
    @Query("""
        SELECT e FROM ProcessedEventEntity e
        WHERE e.consumerGroup = :consumerGroup
        AND e.processingResult = 'FAILED'
        AND e.processedAt > :since
        ORDER BY e.processedAt DESC
        """)
    List<ProcessedEventEntity> findRecentFailures(
        @Param("consumerGroup") String consumerGroup,
        @Param("since") Instant since);
}
