package com.flagship.payment_ledger.ledger;

import lombok.Value;

import java.util.UUID;

/**
 * Domain model for an Account.
 * In Phase 1, this is a plain Java class (no JPA annotations).
 * Represents a financial account in the ledger system.
 */
@Value
public class Account {
    UUID id;
    String accountNumber;
    AccountType accountType;

    public enum AccountType {
        ASSET,
        LIABILITY,
        EQUITY
    }
}

