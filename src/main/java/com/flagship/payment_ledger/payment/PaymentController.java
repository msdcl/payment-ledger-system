package com.flagship.payment_ledger.payment;

import com.flagship.payment_ledger.payment.dto.CreatePaymentRequest;
import com.flagship.payment_ledger.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for payment operations.
 * 
 * Phase 3: Idempotent API endpoint.
 * 
 * Key features:
 * - Requires Idempotency-Key header
 * - Returns same response for duplicate requests
 * - Uses Redis fast-path with database fallback
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {
    
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    
    private final PaymentService paymentService;
    private final PaymentPersistenceService persistenceService;
    private final IdempotencyService idempotencyService;
    
    /**
     * Creates a new payment.
     * 
     * This endpoint is idempotent: calling it multiple times with the same
     * Idempotency-Key will return the same payment without creating duplicates.
     * 
     * @param request Payment creation request
     * @param idempotencyKey Idempotency key from header (required)
     * @return Created payment response
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        
        log.info("Received payment creation request with idempotency key: {}", idempotencyKey);
        
        // Check if this idempotency key has been used before
        var existingPaymentId = idempotencyService.checkIdempotencyKey(idempotencyKey);
        
        if (existingPaymentId.isPresent()) {
            log.info("Idempotency key {} already used. Returning existing payment {}", 
                    idempotencyKey, existingPaymentId.get());
            
            // Return existing payment (idempotent behavior)
            Payment existingPayment = persistenceService.findById(existingPaymentId.get())
                .orElseThrow(() -> new IllegalStateException(
                    "Payment found by idempotency key but not found by ID: " + existingPaymentId.get()));
            
            return ResponseEntity.ok(PaymentResponse.from(existingPayment));
        }
        
        // Convert String currency to CurrencyCode enum (validates currency code)
        CurrencyCode currency;
        try {
            currency = CurrencyCode.valueOf(request.getCurrency().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid currency code: " + request.getCurrency());
        }
        
        // Create new payment
        Payment payment = paymentService.createPayment(
            request.getAmount(),
            currency,
            request.getFromAccountId(),
            request.getToAccountId()
        );
        
        // Save to database (this also stores the idempotency key)
        var savedEntity = persistenceService.save(payment, idempotencyKey);
        
        // Store idempotency key mapping in Redis (for fast future lookups)
        idempotencyService.storeIdempotencyKey(idempotencyKey, savedEntity.getId());
        
        log.info("Created new payment {} with idempotency key {}", savedEntity.getId(), idempotencyKey);
        
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(PaymentResponse.from(savedEntity.toDomain()));
    }
    
    /**
     * Gets a payment by ID.
     * 
     * @param id Payment ID
     * @return Payment response
     */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable("id") java.util.UUID id) {
        return persistenceService.findById(id)
            .map(payment -> ResponseEntity.ok(PaymentResponse.from(payment)))
            .orElse(ResponseEntity.notFound().build());
    }
}
