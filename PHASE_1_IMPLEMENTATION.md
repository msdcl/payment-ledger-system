# Phase 1 Implementation: Ledger First (Financial Correctness Core)

## ✅ Completed Tasks

### 1. SQL Schema Design
Created comprehensive database schema in `V1__create_ledger_schema.sql`:

- **accounts** table: Stores account information with account types (ASSET, LIABILITY, EQUITY)
- **transactions** table: Groups related ledger entries
- **ledger_entries** table: Core of double-entry accounting with debit/credit entries
- **Database constraints**: 
  - CHECK constraints on entry_type and account_type
  - Foreign key constraints preventing orphaned entries
  - Amount must be positive

### 2. Database-Enforced Correctness

**Transaction Balance Enforcement:**
- Created `validate_transaction_balance()` function to check debits = credits
- Created deferred constraint trigger `enforce_transaction_balance` that validates at transaction commit time
- This ensures that even if application-level validation is bypassed, the database enforces the invariant

**Immutability:**
- Ledger entries are designed to be immutable (no UPDATE/DELETE operations provided)
- Foreign key constraints with ON DELETE RESTRICT prevent deletion of accounts/transactions with entries
- Future phases will add explicit immutability enforcement

**Derived Balances:**
- Created `account_balances` view that calculates balances from ledger entries
- Balances are never stored directly, always derived (invariant from Phase 0)

### 3. Java Service Implementation

**LedgerService** (`LedgerService.java`):
- `postTransaction(TransactionRequest)` method:
  - Validates transaction is balanced (application-level)
  - Validates all accounts exist
  - Creates transaction record
  - Creates all ledger entries atomically within a transaction
  - Database trigger validates balance at commit time
  
- `getAccountBalance(UUID)` method:
  - Calculates balance from ledger entries (derived, not stored)
  - Handles different account types correctly (ASSET vs LIABILITY/EQUITY)
  
- `getLedgerEntriesForTransaction(UUID)` method:
  - Retrieves all entries for a transaction

**Domain Models:**
- `Account`: Plain Java class (no JPA) representing an account
- `LedgerEntry`: Plain Java class representing a ledger entry
- `EntryType`: Enum for DEBIT/CREDIT
- `TransactionRequest`: Request object with validation logic

### 4. Tests That Try to Break the Ledger

Created comprehensive test suite in `LedgerServiceTest.java`:

✅ **Valid Operations:**
- Balanced transactions succeed
- Multiple debits/credits that balance
- Account balance calculations

❌ **Attempts to Break the System:**
- Imbalanced transactions (application-level rejection)
- Zero/negative amounts
- Non-existent accounts
- Empty debits/credits
- Database-level enforcement test (conceptual)

## Key Design Decisions

### Why No JPA in Phase 1?
As specified in the project plan, Phase 1 focuses on understanding **database-enforced correctness**. Using JDBC directly helps us:
- See exactly what SQL is being executed
- Understand database constraints and triggers
- Appreciate that correctness is enforced at the data layer, not just in code

### Transaction Balance Enforcement
We use a **two-layer approach**:
1. **Application-level**: Fast validation in Java code
2. **Database-level**: Deferred constraint trigger that validates at commit time

This ensures correctness even if:
- Application code has bugs
- Someone bypasses the application layer
- Concurrent modifications occur

### Immutability Strategy
- Ledger entries are designed to be immutable
- No UPDATE/DELETE operations are provided in the service
- Future phases will add explicit database-level immutability enforcement
- Corrections will be made through reversal entries (not modifications)

## Database Schema Highlights

```sql
-- Core invariant: debits must equal credits
CREATE CONSTRAINT TRIGGER enforce_transaction_balance
    AFTER INSERT OR UPDATE ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_transaction_balance();
```

The `DEFERRABLE INITIALLY DEFERRED` constraint allows all entries for a transaction to be inserted before validation occurs at commit time.

## Learning Outcomes

✅ **Financial correctness is a data problem, not a framework problem**
- Database constraints and triggers enforce invariants
- Code can have bugs; database constraints cannot (if designed correctly)

✅ **Double-entry accounting principles**
- Every transaction must balance (debits = credits)
- Balances are derived from entries, not stored
- Entries are immutable once written

✅ **Database-enforced correctness**
- Application-level validation is fast and user-friendly
- Database-level validation is the ultimate safety net
- Both layers work together to ensure correctness

## Next Steps: Phase 2

Phase 1 is complete. Ready to move to Phase 2: Payment Domain & State Machine, which will:
- Model Payment as a domain object
- Define state transitions (CREATED → AUTHORIZED → SETTLED/FAILED)
- Enforce transitions in code
- Write tests for invalid transitions

**Note:** Phase 2 will NOT integrate with the ledger yet (that's Phase 4).

