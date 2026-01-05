package com.flagship.payment_ledger.payment.event;

import com.flagship.payment_ledger.payment.Payment;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a payment is settled.
 *
 * Phase 5: Events as Facts
 *
 * This event is published when a payment transitions from AUTHORIZED to SETTLED.
 * Settlement indicates funds have been moved and ledger entries created.
 *
 * Includes the ledger transaction ID for consumers that need to correlate
 * with ledger entries.
 */
@Value
public class PaymentSettledEvent implements PaymentEvent {
    UUID eventId;
    UUID paymentId;
    BigDecimal amount;
    String currency;
    UUID fromAccountId;
    UUID toAccountId;
    UUID ledgerTransactionId;
    Instant occurredAt;

    public static final String EVENT_TYPE = "PaymentSettled";

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    /**
     * Creates a PaymentSettledEvent from a Payment domain object and ledger transaction ID.
     */
    public static PaymentSettledEvent fromPayment(Payment payment, UUID ledgerTransactionId) {
        return new PaymentSettledEvent(
            UUID.randomUUID(),
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency().name(),
            payment.getFromAccountId(),
            payment.getToAccountId(),
            ledgerTransactionId,
            Instant.now()
        );
    }
}
