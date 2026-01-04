package com.flagship.payment_ledger.payment;

import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment domain object.
 * 
 * Phase 2: Domain model with explicit state machine (no JPA yet).
 * 
 * Key principles:
 * - Status transitions are explicit and validated
 * - Invalid transitions are rejected
 * - State changes are immutable (create new Payment with new status)
 */
@Value
public class Payment {
    UUID id;
    BigDecimal amount;
    CurrencyCode currency;
    UUID fromAccountId;
    UUID toAccountId;
    PaymentStatus status;
    String failureReason;
    Instant createdAt;
    Instant updatedAt;

    /**
     * Creates a new Payment in CREATED status.
     */
    public static Payment create(UUID id, BigDecimal amount, CurrencyCode currency, 
                                UUID fromAccountId, UUID toAccountId) {
        Instant now = Instant.now();
        return new Payment(
            id,
            amount,
            currency,
            fromAccountId,
            toAccountId,
            PaymentStatus.CREATED,
            null,
            now,
            now
        );
    }

    /**
     * Transitions payment to AUTHORIZED status.
     * Only valid from CREATED status.
     * 
     * @return New Payment instance with AUTHORIZED status
     * @throws IllegalStateException if transition is not allowed
     */
    public Payment authorize() {
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException(
                String.format("Cannot authorize payment in %s status. Only CREATED payments can be authorized.", 
                    this.status)
            );
        }
        return new Payment(
            this.id,
            this.amount,
            this.currency,
            this.fromAccountId,
            this.toAccountId,
            PaymentStatus.AUTHORIZED,
            this.failureReason,
            this.createdAt,
            Instant.now()
        );
    }

    /**
     * Transitions payment to SETTLED status.
     * Only valid from AUTHORIZED status.
     * 
     * @return New Payment instance with SETTLED status
     * @throws IllegalStateException if transition is not allowed
     */
    public Payment settle() {
        if (this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException(
                String.format("Cannot settle payment in %s status. Only AUTHORIZED payments can be settled.", 
                    this.status)
            );
        }
        return new Payment(
            this.id,
            this.amount,
            this.currency,
            this.fromAccountId,
            this.toAccountId,
            PaymentStatus.SETTLED,
            this.failureReason,
            this.createdAt,
            Instant.now()
        );
    }

    /**
     * Transitions payment to FAILED status.
     * Only valid from CREATED or AUTHORIZED status.
     * 
     * @param reason Reason for failure
     * @return New Payment instance with FAILED status
     * @throws IllegalStateException if transition is not allowed
     */
    public Payment fail(String reason) {
        if (this.status != PaymentStatus.CREATED && this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException(
                String.format("Cannot fail payment in %s status. Only CREATED or AUTHORIZED payments can be failed.", 
                    this.status)
            );
        }
        return new Payment(
            this.id,
            this.amount,
            this.currency,
            this.fromAccountId,
            this.toAccountId,
            PaymentStatus.FAILED,
            reason,
            this.createdAt,
            Instant.now()
        );
    }

    /**
     * Checks if the payment is in a terminal state (no further transitions allowed).
     */
    public boolean isTerminal() {
        return this.status == PaymentStatus.SETTLED || this.status == PaymentStatus.FAILED;
    }

    /**
     * Checks if a transition from current status to target status is allowed.
     */
    public boolean canTransitionTo(PaymentStatus targetStatus) {
        if (this.status == targetStatus) {
            return true; // Same status is always allowed (idempotent)
        }

        return switch (this.status) {
            case CREATED -> targetStatus == PaymentStatus.AUTHORIZED || targetStatus == PaymentStatus.FAILED;
            case AUTHORIZED -> targetStatus == PaymentStatus.SETTLED || targetStatus == PaymentStatus.FAILED;
            case SETTLED, FAILED -> false; // Terminal states - no transitions allowed
        };
    }
}

