# Phase 3: Idempotency Documentation

## Overview

Phase 3 implements idempotent APIs that can safely handle duplicate requests. This is critical for payment systems where duplicate requests must never result in duplicate financial transactions.

## What is Idempotency?

**Idempotency** means that making the same request multiple times has the same effect as making it once.

For payment creation:
- First request with idempotency key `abc-123` → Creates payment, returns 201 Created
- Second request with same key `abc-123` → Returns existing payment, returns 200 OK
- No duplicate payment is created

## Implementation Strategy

### Two-Layer Approach

1. **Redis Fast-Path** (Primary)
   - Fast in-memory lookup
   - TTL: 7 days
   - Can be unavailable (network issues, Redis down)

2. **Database Fallback** (Secondary)
   - Slower but always available
   - Source of truth
   - Unique constraint on `idempotency_key` prevents duplicates

### Flow Diagram

```
Request with Idempotency-Key
         │
         ▼
    ┌─────────┐
    │  Redis  │ ← Fast lookup (if available)
    └────┬────┘
         │ Found?
         ├─ YES → Return existing payment (200 OK)
         │
         └─ NO ──┐
                 │
         ┌───────▼───────┐
         │   Database     │ ← Fallback lookup
         └───────┬───────┘
                 │ Found?
                 ├─ YES → Cache in Redis, return existing (200 OK)
                 │
                 └─ NO ──┐
                         │
                 ┌───────▼───────┐
                 │ Create Payment │
                 └───────┬───────┘
                         │
                 ┌───────▼───────┐
                 │ Save to DB      │
                 └───────┬───────┘
                         │
                 ┌───────▼───────┐
                 │ Cache in Redis │ ← Best effort
                 └───────┬───────┘
                         │
                         ▼
                  Return new payment (201 Created)
```

## API Usage

### Create Payment (Idempotent)

**Endpoint:** `POST /api/payments`

**Required Header:**
```
Idempotency-Key: <unique-key>
```

**Request Body:**
```json
{
  "amount": "100.00",
  "currency": "USD",
  "from_account_id": "uuid-here",
  "to_account_id": "uuid-here"
}
```

**First Request Response (201 Created):**
```json
{
  "id": "payment-uuid",
  "amount": "100.00",
  "currency": "USD",
  "from_account_id": "uuid-here",
  "to_account_id": "uuid-here",
  "status": "CREATED",
  "failure_reason": null,
  "created_at": "2024-01-01T10:00:00Z",
  "updated_at": "2024-01-01T10:00:00Z"
}
```

**Duplicate Request Response (200 OK):**
```json
{
  "id": "payment-uuid",  // Same ID as first request
  "amount": "100.00",
  "currency": "USD",
  "from_account_id": "uuid-here",
  "to_account_id": "uuid-here",
  "status": "CREATED",
  "failure_reason": null,
  "created_at": "2024-01-01T10:00:00Z",
  "updated_at": "2024-01-01T10:00:00Z"
}
```

## Idempotency Key Requirements

1. **Required**: Request must include `Idempotency-Key` header
2. **Unique**: Each unique key maps to one payment
3. **Client-generated**: Client is responsible for generating unique keys
4. **Format**: String (recommended: UUID)
5. **TTL**: Keys are stored for 7 days in Redis, permanently in database

## Failure Scenarios

### Redis Down

**Behavior:**
- System automatically falls back to database
- Idempotency still works (database has unique constraint)
- Slightly slower response time
- No data loss

**Example:**
```
Request 1: Redis down → Database lookup → Not found → Create payment → Save to DB
Request 2: Redis down → Database lookup → Found → Return existing payment
```

### Database Constraint Violation

**Scenario:** Two requests arrive simultaneously, both pass idempotency check, both try to insert

**Behavior:**
- Database unique constraint on `idempotency_key` prevents duplicate
- One request succeeds, one gets constraint violation
- Application handles gracefully (retry or return existing)

## Race Conditions

### Concurrent Requests with Same Key

**Scenario:** 10 requests arrive simultaneously with same idempotency key

**What Happens:**
1. All 10 check Redis → Not found
2. All 10 check Database → Not found
3. All 10 try to create payment
4. Database unique constraint ensures only one succeeds
5. Others get constraint violation → Application returns existing payment

**Result:** Only one payment created, all requests return same payment ID

## Best Practices

### Client-Side

1. **Generate unique keys**: Use UUIDs or client-specific identifiers
2. **Retry with same key**: If request fails, retry with same idempotency key
3. **Store keys**: Keep track of idempotency keys for audit purposes
4. **Key format**: Use descriptive prefixes if needed (e.g., `payment-{uuid}`)

### Server-Side

1. **Database is source of truth**: Redis is cache, database is authoritative
2. **Graceful degradation**: System works even if Redis is down
3. **Logging**: Log all idempotency key operations for debugging
4. **Monitoring**: Track Redis hit/miss rates

## Testing

### Manual Testing

```bash
# First request
curl -X POST http://localhost:8080/api/payments \
  -H "Idempotency-Key: test-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": "100.00",
    "currency": "USD",
    "from_account_id": "account-uuid",
    "to_account_id": "account-uuid"
  }'

# Duplicate request (same key)
curl -X POST http://localhost:8080/api/payments \
  -H "Idempotency-Key: test-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": "100.00",
    "currency": "USD",
    "from_account_id": "account-uuid",
    "to_account_id": "account-uuid"
  }'
# Should return same payment ID, status 200 OK
```

## Why This Matters

**Idempotency is a business guarantee, not an API trick.**

- **Network retries**: Clients automatically retry failed requests
- **User errors**: Users double-click submit buttons
- **Service restarts**: Services restart and retry in-flight requests
- **Load balancers**: Load balancers retry failed requests

Without idempotency, each retry would create a new payment, resulting in:
- Duplicate charges
- Incorrect balances
- Financial losses
- Customer complaints

With idempotency:
- Retries are safe
- Duplicate requests return same result
- Financial correctness maintained
- System is resilient to failures

## Learning Outcome

By implementing idempotency with Redis fast-path and database fallback, you understand:

1. **Exactly-once is a lie**: Distributed systems cannot guarantee exactly-once delivery
2. **Idempotency is the solution**: Make operations safe to retry
3. **Trade-offs matter**: Redis is fast but can fail, database is slow but reliable
4. **Defense in depth**: Multiple layers of protection (Redis + DB constraint)
5. **Business guarantee**: Idempotency protects money, not just API correctness
