package com.flagship.payment_ledger.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for idempotency key management.
 * 
 * Phase 3: Implements Redis fast-path with database fallback.
 * 
 * Strategy:
 * 1. Try Redis first (fast, but can be unavailable)
 * 2. Fall back to database (slower, but always available)
 * 3. Store in both for future lookups
 * 
 * This ensures idempotency even when Redis is down.
 */
@Service
@Slf4j
public class IdempotencyService {
    
    private static final String REDIS_KEY_PREFIX = "idempotency:";
    private static final Duration REDIS_TTL = Duration.ofDays(7); // Keep for 7 days
    
    private final PaymentRepository paymentRepository;
    private final Optional<RedisTemplate<String, String>> redisTemplate;
    
    public IdempotencyService(PaymentRepository paymentRepository, 
                             Optional<RedisTemplate<String, String>> redisTemplate) {
        this.paymentRepository = paymentRepository;
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * Checks if an idempotency key has been used before.
     * 
     * Fast-path: Check Redis first
     * Fallback: Check database if Redis unavailable
     * 
     * @param idempotencyKey The idempotency key to check
     * @return Optional containing payment ID if key exists, empty otherwise
     */
    public Optional<UUID> checkIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be null or blank");
        }
        
        // Fast-path: Try Redis first (if available)
        if (redisTemplate.isPresent()) {
            try {
                String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
                String paymentIdStr = redisTemplate.get().opsForValue().get(redisKey);
                
                if (paymentIdStr != null) {
                    log.debug("Idempotency key found in Redis: {}", idempotencyKey);
                    return Optional.of(UUID.fromString(paymentIdStr));
                }
            } catch (Exception e) {
                log.warn("Redis lookup failed for idempotency key: {}. Falling back to database. Error: {}", 
                        idempotencyKey, e.getMessage());
                // Continue to database fallback
            }
        }
        
        // Fallback: Check database
        try {
            Optional<PaymentEntity> existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existingPayment.isPresent()) {
                UUID paymentId = existingPayment.get().getId();
                log.debug("Idempotency key found in database: {}", idempotencyKey);
                
                // Cache in Redis for future lookups (best effort, don't fail if it fails)
                if (redisTemplate.isPresent()) {
                    try {
                        String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
                        redisTemplate.get().opsForValue().set(redisKey, paymentId.toString(), REDIS_TTL);
                    } catch (Exception e) {
                        log.debug("Failed to cache idempotency key in Redis: {}", e.getMessage());
                        // Non-critical, continue
                    }
                }
                
                return Optional.of(paymentId);
            }
        } catch (Exception e) {
            log.error("Database lookup failed for idempotency key: {}. Error: {}", 
                    idempotencyKey, e.getMessage());
            throw new RuntimeException("Failed to check idempotency key", e);
        }
        
        return Optional.empty();
    }
    
    /**
     * Stores an idempotency key mapping to a payment ID.
     * 
     * Stores in both Redis (for fast lookups) and database (for persistence).
     * 
     * @param idempotencyKey The idempotency key
     * @param paymentId The payment ID to associate with the key
     */
    public void storeIdempotencyKey(String idempotencyKey, UUID paymentId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be null or blank");
        }
        if (paymentId == null) {
            throw new IllegalArgumentException("Payment ID cannot be null");
        }
        
        // Store in Redis (best effort, non-blocking)
        if (redisTemplate.isPresent()) {
            try {
                String redisKey = REDIS_KEY_PREFIX + idempotencyKey;
                redisTemplate.get().opsForValue().set(redisKey, paymentId.toString(), REDIS_TTL);
                log.debug("Stored idempotency key in Redis: {} -> {}", idempotencyKey, paymentId);
            } catch (Exception e) {
                log.warn("Failed to store idempotency key in Redis: {}. Error: {}", 
                        idempotencyKey, e.getMessage());
                // Non-critical, continue - database is the source of truth
            }
        }
        
        // Database storage happens automatically when payment is saved
        // (idempotency_key is part of PaymentEntity)
    }
}
