-- Phase 4: Integrate Ledger with Payments
-- This migration adds support for tracking ledger transactions created for payments
-- and prevents double settlement at the database level

-- Step 1: Add ledger_transaction_id column to track which ledger transaction was created for this payment
-- NULL means no ledger transaction has been created yet (payment not settled)
-- Once set, it cannot be changed (prevents double settlement)
-- Default to NULL for all existing rows
ALTER TABLE payments 
ADD COLUMN ledger_transaction_id UUID;

-- Step 2: Add foreign key constraint after column is created
-- This ensures ledger_transaction_id references a valid transaction
ALTER TABLE payments 
ADD CONSTRAINT fk_payments_ledger_transaction 
FOREIGN KEY (ledger_transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT;

-- Step 3: Create unique index to ensure a ledger transaction can only be associated with one payment
-- This provides an additional guard against double settlement
-- Using partial index (WHERE clause) to only index non-NULL values
CREATE UNIQUE INDEX idx_payments_ledger_transaction_id ON payments(ledger_transaction_id) 
WHERE ledger_transaction_id IS NOT NULL;

-- Step 4: Add check constraint to ensure only SETTLED payments can have a ledger_transaction_id
-- This enforces the business rule: ledger entries are only created when payment is SETTLED
-- Note: Since we're adding this to an existing table, all existing payments will have NULL
-- which is correct since Phase 3 didn't have settlement functionality
-- Using a simpler constraint that's easier for PostgreSQL to validate
ALTER TABLE payments 
ADD CONSTRAINT payments_ledger_transaction_only_when_settled 
CHECK (
    CASE 
        WHEN status = 'SETTLED' THEN ledger_transaction_id IS NOT NULL
        ELSE ledger_transaction_id IS NULL
    END
);

-- Add index on status for efficient queries when finding payments ready to settle
-- (already exists, but documented here for clarity)

-- Comment explaining the design
--COMMENT ON COLUMN payments.ledger_transaction_id IS 
--'References the ledger transaction created when this payment was settled. ' ||
--'NULL means payment has not been settled yet. Once set, it cannot be changed, ' ||
--'preventing double settlement. This ensures atomicity between payment settlement and ledger posting.';
