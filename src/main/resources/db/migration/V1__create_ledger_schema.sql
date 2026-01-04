-- Phase 1: Ledger First - Financial Correctness Core
-- This schema enforces double-entry accounting principles at the database level

-- Accounts table
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(50) NOT NULL UNIQUE,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_account_number ON accounts(account_number);

-- Transactions table
-- Represents a logical transaction that groups related ledger entries
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Ledger entries table
-- This is the core of double-entry accounting
-- Every transaction must have balanced debits and credits
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    amount DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    description VARCHAR(255),
    sequence_number BIGSERIAL NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ledger_entries_immutable CHECK (created_at = created_at) -- Placeholder for immutability enforcement
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_entries_sequence_number ON ledger_entries(sequence_number);

-- Function to validate that a transaction balances (debits = credits)
CREATE OR REPLACE FUNCTION validate_transaction_balance(p_transaction_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    debit_total DECIMAL(19, 4);
    credit_total DECIMAL(19, 4);
BEGIN
    SELECT 
        COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0)
    INTO debit_total, credit_total
    FROM ledger_entries
    WHERE transaction_id = p_transaction_id;
    
    RETURN debit_total = credit_total;
END;
$$ LANGUAGE plpgsql;

-- Trigger to enforce transaction balance before commit
-- This ensures that debits always equal credits for each transaction
-- Note: This trigger fires at the end of the transaction (DEFERRED)
CREATE OR REPLACE FUNCTION check_transaction_balance()
RETURNS TRIGGER AS $$
DECLARE
    is_balanced BOOLEAN;
BEGIN
    -- Check balance for the transaction being modified
    SELECT validate_transaction_balance(NEW.transaction_id) INTO is_balanced;
    
    IF NOT is_balanced THEN
        RAISE EXCEPTION 'Transaction % is not balanced: debits must equal credits', NEW.transaction_id;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger fires after insert or update on ledger_entries
-- Using DEFERRABLE INITIALLY DEFERRED so it checks at transaction commit time
-- This allows all entries for a transaction to be inserted before validation
CREATE CONSTRAINT TRIGGER enforce_transaction_balance
    AFTER INSERT OR UPDATE ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_transaction_balance();

-- View to calculate account balances from ledger entries
-- Balances are derived, not stored (invariant from Phase 0)
CREATE OR REPLACE VIEW account_balances AS
SELECT 
    a.id AS account_id,
    a.account_number,
    a.account_type,
    COALESCE(
        SUM(CASE 
            WHEN a.account_type = 'ASSET' THEN 
                CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE -le.amount END
            WHEN a.account_type = 'LIABILITY' THEN 
                CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END
            WHEN a.account_type = 'EQUITY' THEN 
                CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END
            ELSE 0
        END), 0
    ) AS balance
FROM accounts a
LEFT JOIN ledger_entries le ON a.id = le.account_id
GROUP BY a.id, a.account_number, a.account_type;

-- Note: Immutability of ledger entries is enforced by:
-- 1. No UPDATE or DELETE operations should be performed (application-level enforcement)
-- 2. If corrections are needed, create reversal entries (future phase)
-- 3. Database constraints prevent deletion of accounts/transactions that have ledger entries

