# Interview Stories: Payment Ledger System

This document provides talking points and stories for discussing this project in technical interviews. Each section covers a design decision and how to articulate the reasoning behind it.

---

## Story 1: Solving the Dual-Write Problem

### The Question
*"How did you ensure reliable event publishing?"*

### The Story

> "In our payment system, we needed to save payment data to PostgreSQL AND publish events to Kafka. The naive approach of doing these sequentially has a critical flaw:
>
> If we save to the database first and then Kafka fails, we have a payment without an event. If we publish to Kafka first and the database fails, we have an event without a payment. Neither is acceptable for a financial system.
>
> We solved this with the **Transactional Outbox Pattern**. Instead of publishing directly to Kafka, we write events to an outbox table in the SAME database transaction as the payment:
>
> ```sql
> BEGIN TRANSACTION;
>   INSERT INTO payments (...);
>   INSERT INTO outbox_events (...);
> COMMIT;
> ```
>
> A background publisher then polls this table and publishes to Kafka. This guarantees:
> 1. If the payment is saved, the event is saved (same transaction)
> 2. Events are published at-least-once (publisher retries on failure)
> 3. Event ordering is preserved via a sequence number
>
> The trade-off is slightly higher latency for event delivery, but we gain 100% reliability. For a payment system, reliability wins."

### Follow-up Questions

**Q: What if two publishers run simultaneously?**
> "We use `SELECT ... FOR UPDATE SKIP LOCKED` which means if one publisher locks a batch of events, another publisher will skip those and grab different events. This allows horizontal scaling of publishers without coordination."

**Q: How do you handle Kafka being down for hours?**
> "Events accumulate in the outbox. When Kafka recovers, they're published in order. We have monitoring on the outbox backlog size to alert us before it becomes a problem."

---

## Story 2: Making Payments Idempotent

### The Question
*"How do you prevent duplicate payments from network retries?"*

### The Story

> "Network failures are inevitable. When a client creates a payment and the connection drops, they don't know if the payment succeeded. If they retry, we could create a duplicate payment - that's unacceptable.
>
> We require an `Idempotency-Key` header on every payment creation. Here's the flow:
>
> 1. Check Redis for the idempotency key (fast path, ~5ms)
> 2. If not in Redis, check the database (fallback, ~20ms)
> 3. If found, return the existing payment
> 4. If not found, create the payment and store the key
>
> The key insight is that we store the idempotency key in the SAME transaction as the payment:
>
> ```sql
> INSERT INTO payments (id, ..., idempotency_key) VALUES (...);
> ```
>
> A unique constraint on `idempotency_key` prevents race conditions. Even if two requests with the same key arrive simultaneously, only one succeeds at the database level."

### Follow-up Questions

**Q: Why Redis AND database?**
> "Redis is the fast path for the common case. But Redis might be unavailable or the data might expire. The database is the source of truth that ALWAYS works. We never sacrifice correctness for speed."

**Q: What's the TTL on idempotency keys?**
> "24 hours in Redis. The database record lives forever as part of the payment audit trail. 24 hours covers retry scenarios while keeping Redis memory bounded."

---

## Story 3: Double-Entry Ledger Design

### The Question
*"How does your ledger ensure financial correctness?"*

### The Story

> "Traditional single-entry bookkeeping has a dangerous failure mode: if you debit one account but fail to credit another, money disappears. Our double-entry ledger makes this impossible.
>
> Every ledger transaction consists of:
> - One or more debit entries
> - One or more credit entries
> - Sum of debits MUST equal sum of credits
>
> We enforce this at multiple levels:
>
> 1. **Domain level**: The `LedgerTransaction` class validates balance in its constructor
> 2. **Database level**: A check constraint ensures `SUM(debits) = SUM(credits)` per transaction
> 3. **Application level**: The service layer validates before saving
>
> For a payment settlement:
> ```
> Debit:  Account A (sender)    -$100.00
> Credit: Account B (receiver)  +$100.00
> Net:    $0.00 ✓
> ```
>
> If anything fails, the entire transaction rolls back. There's never a state where money exists or disappears."

### Follow-up Questions

**Q: How do you handle refunds?**
> "A refund is just another ledger transaction with reversed entries. The original transaction stays for audit, and the refund transaction links to it. This gives us complete traceability."

**Q: What about performance at scale?**
> "Ledger entries are append-only. We never update, only insert. This makes scaling horizontal via partitioning straightforward. Account balances can be calculated from entries or cached with eventual consistency."

---

## Story 4: Idempotent Event Consumers

### The Question
*"How do you handle Kafka message redelivery?"*

### The Story

> "Kafka guarantees at-least-once delivery, which means consumers must handle duplicates. In a payment system, processing the same event twice could mean sending two confirmation emails or, worse, double-charging.
>
> We built an `IdempotentEventProcessor` that wraps every event handler:
>
> ```java
> boolean processed = eventProcessor.processEvent(
>     eventId,
>     consumerGroup,
>     () -> {
>         // Business logic only runs once
>         sendNotification(event);
>     }
> );
> ```
>
> The processor:
> 1. Checks the `processed_events` table for this event ID + consumer group
> 2. If found, skip (return false)
> 3. If not found, execute handler and record in same transaction
>
> The key is that the record is written in the SAME transaction as the business logic. If the handler fails, no record is written, and the event can be retried."

### Follow-up Questions

**Q: Why event ID plus consumer group?**
> "Different consumer groups need to process the same event independently. The notification service and analytics service both need to see `PaymentCreated`. But each should only process it once."

**Q: What happens if two consumers race on the same event?**
> "The database handles it. We use the event ID as a primary key. Only one INSERT succeeds, the other gets a duplicate key error and knows to skip."

---

## Story 5: Observability for Production

### The Question
*"How do you monitor and debug issues in production?"*

### The Story

> "Production debugging requires three things: tracing, metrics, and health checks.
>
> **Tracing**: Every request gets a correlation ID (generated or from header). This ID flows through every log message, every service call, every Kafka message. When something fails, I can search for one ID and see the entire request lifecycle:
>
> ```
> [abc-123] Received payment request
> [abc-123] Idempotency check: miss
> [abc-123] Payment created: pay-456
> [abc-123] Event written to outbox
> ```
>
> **Metrics**: We expose Prometheus metrics for everything that matters:
> - Payment creation rate and latency
> - Idempotency cache hit rate (tells us if Redis is working)
> - Outbox backlog size (tells us if Kafka is healthy)
>
> **Health Checks**: Beyond simple up/down, we have semantic health:
> - `outboxHealth`: Backlog under 1000? UP. Under 10000? WARNING. Over? DOWN.
> - This tells Kubernetes when to stop sending traffic before things get bad."

### Follow-up Questions

**Q: How do you handle alerting at 3 AM?**
> "Alerts are based on symptoms, not causes. 'Outbox backlog growing' is actionable. 'Kafka consumer lag high' is actionable. 'Redis memory at 80%' is not - that's just monitoring. We only page for things requiring immediate action."

---

## Story 6: Failure Modes and Recovery

### The Question
*"What happens when components fail?"*

### The Story

> "We designed for failure from the start. Here are the three main scenarios:
>
> **Redis down**: The idempotency service falls back to database queries. Latency increases from ~5ms to ~20ms, but correctness is maintained. When Redis recovers, we're back to normal. No manual intervention needed.
>
> **Kafka down**: Events accumulate in the outbox table. The outbox is in PostgreSQL, which is highly durable. When Kafka recovers, the publisher drains the backlog. We've tested backlogs of 100K+ events. The key is monitoring - we alert before the backlog grows too large.
>
> **Application crash**: This is the most interesting case. If we crash mid-transaction, PostgreSQL rolls back - no partial state. If we crash after commit but before Kafka publish, the event is safe in the outbox. If we crash mid-consumer processing, Kafka redelivers and our idempotent processor handles it.
>
> The pattern is: PostgreSQL is the source of truth. Everything else can fail and recover."

### Follow-up Questions

**Q: Have you tested these scenarios?**
> "Yes, we have `FailureScenarioTest` that mocks failures and verifies correct behavior. We also do chaos engineering in staging - randomly killing pods, adding network latency. The system handles it."

---

## Story 7: Scaling Considerations

### The Question
*"How would you scale this system to handle 10x or 100x traffic?"*

### The Story

> "The architecture was designed with scaling in mind:
>
> **Horizontal scaling of API servers**: Stateless. Add more pods. Load balancer distributes.
>
> **Database scaling**:
> - Read replicas for read-heavy operations (payment lookup)
> - Partitioning ledger entries by account ID for write scaling
> - The outbox can be partitioned by aggregate ID
>
> **Kafka scaling**:
> - Partitioned by payment ID, so one payment's events always go to same partition (ordering preserved)
> - Add more partitions for parallelism
> - Consumer groups scale horizontally
>
> **Outbox publisher scaling**:
> - FOR UPDATE SKIP LOCKED allows multiple publishers without coordination
> - Each grabs a different batch
>
> The main bottleneck is typically the database. That's where we'd focus first - connection pooling, query optimization, then read replicas, then sharding."

---

## Key Technical Terms to Know

| Term | Definition |
|------|------------|
| **Transactional Outbox** | Pattern for reliable event publishing using same-DB-transaction writes |
| **Idempotency Key** | Client-provided identifier for safe retry semantics |
| **Double-Entry Ledger** | Accounting system where every transaction has balanced debits and credits |
| **FOR UPDATE SKIP LOCKED** | PostgreSQL clause for non-blocking concurrent access |
| **At-Least-Once Delivery** | Messaging guarantee where messages may be delivered multiple times |
| **Correlation ID** | Request identifier for distributed tracing |
| **Graceful Degradation** | Ability to function at reduced capacity when components fail |
| **Check Constraint** | Database-level validation that ensures data invariants |

---

## Questions to Ask the Interviewer

1. "How do you currently handle idempotency in your payment systems?"
2. "What's your experience with event-driven architectures and the challenges you've faced?"
3. "How do you approach testing distributed systems and failure scenarios?"
4. "What observability tools do you use, and what metrics do you find most valuable?"

---

## Summary Elevator Pitch

> "I built a production-grade payment ledger system that demonstrates three key patterns:
>
> First, **financial correctness** through a double-entry ledger where every transaction is mathematically balanced.
>
> Second, **reliability** through the transactional outbox pattern for event publishing and idempotency keys for safe retries.
>
> Third, **resilience** through graceful degradation - the system continues operating when Redis or Kafka fail, with no data loss.
>
> The whole system is observable with correlation IDs, Prometheus metrics, and semantic health checks. It's designed for production operation, not just happy-path demos."
