# Phase 6: Consumers & Replay Safety

## Overview

Phase 6 implements **idempotent event consumption** to handle the realities of distributed systems: events can be delivered multiple times due to consumer crashes, Kafka rebalances, or network issues. This phase ensures that processing the same event twice never corrupts data.

## Goals Achieved

1. ✅ Create consumer for payment events
2. ✅ Add `processed_events` table for deduplication
3. ✅ Ensure duplicate events don't break the system
4. ✅ Handle consumer crash and replay scenarios

## The Problem: At-Least-Once Delivery

Kafka guarantees **at-least-once delivery**, meaning:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Scenario: Consumer Crash                      │
│                                                                  │
│  1. Consumer receives event                                      │
│  2. Consumer processes event (sends email, updates DB)           │
│  3. Consumer crashes BEFORE committing offset                    │
│  4. Kafka rebalances, another consumer picks up                  │
│  5. Same event is delivered AGAIN                                │
│                                                                  │
│  Result: Email sent twice, DB updated twice! 💥                  │
└─────────────────────────────────────────────────────────────────┘
```

Without protection, duplicate processing causes:
- Duplicate notifications (customer gets 2 emails)
- Double charges or credits
- Incorrect analytics counts
- Corrupted read models

## The Solution: Idempotent Event Processing

Track which events have been processed and skip duplicates:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Idempotent Processing                         │
│                                                                  │
│  1. Consumer receives event with eventId=ABC                     │
│  2. Check: SELECT * FROM processed_events WHERE event_id='ABC'   │
│  3. If exists → SKIP (already processed)                         │
│  4. If not exists → PROCESS and INSERT into processed_events     │
│  5. Commit Kafka offset                                          │
│                                                                  │
│  Replay: Event ABC comes again                                   │
│  - Check finds existing record → Skip processing                 │
│  - No duplicate side effects! ✅                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. Database Schema (V5 Migration)

```sql
CREATE TABLE processed_events (
    -- Event ID from the original event (used for deduplication)
    event_id UUID PRIMARY KEY,

    -- Event metadata for debugging
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,

    -- Which consumer processed this event
    consumer_group VARCHAR(100) NOT NULL,

    -- When it was processed
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- Result tracking
    processing_result VARCHAR(50),  -- 'SUCCESS', 'SKIPPED', 'FAILED'
    error_message TEXT
);

-- Index for deduplication check (the hot path)
CREATE INDEX idx_processed_events_consumer_event
ON processed_events(consumer_group, event_id);
```

**Key Design Decisions:**

1. **Event ID as Primary Key**: The `eventId` from the event payload is the deduplication key, not the Kafka offset.

2. **Consumer Group Column**: Different consumer groups can process the same event independently. The notification service and analytics service both need to process `PaymentCreated`.

3. **Processing Result**: Tracks SUCCESS, SKIPPED, or FAILED for debugging and monitoring.

### 2. IdempotentEventProcessor Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotentEventProcessor {

    private final ProcessedEventRepository repository;

    /**
     * Processes an event idempotently.
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

        // Step 1: Check if already processed
        if (repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            log.info("Event {} already processed by {}, skipping", eventId, consumerGroup);
            return false;  // Duplicate - skip processing
        }

        try {
            // Step 2: Execute the handler
            handler.run();

            // Step 3: Record successful processing
            recordProcessed(ProcessedEvent.success(
                eventId, eventType, aggregateType, aggregateId, consumerGroup
            ));

            return true;

        } catch (Exception e) {
            // Record failure (prevents infinite retry loops)
            recordProcessed(ProcessedEvent.failed(
                eventId, eventType, aggregateType, aggregateId, consumerGroup,
                e.getMessage()
            ));
            throw e;  // Re-throw for Kafka retry/DLQ
        }
    }
}
```

**Transaction Boundary:**

```
BEGIN TRANSACTION
  1. Check if event already processed (SELECT)
  2. Execute handler (business logic)
  3. Record as processed (INSERT)
COMMIT
```

All three steps happen atomically. If step 2 fails, step 3 doesn't execute, and the event can be retried.

### 3. Kafka Consumer with Manual Acknowledgment

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private static final String CONSUMER_GROUP = "payment-event-consumer";

    private final IdempotentEventProcessor eventProcessor;
    private final PaymentEventHandler eventHandler;
    private final ObjectMapper objectMapper;

    /**
     * Main Kafka listener.
     * Uses manual acknowledgment for exactly-once semantics.
     */
    @KafkaListener(
        topics = "${kafka.topic.payments:payments}",
        groupId = "${spring.kafka.consumer.group-id:payment-ledger-consumers}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            EventEnvelope envelope = parseEvent(record.value());

            // Route to appropriate handler with idempotency protection
            boolean processed = routeEvent(envelope, record.value());

            // Only acknowledge after successful processing
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage());
            // Don't acknowledge - Kafka will redeliver
            throw e;
        }
    }

    private boolean routeEvent(EventEnvelope envelope, String rawPayload) {
        return switch (envelope.eventType) {
            case "PaymentCreated" -> handlePaymentCreated(envelope, rawPayload);
            case "PaymentSettled" -> handlePaymentSettled(envelope, rawPayload);
            default -> {
                eventProcessor.skipEvent(envelope.eventId, envelope.eventType,
                    "Payment", envelope.aggregateId, CONSUMER_GROUP,
                    "Unknown event type");
                yield false;
            }
        };
    }

    private boolean handlePaymentCreated(EventEnvelope envelope, String rawPayload) {
        return eventProcessor.processEvent(
            envelope.eventId, envelope.eventType,
            "Payment", envelope.aggregateId, CONSUMER_GROUP,
            () -> {
                PaymentCreatedEvent event = deserialize(rawPayload, PaymentCreatedEvent.class);
                eventHandler.onPaymentCreated(event);
            }
        );
    }
}
```

**Key Configuration:**

```properties
# Manual acknowledgment - we control when offsets are committed
spring.kafka.listener.ack-mode=manual
spring.kafka.consumer.enable-auto-commit=false

# Read only committed messages (for transactional producers)
spring.kafka.consumer.properties.isolation.level=read_committed

# Start from earliest if no committed offset
spring.kafka.consumer.auto-offset-reset=earliest
```

### 4. Event Handlers

```java
@Service
@Slf4j
public class PaymentEventHandler {

    /**
     * Handles PaymentCreated events.
     * This is called only once per event, guaranteed by IdempotentEventProcessor.
     */
    public void onPaymentCreated(PaymentCreatedEvent event) {
        log.info("Handling PaymentCreated: paymentId={}, amount={} {}",
                event.getPaymentId(),
                event.getAmount(),
                event.getCurrency());

        // Safe to send notification - won't be called twice
        sendPaymentCreatedNotification(event);
    }

    /**
     * Handles PaymentSettled events.
     */
    public void onPaymentSettled(PaymentSettledEvent event) {
        log.info("Handling PaymentSettled: paymentId={}, ledgerTxId={}",
                event.getPaymentId(),
                event.getLedgerTransactionId());

        // Safe to trigger fulfillment - won't be called twice
        triggerFulfillment(event);
    }
}
```

## Failure Scenarios

### Scenario 1: Consumer Crashes After Processing, Before Commit

```
Timeline:
1. Consumer receives PaymentCreated (eventId=ABC)
2. Handler executes (email sent)
3. ProcessedEvent record inserted (committed)
4. Consumer CRASHES before Kafka offset commit
5. Kafka rebalances, delivers event ABC again
6. New consumer checks: "Is ABC processed?" → YES
7. Event skipped, no duplicate email

Result: ✅ No duplicate processing
```

### Scenario 2: Consumer Crashes Before Processing Completes

```
Timeline:
1. Consumer receives PaymentCreated (eventId=ABC)
2. Handler starts executing
3. Consumer CRASHES mid-processing
4. Transaction rolls back (no ProcessedEvent record)
5. Kafka rebalances, delivers event ABC again
6. New consumer checks: "Is ABC processed?" → NO
7. Event processed normally

Result: ✅ Event processed exactly once
```

### Scenario 3: Kafka Rebalance During Processing

```
Timeline:
1. Consumer A receives PaymentCreated (eventId=ABC)
2. Kafka triggers rebalance (new consumer joins)
3. Consumer A loses partition, stops processing
4. Consumer B receives same event ABC
5. Race condition: both might try to process

Protection:
- Database transaction with eventId as primary key
- One INSERT succeeds, other fails with duplicate key
- Loser consumer sees "already processed" and skips

Result: ✅ Only one consumer succeeds
```

### Scenario 4: Network Timeout on Kafka Ack

```
Timeline:
1. Consumer processes event successfully
2. ProcessedEvent record committed
3. Kafka ack times out (network issue)
4. Kafka thinks offset not committed
5. Same event redelivered
6. Consumer checks: "Is ABC processed?" → YES
7. Event skipped

Result: ✅ No duplicate processing
```

## Different Consumer Groups

Each consumer group maintains independent processing state:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Event: PaymentCreated                         │
│                    EventId: ABC-123                              │
└─────────────────────┬────────────────────────────────────────────┘
                      │
        ┌─────────────┼─────────────┐
        │             │             │
        ▼             ▼             ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│ Notification  │ │  Analytics    │ │    Audit      │
│   Service     │ │   Service     │ │   Service     │
│               │ │               │ │               │
│ group: notif  │ │ group: analy  │ │ group: audit  │
└───────────────┘ └───────────────┘ └───────────────┘
        │             │             │
        ▼             ▼             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    processed_events table                        │
│                                                                  │
│  event_id  | consumer_group     | processed_at                   │
│  ABC-123   | notification-svc   | 2024-01-05 10:30:00           │
│  ABC-123   | analytics-svc      | 2024-01-05 10:30:01           │
│  ABC-123   | audit-svc          | 2024-01-05 10:30:02           │
└─────────────────────────────────────────────────────────────────┘

Each service processes the same event independently.
Duplicates within a service are prevented.
```

## Testing Strategy

### Unit Tests

1. **First event processing executes handler**
2. **Duplicate events are skipped**
3. **Different consumer groups process independently**
4. **Failed processing is recorded**
5. **Concurrent processing is safe**

### Integration Tests

```java
@Test
@DisplayName("Simulating consumer crash and replay scenario")
void testConsumerCrashAndReplay() {
    UUID eventId = UUID.randomUUID();
    AtomicInteger handlerCallCount = new AtomicInteger(0);

    // First processing (before crash)
    boolean firstProcess = eventProcessor.processEvent(
        eventId, "PaymentCreated", "Payment", aggregateId, CONSUMER_GROUP,
        handlerCallCount::incrementAndGet
    );

    // Simulate crash - same event is replayed
    boolean replayProcess = eventProcessor.processEvent(
        eventId, "PaymentCreated", "Payment", aggregateId, CONSUMER_GROUP,
        handlerCallCount::incrementAndGet
    );

    assertTrue(firstProcess, "First should succeed");
    assertFalse(replayProcess, "Replay should be skipped");
    assertEquals(1, handlerCallCount.get(), "Handler called only once");
}
```

## Files Created

```
src/main/java/com/flagship/payment_ledger/consumer/
├── ProcessedEvent.java            # Domain model
├── ProcessedEventEntity.java      # JPA entity
├── ProcessedEventRepository.java  # Repository
├── IdempotentEventProcessor.java  # Core deduplication logic
├── PaymentEventConsumer.java      # Kafka listener
└── PaymentEventHandler.java       # Business logic handlers

src/main/resources/db/migration/
└── V5__create_processed_events_table.sql

src/test/java/com/flagship/payment_ledger/consumer/
└── IdempotentEventProcessorTest.java
```

## Configuration

```properties
# Kafka consumer settings
spring.kafka.consumer.group-id=payment-ledger-consumers
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.listener.ack-mode=manual
spring.kafka.consumer.properties.isolation.level=read_committed
spring.kafka.listener.concurrency=3

# Feature flags
consumer.enabled=true
```

## Monitoring

Key metrics to track:

| Metric | Query | Alert Threshold |
|--------|-------|-----------------|
| Events processed | `count(processed_events) WHERE result='SUCCESS'` | - |
| Events skipped (duplicates) | `count(processed_events) WHERE result='SKIPPED'` | Spike indicates replay |
| Events failed | `count(processed_events) WHERE result='FAILED'` | > 0 |
| Processing lag | Kafka consumer lag metric | > 1000 |

```sql
-- Find failed events for investigation
SELECT * FROM processed_events
WHERE processing_result = 'FAILED'
AND processed_at > NOW() - INTERVAL '1 hour'
ORDER BY processed_at DESC;

-- Count duplicates detected (indicates replays happening)
SELECT consumer_group, COUNT(*)
FROM processed_events
WHERE processing_result = 'SKIPPED'
GROUP BY consumer_group;
```

## Key Invariants Maintained

1. **Each event processed exactly once per consumer group**
   - `processed_events` table with (event_id, consumer_group) constraint

2. **Crash recovery is safe**
   - Transaction ensures atomicity of processing + recording

3. **No duplicate side effects**
   - Notifications sent once
   - Analytics counted once
   - Downstream triggers fired once

## Next Phase: Phase 7 - Observability

Phase 7 will focus on:
- Structured logging with correlation IDs
- Metrics for payment latency and failures
- Kafka lag monitoring
- Alerting for 3 AM scenarios
