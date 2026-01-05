# Phase 5: Events as Facts (Kafka + Outbox)

## Overview

Phase 5 implements the **Transactional Outbox Pattern** for reliable event publishing to Kafka. This ensures that domain events are published exactly when business operations succeed, with at-least-once delivery guarantees.

## Goals Achieved

1. ✅ Add `outbox_events` table
2. ✅ Write outbox rows inside DB transactions
3. ✅ Implement background publisher → Kafka
4. ✅ Publish events: `PaymentCreated`, `PaymentAuthorized`, `PaymentSettled`, `PaymentFailed`

## Architecture

### The Transactional Outbox Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│                        Single DB Transaction                      │
│  ┌─────────────────┐    ┌─────────────────┐                      │
│  │  Business Data   │    │  Outbox Event   │                      │
│  │  (payments)      │    │  (outbox_events)│                      │
│  └─────────────────┘    └─────────────────┘                      │
│          │                       │                                │
│          └───────────┬───────────┘                                │
│                      │                                            │
│               COMMIT or ROLLBACK                                  │
└─────────────────────────────────────────────────────────────────┘
                       │
                       ▼
         ┌─────────────────────────┐
         │   Background Publisher   │
         │   (polls outbox table)   │
         └─────────────────────────┘
                       │
                       ▼
         ┌─────────────────────────┐
         │         Kafka           │
         │   (payments topic)      │
         └─────────────────────────┘
```

### Why This Pattern?

**Problem**: Dual-write problem - updating a database AND publishing to Kafka is not atomic.

```java
// WRONG - Not atomic!
savePayment(payment);  // Succeeds
publishToKafka(event); // Fails! Event is lost.
```

**Solution**: Write event to database in same transaction, then publish asynchronously.

```java
// CORRECT - Atomic!
@Transactional
void createPayment(payment) {
    savePayment(payment);           // Same transaction
    saveOutboxEvent(paymentEvent);  // Same transaction
}
// If either fails, both are rolled back
```

## Implementation Details

### Database Schema (V4 Migration)

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,  -- 'Payment'
    aggregate_id UUID NOT NULL,             -- payment ID
    event_type VARCHAR(100) NOT NULL,       -- 'PaymentCreated'
    payload JSONB NOT NULL,                 -- Event data
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,  -- NULL = unpublished
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    sequence_number BIGSERIAL NOT NULL
);

-- Index for publisher's main query
CREATE INDEX idx_outbox_events_unpublished
ON outbox_events(created_at)
WHERE published_at IS NULL;
```

### Domain Events

| Event | When Published | Key Data |
|-------|----------------|----------|
| `PaymentCreated` | Payment created via API | paymentId, amount, currency, accounts |
| `PaymentAuthorized` | Payment authorized | paymentId, amount |
| `PaymentSettled` | Payment settled with ledger | paymentId, ledgerTransactionId |
| `PaymentFailed` | Payment failed | paymentId, failureReason |

### Event Payload Example

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "paymentId": "123e4567-e89b-12d3-a456-426614174000",
  "amount": 100.00,
  "currency": "USD",
  "fromAccountId": "...",
  "toAccountId": "...",
  "status": "CREATED",
  "occurredAt": "2024-01-05T10:30:00Z"
}
```

### Key Components

#### OutboxService

Writes events within existing transactions:

```java
@Transactional(propagation = Propagation.MANDATORY)
public OutboxEvent saveEvent(String aggregateType, UUID aggregateId,
                             String eventType, Object payload) {
    // Serializes payload to JSON and saves to outbox table
    // MANDATORY propagation ensures caller has active transaction
}
```

#### OutboxPublisher

Background job that polls and publishes:

```java
@Scheduled(fixedRateString = "${outbox.publisher.poll-interval-ms:1000}")
public void publishPendingEvents() {
    List<OutboxEvent> events = findUnpublishedEvents(batchSize);
    for (OutboxEvent event : events) {
        try {
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
            markPublished(event.getId());
        } catch (Exception e) {
            markFailed(event.getId(), e.getMessage());
        }
    }
}
```

### Kafka Configuration

```properties
# Kafka settings
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3
spring.kafka.producer.properties.enable.idempotence=true

# Outbox publisher settings
outbox.publisher.enabled=true
outbox.publisher.poll-interval-ms=1000
outbox.publisher.batch-size=100
outbox.publisher.max-retries=5

# Topic
kafka.topic.payments=payments
```

## Failure Handling

### Publisher Failure

If Kafka is unavailable:
1. Event remains in outbox (unpublished)
2. Retry count increments
3. Error message stored
4. Publisher retries on next poll

### Retry Logic

```java
if (event.getRetryCount() >= maxRetries) {
    log.warn("Event {} exceeded max retries, needs manual intervention", event.getId());
    return; // Skip, becomes "dead letter"
}
```

### Dead Letter Events

Events that exceed max retries require manual intervention:

```sql
-- Find dead letter events
SELECT * FROM outbox_events
WHERE published_at IS NULL AND retry_count >= 5;
```

## Guarantees

| Property | Guarantee |
|----------|-----------|
| Atomicity | Event written with business data in same transaction |
| Durability | Event persisted to PostgreSQL before publish attempt |
| At-least-once | Events may be published multiple times (consumers must be idempotent) |
| Ordering | Events for same aggregate partitioned together in Kafka |

## Testing Strategy

### Unit Tests (OutboxServiceTest)

- Event creation with correct data
- Finding unpublished events
- Marking events as published
- Retry count increment on failure
- Event payload serialization

### Integration Tests (OutboxPublisherTest)

- Events published to Kafka
- Events marked as published after send
- Aggregate ID used as partition key
- Unpublished count monitoring

## Files Created/Modified

### New Files

```
src/main/java/com/flagship/payment_ledger/
├── outbox/
│   ├── OutboxEvent.java           # Domain model
│   ├── OutboxEventEntity.java     # JPA entity
│   ├── OutboxEventRepository.java # Repository
│   ├── OutboxService.java         # Service for writing events
│   └── OutboxPublisher.java       # Background Kafka publisher
├── payment/event/
│   ├── PaymentEvent.java          # Base event interface
│   ├── PaymentCreatedEvent.java
│   ├── PaymentAuthorizedEvent.java
│   ├── PaymentSettledEvent.java
│   └── PaymentFailedEvent.java
├── config/
│   ├── KafkaConfig.java           # Kafka topic configuration
│   └── JacksonConfig.java         # JSON serialization

src/main/resources/
└── db/migration/
    └── V4__create_outbox_events_table.sql

src/test/java/com/flagship/payment_ledger/outbox/
├── OutboxServiceTest.java
└── OutboxPublisherTest.java
```

### Modified Files

- `build.gradle` - Added Kafka dependencies
- `application.properties` - Added Kafka and outbox configuration
- `PaymentController.java` - Writes PaymentCreated event
- `PaymentSettlementService.java` - Writes PaymentSettled event

## Running Locally

### Prerequisites

1. PostgreSQL running on port 5432
2. Redis running on port 6379
3. Kafka running on port 9092

### Docker Setup for Kafka

```bash
# Start Kafka with Zookeeper
docker run -d --name zookeeper -p 2181:2181 confluentinc/cp-zookeeper:7.5.0 \
  -e ZOOKEEPER_CLIENT_PORT=2181

docker run -d --name kafka -p 9092:9092 confluentinc/cp-kafka:7.5.0 \
  -e KAFKA_BROKER_ID=1 \
  -e KAFKA_ZOOKEEPER_CONNECT=host.docker.internal:2181 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1
```

### Verify Events in Kafka

```bash
# Consume from payments topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic payments --from-beginning
```

## Key Design Decisions

### 1. MANDATORY Propagation for saveEvent()

Ensures OutboxService is always called within an existing transaction. Prevents accidental standalone event writes.

### 2. REQUIRES_NEW for Publisher Operations

Publisher uses separate transactions for finding and marking events. This prevents long-running transactions from blocking publishers.

### 3. SELECT FOR UPDATE SKIP LOCKED

Allows multiple publisher instances to run concurrently without blocking each other.

### 4. Aggregate ID as Kafka Key

Ensures all events for a single payment go to the same partition, preserving order.

### 5. JSON Payload Storage

Flexible schema evolution - events can have different shapes without schema migrations.

## Next Phase: Phase 6 - Consumers & Replay Safety

Phase 6 will focus on:
- Creating consumers for settlement events
- Adding `processed_events` table for deduplication
- Ensuring duplicate events don't break the system
- Simulating consumer crash and replay scenarios
