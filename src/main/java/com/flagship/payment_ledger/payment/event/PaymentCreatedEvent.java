package com.flagship.payment_ledger.payment.event;

import com.flagship.payment_ledger.payment.Payment;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a new payment is created.
 *
 * Phase 5: Events as Facts
 *
 * This event is published when a payment transitions to CREATED status.
 * Contains all information needed by consumers to process the event.
 */
@Value
public class PaymentCreatedEvent implements PaymentEvent {
    UUID eventId;
    UUID paymentId;
    BigDecimal amount;
    String currency;
    UUID fromAccountId;
    UUID toAccountId;
    String status;
    Instant occurredAt;

    public static final String EVENT_TYPE = "PaymentCreated";

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    /**
     * Creates a PaymentCreatedEvent from a Payment domain object.
     */
    public static PaymentCreatedEvent fromPayment(Payment payment) {
        return new PaymentCreatedEvent(
            UUID.randomUUID(),  // unique event ID
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency().name(),
            payment.getFromAccountId(),
            payment.getToAccountId(),
            payment.getStatus().name(),
            Instant.now()
        );
    }
}
