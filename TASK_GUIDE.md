# Task Management Guide

## Overview

This project follows a phased approach where each phase builds upon the previous one. Tasks have been created based on the project plan phases and can be tracked using the built-in task system.

## Phase Structure

### ✅ Phase 0: Mental Model & Rules (COMPLETED)
- Documentation on failure modes and invariants
- **Status:** Complete

### 🧩 Phase 1: Ledger First (Financial Correctness Core)
**Goal:** Learn why ledgers exist and how correctness is enforced.

**Tasks:**
- Design SQL schema (accounts, transactions, ledger_entries)
- Enforce database constraints (debit + credit balance, immutability)
- Write Java service with `postTransaction(debits, credits)`
- Write tests that try to break the ledger

**Constraints:** No Kafka, No APIs, No idempotency yet

### 🧩 Phase 2: Payment Domain & State Machine
**Goal:** Understand business state transitions and guardrails.

**Tasks:**
- Model Payment as domain object
- Define allowed transitions (CREATED → AUTHORIZED → SETTLED/FAILED)
- Enforce transitions in code
- Write tests for invalid transitions

**Constraints:** No ledger integration yet, No Kafka

### 🧩 Phase 3: Idempotent APIs (Trust Under Retries)
**Goal:** Learn how real systems survive duplicate requests.

**Tasks:**
- Create POST /payments endpoint
- Require Idempotency-Key header
- Implement Redis fast-path
- Implement DB fallback
- Simulate duplicate requests
- Document behavior

**Constraints:** No Kafka, No settlement

### 🧩 Phase 4: Integrate Ledger with Payments
**Goal:** Tie state changes to money movement safely.

**Tasks:**
- Create ledger entries atomically on SETTLED
- Add DB guard to prevent double settlement
- Test double settle attempts
- Test retries after partial failures

**Constraints:** Still no Kafka

### 🧩 Phase 5: Events as Facts (Kafka + Outbox)
**Goal:** Learn event-driven thinking, not just Kafka syntax.

**Tasks:**
- Add outbox_events table
- Write outbox rows inside DB transactions
- Implement background publisher → Kafka
- Publish events (payment_created, payment_authorized, payment_settled)

**Constraints:** No consumer logic yet

### 🧩 Phase 6: Consumers & Replay Safety
**Goal:** Learn how distributed systems fail repeatedly.

**Tasks:**
- Create consumer for settlement events
- Add processed_events table
- Ensure duplicate events don't break system
- Simulate consumer crash and replay scenarios

### 🧩 Phase 7: Observability (Operate What You Build)
**Goal:** Think like someone on call.

**Tasks:**
- Add structured logs
- Propagate correlation ID (API → DB → Kafka)
- Add metrics (payment latency, failures, Kafka lag)
- Decide what would page you at 3 AM

### 🧩 Phase 8: Failure Scenarios (Where Seniors Shine)
**Goal:** Learn by breaking your own system.

**Tasks:**
- Test and document: Redis down
- Test and document: Kafka down
- Test and document: DB rollback after outbox write
- Test and document: duplicate events
- Test and document: service restarts
- Write: "What happened?" and "Why it didn't corrupt money"

### 🧩 Phase 9: Documentation & Interview Story
**Goal:** Convert work into hireable signal.

**Tasks:**
- Write README (why this design, trade-offs, improvements)
- Prepare 3 stories (idempotency bug, ledger invariant, event replay issue)

## How to Use Tasks

### Viewing Tasks
Tasks are automatically tracked in your IDE. You can view them in the task panel.

### Working on Tasks
1. **Start a task:** Mark it as `in_progress` when you begin working
2. **Complete a task:** Mark it as `completed` when done
3. **Skip a task:** Mark it as `cancelled` if it's no longer needed

### Task Naming Convention
Tasks follow this pattern: `phase-{number}-{description}`
- Example: `phase-1-schema` = Phase 1, schema design task

### Sequential Dependencies
**Important:** Phases should be completed in order:
- Phase 1 must be done before Phase 2
- Phase 2 must be done before Phase 3
- And so on...

However, within a phase, some tasks can be done in parallel if they don't depend on each other.

## Key Principles to Remember

From Phase 0 mental model, always consider:

1. **What happens if this is called twice?** (Idempotency)
2. **What happens if the service crashes here?** (Durability)
3. **What happens if this event is replayed?** (Replay safety)
4. **What happens if two requests arrive concurrently?** (Concurrency)

## Progress Tracking

- ✅ **Completed:** Task is done
- 🔄 **In Progress:** Currently working on it
- ⏳ **Pending:** Not started yet
- ❌ **Cancelled:** No longer needed

## Next Steps

1. Review the task list
2. Start with Phase 1 tasks
3. Work through phases sequentially
4. Update task status as you progress
5. Refer back to `PHASE_0_MENTAL_MODEL.md` when making design decisions

