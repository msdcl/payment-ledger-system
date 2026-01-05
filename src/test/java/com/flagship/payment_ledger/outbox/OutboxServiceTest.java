package com.flagship.payment_ledger.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flagship.payment_ledger.ledger.Account;
import com.flagship.payment_ledger.ledger.AccountService;
import com.flagship.payment_ledger.payment.CurrencyCode;
import com.flagship.payment_ledger.payment.Payment;
import com.flagship.payment_ledger.payment.PaymentPersistenceService;
import com.flagship.payment_ledger.payment.PaymentService;
import com.flagship.payment_ledger.payment.PaymentSettlementService;
import com.flagship.payment_ledger.payment.event.PaymentCreatedEvent;
import com.flagship.payment_ledger.payment.event.PaymentSettledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 Tests: Outbox Pattern and Event Publishing
 *
 * These tests verify that:
 * - Events are written to outbox atomically with business operations
 * - Events can be retrieved for publishing
 * - Events can be marked as published
 * - Events can be marked as failed with retry tracking
 * - Payment events contain correct data
 */
@SpringBootTest
@Testcontainers
class OutboxServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payment_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Disable outbox publisher during tests
        registry.add("outbox.publisher.enabled", () -> "false");
        // Disable Kafka for these tests
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
    }

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentPersistenceService persistenceService;

    @Autowired
    private PaymentSettlementService settlementService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID account1Id;
    private UUID account2Id;

    @BeforeEach
    void setUp() {
        // Clean up outbox events from previous tests
        outboxEventRepository.deleteAll();

        // Create test accounts
        String account1Number = "ACC-" + UUID.randomUUID().toString().substring(0, 8);
        String account2Number = "ACC-" + UUID.randomUUID().toString().substring(0, 8);

        account1Id = accountService.createAccount(account1Number, Account.AccountType.ASSET);
        account2Id = accountService.createAccount(account2Number, Account.AccountType.ASSET);

        printTestSetup();
    }

    private void printTestSetup() {
        System.out.println("\n--- Test Setup ---");
        System.out.println("Account 1 ID: " + account1Id);
        System.out.println("Account 2 ID: " + account2Id);
    }

    private void printTestHeader(String testName) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST: " + testName);
        System.out.println("=".repeat(80));
    }

    private void printSuccess(String message) {
        System.out.println("✓ SUCCESS: " + message);
    }

    @Test
    @DisplayName("Outbox event should be created with correct data")
    @Transactional
    void testSaveEvent_CreatesCorrectEvent() throws Exception {
        printTestHeader("Save Event - Creates Correct Event");

        UUID aggregateId = UUID.randomUUID();
        String aggregateType = "Payment";
        String eventType = "TestEvent";
        TestPayload payload = new TestPayload("test-value", 123);

        OutboxEvent event = outboxService.saveEvent(aggregateType, aggregateId, eventType, payload);

        System.out.println("Event ID: " + event.getId());
        System.out.println("Aggregate Type: " + event.getAggregateType());
        System.out.println("Aggregate ID: " + event.getAggregateId());
        System.out.println("Event Type: " + event.getEventType());
        System.out.println("Payload: " + event.getPayload());
        System.out.println("Published At: " + event.getPublishedAt());

        assertNotNull(event.getId(), "Event ID should be generated");
        assertEquals(aggregateType, event.getAggregateType());
        assertEquals(aggregateId, event.getAggregateId());
        assertEquals(eventType, event.getEventType());
        assertFalse(event.isPublished(), "Event should not be published yet");
        assertNull(event.getPublishedAt(), "Published timestamp should be null");
        assertEquals(0, event.getRetryCount(), "Retry count should be 0");

        // Verify payload is valid JSON
        TestPayload deserializedPayload = objectMapper.readValue(event.getPayload(), TestPayload.class);
        assertEquals("test-value", deserializedPayload.name());
        assertEquals(123, deserializedPayload.value());

        printSuccess("Outbox event created with correct data");
    }

    @Test
    @DisplayName("Unpublished events should be retrievable")
    void testFindUnpublishedEvents() {
        printTestHeader("Find Unpublished Events");

        // Create payment to trigger event
        Payment payment = paymentService.createPayment(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());

        // Find unpublished events
        List<OutboxEvent> unpublished = outboxService.findUnpublishedEvents(10);

        System.out.println("Unpublished events count: " + unpublished.size());
        unpublished.forEach(e -> {
            System.out.println("  - Event: " + e.getEventType() + " for " + e.getAggregateId());
        });

        assertFalse(unpublished.isEmpty(), "Should have unpublished events");

        printSuccess("Unpublished events retrieved successfully");
    }

    @Test
    @DisplayName("Event should be marked as published")
    void testMarkPublished() {
        printTestHeader("Mark Event as Published");

        // Create payment to trigger event
        Payment payment = paymentService.createPayment(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());

        // Get unpublished event
        List<OutboxEvent> unpublished = outboxService.findUnpublishedEvents(10);
        assertFalse(unpublished.isEmpty());
        OutboxEvent event = unpublished.get(0);

        System.out.println("Event ID: " + event.getId());
        System.out.println("Published before: " + event.isPublished());

        // Mark as published
        outboxService.markPublished(event.getId());

        // Verify it's no longer in unpublished list
        List<OutboxEvent> stillUnpublished = outboxService.findUnpublishedEvents(10);
        boolean eventStillUnpublished = stillUnpublished.stream()
                .anyMatch(e -> e.getId().equals(event.getId()));

        System.out.println("Event still unpublished: " + eventStillUnpublished);

        assertFalse(eventStillUnpublished, "Event should no longer be in unpublished list");

        printSuccess("Event marked as published successfully");
    }

    @Test
    @DisplayName("Failed event should increment retry count")
    void testMarkFailed_IncrementsRetryCount() {
        printTestHeader("Mark Event as Failed - Increments Retry Count");

        // Create payment to trigger event
        Payment payment = paymentService.createPayment(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());

        // Get event
        List<OutboxEvent> unpublished = outboxService.findUnpublishedEvents(10);
        OutboxEvent event = unpublished.get(0);

        System.out.println("Event ID: " + event.getId());
        System.out.println("Initial retry count: " + event.getRetryCount());

        // Mark as failed multiple times
        outboxService.markFailed(event.getId(), "Connection timeout");
        outboxService.markFailed(event.getId(), "Kafka unavailable");
        outboxService.markFailed(event.getId(), "Broker not available");

        // Verify retry count
        OutboxEventEntity entity = outboxEventRepository.findById(event.getId()).orElseThrow();
        System.out.println("Final retry count: " + entity.getRetryCount());
        System.out.println("Last error: " + entity.getLastError());

        assertEquals(3, entity.getRetryCount(), "Retry count should be 3");
        assertEquals("Broker not available", entity.getLastError(), "Should have last error message");

        printSuccess("Failed event retry count incremented correctly");
    }

    @Test
    @DisplayName("PaymentCreated event should be written on payment creation")
    void testPaymentCreatedEvent_IsWritten() throws Exception {
        printTestHeader("PaymentCreated Event - Written on Payment Creation");

        long initialCount = outboxService.countUnpublished();
        System.out.println("Initial unpublished count: " + initialCount);

        // Create payment (this should write event to outbox)
        Payment payment = paymentService.createPayment(
                new BigDecimal("250.00"),
                CurrencyCode.EUR,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());

        // Get the event
        List<OutboxEvent> events = outboxService.getEventsForAggregate("Payment", payment.getId());

        System.out.println("Events for payment: " + events.size());

        assertFalse(events.isEmpty(), "Should have events for payment");

        OutboxEvent createdEvent = events.stream()
                .filter(e -> e.getEventType().equals(PaymentCreatedEvent.EVENT_TYPE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PaymentCreated event not found"));

        System.out.println("Event type: " + createdEvent.getEventType());
        System.out.println("Payload: " + createdEvent.getPayload());

        // Verify payload
        PaymentCreatedEvent eventPayload = objectMapper.readValue(
                createdEvent.getPayload(), PaymentCreatedEvent.class);

        assertEquals(payment.getId(), eventPayload.getPaymentId());
        assertEquals(new BigDecimal("250.00"), eventPayload.getAmount());
        assertEquals("EUR", eventPayload.getCurrency());
        assertEquals(account1Id, eventPayload.getFromAccountId());
        assertEquals(account2Id, eventPayload.getToAccountId());

        printSuccess("PaymentCreated event written with correct data");
    }

    @Test
    @DisplayName("PaymentSettled event should be written on payment settlement")
    void testPaymentSettledEvent_IsWritten() throws Exception {
        printTestHeader("PaymentSettled Event - Written on Settlement");

        // Create and authorize payment
        Payment payment = paymentService.createPayment(
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());
        Payment saved = persistenceService.findById(payment.getId()).orElseThrow();
        Payment authorized = paymentService.authorizePayment(saved);
        persistenceService.update(authorized);

        System.out.println("Payment ID: " + authorized.getId());
        System.out.println("Payment Status: " + authorized.getStatus());

        // Settle payment (this should write event to outbox)
        UUID ledgerTransactionId = settlementService.settlePayment(authorized.getId());

        System.out.println("Ledger Transaction ID: " + ledgerTransactionId);

        // Get the events
        List<OutboxEvent> events = outboxService.getEventsForAggregate("Payment", authorized.getId());

        System.out.println("Total events for payment: " + events.size());
        events.forEach(e -> System.out.println("  - " + e.getEventType()));

        // Find settled event
        OutboxEvent settledEvent = events.stream()
                .filter(e -> e.getEventType().equals(PaymentSettledEvent.EVENT_TYPE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PaymentSettled event not found"));

        // Verify payload
        PaymentSettledEvent eventPayload = objectMapper.readValue(
                settledEvent.getPayload(), PaymentSettledEvent.class);

        assertEquals(authorized.getId(), eventPayload.getPaymentId());
        assertEquals(new BigDecimal("500.00"), eventPayload.getAmount());
        assertEquals("USD", eventPayload.getCurrency());
        assertEquals(ledgerTransactionId, eventPayload.getLedgerTransactionId());

        printSuccess("PaymentSettled event written with correct data including ledger transaction ID");
    }

    @Test
    @DisplayName("Events should be written atomically with business operation")
    void testEventsAreAtomicWithBusinessOperation() {
        printTestHeader("Events Atomic with Business Operation");

        long initialEventCount = outboxService.countUnpublished();
        System.out.println("Initial event count: " + initialEventCount);

        // Create and settle a payment
        Payment payment = paymentService.createPayment(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());
        Payment saved = persistenceService.findById(payment.getId()).orElseThrow();
        Payment authorized = paymentService.authorizePayment(saved);
        persistenceService.update(authorized);

        // Settle (creates PaymentSettled event)
        settlementService.settlePayment(authorized.getId());

        // Verify events exist for this payment
        List<OutboxEvent> events = outboxService.getEventsForAggregate("Payment", authorized.getId());

        System.out.println("Events for payment: " + events.size());
        events.forEach(e -> System.out.println("  - " + e.getEventType() + " at " + e.getCreatedAt()));

        // Should have at least PaymentCreated and PaymentSettled
        assertTrue(events.size() >= 2, "Should have at least 2 events (created and settled)");

        boolean hasCreated = events.stream()
                .anyMatch(e -> e.getEventType().equals(PaymentCreatedEvent.EVENT_TYPE));
        boolean hasSettled = events.stream()
                .anyMatch(e -> e.getEventType().equals(PaymentSettledEvent.EVENT_TYPE));

        assertTrue(hasCreated, "Should have PaymentCreated event");
        assertTrue(hasSettled, "Should have PaymentSettled event");

        printSuccess("Events written atomically with business operations");
    }

    @Test
    @DisplayName("Count unpublished events for monitoring")
    void testCountUnpublished() {
        printTestHeader("Count Unpublished Events");

        long initialCount = outboxService.countUnpublished();
        System.out.println("Initial count: " + initialCount);

        // Create multiple payments
        for (int i = 0; i < 3; i++) {
            Payment payment = paymentService.createPayment(
                    new BigDecimal("10.00"),
                    CurrencyCode.USD,
                    account1Id,
                    account2Id
            );
            persistenceService.save(payment, "test-key-" + UUID.randomUUID());
        }

        long afterCount = outboxService.countUnpublished();
        System.out.println("After creating 3 payments: " + afterCount);

        assertEquals(initialCount + 3, afterCount, "Should have 3 more unpublished events");

        printSuccess("Unpublished event count works correctly");
    }

    // Helper record for testing
    record TestPayload(String name, int value) {}
}
