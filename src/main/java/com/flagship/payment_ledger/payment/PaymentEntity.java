package com.flagship.payment_ledger.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity for Payment persistence.
 * 
 * Phase 3: Adds persistence layer for payments.
 * 
 * Key design principles:
 * - No @Setter: Prevents bypassing invariants and domain rules
 * - No @Builder: Prevents invalid entity construction
 * - Immutable fields: ID, account IDs, created_at are updatable = false
 * - Lifecycle hooks: @PrePersist and @PreUpdate handle timestamps automatically
 * - Type-safe currency: Uses CurrencyCode enum instead of String
 * - Controlled factory: fromDomain() is the only way to create entities
 * 
 * Note: Idempotency key is a persistence concern, not a domain concern.
 * It's passed separately in fromDomain() because the domain model doesn't
 * need to know about idempotency - that's handled at the API/persistence layer.
 */
@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payments_idempotency_key", columnList = "idempotency_key"),
        @Index(name = "idx_payments_status", columnList = "status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEntity {
    
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;
    
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CurrencyCode currency;
    
    @Column(name = "from_account_id", nullable = false, updatable = false)
    private UUID fromAccountId;
    
    @Column(name = "to_account_id", nullable = false, updatable = false)
    private UUID toAccountId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;
    
    @Column(name = "failure_reason")
    private String failureReason;
    
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    /**
     * Phase 4: Ledger transaction ID created when payment is settled.
     * NULL means payment has not been settled yet.
     * Once set, it cannot be changed (enforced by application logic and database constraint),
     * preventing double settlement.
     * This ensures atomicity between payment settlement and ledger posting.
     */
    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;
    
    /**
     * JPA lifecycle hook: Sets timestamps before persisting.
     * This removes the responsibility from service layer.
     */
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
    
    /**
     * JPA lifecycle hook: Updates timestamp before updating.
     * This removes the responsibility from service layer.
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
    
    /**
     * Controlled factory method to create entity from domain object.
     * This is the ONLY way to create PaymentEntity instances.
     * 
     * Note: Idempotency key is passed separately because it's a persistence
     * concern, not a domain concern. The domain model doesn't need to know
     * about idempotency - that's handled at the API/persistence layer.
     * 
     * @param payment Domain payment object
     * @param idempotencyKey Idempotency key for this payment
     * @return New PaymentEntity instance
     */
    static PaymentEntity fromDomain(Payment payment, String idempotencyKey) {
        return new PaymentEntity(
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getFromAccountId(),
            payment.getToAccountId(),
            payment.getStatus(),
            payment.getFailureReason(),
            idempotencyKey,
            null, // createdAt - will be set by @PrePersist
            null, // updatedAt - will be set by @PrePersist
            null  // ledgerTransactionId - set when payment is settled (Phase 4)
        );
    }
    
    /**
     * Converts entity to domain Payment object.
     *
     * @return Domain Payment object
     */
    public Payment toDomain() {
        return new Payment(
            id,
            amount,
            currency,
            fromAccountId,
            toAccountId,
            status,
            failureReason,
            createdAt,
            updatedAt
        );
    }
    
    /**
     * Updates entity from domain object.
     * Only mutable fields (status, failureReason) can be updated.
     * Timestamps are handled automatically by @PreUpdate.
     * 
     * This method is used when updating an existing payment.
     * 
     * @param payment Domain payment object with updated state
     */
    void updateFromDomain(Payment payment) {
        // Only allow updating mutable fields
        // Immutable fields (id, amount, currency, account IDs, idempotencyKey, createdAt, ledgerTransactionId) cannot be changed
        this.status = payment.getStatus();
        this.failureReason = payment.getFailureReason();
        // updatedAt will be set automatically by @PreUpdate
    }
    
    /**
     * Phase 4: Sets the ledger transaction ID after settlement.
     * This can only be called once and prevents double settlement.
     * 
     * @param ledgerTransactionId The UUID of the ledger transaction created for this payment
     */
    void setLedgerTransactionId(UUID ledgerTransactionId) {
        if (this.ledgerTransactionId != null) {
            throw new IllegalStateException(
                "Ledger transaction ID already set for payment " + this.id + 
                ". Cannot settle payment twice.");
        }
        if (this.status != PaymentStatus.SETTLED) {
            throw new IllegalStateException(
                "Cannot set ledger transaction ID for payment in " + this.status + " status. " +
                "Payment must be SETTLED.");
        }
        this.ledgerTransactionId = ledgerTransactionId;
    }
    
    /**
     * Phase 4: Checks if this payment has already been settled (has ledger transaction).
     * 
     * @return true if payment has been settled and ledger entries created
     */
    boolean isSettled() {
        return ledgerTransactionId != null;
    }
}
