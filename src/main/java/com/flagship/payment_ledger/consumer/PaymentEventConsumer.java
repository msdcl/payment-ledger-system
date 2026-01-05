package com.flagship.payment_ledger.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flagship.payment_ledger.payment.event.PaymentCreatedEvent;
import com.flagship.payment_ledger.payment.event.PaymentSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka consumer for payment events.
 *
 * Phase 6: Consumers & Replay Safety
 *
 * This consumer:
 * 1. Receives events from the payments topic
 * 2. Deserializes the JSON payload
 * 3. Routes events to appropriate handlers
 * 4. Ensures idempotent processing (no duplicate handling)
 * 5. Manually acknowledges messages after successful processing
 *
 * Key design decisions:
 * - Manual acknowledgment (ack-mode=manual) ensures we only commit
 *   offsets after successful processing
 * - Idempotent processing via ProcessedEvent table handles replays
 * - Each event type has its own handler for separation of concerns
 */
@Component
@ConditionalOnProperty(name = "consumer.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private static final String CONSUMER_GROUP = "payment-event-consumer";

    private final IdempotentEventProcessor eventProcessor;
    private final PaymentEventHandler eventHandler;
    private final ObjectMapper objectMapper;

    /**
     * Main Kafka listener for payment events.
     *
     * Uses manual acknowledgment to ensure we only commit offsets
     * after successful processing.
     */
    @KafkaListener(
        topics = "${kafka.topic.payments:payments}",
        groupId = "${spring.kafka.consumer.group-id:payment-ledger-consumers}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("Received message: topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        try {
            // Parse the event to extract common fields
            EventEnvelope envelope = parseEvent(record.value());

            if (envelope == null) {
                log.warn("Could not parse event, acknowledging to skip: {}", record.value());
                ack.acknowledge();
                return;
            }

            // Route to appropriate handler based on event type
            boolean processed = routeEvent(envelope, record.value());

            // Acknowledge the message
            ack.acknowledge();

            if (processed) {
                log.info("Processed event: type={}, eventId={}, aggregateId={}",
                        envelope.eventType, envelope.eventId, envelope.aggregateId);
            }

        } catch (Exception e) {
            log.error("Error processing message at offset {}: {}",
                    record.offset(), e.getMessage(), e);
            // Don't acknowledge - message will be redelivered
            // In production, consider DLQ after N retries
            throw e;
        }
    }

    /**
     * Routes an event to the appropriate handler based on event type.
     */
    private boolean routeEvent(EventEnvelope envelope, String rawPayload) {
        return switch (envelope.eventType) {
            case "PaymentCreated" -> handlePaymentCreated(envelope, rawPayload);
            case "PaymentAuthorized" -> handlePaymentAuthorized(envelope, rawPayload);
            case "PaymentSettled" -> handlePaymentSettled(envelope, rawPayload);
            case "PaymentFailed" -> handlePaymentFailed(envelope, rawPayload);
            default -> {
                log.debug("Unknown event type: {}, skipping", envelope.eventType);
                eventProcessor.skipEvent(
                    envelope.eventId, envelope.eventType,
                    "Payment", envelope.aggregateId,
                    CONSUMER_GROUP, "Unknown event type"
                );
                yield false;
            }
        };
    }

    private boolean handlePaymentCreated(EventEnvelope envelope, String rawPayload) {
        return eventProcessor.processEvent(
            envelope.eventId, envelope.eventType,
            "Payment", envelope.aggregateId,
            CONSUMER_GROUP,
            () -> {
                PaymentCreatedEvent event = deserialize(rawPayload, PaymentCreatedEvent.class);
                eventHandler.onPaymentCreated(event);
            }
        );
    }

    private boolean handlePaymentAuthorized(EventEnvelope envelope, String rawPayload) {
        return eventProcessor.processEvent(
            envelope.eventId, envelope.eventType,
            "Payment", envelope.aggregateId,
            CONSUMER_GROUP,
            () -> {
                // For now, just log. Can add specific handler logic later.
                log.info("Payment authorized: {}", envelope.aggregateId);
            }
        );
    }

    private boolean handlePaymentSettled(EventEnvelope envelope, String rawPayload) {
        return eventProcessor.processEvent(
            envelope.eventId, envelope.eventType,
            "Payment", envelope.aggregateId,
            CONSUMER_GROUP,
            () -> {
                PaymentSettledEvent event = deserialize(rawPayload, PaymentSettledEvent.class);
                eventHandler.onPaymentSettled(event);
            }
        );
    }

    private boolean handlePaymentFailed(EventEnvelope envelope, String rawPayload) {
        return eventProcessor.processEvent(
            envelope.eventId, envelope.eventType,
            "Payment", envelope.aggregateId,
            CONSUMER_GROUP,
            () -> {
                log.info("Payment failed: {}", envelope.aggregateId);
                // Add notification logic here
            }
        );
    }

    /**
     * Parses event to extract common envelope fields.
     */
    private EventEnvelope parseEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);

            UUID eventId = UUID.fromString(node.get("eventId").asText());
            UUID paymentId = UUID.fromString(node.get("paymentId").asText());
            String eventType = extractEventType(node);

            return new EventEnvelope(eventId, paymentId, eventType);

        } catch (Exception e) {
            log.error("Failed to parse event envelope: {}", e.getMessage());
            return null;
        }
    }

    private String extractEventType(JsonNode node) {
        // Try to get eventType from different possible locations
        if (node.has("eventType")) {
            return node.get("eventType").asText();
        }
        // Infer from class name if present
        if (node.has("@class")) {
            String className = node.get("@class").asText();
            return className.substring(className.lastIndexOf('.') + 1);
        }
        // Check for specific fields to infer type
        if (node.has("ledgerTransactionId")) {
            return "PaymentSettled";
        }
        if (node.has("failureReason") && node.get("failureReason").asText() != null) {
            return "PaymentFailed";
        }
        if (node.has("status")) {
            String status = node.get("status").asText();
            return switch (status) {
                case "CREATED" -> "PaymentCreated";
                case "AUTHORIZED" -> "PaymentAuthorized";
                case "SETTLED" -> "PaymentSettled";
                case "FAILED" -> "PaymentFailed";
                default -> "Unknown";
            };
        }
        return "Unknown";
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize event: " + e.getMessage(), e);
        }
    }

    /**
     * Simple envelope to hold common event fields.
     */
    private record EventEnvelope(UUID eventId, UUID aggregateId, String eventType) {}
}
