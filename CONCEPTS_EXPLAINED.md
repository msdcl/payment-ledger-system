# Payment Ledger Concepts - Simple Explanations

This document explains every concept used in this project in simple language, with real-world examples.

---

## Table of Contents

1. [Double-Entry Ledger](#1-double-entry-ledger)
2. [Immutability](#2-immutability)
3. [State Machine](#3-state-machine)
4. [Idempotency](#4-idempotency)
5. [Database Constraints](#5-database-constraints)
6. [Transactional Outbox Pattern](#6-transactional-outbox-pattern)
7. [ACID Transactions](#7-acid-transactions)
8. [Graceful Degradation](#8-graceful-degradation)
9. [Idempotent Consumers](#9-idempotent-consumers)
10. [Derived State vs Stored State](#10-derived-state-vs-stored-state)
11. [Correlation IDs](#11-correlation-ids)
12. [Optimistic Locking](#12-optimistic-locking)
13. [At-Least-Once Delivery](#13-at-least-once-delivery)
14. [Defense in Depth](#14-defense-in-depth)
15. [Stateless Services](#15-stateless-services)

---

## 1. Double-Entry Ledger

### What Is It? (Simple Explanation)

Imagine you have ₹100 in your pocket. You give ₹100 to your friend.

- Your pocket: -₹100 (money went out)
- Friend's pocket: +₹100 (money came in)

**Total change in the world = -100 + 100 = 0**

Double-entry means: **Every time money moves, we record BOTH sides** - where it came from AND where it went.

### Why Is It Used?

To make sure money never appears or disappears magically. If debits don't equal credits, something is wrong.

### Real-World Example

```
Bank Transfer: You send ₹1000 to Mom

WRONG WAY (Single Entry):
  - Mom's account: +₹1000
  - (Your account not updated - BUG!)

RIGHT WAY (Double Entry):
  - Your account:  -₹1000 (DEBIT)
  - Mom's account: +₹1000 (CREDIT)
  - Total: -1000 + 1000 = 0 ✓
```

### What Goes Wrong Without It?

```
Scenario: System crashes after crediting Mom but before debiting you

WITHOUT Double-Entry:
  - Mom has ₹1000 extra
  - You still have your ₹1000
  - Bank lost ₹1000 (money created from nowhere!)

WITH Double-Entry:
  - Transaction either completes FULLY or NOT AT ALL
  - If debits ≠ credits, database REJECTS the transaction
```

### Where It's Used In This Project

```java
// LedgerService.java - Every transaction must balance
TransactionRequest request = new TransactionRequest(
    "Payment settlement",
    List.of(DebitCredit.of(fromAccount, amount, "Debit")),   // -100
    List.of(DebitCredit.of(toAccount, amount, "Credit"))     // +100
);
// Sum = 0, balanced ✓
```

---

## 2. Immutability

### What Is It? (Simple Explanation)

Once something is created, it **cannot be changed**. Like a printed receipt - you can't erase and rewrite it. If you need changes, you create a NEW receipt.

### Why Is It Used?

1. **No accidental changes** - You can't accidentally modify old data
2. **Easy to track history** - Every version is preserved
3. **Thread-safe** - Multiple processes can read safely

### Real-World Example

```
MUTABLE (Can Change) - DANGEROUS:
  Receipt receipt = new Receipt(100);
  receipt.setAmount(50);  // Oops! Changed the original
  // Now we don't know it was originally ₹100

IMMUTABLE (Cannot Change) - SAFE:
  Receipt receipt1 = new Receipt(100);
  Receipt receipt2 = receipt1.withAmount(50);  // Creates NEW receipt
  // receipt1 still shows ₹100
  // receipt2 shows ₹50
  // Both exist, full history preserved
```

### What Goes Wrong Without It?

```
Scenario: Two threads processing same payment

WITHOUT Immutability:
  Thread 1: payment.setStatus("AUTHORIZED")
  Thread 2: payment.setStatus("SETTLED")
  // Race condition! Final status unpredictable
  // Could be AUTHORIZED, SETTLED, or corrupted

WITH Immutability:
  Thread 1: Payment p1 = payment.authorize();  // New object
  Thread 2: Payment p2 = payment.settle();     // New object
  // Original payment unchanged
  // Each thread has its own copy
```

### Where It's Used In This Project

```java
// Payment.java - @Value makes it immutable
@Value  // All fields final, no setters
public class Payment {
    UUID id;
    BigDecimal amount;
    PaymentStatus status;

    // Returns NEW payment, doesn't modify this one
    public Payment authorize() {
        return new Payment(id, amount, AUTHORIZED, ...);
    }
}
```

---

## 3. State Machine

### What Is It? (Simple Explanation)

A set of rules about what states something can be in, and how it can move between states. Like a traffic light:

```
RED → GREEN → YELLOW → RED → ...

You CANNOT go: RED → YELLOW (invalid transition)
```

### Why Is It Used?

To prevent invalid state changes. A payment should follow a specific path.

### Real-World Example

```
Order Status State Machine:

  PLACED → CONFIRMED → SHIPPED → DELIVERED
    ↓         ↓           ↓
  CANCELLED  CANCELLED   (cannot cancel after shipped)

VALID: PLACED → CONFIRMED → SHIPPED
INVALID: PLACED → DELIVERED (skipped steps!)
INVALID: DELIVERED → PLACED (can't go backwards!)
```

### What Goes Wrong Without It?

```
Scenario: Payment processing

WITHOUT State Machine:
  payment.setStatus("SETTLED");  // Direct set, no validation
  // Oops! Payment was never AUTHORIZED
  // Money moved without proper checks!

WITH State Machine:
  payment.settle();  // Throws error!
  // "Cannot settle payment in CREATED status. Must be AUTHORIZED first."
```

### Where It's Used In This Project

```java
// Payment.java - State transitions with validation
public Payment authorize() {
    if (status != CREATED) {
        throw new IllegalStateException(
            "Cannot authorize payment in " + status + " status");
    }
    return new Payment(..., AUTHORIZED, ...);
}

public Payment settle() {
    if (status != AUTHORIZED) {
        throw new IllegalStateException(
            "Cannot settle payment in " + status + " status");
    }
    return new Payment(..., SETTLED, ...);
}

// Valid path: CREATED → AUTHORIZED → SETTLED
// Invalid: CREATED → SETTLED (throws exception)
```

---

## 4. Idempotency

### What Is It? (Simple Explanation)

Doing something multiple times has the **same effect as doing it once**.

Like pressing the elevator button:
- Press once → Elevator comes
- Press 10 times → Elevator still comes (same result, not 10 elevators!)

### Why Is It Used?

Networks are unreliable. Requests can fail, timeout, or be retried. We need to handle duplicates safely.

### Real-World Example

```
Scenario: You click "Pay ₹1000" button

Request 1: Sent to server... (network timeout, no response)
You think: "Did it work? Let me click again"
Request 2: Sent to server...

WITHOUT Idempotency:
  Request 1: Charge ₹1000 ✓
  Request 2: Charge ₹1000 ✓
  Total charged: ₹2000 😱

WITH Idempotency (using idempotency key):
  Request 1: Charge ₹1000, key="abc123" ✓
  Request 2: Charge ₹1000, key="abc123" → "Already processed, returning existing result"
  Total charged: ₹1000 ✓
```

### What Goes Wrong Without It?

```
Real disaster scenario:

User: Transfers ₹50,000 to vendor
Network: Slow, request times out
App: Shows "Error, please retry"
User: Clicks retry 3 times

WITHOUT Idempotency:
  Transfer 1: ₹50,000 sent
  Transfer 2: ₹50,000 sent
  Transfer 3: ₹50,000 sent
  Total: ₹1,50,000 sent! User lost ₹1,00,000!

WITH Idempotency:
  Transfer 1: ₹50,000 sent (idempotency_key = "txn-123")
  Transfer 2: Same key, return existing result
  Transfer 3: Same key, return existing result
  Total: ₹50,000 sent ✓
```

### Where It's Used In This Project

```java
// PaymentController.java - Requires idempotency key header
@PostMapping
public ResponseEntity<PaymentResponse> createPayment(
        @RequestBody CreatePaymentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {  // Required!

    // Check if this key was already used
    Optional<UUID> existing = idempotencyService.checkIdempotencyKey(idempotencyKey);

    if (existing.isPresent()) {
        // Already processed! Return same result
        return ResponseEntity.ok(existingPayment);
    }

    // First time - create new payment
    Payment payment = paymentService.createPayment(...);
    // Store the key so duplicates are detected
}
```

---

## 5. Database Constraints

### What Is It? (Simple Explanation)

Rules enforced by the database itself. Even if your code has bugs, the database will reject invalid data.

Like a bouncer at a club:
- Code says "Let everyone in"
- Database (bouncer) says "No, only people on the list"

### Why Is It Used?

**Defense in depth** - Multiple layers of protection. If application check fails, database catches it.

### Real-World Example

```
UNIQUE Constraint Example:

Table: users
Columns: id, email (UNIQUE)

Application Bug: Forgot to check if email exists

INSERT INTO users (email) VALUES ('john@email.com');  ✓
INSERT INTO users (email) VALUES ('john@email.com');  ✗ REJECTED!

Database says: "ERROR: duplicate key violates unique constraint"

Even with buggy code, duplicate email is IMPOSSIBLE.
```

### What Goes Wrong Without It?

```
Scenario: Two requests arrive at same millisecond

WITHOUT Database Constraint:
  Thread 1: Check if payment exists? No. Create payment.
  Thread 2: Check if payment exists? No. Create payment.
  Result: TWO payments created! Money charged twice!

WITH Database Constraint (UNIQUE on idempotency_key):
  Thread 1: Check? No. Insert payment. ✓
  Thread 2: Check? No. Insert payment. ✗ UNIQUE VIOLATION!
  Result: Only ONE payment. Database saved us!
```

### Where It's Used In This Project

```sql
-- V2__create_payments_table.sql
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) UNIQUE,  -- Prevents duplicates!
    ...
);

-- V3__add_ledger_integration.sql
ALTER TABLE payments
ADD CONSTRAINT unique_ledger_transaction
UNIQUE (ledger_transaction_id);  -- One ledger TX per payment!

-- V1__create_ledger_schema.sql (Trigger)
CREATE TRIGGER enforce_balanced_transaction
AFTER INSERT ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION check_transaction_balance();
-- Database REJECTS unbalanced ledger entries!
```

---

## 6. Transactional Outbox Pattern

### What Is It? (Simple Explanation)

Instead of sending a message directly to a queue (Kafka), we write it to a database table first. A separate process reads from that table and sends to the queue.

Like leaving a note for a courier:
1. You write the note and put it in a box (database)
2. Courier picks up notes from the box and delivers them
3. Even if courier is sick today, notes are safe in the box

### Why Is It Used?

To solve the **dual-write problem** - when you need to update database AND send a message, but one might fail.

### Real-World Example

```
Scenario: Create order and send "OrderCreated" notification

WRONG WAY (Direct Send):
  1. Save order to database ✓
  2. Send message to Kafka ✗ (Kafka is down!)
  Result: Order exists but notification never sent!

  OR

  1. Save order to database ✗ (database error!)
  2. Message already sent to Kafka ✓
  Result: Notification sent for order that doesn't exist!

RIGHT WAY (Outbox):
  1. Save order + outbox_event in SAME transaction
     - If database fails, both fail (consistent!)
     - If database succeeds, both succeed
  2. Background process sends outbox events to Kafka
  Result: Order and notification are always in sync!
```

### What Goes Wrong Without It?

```
Payment System Without Outbox:

Step 1: payment.save();           // Success
Step 2: kafka.send("PaymentCreated");  // Network error!
Step 3: throw exception, transaction rolls back?
        NO! Payment already committed!

Result: Payment exists in DB, but no event published
        - Analytics system doesn't know about payment
        - Email notification never sent
        - Inventory never updated

WITH OUTBOX:
Step 1: payment.save();           // In transaction
Step 2: outbox_event.save();      // Same transaction
Step 3: Commit transaction        // Both saved together
Step 4: (Later) Publisher reads outbox, sends to Kafka
        Even if Kafka is down for hours, events wait safely in outbox
```

### Where It's Used In This Project

```java
// PaymentSettlementService.java
@Transactional  // Everything in one transaction!
public UUID settlePayment(UUID paymentId) {
    // 1. Update payment status
    Payment settled = paymentService.settlePayment(payment);

    // 2. Create ledger entries
    UUID ledgerTxId = ledgerService.postTransaction(...);

    // 3. Write event to outbox (SAME TRANSACTION!)
    outboxService.saveEvent("Payment", paymentId, "PaymentSettled", event);

    // If ANY step fails, ALL steps rollback
    // If all succeed, event is guaranteed to be in outbox
}

// OutboxPublisher.java - Separate background process
@Scheduled(fixedRate = 1000)  // Every second
public void publishPendingEvents() {
    List<OutboxEvent> events = outboxService.findUnpublishedEvents(100);
    for (OutboxEvent event : events) {
        kafka.send(event);
        outboxService.markPublished(event.getId());
    }
}
```

---

## 7. ACID Transactions

### What Is It? (Simple Explanation)

Four guarantees that databases provide:

| Letter | Meaning | Simple Explanation |
|--------|---------|-------------------|
| **A** | Atomicity | All or nothing - like a light switch (on/off, never half-on) |
| **C** | Consistency | Rules are always followed (balance can't go negative if not allowed) |
| **I** | Isolation | Transactions don't interfere with each other |
| **D** | Durability | Once saved, it survives crashes (written to disk) |

### Why Is It Used?

To ensure data integrity. Money systems CANNOT have partial states.

### Real-World Example

```
Transfer ₹500 from Account A to Account B

ATOMICITY (All or Nothing):
  Step 1: Debit A: -₹500
  Step 2: Credit B: +₹500

  If Step 2 fails → Step 1 is also undone
  Never: A debited but B not credited

CONSISTENCY (Rules Enforced):
  Account A has ₹300
  Transfer ₹500 → REJECTED (insufficient funds rule)

ISOLATION (No Interference):
  You check balance: ₹1000
  Meanwhile, someone transfers ₹200 out
  Your transaction sees ₹1000 (isolated from other transaction)

DURABILITY (Survives Crashes):
  Transfer completes, success message shown
  Server crashes 1 second later
  Server restarts → Transfer is still there (not lost)
```

### What Goes Wrong Without It?

```
WITHOUT ATOMICITY:
  Debit A: -₹500 ✓
  <System crash>
  Credit B: Never happens
  Result: ₹500 vanished from A, never reached B!

WITHOUT ISOLATION:
  Thread 1: Read balance (₹1000)
  Thread 2: Withdraw ₹1000 ✓
  Thread 1: Withdraw ₹1000 ✓ (still sees old balance!)
  Result: Account is -₹1000 (overdraft)

WITHOUT DURABILITY:
  Transfer completes ✓
  Success message shown ✓
  Power outage
  Data only in memory, not on disk
  Result: Transfer lost forever!
```

### Where It's Used In This Project

```java
// PaymentSettlementService.java
@Transactional  // ACID guarantees for everything inside
public UUID settlePayment(UUID paymentId) {
    // All these steps are ONE atomic operation:
    Payment settled = paymentService.settlePayment(payment);  // Step 1
    UUID ledgerTxId = ledgerService.postTransaction(...);     // Step 2
    entity.setLedgerTransactionId(ledgerTxId);                // Step 3
    outboxService.saveEvent(...);                              // Step 4

    // If Step 3 fails:
    //   - Step 1 rolled back
    //   - Step 2 rolled back
    //   - Step 4 never happens
    // Database is NEVER in partial state
}
```

---

## 8. Graceful Degradation

### What Is It? (Simple Explanation)

When a part of the system fails, the rest continues working (maybe slower, but working).

Like a car with a flat tire:
- You can still drive slowly to a repair shop
- You don't stop completely

### Why Is It Used?

100% uptime is impossible. Systems must handle partial failures.

### Real-World Example

```
Website with Image CDN:

NORMAL: Images load from fast CDN (100ms)
CDN DOWN: Images load from slow main server (500ms)

User experience:
- WITH graceful degradation: "Website is a bit slow today"
- WITHOUT: "Website is completely down"

Which would you prefer?
```

### What Goes Wrong Without It?

```
Payment System Dependencies:
- PostgreSQL (database)
- Redis (cache)
- Kafka (messaging)

WITHOUT Graceful Degradation:
  Redis goes down → ENTIRE PAYMENT SYSTEM DOWN
  Users can't pay for anything!
  Revenue loss: $$$$$

WITH Graceful Degradation:
  Redis goes down → Fall back to database lookups
  System is slower but WORKS
  Users can still pay
  Revenue: Still flowing
```

### Where It's Used In This Project

```java
// IdempotencyService.java - Redis fallback to database
public Optional<UUID> checkIdempotencyKey(String idempotencyKey) {
    // Try fast path (Redis)
    try {
        String result = redisTemplate.get().opsForValue().get(key);
        if (result != null) return Optional.of(UUID.fromString(result));
    } catch (Exception e) {
        log.warn("Redis failed, falling back to database");
        // DON'T THROW! Continue to fallback
    }

    // Fallback to database (slower but always works)
    return paymentRepository.findByIdempotencyKey(idempotencyKey)
            .map(PaymentEntity::getId);
}
```

---

## 9. Idempotent Consumers

### What Is It? (Simple Explanation)

A message consumer that can receive the same message multiple times but only processes it once.

Like a "mark as read" feature:
- First time you read email → Mark as read, show content
- Second time (duplicate) → Already read, skip processing

### Why Is It Used?

Messages can be delivered multiple times due to:
- Network retries
- Consumer crashes and restarts
- Kafka rebalancing

### Real-World Example

```
Email Notification System:

Event: "OrderShipped" for Order #123

WITHOUT Idempotent Consumer:
  Receive event → Send email
  Kafka rebalance → Receive same event again
  Send email again!
  Customer gets 5 emails: "Your order shipped!" 😠

WITH Idempotent Consumer:
  Receive event → Check processed_events table
  Not found → Process, send email, record in table
  Receive duplicate → Found in table → Skip
  Customer gets exactly 1 email ✓
```

### What Goes Wrong Without It?

```
Inventory System:

Event: "OrderPlaced" for 5 items

WITHOUT Idempotent Consumer:
  Process → Reduce inventory by 5
  Duplicate → Reduce inventory by 5 again!
  Actual inventory: 100
  System shows: 90 (reduced twice!)

  Result: We think we have 90, but actually have 100
  Or worse: We sell items we don't have!

WITH Idempotent Consumer:
  Process → Reduce by 5, record event_id
  Duplicate → Event already processed, skip
  Inventory correctly shows: 95
```

### Where It's Used In This Project

```java
// IdempotentEventProcessor.java
public boolean processEvent(UUID eventId, String eventType,
                           String consumerGroup, Runnable handler) {
    // Check if already processed
    if (repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
        log.info("Event already processed, skipping: {}", eventId);
        return false;  // Duplicate!
    }

    // Process the event
    handler.run();

    // Record that we processed it
    ProcessedEventEntity record = new ProcessedEventEntity(
        eventId, eventType, consumerGroup, "SUCCESS"
    );
    repository.save(record);

    return true;  // First time processing
}

// Database table prevents duplicates
CREATE TABLE processed_events (
    event_id UUID,
    consumer_group VARCHAR(255),
    PRIMARY KEY (event_id, consumer_group)  -- Can't insert same combo twice
);
```

---

## 10. Derived State vs Stored State

### What Is It? (Simple Explanation)

**Stored State**: Value saved directly in database
**Derived State**: Value calculated from other data when needed

Like your age:
- Stored: "Age = 25" (wrong next year!)
- Derived: Calculate from birthdate (always correct!)

### Why Is It Used?

Stored state can become inconsistent. Derived state is always accurate.

### Real-World Example

```
Bank Account Balance:

STORED STATE (Dangerous):
  balance column in accounts table = ₹10,000

  Problem: If someone directly updates this, or a bug sets wrong value,
           how do you know if ₹10,000 is correct?

DERIVED STATE (Safe):
  No balance column!
  Balance = SUM(deposits) - SUM(withdrawals)

  SELECT SUM(CASE WHEN type='CREDIT' THEN amount ELSE -amount END)
  FROM ledger_entries
  WHERE account_id = ?

  Result: ₹10,000 (calculated from actual transactions)

  This CAN'T be wrong because it's math!
  To fake a balance, you'd have to insert fake transactions.
```

### What Goes Wrong Without It?

```
E-commerce Inventory:

STORED STATE:
  products.quantity = 100

  Bug: Update ran twice, set quantity = 100 when it should be 50
  Nobody knows it's wrong until physical count!

DERIVED STATE:
  quantity = SUM(received) - SUM(sold)

  SELECT
    (SELECT SUM(quantity) FROM inventory_received WHERE product=?) -
    (SELECT SUM(quantity) FROM inventory_sold WHERE product=?)

  This shows 50 because it's calculated from actual events
  The bug is impossible - you can't insert fake sale records easily
```

### Where It's Used In This Project

```java
// LedgerService.java - Balance is CALCULATED, not stored
public BigDecimal getAccountBalance(UUID accountId) {
    // No balance column in accounts table!
    // Calculate from ledger entries
    return jdbcTemplate.queryForObject(
        """
        SELECT COALESCE(
            SUM(CASE
                WHEN entry_type = 'DEBIT' THEN amount
                ELSE -amount
            END), 0)
        FROM ledger_entries
        WHERE account_id = ?
        """,
        BigDecimal.class,
        accountId
    );
}

// SQL View (database level)
CREATE VIEW account_balances AS
SELECT
    account_id,
    SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) as balance
FROM ledger_entries
GROUP BY account_id;

// Balance can NEVER be wrong because it's math!
```

---

## 11. Correlation IDs

### What Is It? (Simple Explanation)

A unique ID that follows a request through all systems. Like a tracking number for a package.

```
Request ID: req-12345

API Server log: [req-12345] Received payment request
Database log:   [req-12345] Inserted payment record
Kafka log:      [req-12345] Published PaymentCreated event
Email Service:  [req-12345] Sent confirmation email
```

### Why Is It Used?

To debug issues across multiple services. Without it, finding related logs is like finding a needle in a haystack.

### Real-World Example

```
User: "My payment failed!"
Support: "When?"
User: "Around 2pm"

WITHOUT Correlation ID:
  Search logs for 2pm... 10,000 log entries
  Which ones are for this user? No idea!
  Debugging time: 2 hours

WITH Correlation ID:
  Look up request ID from user's browser: req-789xyz
  Search all logs for "req-789xyz"
  Found 15 related entries in 2 seconds
  Problem identified: "Payment gateway timeout at step 3"
  Debugging time: 5 minutes
```

### Where It's Used In This Project

```java
// CorrelationIdFilter.java - Adds ID to every request
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        // Get from header or generate new one
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        // Add to MDC (appears in all logs)
        MDC.put("correlationId", correlationId);

        // Add to response header
        response.setHeader("X-Correlation-ID", correlationId);
    }
}

// Log output
// 2024-01-05 10:30:00 [correlationId=abc-123] Payment created: PAY-456
// 2024-01-05 10:30:01 [correlationId=abc-123] Ledger entry posted
// 2024-01-05 10:30:02 [correlationId=abc-123] Event published

// Now you can grep "abc-123" and see the entire request journey!
```

---

## 12. Optimistic Locking

### What Is It? (Simple Explanation)

A way to handle concurrent updates WITHOUT locking the database row.

1. Read record with version number
2. Make changes
3. Update WHERE version = old_version
4. If 0 rows updated → Someone else changed it, retry!

Like editing a Google Doc:
- You see "Last edited 10:00 AM"
- You edit and save
- Google checks: "Was it really last edited at 10:00 AM?"
- If yes → Save your changes
- If no → "Someone else edited. Merge changes?"

### Why Is It Used?

Better performance than pessimistic locking (row locks). Works well when conflicts are rare.

### Real-World Example

```
Two cashiers processing same order:

PESSIMISTIC LOCKING (Lock first, then update):
  Cashier 1: LOCK order #123
  Cashier 2: Wait... wait... wait...
  Cashier 1: Update, UNLOCK
  Cashier 2: Now I can proceed
  Problem: Cashier 2 blocked for long time!

OPTIMISTIC LOCKING (Check at save time):
  Cashier 1: Read order (version=1)
  Cashier 2: Read order (version=1)
  Cashier 1: Save (WHERE version=1) → Success, version=2
  Cashier 2: Save (WHERE version=1) → FAIL! (version is now 2)
  Cashier 2: Reload, retry

  No blocking, just retry on conflict!
```

### What Goes Wrong Without It?

```
WITHOUT Optimistic Locking:

Thread 1: SELECT * FROM payment WHERE id=123  → status=AUTHORIZED
Thread 2: SELECT * FROM payment WHERE id=123  → status=AUTHORIZED
Thread 1: UPDATE payment SET status='SETTLED' WHERE id=123 ✓
Thread 2: UPDATE payment SET status='FAILED' WHERE id=123 ✓

Final status: FAILED (Thread 2's update won, Thread 1's lost!)
But Thread 1 already created ledger entries for SETTLED!
INCONSISTENT STATE!

WITH Optimistic Locking:

Thread 1: SELECT *, version FROM payment → version=1
Thread 2: SELECT *, version FROM payment → version=1
Thread 1: UPDATE SET status='SETTLED', version=2 WHERE version=1 ✓
Thread 2: UPDATE SET status='FAILED', version=2 WHERE version=1 ✗
         (0 rows updated because version is now 2)

Thread 2 gets OptimisticLockException, can retry or handle gracefully
```

### Where It's Used In This Project

```java
// PaymentEntity.java
@Entity
public class PaymentEntity {
    @Version  // JPA optimistic locking
    private Long version;

    // When saving:
    // UPDATE payments SET ..., version = version + 1
    // WHERE id = ? AND version = ?
    //
    // If version changed, 0 rows updated → OptimisticLockException
}
```

---

## 13. At-Least-Once Delivery

### What Is It? (Simple Explanation)

A guarantee that a message will be delivered at least once. It might be delivered twice (or more), but never zero times.

Like certified mail:
- Post office keeps trying until you sign
- You might sign twice by mistake
- But you WILL get the letter

### Why Is It Used?

It's the safest delivery guarantee. Missing a message (zero delivery) can be catastrophic. Handling duplicates is easier.

### Real-World Example

```
Food Delivery App:

Message: "Prepare Order #456"

AT-MOST-ONCE (Fire and forget):
  Send message...
  Network hiccup, message lost!
  Order never prepared 😞
  Customer waits forever

AT-LEAST-ONCE:
  Send message...
  No acknowledgment received, resend!
  Kitchen receives twice
  Second time: "Already preparing this order, ignore"
  Customer gets food ✓
```

### Trade-offs

```
AT-MOST-ONCE:  May lose messages (BAD for payments!)
AT-LEAST-ONCE: May duplicate messages (handle with idempotency)
EXACTLY-ONCE:  Technically impossible in distributed systems
               (we simulate it with at-least-once + idempotent consumers)
```

### Where It's Used In This Project

```java
// OutboxPublisher.java - Keeps trying until successful
private void publishEvent(OutboxEvent event) {
    try {
        kafkaTemplate.send(topic, key, value).get();  // Wait for ack
        outboxService.markPublished(event.getId());   // Only mark if successful
    } catch (Exception e) {
        // DON'T mark as published!
        // Event stays in outbox
        // Will be retried on next poll
        outboxService.markFailed(event.getId(), e.getMessage());
    }
}

// The event will be retried until:
// 1. Successfully delivered to Kafka
// 2. Max retries exceeded (goes to dead letter)

// Combined with idempotent consumers:
// - Publisher sends at-least-once
// - Consumer deduplicates
// - Result: Effectively exactly-once processing
```

---

## 14. Defense in Depth

### What Is It? (Simple Explanation)

Multiple layers of security/validation, like an onion. If one layer fails, others still protect you.

Like home security:
- Layer 1: Gate with lock
- Layer 2: Door with lock
- Layer 3: Alarm system
- Layer 4: Safe for valuables

Thief bypasses gate? Door stops them.
Bypasses door? Alarm alerts you.

### Why Is It Used?

No single protection is perfect. Bugs happen. Defense in depth means a single bug doesn't cause disaster.

### Real-World Example

```
Preventing Double Payment:

SINGLE LAYER (Risky):
  Application checks Redis for idempotency key
  If not found → Create payment

  Problem: Redis fails → Check fails → Duplicate payment!

DEFENSE IN DEPTH (Safe):
  Layer 1: Redis cache check (fast)
  Layer 2: Database lookup (reliable)
  Layer 3: Database UNIQUE constraint (guaranteed)
  Layer 4: Ledger balance check (final verification)

  Even if Layers 1, 2, AND 3 all fail simultaneously,
  Layer 4 will catch the problem!
```

### Where It's Used In This Project

```
Double Settlement Protection (4 Layers):

Layer 1: Application Check
  if (entity.isSettled()) return existingLedgerTxId;

Layer 2: Entity Validation
  entity.setLedgerTransactionId(id);
  // Throws if already set

Layer 3: Database Constraint
  UNIQUE(ledger_transaction_id)
  // DB rejects duplicate

Layer 4: Ledger Balance Verification
  // Even if payment saved twice, ledger entries must balance
  // Trigger rejects unbalanced entries


Idempotency Protection (3 Layers):

Layer 1: Redis Check
  // Fast, but can fail

Layer 2: Database Lookup
  // Slower, reliable

Layer 3: Database Constraint
  UNIQUE(idempotency_key)
  // Guaranteed protection
```

---

## 15. Stateless Services

### What Is It? (Simple Explanation)

A service that doesn't remember anything between requests. Every request contains all needed information.

Like a stateless waiter:
- Doesn't remember your face
- Every time you order, you show your table number
- Any waiter can serve you (they all check the order ticket)

vs Stateful waiter:
- Remembers you ordered coffee
- If he goes on break, new waiter doesn't know about your coffee!

### Why Is It Used?

1. **Easy scaling**: Add more servers, they all work the same
2. **Fault tolerance**: Server crash? Others continue
3. **No session affinity**: Any server handles any request

### Real-World Example

```
STATEFUL Service (Problematic):

  Server A: Holds user session in memory

  Request 1 → Server A: Login successful, session stored in A's memory
  Request 2 → Server B: "Who are you??" (B doesn't have session!)
  Request 3 → Server A crashes: Session LOST!

  Requires: Sticky sessions, session replication, complexity...

STATELESS Service:

  All state in database

  Request 1 → Server A: Login, write session to Redis/DB
  Request 2 → Server B: Check Redis/DB, finds session ✓
  Request 3 → Server A crashes: Server C picks up, reads from DB ✓

  Any server can handle any request!
```

### What Goes Wrong Without It?

```
Payment Processing with Stateful Service:

Server A: Processing payment #123, stored in memory
Server A: Crashes!
Payment #123 state: LOST!

User: "Where's my payment??"
System: "We don't know..."

WITH Stateless Service:

Server A: Read payment from DB, process, write to DB
Server A: Crashes!
Server B: Read payment from DB, continue processing

User: "Where's my payment?"
System: "Here it is! Processing continued."
```

### Where It's Used In This Project

```java
// Every service method is stateless
// All state comes from and goes to database

@Service
public class PaymentSettlementService {

    // No instance variables storing payment state!
    // Every method reads from DB, processes, writes to DB

    public UUID settlePayment(UUID paymentId) {
        // READ from database
        Payment payment = persistenceService.findById(paymentId);

        // PROCESS (pure computation, no stored state)
        Payment settled = paymentService.settlePayment(payment);

        // WRITE to database
        persistenceService.update(settled);

        // RETURN result
        return ledgerTxId;
    }

    // If this server crashes between steps:
    // - Transaction rolls back (ACID)
    // - Another server can retry from beginning
    // - No state lost!
}
```

---

## Summary: How All Concepts Work Together

```
User clicks "Pay ₹100"
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  API Layer                                                   │
│  • Idempotency Key checked (Redis → DB fallback)            │
│  • Correlation ID generated for tracing                      │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  Domain Layer                                                │
│  • Payment created (Immutable object)                        │
│  • State Machine enforces: CREATED → AUTHORIZED → SETTLED    │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  Persistence Layer (@Transactional - ACID)                   │
│  • Payment saved                                             │
│  • Ledger entries created (Double-entry, must balance)       │
│  • Outbox event written (Transactional Outbox)              │
│  • Database Constraints prevent duplicates                   │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  Async Layer                                                 │
│  • Outbox Publisher reads events (At-least-once delivery)   │
│  • Sends to Kafka                                            │
│  • Idempotent Consumers process events                       │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  If Anything Fails                                           │
│  • Redis down → Graceful Degradation to DB                  │
│  • Kafka down → Events wait in Outbox                       │
│  • Server crash → Stateless, another picks up               │
│  • Duplicate request → Defense in Depth catches it          │
└─────────────────────────────────────────────────────────────┘
```

---

## Quick Reference Card

| Concept | One-Line Explanation | Used When |
|---------|---------------------|-----------|
| Double-Entry Ledger | Every money movement has two sides that balance | Moving money between accounts |
| Immutability | Once created, never changed | Domain objects (Payment, LedgerEntry) |
| State Machine | Controlled transitions between states | Payment status changes |
| Idempotency | Same request = same result (no duplicates) | Payment creation, settlement |
| DB Constraints | Rules enforced by database | Preventing duplicates, enforcing business rules |
| Transactional Outbox | Events saved with data, sent later | Publishing events reliably |
| ACID Transactions | All-or-nothing operations | Any database write |
| Graceful Degradation | Fallback when components fail | Redis unavailable |
| Idempotent Consumers | Process message exactly once | Event processing |
| Derived State | Calculate don't store | Account balances |
| Correlation IDs | Tracking ID across services | Request tracing, debugging |
| Optimistic Locking | Check version before update | Concurrent modifications |
| At-Least-Once | Message will arrive (maybe twice) | Event publishing |
| Defense in Depth | Multiple protection layers | Critical operations |
| Stateless Services | No memory between requests | All services |

---

## Interview One-Liners

**"Why double-entry?"**
> "To ensure money never appears or disappears. If debits don't equal credits, we know something is wrong."

**"Why immutability?"**
> "To prevent accidental changes and make the system thread-safe. We create new objects instead of modifying existing ones."

**"Why idempotency?"**
> "Networks are unreliable. Users retry. We need duplicate requests to be safe, returning the same result without side effects."

**"Why transactional outbox?"**
> "To solve the dual-write problem. We write events and data in the same transaction, guaranteeing consistency."

**"Why derived state?"**
> "Stored state can become inconsistent. Derived state is calculated from source data and is always correct."

**"Why defense in depth?"**
> "No single protection is perfect. Multiple layers mean a bug in one layer doesn't cause disaster."
