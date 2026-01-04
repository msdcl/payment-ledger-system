package com.flagship.payment_ledger.payment;

/**
 * Payment status enum representing the state of a payment.
 * 
 * Phase 2: Explicit state machine with defined transitions.
 * Status fields are not "just columns" - they have rules and constraints.
 */
public enum PaymentStatus {
    /**
     * Payment has been created but not yet authorized.
     * Initial state for all payments.
     */
    CREATED,
    
    /**
     * Payment has been authorized (funds reserved/verified).
     * Can transition to SETTLED or FAILED.
     */
    AUTHORIZED,
    
    /**
     * Payment has been settled (funds moved).
     * Terminal state - no further transitions allowed.
     */
    SETTLED,
    
    /**
     * Payment has failed.
     * Terminal state - no further transitions allowed.
     */
    FAILED
}

