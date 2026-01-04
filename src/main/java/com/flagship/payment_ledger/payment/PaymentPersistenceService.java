package com.flagship.payment_ledger.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for payment persistence operations.
 * 
 * Phase 3: Handles saving and retrieving payments from database.
 * 
 * This service bridges the domain layer (Payment) and persistence layer (PaymentEntity).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentPersistenceService {
    
    private final PaymentRepository paymentRepository;
    
    /**
     * Saves a payment to the database.
     * 
     * @param payment Domain payment object
     * @param idempotencyKey Idempotency key for this payment
     * @return Saved payment entity
     */
    @Transactional
    public PaymentEntity save(Payment payment, String idempotencyKey) {
        PaymentEntity entity = PaymentEntity.fromDomain(payment, idempotencyKey);
        PaymentEntity saved = paymentRepository.save(entity);
        log.debug("Saved payment {} with idempotency key {}", saved.getId(), idempotencyKey);
        return saved;
    }
    
    /**
     * Finds a payment by ID.
     * 
     * @param paymentId Payment ID
     * @return Optional containing payment if found
     */
    @Transactional(readOnly = true)
    public Optional<Payment> findById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
            .map(PaymentEntity::toDomain);
    }
    
    /**
     * Finds a payment by idempotency key.
     * 
     * @param idempotencyKey Idempotency key
     * @return Optional containing payment if found
     */
    @Transactional(readOnly = true)
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
            .map(PaymentEntity::toDomain);
    }
    
    /**
     * Updates a payment in the database.
     * 
     * Uses controlled update method instead of setters to prevent
     * bypassing domain invariants.
     * 
     * @param payment Domain payment object (must have existing ID)
     * @return Updated payment entity
     */
    @Transactional
    public PaymentEntity update(Payment payment) {
        PaymentEntity existing = paymentRepository.findById(payment.getId())
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + payment.getId()));
        
        // Use controlled update method (no setters - prevents bypassing invariants)
        // Timestamps are handled automatically by @PreUpdate lifecycle hook
        existing.updateFromDomain(payment);
        
        PaymentEntity updated = paymentRepository.save(existing);
        log.debug("Updated payment {}", updated.getId());
        return updated;
    }
    
    /**
     * Phase 4: Finds a payment entity by ID (returns entity, not domain object).
     * Used when we need to modify the entity directly (e.g., setting ledger transaction ID).
     * 
     * @param paymentId Payment ID
     * @return Optional containing payment entity if found
     */
    @Transactional(readOnly = true)
    public Optional<PaymentEntity> findByIdEntity(UUID paymentId) {
        return paymentRepository.findById(paymentId);
    }
    
    /**
     * Phase 4: Saves a payment entity directly.
     * Used when we need to save an entity that has been modified directly
     * (e.g., after setting ledger transaction ID).
     * 
     * @param entity Payment entity to save
     * @return Saved payment entity
     */
    @Transactional
    public PaymentEntity saveEntity(PaymentEntity entity) {
        PaymentEntity saved = paymentRepository.save(entity);
        log.debug("Saved payment entity {}", saved.getId());
        return saved;
    }
}
