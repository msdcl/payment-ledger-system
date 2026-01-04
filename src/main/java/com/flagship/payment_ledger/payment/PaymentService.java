package com.flagship.payment_ledger.payment;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service for managing payment state transitions.
 * 
 * Phase 2: Explicit state machine enforcement.
 * 
 * This service enforces the payment state machine rules:
 * - CREATED → AUTHORIZED
 * - AUTHORIZED → SETTLED / FAILED
 * - Terminal states (SETTLED, FAILED) cannot transition
 * 
 * Note: No ledger integration yet (Phase 4)
 * Note: Persistence is handled by PaymentPersistenceService (Phase 3)
 */
@Service
public class PaymentService {

    /**
     * Creates a new payment in CREATED status.
     * 
     * @param amount Payment amount
     * @param currency Payment currency
     * @param fromAccountId Source account ID
     * @param toAccountId Destination account ID
     * @return New Payment in CREATED status
     */
    public Payment createPayment(BigDecimal amount, CurrencyCode currency, 
                                UUID fromAccountId, UUID toAccountId) {
        // Validate inputs
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        if (currency == null) {
            throw new IllegalArgumentException("Currency is required");
        }
        if (fromAccountId == null || toAccountId == null) {
            throw new IllegalArgumentException("Account IDs are required");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("From and to accounts must be different");
        }

        UUID paymentId = UUID.randomUUID();
        return Payment.create(paymentId, amount, currency, fromAccountId, toAccountId);
    }

    /**
     * Authorizes a payment (transitions from CREATED to AUTHORIZED).
     * 
     * @param payment Payment to authorize
     * @return Payment in AUTHORIZED status
     * @throws IllegalStateException if payment is not in CREATED status
     */
    public Payment authorizePayment(Payment payment) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment cannot be null");
        }
        return payment.authorize();
    }

    /**
     * Settles a payment (transitions from AUTHORIZED to SETTLED).
     * 
     * @param payment Payment to settle
     * @return Payment in SETTLED status
     * @throws IllegalStateException if payment is not in AUTHORIZED status
     */
    public Payment settlePayment(Payment payment) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment cannot be null");
        }
        return payment.settle();
    }

    /**
     * Fails a payment (transitions from CREATED or AUTHORIZED to FAILED).
     * 
     * @param payment Payment to fail
     * @param reason Reason for failure
     * @return Payment in FAILED status
     * @throws IllegalStateException if payment is in a terminal state
     */
    public Payment failPayment(Payment payment, String reason) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment cannot be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Failure reason is required");
        }
        return payment.fail(reason);
    }
}

