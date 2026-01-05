package com.flagship.payment_ledger.payment.event;

import com.flagship.payment_ledger.payment.Payment;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a payment fails.
 *
 * Phase 5: Events as Facts
 *
 * This event is published when a payment transitions to FAILED status.
 * Contains the failure reason for consumers to handle accordingly.
 */
@Value
public class PaymentFailedEvent implements PaymentEvent {
    UUID eventId;
    UUID paymentId;
    BigDecimal amount;
    String currency;
    UUID fromAccountId;
    UUID toAccountId;
    String failureReason;
    String previousStatus;
    Instant occurredAt;

    public static final String EVENT_TYPE = "PaymentFailed";

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    /**
     * Creates a PaymentFailedEvent from a Payment domain object.
     */
    public static PaymentFailedEvent fromPayment(Payment payment, String previousStatus) {
        return new PaymentFailedEvent(
            UUID.randomUUID(),
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency().name(),
            payment.getFromAccountId(),
            payment.getToAccountId(),
            payment.getFailureReason(),
            previousStatus,
            Instant.now()
        );
    }
}
