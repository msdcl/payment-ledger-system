package com.flagship.payment_ledger.ledger;

import lombok.Value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Request object for posting a transaction.
 * Contains debits and credits that must balance.
 * 
 * Invariant: Sum of debits must equal sum of credits.
 */
@Value
public class TransactionRequest {
    String description;
    List<DebitCredit> debits;
    List<DebitCredit> credits;

    /**
     * Validates that debits and credits balance.
     * This is a business rule that must be enforced.
     */
    public boolean isBalanced() {
        BigDecimal debitTotal = debits.stream()
            .map(DebitCredit::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal creditTotal = credits.stream()
            .map(DebitCredit::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return debitTotal.compareTo(creditTotal) == 0;
    }

    public BigDecimal getDebitTotal() {
        return debits.stream()
            .map(DebitCredit::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getCreditTotal() {
        return credits.stream()
            .map(DebitCredit::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Represents a single debit or credit entry.
     */
    @Value
    public static class DebitCredit {
        UUID accountId;
        BigDecimal amount;
        String description;

        private DebitCredit(UUID accountId, BigDecimal amount, String description) {
            this.accountId = Objects.requireNonNull(accountId);
            this.amount = Objects.requireNonNull(amount);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }
            this.description = description;
        }

        public static DebitCredit of(UUID accountId, BigDecimal amount, String description) {
            return new DebitCredit(accountId, amount, description);
        }
    }
}

