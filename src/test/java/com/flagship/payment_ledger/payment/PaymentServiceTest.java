package com.flagship.payment_ledger.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Tests: Payment State Machine
 *
 * These tests verify that:
 * - Valid state transitions work correctly
 * - Invalid state transitions are rejected
 * - State machine rules are enforced
 *
 * Goal: Verify that status fields are not "just columns" but have explicit rules.
 */
@SpringBootTest
@Testcontainers
class PaymentServiceTest {

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
        // Disable Kafka and outbox publisher for these tests
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
        registry.add("consumer.enabled", () -> "false");
        registry.add("outbox.publisher.enabled", () -> "false");
    }

    @Autowired
    private PaymentService paymentService;

    private UUID account1Id;
    private UUID account2Id;

    // Helper methods for test output
    private void printTestHeader(String testName) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST: " + testName);
        System.out.println("=".repeat(80));
    }

    private void printInput(String label, Object value) {
        System.out.println("INPUT  - " + label + ": " + value);
    }

    private void printOutput(String label, Object value) {
        System.out.println("OUTPUT - " + label + ": " + value);
    }

    private void printSuccess(String message) {
        System.out.println("✓ SUCCESS: " + message);
    }

    private void printExpectedException(String exceptionType, String reason) {
        System.out.println("⚠ EXPECTED EXCEPTION: " + exceptionType);
        System.out.println("  Reason: " + reason);
    }

    private void printExceptionDetails(Exception e) {
        System.out.println("  Exception Message: " + e.getMessage());
    }

    @BeforeEach
    void setUp() {
        account1Id = UUID.randomUUID();
        account2Id = UUID.randomUUID();
    }

    @Test
    @DisplayName("Create payment should initialize with CREATED status")
    void testCreatePayment() {
        printTestHeader("Create Payment");
        
        BigDecimal amount = new BigDecimal("100.00");
        CurrencyCode currency = CurrencyCode.USD;
        
        printInput("Amount", amount);
        printInput("Currency", currency);
        printInput("From Account", account1Id);
        printInput("To Account", account2Id);

        Payment payment = paymentService.createPayment(amount, currency, account1Id, account2Id);

        printOutput("Payment ID", payment.getId());
        printOutput("Status", payment.getStatus());
        printOutput("Created At", payment.getCreatedAt());
        
        assertNotNull(payment.getId());
        assertEquals(PaymentStatus.CREATED, payment.getStatus());
        assertNotNull(payment.getCreatedAt());
        assertEquals(amount, payment.getAmount());
        assertEquals(currency, payment.getCurrency());
        assertEquals(account1Id, payment.getFromAccountId());
        assertEquals(account2Id, payment.getToAccountId());
        assertNull(payment.getFailureReason());
        printSuccess("Payment created successfully in CREATED status");
    }

    @Test
    @DisplayName("Valid transition: CREATED → AUTHORIZED")
    void testCreatedToAuthorized() {
        printTestHeader("Valid Transition: CREATED → AUTHORIZED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        printInput("Initial Status", created.getStatus());

        Payment authorized = paymentService.authorizePayment(created);
        printOutput("New Status", authorized.getStatus());
        printOutput("Updated At", authorized.getUpdatedAt());

        assertEquals(PaymentStatus.AUTHORIZED, authorized.getStatus());
        assertTrue(authorized.getUpdatedAt().isAfter(created.getUpdatedAt()));
        assertEquals(created.getId(), authorized.getId());
        printSuccess("Payment successfully transitioned from CREATED to AUTHORIZED");
    }

    @Test
    @DisplayName("Valid transition: AUTHORIZED → SETTLED")
    void testAuthorizedToSettled() {
        printTestHeader("Valid Transition: AUTHORIZED → SETTLED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        Payment authorized = paymentService.authorizePayment(created);
        printInput("Initial Status", authorized.getStatus());

        Payment settled = paymentService.settlePayment(authorized);
        printOutput("New Status", settled.getStatus());
        printOutput("Updated At", settled.getUpdatedAt());

        assertEquals(PaymentStatus.SETTLED, settled.getStatus());
        assertTrue(settled.isTerminal());
        assertTrue(settled.getUpdatedAt().isAfter(authorized.getUpdatedAt()));
        printSuccess("Payment successfully transitioned from AUTHORIZED to SETTLED");
    }

    @Test
    @DisplayName("Valid transition: AUTHORIZED → FAILED")
    void testAuthorizedToFailed() {
        printTestHeader("Valid Transition: AUTHORIZED → FAILED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        Payment authorized = paymentService.authorizePayment(created);
        printInput("Initial Status", authorized.getStatus());
        String failureReason = "Insufficient funds";

        Payment failed = paymentService.failPayment(authorized, failureReason);
        printOutput("New Status", failed.getStatus());
        printOutput("Failure Reason", failed.getFailureReason());

        assertEquals(PaymentStatus.FAILED, failed.getStatus());
        assertEquals(failureReason, failed.getFailureReason());
        assertTrue(failed.isTerminal());
        printSuccess("Payment successfully transitioned from AUTHORIZED to FAILED");
    }

    @Test
    @DisplayName("Valid transition: CREATED → FAILED")
    void testCreatedToFailed() {
        printTestHeader("Valid Transition: CREATED → FAILED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        printInput("Initial Status", created.getStatus());
        String failureReason = "Invalid account";

        Payment failed = paymentService.failPayment(created, failureReason);
        printOutput("New Status", failed.getStatus());
        printOutput("Failure Reason", failed.getFailureReason());

        assertEquals(PaymentStatus.FAILED, failed.getStatus());
        assertEquals(failureReason, failed.getFailureReason());
        assertTrue(failed.isTerminal());
        printSuccess("Payment successfully transitioned from CREATED to FAILED");
    }

    @Test
    @DisplayName("Invalid transition: CREATED → SETTLED should be rejected")
    void testInvalidTransition_CreatedToSettled() {
        printTestHeader("Invalid Transition: CREATED → SETTLED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        printInput("Current Status", created.getStatus());
        printInput("Target Status", PaymentStatus.SETTLED);
        printExpectedException("IllegalStateException", 
            "Cannot settle payment in CREATED status. Only AUTHORIZED payments can be settled.");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            paymentService.settlePayment(created);
        }, "Should reject transition from CREATED to SETTLED");

        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("CREATED") && 
                  exception.getMessage().contains("AUTHORIZED"),
                  "Exception should mention CREATED and AUTHORIZED statuses");
        printSuccess("Invalid transition correctly rejected");
    }

    @Test
    @DisplayName("Invalid transition: SETTLED → AUTHORIZED should be rejected")
    void testInvalidTransition_SettledToAuthorized() {
        printTestHeader("Invalid Transition: SETTLED → AUTHORIZED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        Payment authorized = paymentService.authorizePayment(created);
        Payment settled = paymentService.settlePayment(authorized);
        printInput("Current Status", settled.getStatus());
        printInput("Target Status", PaymentStatus.AUTHORIZED);
        printExpectedException("IllegalStateException", 
            "Cannot authorize payment in SETTLED status. Only CREATED payments can be authorized.");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            paymentService.authorizePayment(settled);
        }, "Should reject transition from SETTLED to AUTHORIZED");

        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("SETTLED") || 
                  exception.getMessage().contains("CREATED"),
                  "Exception should mention terminal state");
        printSuccess("Invalid transition from terminal state correctly rejected");
    }

    @Test
    @DisplayName("Invalid transition: SETTLED → SETTLED should be rejected")
    void testInvalidTransition_SettledToSettled() {
        printTestHeader("Invalid Transition: SETTLED → SETTLED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        Payment authorized = paymentService.authorizePayment(created);
        Payment settled = paymentService.settlePayment(authorized);
        printInput("Current Status", settled.getStatus());
        printExpectedException("IllegalStateException", 
            "Cannot settle payment in SETTLED status. Only AUTHORIZED payments can be settled.");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            paymentService.settlePayment(settled);
        }, "Should reject attempting to settle an already settled payment");

        printExceptionDetails(exception);
        printSuccess("Invalid transition from terminal state correctly rejected");
    }

    @Test
    @DisplayName("Invalid transition: FAILED → SETTLED should be rejected")
    void testInvalidTransition_FailedToSettled() {
        printTestHeader("Invalid Transition: FAILED → SETTLED");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        Payment failed = paymentService.failPayment(created, "Test failure");
        printInput("Current Status", failed.getStatus());
        printExpectedException("IllegalStateException", 
            "Cannot settle payment in FAILED status. Only AUTHORIZED payments can be settled.");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            paymentService.settlePayment(failed);
        }, "Should reject transition from FAILED to SETTLED");

        printExceptionDetails(exception);
        printSuccess("Invalid transition from terminal state correctly rejected");
    }

    @Test
    @DisplayName("Idempotent transition: Same status should be allowed")
    void testIdempotentTransition() {
        printTestHeader("Idempotent Transition: Same Status");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        printInput("Current Status", created.getStatus());
        printInput("Target Status", PaymentStatus.CREATED);

        // canTransitionTo should return true for same status
        assertTrue(created.canTransitionTo(PaymentStatus.CREATED), 
                   "Same status transition should be allowed (idempotent)");
        printSuccess("Idempotent transition correctly allowed");
    }

    @Test
    @DisplayName("canTransitionTo should correctly validate all transitions")
    void testCanTransitionTo() {
        printTestHeader("canTransitionTo Validation");
        
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account2Id
        );
        Payment authorized = paymentService.authorizePayment(created);
        Payment settled = paymentService.settlePayment(authorized);
        Payment failed = paymentService.failPayment(created, "Test");

        // CREATED can transition to AUTHORIZED or FAILED
        printInput("CREATED → AUTHORIZED", created.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertTrue(created.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertTrue(created.canTransitionTo(PaymentStatus.FAILED));
        assertFalse(created.canTransitionTo(PaymentStatus.SETTLED));

        // AUTHORIZED can transition to SETTLED or FAILED
        printInput("AUTHORIZED → SETTLED", authorized.canTransitionTo(PaymentStatus.SETTLED));
        assertTrue(authorized.canTransitionTo(PaymentStatus.SETTLED));
        assertTrue(authorized.canTransitionTo(PaymentStatus.FAILED));
        assertFalse(authorized.canTransitionTo(PaymentStatus.CREATED));

        // SETTLED is terminal - no transitions
        printInput("SETTLED → any", settled.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertFalse(settled.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertFalse(settled.canTransitionTo(PaymentStatus.FAILED));
        assertFalse(settled.canTransitionTo(PaymentStatus.CREATED));

        // FAILED is terminal - no transitions
        printInput("FAILED → any", failed.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertFalse(failed.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertFalse(failed.canTransitionTo(PaymentStatus.SETTLED));
        assertFalse(failed.canTransitionTo(PaymentStatus.CREATED));

        printSuccess("All transition validations working correctly");
    }

    @Test
    @DisplayName("Create payment with invalid amount should be rejected")
    void testCreatePayment_InvalidAmount() {
        printTestHeader("Create Payment - Invalid Amount");
        
        printInput("Amount", BigDecimal.ZERO);
        printExpectedException("IllegalArgumentException", "Payment amount must be positive");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            paymentService.createPayment(BigDecimal.ZERO, CurrencyCode.USD, account1Id, account2Id);
        }, "Should reject zero amount");

        printExceptionDetails(exception);
        printSuccess("Invalid amount correctly rejected");
    }

    @Test
    @DisplayName("Create payment with same from/to account should be rejected")
    void testCreatePayment_SameAccount() {
        printTestHeader("Create Payment - Same Account");
        
        printInput("From Account", account1Id);
        printInput("To Account", account1Id);
        printExpectedException("IllegalArgumentException", 
            "From and to accounts must be different");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            paymentService.createPayment(new BigDecimal("100.00"), CurrencyCode.USD, account1Id, account1Id);
        }, "Should reject payment with same from and to account");

        printExceptionDetails(exception);
        printSuccess("Same account payment correctly rejected");
    }
}

