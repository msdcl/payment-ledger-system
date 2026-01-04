# Phase 0: Mental Model & Rules of the System

## What Can Go Wrong in a Payment System?

Payment systems operate in a hostile environment where failures are not exceptions—they are the norm. Understanding these failure modes is essential to building a system that remains correct under all conditions.

### 1. Duplicate Requests

**The Problem:** A client sends the same payment request multiple times due to:
- Network timeouts causing automatic retries
- User double-clicking submit buttons
- Mobile apps retrying after app crashes
- Load balancers retrying failed requests

**The Consequence:** Without protection, a $100 payment could be processed twice, resulting in $200 being debited from the sender's account while only $100 is credited to the receiver. This violates the fundamental principle that money cannot be created or destroyed.

**Why It's Hard:** The duplicate request may arrive seconds, minutes, or even days after the original. The system must recognize it as a duplicate and return the same result without side effects.

### 2. Partial Failures

**The Problem:** Distributed systems fail partially. Common scenarios:
- Database write succeeds but Kafka publish fails
- Payment status updated but ledger entries not created
- Ledger entries created but account balance not updated
- Transaction committed in one database but not replicated to another

**The Consequence:** The system enters an inconsistent state. A payment might be marked as SETTLED in the database, but the corresponding ledger entries are missing. Or ledger entries exist but the payment status is still AUTHORIZED. This breaks auditability and can lead to incorrect balance calculations.

**Why It's Hard:** You cannot atomically commit across multiple systems (database + Kafka + external services). You must design for eventual consistency while maintaining correctness guarantees.

### 3. Service Restarts

**The Problem:** Services crash, restart, or are deployed:
- A payment is being processed when the service crashes
- The service restarts and processes the same payment again
- In-flight requests are lost or duplicated
- Database connections are reset mid-transaction

**The Consequence:** A payment that was 90% complete (status updated, ledger entries partially written) may be retried from the beginning, causing duplicate ledger entries or state corruption. Alternatively, a payment might be lost entirely if it was only in memory.

**Why It's Hard:** You must ensure that all state changes are durable and idempotent. A restart should never cause money to be moved twice or lost.

### 4. Event Replays

**The Problem:** In event-driven systems, events can be replayed:
- Kafka consumer crashes and replays from an earlier offset
- Event store is restored from backup
- Consumer group is reset for debugging
- Events are manually replayed to recover from failures

**The Consequence:** If a `payment_settled` event is replayed, the system might:
- Create duplicate ledger entries
- Update the payment status multiple times
- Trigger downstream processes multiple times

**Why It's Hard:** Events represent facts that happened, but replaying them must not cause side effects to be applied again. The system must be able to distinguish "this event was already processed" from "this is a new event."

### 5. Network Interruptions

**The Problem:** Networks are unreliable:
- Timeouts that are indistinguishable from failures
- Messages arrive out of order
- Messages are lost and retried
- Split-brain scenarios in distributed systems

**The Consequence:** A payment authorization might be sent twice due to a timeout, or a settlement confirmation might be lost, leaving the payment in an ambiguous state.

### 6. Concurrent Modifications

**The Problem:** Multiple threads or services modify the same payment simultaneously:
- Two services try to settle the same payment
- Race condition between authorization and failure
- Concurrent balance checks leading to overdrafts

**The Consequence:** Without proper locking or optimistic concurrency control, the last write wins, potentially overwriting important state changes or allowing invalid transitions.

---

## Non-Negotiable Invariants

These invariants must hold true in all circumstances, regardless of failures, retries, or concurrent operations. Violating any of these means the system has corrupted financial data.

### Invariant 1: A Payment Must Not Affect Money Twice

**Statement:** For any given payment, the financial impact (debit from sender, credit to receiver) must occur exactly once, even if:
- The payment request is sent multiple times
- The service restarts during processing
- Events are replayed
- The same payment is processed concurrently

**Enforcement Mechanisms:**
- **Idempotency Keys:** Every payment request must include a unique idempotency key. Processing the same key twice must return the same result without side effects.
- **Database Constraints:** Use unique constraints on idempotency keys to prevent duplicate payment creation.
- **Idempotent Operations:** All payment state transitions and ledger operations must be idempotent. Applying the same operation twice must have the same effect as applying it once.
- **Deduplication:** Track processed events by event ID to prevent reprocessing.

**Why This Matters:** Money cannot be created or destroyed. If a $100 payment is processed twice, $200 is debited but only $100 should move. The extra $100 is effectively stolen from the sender.

### Invariant 2: Ledger Must Always Balance

**Statement:** The sum of all debit entries must equal the sum of all credit entries at all times. This is the fundamental rule of double-entry accounting.

**Mathematical Expression:** `Σ(debits) = Σ(credits)` for all ledger entries.

**Enforcement Mechanisms:**
- **Atomic Transactions:** Ledger entries (debit + credit) must be created atomically within a single database transaction. Either both are written or neither.
- **Database Constraints:** Use database-level check constraints or triggers to validate that transactions balance before commit.
- **Immutable Entries:** Once written, ledger entries cannot be modified or deleted. Corrections are made through reversal entries.
- **Derived Balances:** Account balances are calculated from ledger entries, not stored directly. This ensures balances always reflect the true state of the ledger.
- **Reconciliation:** Periodic reconciliation jobs verify that the ledger balances and flag any discrepancies.

**Why This Matters:** An imbalanced ledger means money has been created or destroyed. In a real financial system, this would be a critical audit failure and could indicate fraud or system corruption.

### Invariant 3: Retries Must Be Safe

**Statement:** Any operation that can be retried (due to timeouts, failures, or manual intervention) must be safe to retry. Retrying must not cause:
- Duplicate financial impact
- Invalid state transitions
- Corruption of existing data
- Side effects to be applied multiple times

**Enforcement Mechanisms:**
- **Idempotent State Transitions:** State machine transitions must be idempotent. Attempting to transition from AUTHORIZED to SETTLED twice must have the same effect as doing it once.
- **Idempotent Ledger Operations:** Creating ledger entries for a payment must be idempotent. Checking if entries already exist before creating new ones.
- **Optimistic Locking:** Use version numbers or timestamps to detect concurrent modifications and prevent invalid state transitions.
- **Idempotent Event Processing:** Event consumers must check if an event has already been processed before applying its effects.

**Why This Matters:** In distributed systems, retries are not exceptional—they are expected. Network timeouts, service restarts, and transient failures happen constantly. If retries are not safe, the system will corrupt data under normal operating conditions.

---

## Design Principles Derived from These Invariants

1. **Correctness Over Performance:** It is better to be slow and correct than fast and wrong. Financial systems prioritize correctness above all else.

2. **Pessimistic by Default:** Assume failures will happen. Design every operation to be safe under failure conditions.

3. **Idempotency Everywhere:** Every operation that can be retried must be idempotent. This is not optional—it is a requirement.

4. **Database as Source of Truth:** The database enforces correctness through constraints, transactions, and immutability. Code can have bugs; database constraints cannot.

5. **Events as Facts, Not Commands:** Events represent things that have already happened. They are immutable facts. Replaying an event must not cause its effects to be applied again.

6. **Derived State, Not Stored State:** Account balances are calculated from ledger entries, not stored. This ensures balances always reflect the true ledger state.

7. **Explicit State Machines:** State transitions are explicit, validated, and enforced. Invalid transitions are rejected at the database or application level.

---

## Learning Outcome

By internalizing these failure modes and invariants, you stop thinking in "happy paths." Every line of code you write must consider:
- What happens if this is called twice?
- What happens if the service crashes here?
- What happens if this event is replayed?
- What happens if two requests arrive concurrently?

This mindset shift—from assuming success to assuming failure—is what separates production-grade financial systems from prototypes. Money systems are hard not because of complex business logic, but because they must remain correct despite constant failures.

