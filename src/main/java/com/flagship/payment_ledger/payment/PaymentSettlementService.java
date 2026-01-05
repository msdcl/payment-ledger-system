package com.flagship.payment_ledger.payment;

import com.flagship.payment_ledger.ledger.LedgerService;
import com.flagship.payment_ledger.ledger.TransactionRequest;
import com.flagship.payment_ledger.observability.PaymentMetrics;
import com.flagship.payment_ledger.outbox.OutboxService;
import com.flagship.payment_ledger.payment.event.PaymentSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for settling payments and creating ledger entries atomically.
 *
 * Phase 4: Integrates payment settlement with ledger posting.
 * Phase 5: Publishes PaymentSettled event via outbox.
 *
 * Key principles:
 * - Settlement and ledger posting happen atomically in a single transaction
 * - Database constraints prevent double settlement
 * - Idempotent: safe to retry if partial failure occurs
 * - Guards against concurrent settlement attempts
 * - Events are written atomically with business operations
 *
 * This service ensures that:
 * 1. Payment can only be settled once (checked at database level)
 * 2. Ledger entries are created atomically with payment settlement
 * 3. If ledger creation fails, payment settlement is rolled back
 * 4. Retries are safe (idempotent)
 * 5. PaymentSettled event is written to outbox in same transaction
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentSettlementService {

    private static final String AGGREGATE_TYPE = "Payment";

    private final PaymentService paymentService;
    private final PaymentPersistenceService persistenceService;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final PaymentMetrics paymentMetrics;
    
    /**
     * Settles a payment and creates ledger entries atomically.
     * 
     * This method:
     * 1. Validates payment is in AUTHORIZED status
     * 2. Checks if payment has already been settled (idempotency)
     * 3. Transitions payment to SETTLED status
     * 4. Creates ledger entries (debit from source, credit to destination)
     * 5. Updates payment with ledger transaction ID
     * 
     * All operations happen within a single database transaction.
     * If any step fails, the entire operation is rolled back.
     * 
     * @param paymentId The ID of the payment to settle
     * @return The UUID of the created ledger transaction
     * @throws IllegalArgumentException if payment is not found or not in AUTHORIZED status
     * @throws IllegalStateException if payment has already been settled
     */
    @Transactional
    public UUID settlePayment(UUID paymentId) {
        long startTime = System.currentTimeMillis();
        MDC.put("paymentId", paymentId.toString());

        log.info("Attempting to settle payment");

        try {
            // Load payment from database (ensures we have latest state)
            Payment payment = persistenceService.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

            // Check if payment has already been settled (idempotency check)
            PaymentEntity entity = persistenceService.findByIdEntity(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment entity not found: " + paymentId));

            if (entity.isSettled()) {
                log.info("Payment already settled with ledger transaction: ledgerTxId={}",
                        entity.getLedgerTransactionId());
                return entity.getLedgerTransactionId();
            }

            // Validate payment is in AUTHORIZED status
            if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
                paymentMetrics.recordPaymentSettled(payment.getCurrency().name(), "invalid_status");
                throw new IllegalStateException(
                    String.format("Cannot settle payment %s in %s status. Payment must be AUTHORIZED.",
                        paymentId, payment.getStatus()));
            }

            // Transition payment to SETTLED status
            Payment settledPayment = paymentService.settlePayment(payment);

            // Create ledger transaction for this payment
            // Debit from source account, credit to destination account
            TransactionRequest ledgerTransaction = new TransactionRequest(
                String.format("Payment settlement: %s", paymentId),
                List.of(
                    TransactionRequest.DebitCredit.of(
                        settledPayment.getFromAccountId(),
                        settledPayment.getAmount(),
                        String.format("Payment %s: debit from account", paymentId)
                    )
                ),
                List.of(
                    TransactionRequest.DebitCredit.of(
                        settledPayment.getToAccountId(),
                        settledPayment.getAmount(),
                        String.format("Payment %s: credit to account", paymentId)
                    )
                )
            );

            // Post transaction to ledger (this validates balance and creates entries)
            UUID ledgerTransactionId = ledgerService.postTransaction(ledgerTransaction);
            log.debug("Created ledger transaction: ledgerTxId={}", ledgerTransactionId);

            // Update payment entity with ledger transaction ID
            // This also updates the payment status to SETTLED
            entity.updateFromDomain(settledPayment);
            entity.setLedgerTransactionId(ledgerTransactionId);

            // Save updated payment entity
            PaymentEntity savedEntity = persistenceService.saveEntity(entity);

            // Phase 5: Write PaymentSettled event to outbox (same transaction)
            PaymentSettledEvent event = PaymentSettledEvent.fromPayment(settledPayment, ledgerTransactionId);
            outboxService.saveEvent(AGGREGATE_TYPE, paymentId,
                    PaymentSettledEvent.EVENT_TYPE, event);

            // Record metrics
            long duration = System.currentTimeMillis() - startTime;
            paymentMetrics.recordPaymentSettled(payment.getCurrency().name(), "success");
            paymentMetrics.recordPaymentLatency("settle", duration);

            log.info("Payment settled successfully: ledgerTxId={}, amount={}, currency={}, duration={}ms",
                    ledgerTransactionId, payment.getAmount(), payment.getCurrency(), duration);

            return ledgerTransactionId;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            paymentMetrics.recordPaymentSettled("unknown", "error");
            paymentMetrics.recordPaymentLatency("settle", duration);
            log.error("Payment settlement failed: error={}, duration={}ms", e.getMessage(), duration);
            throw e;
        } finally {
            MDC.remove("paymentId");
        }
    }
    
    /**
     * Checks if a payment has already been settled.
     * 
     * @param paymentId The payment ID to check
     * @return true if payment has been settled (has ledger transaction), false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isSettled(UUID paymentId) {
        return persistenceService.findByIdEntity(paymentId)
            .map(PaymentEntity::isSettled)
            .orElse(false);
    }
}
