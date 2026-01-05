package com.flagship.payment_ledger.consumer;

import com.flagship.payment_ledger.payment.event.PaymentCreatedEvent;
import com.flagship.payment_ledger.payment.event.PaymentSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Handler for payment events.
 *
 * Phase 6: Consumers & Replay Safety
 *
 * This service contains the business logic for handling payment events.
 * It's called by the PaymentEventConsumer after idempotency checks pass.
 *
 * Example use cases:
 * - Send notifications when payments are created/settled
 * - Update read models or caches
 * - Trigger downstream processes
 * - Send webhooks to external systems
 *
 * Note: All handlers are already protected by idempotent processing,
 * so they don't need to implement their own deduplication.
 */
@Service
@ConditionalOnProperty(name = "consumer.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PaymentEventHandler {

    // In a real system, you might inject:
    // - NotificationService for sending emails/SMS
    // - WebhookService for calling external APIs
    // - CacheService for updating read models
    // - AnalyticsService for tracking metrics

    /**
     * Handles PaymentCreated events.
     *
     * Example actions:
     * - Send confirmation email to customer
     * - Update payment dashboard
     * - Notify fraud detection system
     */
    public void onPaymentCreated(PaymentCreatedEvent event) {
        log.info("Handling PaymentCreated: paymentId={}, amount={} {}, from={} to={}",
                event.getPaymentId(),
                event.getAmount(),
                event.getCurrency(),
                event.getFromAccountId(),
                event.getToAccountId());

        // Example: Send notification
        sendPaymentCreatedNotification(event);

        // Example: Update analytics
        trackPaymentCreated(event);
    }

    /**
     * Handles PaymentSettled events.
     *
     * Example actions:
     * - Send settlement confirmation to customer
     * - Update account balances in read model
     * - Trigger fulfillment process
     * - Send webhook to merchant
     */
    public void onPaymentSettled(PaymentSettledEvent event) {
        log.info("Handling PaymentSettled: paymentId={}, ledgerTxId={}, amount={} {}",
                event.getPaymentId(),
                event.getLedgerTransactionId(),
                event.getAmount(),
                event.getCurrency());

        // Example: Send notification
        sendPaymentSettledNotification(event);

        // Example: Trigger downstream process
        triggerFulfillment(event);
    }

    /**
     * Simulates sending a payment created notification.
     * In production, this would call a NotificationService.
     */
    private void sendPaymentCreatedNotification(PaymentCreatedEvent event) {
        // Simulate notification
        log.debug("Would send notification: Payment {} created for {} {}",
                event.getPaymentId(),
                event.getAmount(),
                event.getCurrency());

        // In production:
        // notificationService.sendEmail(
        //     getCustomerEmail(event.getFromAccountId()),
        //     "Payment Created",
        //     "Your payment of " + event.getAmount() + " " + event.getCurrency() + " has been created."
        // );
    }

    /**
     * Simulates sending a payment settled notification.
     */
    private void sendPaymentSettledNotification(PaymentSettledEvent event) {
        log.debug("Would send notification: Payment {} settled, ledger tx {}",
                event.getPaymentId(),
                event.getLedgerTransactionId());

        // In production:
        // notificationService.sendEmail(
        //     getCustomerEmail(event.getFromAccountId()),
        //     "Payment Completed",
        //     "Your payment of " + event.getAmount() + " " + event.getCurrency() + " has been completed."
        // );
    }

    /**
     * Simulates tracking payment creation for analytics.
     */
    private void trackPaymentCreated(PaymentCreatedEvent event) {
        log.debug("Would track analytics: payment_created, amount={}",
                event.getAmount());

        // In production:
        // analyticsService.track("payment_created", Map.of(
        //     "paymentId", event.getPaymentId(),
        //     "amount", event.getAmount(),
        //     "currency", event.getCurrency()
        // ));
    }

    /**
     * Simulates triggering a fulfillment process after settlement.
     */
    private void triggerFulfillment(PaymentSettledEvent event) {
        log.debug("Would trigger fulfillment for payment {}",
                event.getPaymentId());

        // In production:
        // fulfillmentService.startFulfillment(event.getPaymentId());
    }
}
