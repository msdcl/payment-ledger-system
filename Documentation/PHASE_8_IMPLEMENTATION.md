# Phase 8: Failure Scenarios & Resilience

## Overview

Phase 8 documents and tests the system's behavior under various failure conditions. This is critical for understanding how the payment ledger maintains data integrity when components fail. The goal is to demonstrate **no data loss** and **no corruption** under any failure scenario.

## Goals Achieved

1. ✅ Document Redis failure behavior
2. ✅ Document Kafka failure behavior
3. ✅ Document database transaction rollback behavior
4. ✅ Create failure scenario tests
5. ✅ Document recovery procedures

## Failure Modes Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Failure Mode Matrix                                  │
├──────────────────┬──────────────────────────────────────────────────────────┤
│ Component        │ Failure Impact & Recovery                                │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ Redis            │ Graceful degradation - DB fallback for idempotency       │
│                  │ No data loss, slightly higher latency                    │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ Kafka            │ Events accumulate in outbox                              │
│                  │ No data loss, events publish on recovery                 │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ Database         │ Transaction rollback - atomic all-or-nothing             │
│                  │ No partial state, client can retry                       │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ Application      │ Crash recovery via outbox and idempotency                │
│                  │ No duplicate processing, safe restart                    │
└──────────────────┴──────────────────────────────────────────────────────────┘
```

## Scenario 1: Redis Unavailable

### What Happens

When Redis is down, the idempotency service falls back to database lookups:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Redis Down - Fallback Flow                                │
│                                                                              │
│  Client Request (with Idempotency-Key)                                       │
│        │                                                                     │
│        ▼                                                                     │
│  ┌─────────────────────┐                                                     │
│  │ Check Redis Cache   │ ──── TIMEOUT/ERROR ────┐                            │
│  └─────────────────────┘                        │                            │
│                                                 ▼                            │
│                                    ┌─────────────────────┐                   │
│                                    │ Check Database      │                   │
│                                    │ (idempotency_key    │                   │
│                                    │  column in payments)│                   │
│                                    └─────────────────────┘                   │
│                                                 │                            │
│                            ┌────────────────────┴────────────────────┐       │
│                            ▼                                         ▼       │
│                    Found in DB                               Not Found       │
│                    Return existing                           Create new      │
│                    payment                                   payment         │
│                                                                              │
│  Result: System continues operating with ~10-50ms additional latency         │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Code Implementation

```java
@Service
public class IdempotencyService {

    public Optional<UUID> checkIdempotencyKey(String idempotencyKey) {
        // Try Redis first (fast path)
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + idempotencyKey);
            if (cached != null) {
                return Optional.of(UUID.fromString(cached));
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, falling back to database: {}", e.getMessage());
            // Fall through to database check
        }

        // Database fallback (always works)
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(PaymentEntity::getId);
    }
}
```

### Impact Analysis

| Aspect | Normal Operation | Redis Down |
|--------|------------------|------------|
| Latency | ~5ms (Redis) | ~20-50ms (DB) |
| Throughput | High | Slightly reduced |
| Data Integrity | ✅ | ✅ |
| Idempotency | ✅ | ✅ |

### Recovery

Redis recovery is automatic. When Redis comes back:
1. New requests start using Redis again
2. No manual intervention required
3. Redis cache can be warmed up naturally

---

## Scenario 2: Kafka Unavailable

### What Happens

When Kafka is down, events accumulate safely in the PostgreSQL outbox:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Kafka Down - Outbox Accumulation                          │
│                                                                              │
│  Payment Created                                                             │
│        │                                                                     │
│        ▼                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │ BEGIN TRANSACTION                                                    │     │
│  │   1. INSERT INTO payments (...)                                      │     │
│  │   2. INSERT INTO outbox_events (...)  ◄── Event stored safely        │     │
│  │ COMMIT                                                               │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│        │                                                                     │
│        ▼                                                                     │
│  ┌─────────────────────┐                                                     │
│  │ Outbox Publisher    │                                                     │
│  │ (Background Job)    │                                                     │
│  └─────────────────────┘                                                     │
│        │                                                                     │
│        ▼                                                                     │
│  ┌─────────────────────┐                                                     │
│  │ Kafka Send          │ ──── CONNECTION REFUSED ────┐                       │
│  └─────────────────────┘                             │                       │
│                                                      ▼                       │
│                                         ┌─────────────────────┐              │
│                                         │ Increment retry_cnt │              │
│                                         │ Event stays in      │              │
│                                         │ outbox              │              │
│                                         └─────────────────────┘              │
│                                                                              │
│  Outbox grows until Kafka recovers                                           │
│  Events maintain ordering via sequence_number                                │
│  Events published in order when Kafka returns                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Outbox Table State During Outage

```sql
-- During Kafka outage, events accumulate
SELECT id, event_type, retry_count, created_at, published_at
FROM outbox_events
WHERE published_at IS NULL
ORDER BY created_at;

-- Result:
-- id       | event_type      | retry_count | created_at          | published_at
-- ---------|-----------------|-------------|---------------------|-------------
-- uuid-1   | PaymentCreated  | 3           | 2024-01-05 10:00:00 | NULL
-- uuid-2   | PaymentCreated  | 2           | 2024-01-05 10:00:05 | NULL
-- uuid-3   | PaymentSettled  | 1           | 2024-01-05 10:00:10 | NULL
-- uuid-4   | PaymentCreated  | 0           | 2024-01-05 10:00:15 | NULL
```

### Impact Analysis

| Aspect | Normal Operation | Kafka Down |
|--------|------------------|------------|
| Payment Creation | ✅ Works | ✅ Works |
| Payment Settlement | ✅ Works | ✅ Works |
| Event Publishing | Immediate | Delayed |
| Data Integrity | ✅ | ✅ |
| Event Ordering | ✅ | ✅ (preserved) |

### Monitoring During Outage

```promql
# Alert on growing outbox
outbox_backlog_size > 1000

# Alert on old events (stale backlog)
outbox_backlog_age_seconds > 300

# Check in Grafana
rate(outbox_events_published_total[5m]) == 0  # Publishing stopped
```

### Recovery

When Kafka recovers:

1. **Automatic recovery**: Publisher resumes publishing from oldest event
2. **Order preserved**: Events published in sequence_number order
3. **No duplicates**: Events marked as published before Kafka ack

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Kafka Recovery - Drain Outbox                             │
│                                                                              │
│  Kafka comes back online                                                     │
│        │                                                                     │
│        ▼                                                                     │
│  ┌─────────────────────┐                                                     │
│  │ Publisher polls     │                                                     │
│  │ outbox_events       │                                                     │
│  └─────────────────────┘                                                     │
│        │                                                                     │
│        │ FOR UPDATE SKIP LOCKED (batch of 100)                               │
│        ▼                                                                     │
│  ┌─────────────────────┐                                                     │
│  │ Send to Kafka       │ ──── SUCCESS ────┐                                  │
│  └─────────────────────┘                  │                                  │
│                                           ▼                                  │
│                              ┌─────────────────────┐                         │
│                              │ Mark published_at   │                         │
│                              │ = NOW()             │                         │
│                              └─────────────────────┘                         │
│                                           │                                  │
│  Repeat until outbox is drained          ▼                                   │
│                              outbox_backlog_size = 0                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Scenario 3: Database Transaction Rollback

### What Happens

If any part of a transaction fails, everything rolls back atomically:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Transaction Rollback - Atomic Operations                  │
│                                                                              │
│  Successful Transaction:                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │ BEGIN TRANSACTION                                                    │     │
│  │   1. INSERT INTO payments (...)           ✅ Success                 │     │
│  │   2. INSERT INTO outbox_events (...)      ✅ Success                 │     │
│  │ COMMIT                                    ✅ Both persisted          │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  Failed Transaction (constraint violation, error, etc.):                     │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │ BEGIN TRANSACTION                                                    │     │
│  │   1. INSERT INTO payments (...)           ✅ Success                 │     │
│  │   2. INSERT INTO ledger_entries (...)     ❌ Balance violation       │     │
│  │ ROLLBACK                                  ⏪ Payment also undone     │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  Result: Either ALL changes persist, or NONE persist                         │
│  No orphaned payments, no orphaned events, no partial state                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Settlement Transaction Example

```java
@Transactional  // All-or-nothing
public UUID settlePayment(UUID paymentId) {
    // 1. Load and validate payment
    Payment payment = persistenceService.findById(paymentId).orElseThrow();

    // 2. Create ledger entries (may throw if balance insufficient)
    UUID ledgerTxId = ledgerService.postTransaction(/* ... */);

    // 3. Update payment status
    entity.setLedgerTransactionId(ledgerTxId);
    persistenceService.saveEntity(entity);

    // 4. Write event to outbox
    outboxService.saveEvent(AGGREGATE_TYPE, paymentId, "PaymentSettled", event);

    // All 4 steps succeed together or all roll back together
    return ledgerTxId;
}
```

### Rollback Scenarios

| Scenario | What Happens | Result |
|----------|--------------|--------|
| Insufficient balance | Ledger throws exception | Payment not updated, no event |
| Duplicate idempotency key | Unique constraint violation | Request rejected, no data |
| Connection lost mid-transaction | Timeout, rollback | No partial state |
| Application crash | Uncommitted transaction | Database cleans up |

---

## Scenario 4: Application Crash & Recovery

### What Happens

If the application crashes, the system recovers cleanly on restart:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Application Crash & Recovery                              │
│                                                                              │
│  CRASH POINT 1: During payment creation                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │ BEGIN TRANSACTION                                                    │     │
│  │   INSERT INTO payments (...)                                         │     │
│  │   ████ CRASH ████                                                    │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│  Recovery: Transaction uncommitted → Database rollback → No partial data    │
│  Client can retry safely with same idempotency key                          │
│                                                                              │
│  CRASH POINT 2: After commit, before Kafka publish                           │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │ BEGIN TRANSACTION                                                    │     │
│  │   INSERT INTO payments (...)                                         │     │
│  │   INSERT INTO outbox_events (...)                                    │     │
│  │ COMMIT ✅                                                            │     │
│  │ ████ CRASH ████                                                      │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│  Recovery: Event in outbox → Publisher picks up on restart → No loss        │
│                                                                              │
│  CRASH POINT 3: During Kafka consumer processing                             │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │ Consumer receives event                                              │     │
│  │ Begin processing...                                                  │     │
│  │ ████ CRASH ████                                                      │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│  Recovery: Kafka redelivers event → Idempotent processor checks             │
│            processed_events table → Skip if already processed               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Recovery Guarantees

| Crash Point | Data State | Recovery Action |
|-------------|------------|-----------------|
| Before commit | Nothing persisted | Client retries |
| After payment commit, before event sent | Payment + event in DB | Publisher sends on restart |
| After Kafka send, before ack | Event may be sent | Consumer deduplication handles |
| During consumer processing | Depends on where | Idempotent processing handles |

---

## Scenario 5: Network Partition

### Split Brain Prevention

The system uses single-leader PostgreSQL to prevent split-brain:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Network Partition Handling                                │
│                                                                              │
│                    ┌───────────────────┐                                     │
│                    │   PostgreSQL      │                                     │
│                    │   (Single Leader) │                                     │
│                    └─────────┬─────────┘                                     │
│                              │                                               │
│            ┌─────────────────┼─────────────────┐                             │
│            │                 │                 │                             │
│     ┌──────┴──────┐   ┌──────┴──────┐   ┌──────┴──────┐                      │
│     │   App 1     │   │   App 2     │   │   App 3     │                      │
│     └─────────────┘   └─────────────┘   └─────────────┘                      │
│                                                                              │
│   Network Partition:                                                         │
│   • App 1 loses connection to DB → Requests fail (no partial state)         │
│   • App 2 & 3 continue operating → Consistency maintained                   │
│   • All state changes go through single PostgreSQL                          │
│   • No conflicting writes possible                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Testing Failure Scenarios

### Test Class Structure

```java
@SpringBootTest
@Testcontainers
class FailureScenarioTest {

    @Nested
    @DisplayName("Redis Failure Scenarios")
    class RedisFailureTests {
        @Test
        void testRedisUnavailable_SystemContinuesOperating();
        @Test
        void testIdempotency_FallsBackToDatabase();
    }

    @Nested
    @DisplayName("Kafka Failure Scenarios")
    class KafkaFailureTests {
        @Test
        void testKafkaUnavailable_EventsRemainInOutbox();
        @Test
        void testOutbox_ProvidesEventDurability();
    }

    @Nested
    @DisplayName("Database Transaction Scenarios")
    class DatabaseTransactionTests {
        @Test
        void testRollback_NoPartialState();
        @Test
        void testConcurrentPaymentCreation();
    }

    @Nested
    @DisplayName("Recovery Scenarios")
    class RecoveryTests {
        @Test
        void testOutboxRecovery();
        @Test
        void testFailedEventRetry();
    }
}
```

### Running Failure Tests

```bash
# Run all failure scenario tests
./gradlew test --tests "*.failure.*"

# Run specific scenario
./gradlew test --tests "*.FailureScenarioTest.RedisFailureTests"
```

---

## Operational Runbook

### Redis Failure Alert

```bash
# 1. Check Redis status
redis-cli ping

# 2. Check application logs for fallback
grep "falling back to database" /var/log/payment-ledger/*.log

# 3. Monitor latency increase
curl http://localhost:8050/actuator/metrics/http.server.requests

# 4. No action needed - system auto-recovers when Redis returns
```

### Kafka Failure Alert

```bash
# 1. Check outbox backlog
curl http://localhost:8050/actuator/metrics/outbox.backlog.size

# 2. Check oldest event age
curl http://localhost:8050/actuator/metrics/outbox.backlog.age.seconds

# 3. Check Kafka broker status
kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# 4. Check for dead letter events
psql -c "SELECT COUNT(*) FROM outbox_events WHERE retry_count >= 5"

# 5. When Kafka recovers, monitor drain
watch -n 1 'curl -s http://localhost:8050/actuator/metrics/outbox.backlog.size'
```

### High Outbox Backlog

```bash
# 1. Check Kafka connectivity
curl http://localhost:8050/actuator/health/kafkaHealth

# 2. Check publisher is running
grep "OutboxPublisher" /var/log/payment-ledger/*.log | tail -20

# 3. Manual intervention for dead letter events
psql -c "SELECT * FROM outbox_events WHERE retry_count >= 5"

# 4. Reset retry count if Kafka was just down
psql -c "UPDATE outbox_events SET retry_count = 0 WHERE retry_count >= 5 AND published_at IS NULL"
```

---

## Key Invariants Maintained

1. **No data loss**: Every payment and event is durably stored
2. **No partial state**: Transactions are atomic
3. **No duplicate processing**: Idempotency at every layer
4. **Eventual consistency**: Events eventually reach consumers
5. **Order preservation**: Events maintain sequence within aggregate

## Files Created

```
src/test/java/com/flagship/payment_ledger/failure/
└── FailureScenarioTest.java      # Comprehensive failure tests

Documentation/
└── PHASE_8_IMPLEMENTATION.md     # This document
```

## Next Phase: Phase 9 - Documentation & Interview Stories

Phase 9 will focus on:
- Comprehensive README with architecture overview
- API documentation
- Interview-ready stories about design decisions
- Deployment and operations guide
