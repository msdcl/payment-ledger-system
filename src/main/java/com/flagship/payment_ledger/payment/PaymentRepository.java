package com.flagship.payment_ledger.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Payment persistence.
 * 
 * Phase 3: JPA repository for payment operations.
 */
@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
    
    /**
     * Finds a payment by idempotency key.
     * Used for idempotency checking.
     */
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);
    
    /**
     * Checks if a payment with the given idempotency key exists.
     * More efficient than findByIdempotencyKey when you only need existence check.
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM PaymentEntity p WHERE p.idempotencyKey = :key")
    boolean existsByIdempotencyKey(@Param("key") String idempotencyKey);
}
