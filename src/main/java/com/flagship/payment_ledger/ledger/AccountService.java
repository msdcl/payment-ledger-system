package com.flagship.payment_ledger.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for managing accounts.
 * Phase 1: Simple account creation for testing purposes.
 */
@Service
public class AccountService {
    
    private final JdbcTemplate jdbcTemplate;

    public AccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID createAccount(String accountNumber, Account.AccountType accountType) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO accounts (id, account_number, account_type, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
            accountId,
            accountNumber,
            accountType.name()
        );
        return accountId;
    }
}

