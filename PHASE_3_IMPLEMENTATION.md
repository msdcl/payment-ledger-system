# Phase 3 Implementation: Idempotent APIs (Trust Under Retries)

## ✅ Completed Tasks

### 1. REST API Endpoint
Created `POST /api/payments` endpoint with:
- Required `Idempotency-Key` header
- Request validation using Jakarta Bean Validation
- Proper HTTP status codes (201 Created, 200 OK for duplicates)
- Error handling with `GlobalExceptionHandler`
- `GET /api/payments/{id}` for retrieving payments

### 2. Idempotency Service
Implemented `IdempotencyService` with:
- **Redis fast-path**: Fast in-memory lookup (if available)
- **Database fallback**: Always available, source of truth
- **Graceful degradation**: Works even when Redis is down
- **Best-effort caching**: Stores in Redis for future lookups
- **7-day TTL**: Idempotency keys cached for 7 days

### 3. Payment Persistence
- Created `payments` table with Flyway migration (`V2__create_payments_table.sql`)
- Added `PaymentEntity` JPA entity (separate from domain object)
- Created `PaymentRepository` with idempotency key lookup
- Implemented `PaymentPersistenceService` for domain/persistence separation
- Unique constraint on `idempotency_key` prevents duplicates

### 4. DTOs and Validation
- `CreatePaymentRequest`: Request DTO with Jakarta Bean Validation
- `PaymentResponse`: Response DTO
- `GlobalExceptionHandler`: Consistent error responses
- `ApiError`: Standard error response format

### 5. Comprehensive Tests
Created `PaymentControllerTest` with:
- ✅ Same request sent twice returns same payment
- ✅ Concurrent duplicate requests handled correctly (10 threads)
- ✅ Missing idempotency key rejected
- ✅ Invalid requests rejected
- ✅ Get payment by ID works
- ✅ Non-existent payment returns 404

### 6. Configuration
- Redis configuration with optional support
- JPA configuration
- Application properties for Redis and database

## Architecture

### Idempotency Flow

```
Client Request
    │
    ├─ Idempotency-Key Header (required)
    │
    ▼
┌─────────────────────┐
│ PaymentController   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ IdempotencyService  │
└──────────┬──────────┘
           │
    ┌──────┴──────┐
    │             │
    ▼             ▼
┌─────────┐  ┌──────────┐
│  Redis  │  │ Database │
│ (fast)  │  │ (source  │
│         │  │ of truth)│
└─────────┘  └──────────┘
```

### Two-Layer Strategy

1. **Redis (Fast-Path)**
   - TTL: 7 days
   - Fast lookup (< 1ms)
   - Can be unavailable
   - Best-effort caching
   - Key format: `idempotency:{key}`

2. **Database (Fallback)**
   - Always available
   - Unique constraint on `idempotency_key` prevents duplicates
   - Source of truth
   - Slower but reliable

## Key Design Decisions

### 1. Database as Source of Truth
- Redis is a cache, database is authoritative
- Unique constraint on `idempotency_key` prevents duplicates
- Even if Redis fails, idempotency is maintained
- Database constraint handles race conditions

### 2. Graceful Degradation
- System works without Redis
- Redis failures don't break the API
- Logs warnings but continues operation
- Optional Redis dependency

### 3. Race Condition Handling
- Database unique constraint handles concurrent inserts
- First insert succeeds, others get constraint violation
- Application handles constraint violations gracefully
- All concurrent requests return same payment ID

### 4. Separation of Concerns
- **Domain layer**: `Payment` (immutable, business logic, no JPA)
- **Persistence layer**: `PaymentEntity` (JPA, database mapping)
- **Service layer**: Orchestrates operations
- **Controller layer**: HTTP handling, DTOs

### 5. Java 20 Best Practices
- Immutable domain objects with Lombok `@Value`
- Pattern matching with switch expressions
- Optional for null safety
- Records for DTOs (using Lombok `@Value`)
- Proper exception handling
- Jakarta Bean Validation

## Files Created

### Database Migration
- `V2__create_payments_table.sql` - Payments table with idempotency support

### Domain & Persistence
- `PaymentEntity.java` - JPA entity (separate from domain Payment)
- `PaymentRepository.java` - JPA repository with idempotency key lookup
- `PaymentPersistenceService.java` - Persistence service

### API Layer
- `PaymentController.java` - REST controller with idempotency
- `CreatePaymentRequest.java` - Request DTO with validation
- `PaymentResponse.java` - Response DTO
- `GlobalExceptionHandler.java` - Global error handling
- `ApiError.java` - Error response DTO

### Idempotency
- `IdempotencyService.java` - Idempotency management (Redis + DB)
- `RedisConfig.java` - Redis configuration

### Tests
- `PaymentControllerTest.java` - Comprehensive API tests with Testcontainers

### Documentation
- `IDEMPOTENCY_DOCUMENTATION.md` - Detailed idempotency guide
- `PHASE_3_IMPLEMENTATION.md` - This file

## API Usage Examples

### Create Payment (First Request)

```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Idempotency-Key: payment-123" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": "100.00",
    "currency": "USD",
    "from_account_id": "account-uuid",
    "to_account_id": "account-uuid"
  }'
```

**Response (201 Created):**
```json
{
  "id": "payment-uuid",
  "amount": "100.00",
  "currency": "USD",
  "from_account_id": "account-uuid",
  "to_account_id": "account-uuid",
  "status": "CREATED",
  "failure_reason": null,
  "created_at": "2024-01-01T10:00:00Z",
  "updated_at": "2024-01-01T10:00:00Z"
}
```

### Duplicate Request (Same Idempotency Key)

```bash
# Same request with same idempotency key
curl -X POST http://localhost:8080/api/payments \
  -H "Idempotency-Key: payment-123" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": "100.00",
    "currency": "USD",
    "from_account_id": "account-uuid",
    "to_account_id": "account-uuid"
  }'
```

**Response (200 OK):**
```json
{
  "id": "payment-uuid",  // Same ID as first request
  "amount": "100.00",
  "currency": "USD",
  "from_account_id": "account-uuid",
  "to_account_id": "account-uuid",
  "status": "CREATED",
  "failure_reason": null,
  "created_at": "2024-01-01T10:00:00Z",
  "updated_at": "2024-01-01T10:00:00Z"
}
```

## Testing

### Run All Tests
```bash
./gradlew test
```

### Run Payment API Tests
```bash
./gradlew test --tests "com.flagship.payment_ledger.payment.PaymentControllerTest"
```

### Test Scenarios Covered
1. ✅ Create payment with idempotency key
2. ✅ Same request twice returns same payment
3. ✅ Concurrent duplicate requests (10 threads)
4. ✅ Missing idempotency key rejected
5. ✅ Invalid request body rejected
6. ✅ Get payment by ID
7. ✅ Get non-existent payment returns 404

## Failure Scenarios Handled

### Redis Down
- System automatically falls back to database
- Idempotency still works
- Slightly slower but functional
- No data loss

### Concurrent Requests
- Database unique constraint prevents duplicates
- First request creates payment
- Other requests return existing payment
- All requests return same payment ID

### Database Constraint Violation
- Handled gracefully
- Application returns existing payment
- No error to client (idempotent behavior)

## Learning Outcomes

✅ **Idempotency is a business guarantee, not an API trick**
- Protects against duplicate financial transactions
- Essential for payment systems
- Must work even when infrastructure fails

✅ **Exactly-once is a lie**
- Distributed systems cannot guarantee exactly-once delivery
- Idempotency makes operations safe to retry
- Accept at-least-once, design for idempotency

✅ **Redis vs Database trade-offs**
- Redis: Fast but can fail
- Database: Slow but reliable
- Use both: Fast path + reliable fallback

✅ **Race conditions are real**
- Concurrent requests happen in production
- Database constraints are your friend
- Design for concurrency from the start

✅ **Defense in depth**
- Multiple layers of protection
- Redis for speed, database for correctness
- Unique constraints prevent duplicates

## Next Steps: Phase 4

Phase 3 is complete. Ready to move to Phase 4: Integrate Ledger with Payments, which will:
- Create ledger entries when payment is SETTLED
- Add database guard to prevent double settlement
- Test retries and partial failures
- Ensure atomicity between payment and ledger

**Note:** Phase 4 will NOT add Kafka yet (that's Phase 5).
