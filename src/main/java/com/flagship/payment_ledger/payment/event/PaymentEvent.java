package com.flagship.payment_ledger.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Base interface for payment events.
 *
 * Phase 5: Events as Facts
 *
 * All payment events share these common properties:
 * - Event ID for deduplication
 * - Payment ID (aggregate ID)
 * - Timestamp of when the event occurred
 */
public interface PaymentEvent {

    /**
     * Unique identifier for this event instance.
     * Used for deduplication in consumers.
     */
    UUID getEventId();

    /**
     * The payment this event is about.
     */
    UUID getPaymentId();

    /**
     * When this event occurred.
     */
    Instant getOccurredAt();

    /**
     * Event type name for routing/filtering.
     */
    String getEventType();
}
