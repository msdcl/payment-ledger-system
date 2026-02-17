#####Event-Driven Payments & Ledger Platform

PHASE 0 — Mental Model & Rules of the System

Goal: Build the correct mental model before touching code.

What you should understand deeply here

Why money systems are hard

Why correctness > performance

Why retries are normal, not exceptional

Why events are facts, not commands

Tasks

Write a 1-page doc:
“What can go wrong in a payment system?”

duplicate requests

partial failures

service restarts

event replays

Define non-negotiable invariants:

a payment must not affect money twice

ledger must always balance

retries must be safe

✅ Learning outcome:
You stop thinking in “happy paths”.

🧩 PHASE 1 — Ledger First (Financial Correctness Core)

Goal: Learn why ledgers exist and how correctness is enforced.

Concepts you’ll master

Double-entry accounting

Immutability

Derived state vs stored state

Database-enforced correctness

Tasks

Design SQL schema without Spring/JPA

accounts

transactions

ledger_entries

Enforce:

debit + credit must balance

ledger entries are immutable

Write a small Java service:

postTransaction(debits, credits)

Write tests:

try to break the ledger (imbalanced entries)

❌ No Kafka
❌ No APIs
❌ No idempotency yet

✅ Learning outcome:
You understand financial correctness as a data problem, not a framework problem.

🧩 PHASE 2 — Payment Domain & State Machine

Goal: Understand business state transitions and guardrails.


Explicit state machines

Invalid transitions

Domain-driven thinking

Why “status enums” need rules

Tasks

Model Payment as a domain object (no JPA yet)

Define allowed transitions:

CREATED → AUTHORIZED

AUTHORIZED → SETTLED / FAILED

Enforce transitions in code

Write tests for invalid transitions

❌ No ledger integration yet
❌ No Kafka

✅ Learning outcome:
You stop treating status fields as “just columns”.

🧩 PHASE 3 — Idempotent APIs (Trust Under Retries)

Goal: Learn how real systems survive duplicate requests.

Concepts you’ll master

Idempotency keys

Race conditions

Redis vs DB trade-offs

Exactly-once is a lie

Tasks

Create POST /payments

Require Idempotency-Key

Implement:

Redis fast-path

DB fallback

Simulate:

same request sent twice

concurrent duplicate requests

Document behavior clearly

❌ No Kafka
❌ No settlement

✅ Learning outcome:
You understand why idempotency is a business guarantee, not an API trick.

🧩 PHASE 4 — Integrate Ledger with Payments

Goal: Tie state changes to money movement safely.

Concepts you’ll master

Transaction boundaries

When to write ledger entries

Preventing double settlement

Tasks

On SETTLED:

create ledger entries atomically

Add a DB guard:

prevent same payment from posting ledger twice

Test:

double settle attempts

retries after partial failures

❌ Still no Kafka

✅ Learning outcome:
You see how data invariants protect you more than code.

🧩 PHASE 5 — Events as Facts (Kafka + Outbox)

Goal: Learn event-driven thinking, not just Kafka syntax.

Concepts you’ll master

Events vs commands

Transactional outbox

Event replay

Eventual consistency

Tasks

Add outbox_events table

Write outbox rows inside DB transactions

Background publisher → Kafka

Publish events:

payment_created

payment_authorized

payment_settled

❌ No consumer logic yet

✅ Learning outcome:
You understand why “publish after commit” matters.

🧩 PHASE 6 — Consumers & Replay Safety

Goal: Learn how distributed systems fail repeatedly.

Concepts you’ll master

At-least-once delivery

Deduplication

Consumer crashes

Replay safety

Tasks

Create consumer for settlement events

Add processed_events table

Ensure:

duplicate events don’t break system

Simulate:

consumer crash mid-processing

replay from offset 0

✅ Learning outcome:
You become comfortable with chaos and retries.

🧩 PHASE 7 — Observability (Operate What You Build)

Goal: Think like someone on call.

Concepts you’ll master

Correlation IDs

Meaningful metrics

Debuggability

Tasks

Add structured logs

Propagate correlation ID:

API → DB → Kafka

Add metrics:

payment latency

failures

Kafka lag

Decide:

what would page you at 3 AM?

✅ Learning outcome:
You understand production visibility, not just code correctness.

🧩 PHASE 8 — Failure Scenarios (Where Seniors Shine)

Goal: Learn by breaking your own system.

Tasks

Manually test and document:

Redis down

Kafka down

DB rollback after outbox write

duplicate events

service restarts

Write:

“What happened?”

“Why it didn’t corrupt money”

✅ Learning outcome:
You gain engineering confidence, not just knowledge.

