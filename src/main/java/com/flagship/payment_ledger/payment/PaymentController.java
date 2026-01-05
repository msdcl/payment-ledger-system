package com.flagship.payment_ledger.payment;

import com.flagship.payment_ledger.observability.PaymentMetrics;
import com.flagship.payment_ledger.outbox.OutboxService;
import com.flagship.payment_ledger.payment.dto.CreatePaymentRequest;
import com.flagship.payment_ledger.payment.dto.PaymentResponse;
import com.flagship.payment_ledger.payment.event.PaymentCreatedEvent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
 * Phase 5: Publishes PaymentCreated event via outbox.
 *
 * Key features:
 * - Requires Idempotency-Key header
 * - Returns same response for duplicate requests
 * - Uses Redis fast-path with database fallback
 * - Writes events atomically with payment creation
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String AGGREGATE_TYPE = "Payment";

    private final PaymentService paymentService;
    private final PaymentPersistenceService persistenceService;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final PaymentMetrics paymentMetrics;
    
    /**
     * Creates a new payment.
     *
     * This endpoint is idempotent: calling it multiple times with the same
     * Idempotency-Key will return the same payment without creating duplicates.
     *
     * Phase 5: Also writes PaymentCreated event to outbox atomically.
     *
     * @param request Payment creation request
     * @param idempotencyKey Idempotency key from header (required)
     * @return Created payment response
     */
    @PostMapping
    @Transactional
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {

        long startTime = System.currentTimeMillis();
        String currency = request.getCurrency().toUpperCase();

        log.info("Received payment creation request: idempotencyKey={}, amount={}, currency={}",
                idempotencyKey, request.getAmount(), currency);

        try {
            // Check if this idempotency key has been used before
            var existingPaymentId = idempotencyService.checkIdempotencyKey(idempotencyKey);

            if (existingPaymentId.isPresent()) {
                // Track idempotency hit
                paymentMetrics.recordIdempotencyHit();

                MDC.put("paymentId", existingPaymentId.get().toString());
                log.info("Idempotency key already used, returning existing payment");

                Payment existingPayment = persistenceService.findById(existingPaymentId.get())
                    .orElseThrow(() -> new IllegalStateException(
                        "Payment found by idempotency key but not found by ID: " + existingPaymentId.get()));

                return ResponseEntity.ok(PaymentResponse.from(existingPayment));
            }

            // Track idempotency miss (new request)
            paymentMetrics.recordIdempotencyMiss();

            // Convert String currency to CurrencyCode enum (validates currency code)
            CurrencyCode currencyCode;
            try {
                currencyCode = CurrencyCode.valueOf(currency);
            } catch (IllegalArgumentException e) {
                paymentMetrics.recordPaymentCreated("invalid", "validation_error");
                throw new IllegalArgumentException("Invalid currency code: " + request.getCurrency());
            }

            // Create new payment
            Payment payment = paymentService.createPayment(
                request.getAmount(),
                currencyCode,
                request.getFromAccountId(),
                request.getToAccountId()
            );

            // Save to database (this also stores the idempotency key)
            var savedEntity = persistenceService.save(payment, idempotencyKey);
            Payment savedPayment = savedEntity.toDomain();

            // Add payment ID to MDC for structured logging
            MDC.put("paymentId", savedPayment.getId().toString());

            // Phase 5: Write PaymentCreated event to outbox (same transaction)
            PaymentCreatedEvent event = PaymentCreatedEvent.fromPayment(savedPayment);
            outboxService.saveEvent(AGGREGATE_TYPE, savedPayment.getId(),
                    PaymentCreatedEvent.EVENT_TYPE, event);

            // Store idempotency key mapping in Redis (for fast future lookups)
            idempotencyService.storeIdempotencyKey(idempotencyKey, savedEntity.getId());

            // Record metrics
            long duration = System.currentTimeMillis() - startTime;
            paymentMetrics.recordPaymentCreated(currency, "success");
            paymentMetrics.recordPaymentLatency("create", duration);

            log.info("Payment created successfully: amount={}, currency={}, duration={}ms",
                    request.getAmount(), currency, duration);

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(PaymentResponse.from(savedPayment));

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            paymentMetrics.recordPaymentCreated(currency, "error");
            paymentMetrics.recordPaymentLatency("create", duration);
            log.error("Payment creation failed: error={}, duration={}ms", e.getMessage(), duration);
            throw e;
        } finally {
            MDC.remove("paymentId");
        }
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
