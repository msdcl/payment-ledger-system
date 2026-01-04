# Phase 4 Implementation: Integrate Ledger with Payments

## ✅ Completed Tasks

### 1. Database Migration: Ledger Integration
Created `V3__add_ledger_integration.sql` migration that:
- Adds `ledger_transaction_id` column to `payments` table
- Creates unique index to ensure one ledger transaction per payment
- Adds check constraint: only SETTLED payments can have `ledger_transaction_id`
- Documents the design with comments

**Key Design Decision:**
- `ledger_transaction_id` is NULL until payment is settled
- Once set, it cannot be changed (enforced by application logic and database constraint)
- This prevents double settlement at the database level

### 2. PaymentEntity Updates
Enhanced `PaymentEntity` to support ledger integration:
- Added `ledgerTransactionId` field (nullable, can be set once)
- Added `setLedgerTransactionId()` method with validation:
  - Prevents setting if already set (double settlement guard)
  - Validates payment is in SETTLED status
- Added `isSettled()` method to check if payment has ledger transaction
- Updated `fromDomain()` factory to initialize `ledgerTransactionId` as NULL

**Key Design Principles:**
- No setters on entity (prevents bypassing invariants)
- Controlled mutation via `setLedgerTransactionId()` method
- Application-level validation before database constraint

### 3. PaymentSettlementService
Created new service that handles atomic settlement:
- **`settlePayment(UUID paymentId)`**: Main settlement method
  - Validates payment exists and is in AUTHORIZED status
  - Checks if already settled (idempotency)
  - Transitions payment to SETTLED status
  - Creates ledger transaction (debit from source, credit to destination)
  - Updates payment with ledger transaction ID
  - All operations in single `@Transactional` method

- **`isSettled(UUID paymentId)`**: Check if payment has been settled

**Key Features:**
- **Atomicity**: Payment settlement and ledger posting happen in one transaction
- **Idempotency**: Safe to retry if partial failure occurs
- **Database Guards**: Unique constraint prevents double settlement
- **Validation**: Multiple layers of validation (application + database)

### 4. PaymentPersistenceService Enhancements
Added helper methods for settlement service:
- **`findByIdEntity(UUID paymentId)`**: Returns entity directly (not domain object)
- **`saveEntity(PaymentEntity entity)`**: Saves entity directly

These methods are needed because settlement requires direct entity manipulation
(setting `ledgerTransactionId`), which is a persistence concern.

### 5. Comprehensive Tests
Created `PaymentSettlementServiceTest` with tests for:
- ✅ Successful settlement creates ledger entries atomically
- ✅ Double settle attempts are idempotent (returns same ledger transaction ID)
- ✅ Invalid status settlement is rejected (CREATED → SETTLED)
- ✅ Non-existent payment settlement is rejected
- ✅ `isSettled()` correctly identifies settlement status
- ✅ Settlement atomicity (payment and ledger updated together)

## Key Design Decisions

### 1. Atomic Settlement
**Problem:** Payment settlement and ledger posting must happen atomically.

**Solution:** Single `@Transactional` method that:
1. Updates payment status to SETTLED
2. Creates ledger transaction
3. Updates payment with ledger transaction ID

If any step fails, entire operation rolls back.

### 2. Double Settlement Prevention
**Problem:** Need to prevent same payment from being settled twice.

**Solution:** Multi-layer defense:
1. **Application-level**: Check `isSettled()` before proceeding
2. **Entity-level**: `setLedgerTransactionId()` validates it's not already set
3. **Database-level**: Unique constraint on `ledger_transaction_id`
4. **Idempotency**: If already settled, return existing ledger transaction ID

### 3. Idempotent Retries
**Problem:** Retries after partial failures must be safe.

**Solution:**
- Check if payment already has `ledger_transaction_id` before creating new one
- If already settled, return existing ledger transaction ID
- No duplicate ledger entries created
- Safe to retry multiple times

### 4. Transaction Boundaries
**Problem:** When to create ledger entries?

**Solution:** Only when payment transitions to SETTLED status:
- CREATED → No ledger entries (payment not yet authorized)
- AUTHORIZED → No ledger entries (payment authorized but not settled)
- SETTLED → Ledger entries created atomically
- FAILED → No ledger entries (payment failed)

This ensures money only moves when payment is actually settled.

## Database Constraints

### 1. Unique Constraint
```sql
CREATE UNIQUE INDEX idx_payments_ledger_transaction_id 
ON payments(ledger_transaction_id) 
WHERE ledger_transaction_id IS NOT NULL;
```
**Purpose:** Ensures one ledger transaction can only be associated with one payment.
Prevents double settlement at database level.

### 2. Check Constraint
```sql
ALTER TABLE payments 
ADD CONSTRAINT payments_ledger_transaction_only_when_settled 
CHECK (
    (status = 'SETTLED' AND ledger_transaction_id IS NOT NULL) OR
    (status != 'SETTLED' AND ledger_transaction_id IS NULL)
);
```
**Purpose:** Enforces business rule: only SETTLED payments can have ledger transaction.
Prevents invalid states (e.g., CREATED payment with ledger transaction).

## Ledger Transaction Structure

When a payment is settled, a ledger transaction is created with:
- **Description**: "Payment settlement: {paymentId}"
- **Debit Entry**: 
  - Account: `from_account_id`
  - Amount: Payment amount
  - Description: "Payment {paymentId}: debit from account"
- **Credit Entry**:
  - Account: `to_account_id`
  - Amount: Payment amount
  - Description: "Payment {paymentId}: credit to account"

This follows double-entry accounting principles: debits = credits.

## Error Handling

### Invalid Status
- **Error**: `IllegalStateException`
- **Message**: "Cannot settle payment {id} in {status} status. Payment must be AUTHORIZED."
- **Handling**: Payment must be in AUTHORIZED status before settlement

### Already Settled
- **Behavior**: Idempotent - returns existing ledger transaction ID
- **No Error**: This is expected behavior for retries
- **Logging**: Info log indicates payment already settled

### Payment Not Found
- **Error**: `IllegalArgumentException`
- **Message**: "Payment not found: {id}"
- **Handling**: Payment must exist before settlement

## Testing Strategy

### Unit Tests
- Test settlement flow end-to-end
- Test idempotency (double settlement)
- Test invalid states
- Test atomicity

### Integration Tests
- Test with real database (Testcontainers)
- Test transaction rollback on failure
- Test concurrent settlement attempts (future enhancement)

## Key Learnings

✅ **Atomicity is critical**
- Payment and ledger must be updated together
- Single transaction ensures consistency
- Rollback on any failure prevents partial state

✅ **Database constraints are your friend**
- Application-level checks can be bypassed
- Database constraints enforce invariants
- Unique constraints prevent duplicates

✅ **Idempotency enables retries**
- Retries are normal, not exceptional
- Design for idempotency from the start
- Check before create, not after

✅ **Multi-layer defense**
- Application validation
- Entity-level guards
- Database constraints
- Each layer catches different failure modes

## Next Steps: Phase 5

Phase 4 is complete. Ready to move to Phase 5: Events as Facts (Kafka + Outbox), which will:
- Add outbox_events table for transactional outbox pattern
- Write outbox rows inside DB transactions
- Implement background publisher → Kafka
- Publish events: payment_created, payment_authorized, payment_settled

**Note:** Phase 4 does NOT add Kafka yet (that's Phase 5).
