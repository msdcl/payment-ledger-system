package com.flagship.payment_ledger.ledger;

import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Domain model for a Ledger Entry.
 * In Phase 1, this is a plain Java class (no JPA annotations).
 * Represents a single debit or credit entry in the ledger.
 * 
 * Key invariant: All transactions must have balanced debits and credits.
 */
@Value
public class LedgerEntry {
    UUID id;
    UUID transactionId;
    UUID accountId;
    BigDecimal amount;
    EntryType entryType;
    String description;
    Long sequenceNumber;
}

