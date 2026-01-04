package com.flagship.payment_ledger.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service for posting transactions to the ledger.
 * 
 * This service enforces the core invariants:
 * 1. Debits must equal credits (balanced transactions)
 * 2. Ledger entries are immutable once written
 * 3. All operations are atomic within a transaction
 * 
 * Phase 1: No JPA, using JDBC directly to understand database-enforced correctness.
 */
@Service
public class LedgerService {
    
    private final JdbcTemplate jdbcTemplate;

    public LedgerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Posts a transaction to the ledger.
     * 
     * This method:
     * 1. Validates that debits equal credits (application-level check)
     * 2. Creates a transaction record
     * 3. Creates ledger entries atomically
     * 4. Database trigger enforces balance at commit time
     * 
     * @param request The transaction request with debits and credits
     * @return The UUID of the created transaction
     * @throws IllegalArgumentException if transaction is not balanced
     */
    @Transactional
    public UUID postTransaction(TransactionRequest request) {
        // Application-level validation: debits must equal credits
        if (!request.isBalanced()) {
            throw new IllegalArgumentException(
                String.format("Transaction is not balanced: debits=%s, credits=%s",
                    request.getDebitTotal(), request.getCreditTotal()));
        }

        // Validate all accounts exist
        validateAccountsExist(request);

        // Create transaction record
        UUID transactionId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO transactions (id, description, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
            transactionId,
            request.getDescription()
        );

        // Create debit entries
        for (TransactionRequest.DebitCredit debit : request.getDebits()) {
            createLedgerEntry(transactionId, debit.getAccountId(), debit.getAmount(), 
                            EntryType.DEBIT, debit.getDescription());
        }

        // Create credit entries
        for (TransactionRequest.DebitCredit credit : request.getCredits()) {
            createLedgerEntry(transactionId, credit.getAccountId(), credit.getAmount(), 
                            EntryType.CREDIT, credit.getDescription());
        }

        // Database trigger will validate balance before commit
        // If balance is wrong, transaction will rollback
        
        return transactionId;
    }

    private void createLedgerEntry(UUID transactionId, UUID accountId, BigDecimal amount, 
                                  EntryType entryType, String description) {
        jdbcTemplate.update(
            "INSERT INTO ledger_entries (id, transaction_id, account_id, amount, entry_type, description, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            transactionId,
            accountId,
            amount,
            entryType.name(),
            description
        );
    }

    private void validateAccountsExist(TransactionRequest request) {
        List<UUID> allAccountIds = request.getDebits().stream()
            .map(TransactionRequest.DebitCredit::getAccountId)
            .toList();
        allAccountIds = new java.util.ArrayList<>(allAccountIds);
        allAccountIds.addAll(
            request.getCredits().stream()
                .map(TransactionRequest.DebitCredit::getAccountId)
                .toList()
        );

        for (UUID accountId : allAccountIds) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE id = ?",
                Integer.class,
                accountId
            );
            if (count == null || count == 0) {
                throw new IllegalArgumentException("Account not found: " + accountId);
            }
        }
    }

    /**
     * Gets the balance for an account by calculating from ledger entries.
     * Balances are derived, not stored (invariant from Phase 0).
     */
    public BigDecimal getAccountBalance(UUID accountId) {
        // Get account type first
        String accountType = jdbcTemplate.queryForObject(
            "SELECT account_type FROM accounts WHERE id = ?",
            String.class,
            accountId
        );

        if (accountType == null) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }

        // Calculate balance based on account type
        // For ASSET: debit increases, credit decreases
        // For LIABILITY/EQUITY: credit increases, debit decreases
        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE " +
            "  WHEN ? = 'ASSET' THEN CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END " +
            "  WHEN ? IN ('LIABILITY', 'EQUITY') THEN CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END " +
            "  ELSE 0 END), 0) " +
            "FROM ledger_entries WHERE account_id = ?",
            BigDecimal.class,
            accountType,
            accountType,
            accountId
        );

        return balance != null ? balance : BigDecimal.ZERO;
    }

    /**
     * Gets all ledger entries for a transaction.
     */
    public List<LedgerEntry> getLedgerEntriesForTransaction(UUID transactionId) {
        return jdbcTemplate.query(
            "SELECT id, transaction_id, account_id, amount, entry_type, description, sequence_number " +
            "FROM ledger_entries WHERE transaction_id = ? ORDER BY sequence_number",
            ledgerEntryRowMapper(),
            transactionId
        );
    }

    private RowMapper<LedgerEntry> ledgerEntryRowMapper() {
        return (rs, rowNum) -> new LedgerEntry(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("transaction_id")),
            UUID.fromString(rs.getString("account_id")),
            rs.getBigDecimal("amount"),
            EntryType.valueOf(rs.getString("entry_type")),
            rs.getString("description"),
            rs.getLong("sequence_number")
        );
    }
}

