package com.flagship.payment_ledger.payment.event;

import com.flagship.payment_ledger.payment.Payment;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a payment is authorized.
 *
 * Phase 5: Events as Facts
 *
 * This event is published when a payment transitions from CREATED to AUTHORIZED.
 * Authorization indicates funds have been verified/reserved.
 */
@Value
public class PaymentAuthorizedEvent implements PaymentEvent {
    UUID eventId;
    UUID paymentId;
    BigDecimal amount;
    String currency;
    UUID fromAccountId;
    UUID toAccountId;
    Instant occurredAt;

    public static final String EVENT_TYPE = "PaymentAuthorized";

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    /**
     * Creates a PaymentAuthorizedEvent from a Payment domain object.
     */
    public static PaymentAuthorizedEvent fromPayment(Payment payment) {
        return new PaymentAuthorizedEvent(
            UUID.randomUUID(),
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency().name(),
            payment.getFromAccountId(),
            payment.getToAccountId(),
            Instant.now()
        );
    }
}
