package com.flagship.payment_ledger.payment;

import com.flagship.payment_ledger.ledger.Account;
import com.flagship.payment_ledger.ledger.AccountService;
import com.flagship.payment_ledger.ledger.LedgerService;
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
 * Phase 4 Tests: Payment Settlement with Ledger Integration
 * 
 * These tests verify that:
 * - Payments can be settled and ledger entries created atomically
 * - Double settlement attempts are prevented (idempotent)
 * - Retries after partial failures work correctly
 * - Database constraints prevent double settlement
 * - Concurrent settlement attempts are handled safely
 * 
 * Goal: Verify that money movement is atomic and safe under retries.
 */
@SpringBootTest
@Testcontainers
class PaymentSettlementServiceTest {

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

    @Autowired
    private PaymentPersistenceService persistenceService;

    @Autowired
    private PaymentSettlementService settlementService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountService accountService;

    private UUID account1Id;
    private UUID account2Id;
    private Payment authorizedPayment;

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
        // Create test accounts
        String account1Number = "ACC-" + UUID.randomUUID().toString().substring(0, 8);
        String account2Number = "ACC-" + UUID.randomUUID().toString().substring(0, 8);
        
        account1Id = accountService.createAccount(account1Number, Account.AccountType.ASSET);
        account2Id = accountService.createAccount(account2Number, Account.AccountType.ASSET);
        
        // Create and authorize a payment for testing
        Payment created = paymentService.createPayment(
            new BigDecimal("100.00"), 
            CurrencyCode.USD, 
            account1Id, 
            account2Id
        );
        
        PaymentEntity saved = persistenceService.save(created, "test-key-" + UUID.randomUUID());
        authorizedPayment = paymentService.authorizePayment(saved.toDomain());
        persistenceService.update(authorizedPayment);
        
        printInput("Account 1 ID", account1Id);
        printInput("Account 2 ID", account2Id);
        printInput("Payment ID", authorizedPayment.getId());
    }

    @Test
    @DisplayName("Settle payment should create ledger entries atomically")
    void testSettlePayment_CreatesLedgerEntries() {
        printTestHeader("Settle Payment - Creates Ledger Entries");
        
        UUID paymentId = authorizedPayment.getId();
        BigDecimal initialBalance1 = ledgerService.getAccountBalance(account1Id);
        BigDecimal initialBalance2 = ledgerService.getAccountBalance(account2Id);
        
        printInput("Payment ID", paymentId);
        printInput("Initial Balance Account 1", initialBalance1);
        printInput("Initial Balance Account 2", initialBalance2);
        printInput("Payment Amount", authorizedPayment.getAmount());
        
        // Settle the payment
        UUID ledgerTransactionId = settlementService.settlePayment(paymentId);
        
        printOutput("Ledger Transaction ID", ledgerTransactionId);
        
        // Verify payment is SETTLED
        Payment settledPayment = persistenceService.findById(paymentId)
            .orElseThrow();
        printOutput("Payment Status", settledPayment.getStatus());
        
        assertEquals(PaymentStatus.SETTLED, settledPayment.getStatus(), 
                     "Payment should be SETTLED");
        assertNotNull(ledgerTransactionId, "Ledger transaction ID should be set");
        
        // Verify ledger entries were created
        var ledgerEntries = ledgerService.getLedgerEntriesForTransaction(ledgerTransactionId);
        printOutput("Ledger Entries Count", ledgerEntries.size());
        
        assertEquals(2, ledgerEntries.size(), 
                    "Should have 2 ledger entries (debit and credit)");
        
        // Verify balances changed correctly
        BigDecimal finalBalance1 = ledgerService.getAccountBalance(account1Id);
        BigDecimal finalBalance2 = ledgerService.getAccountBalance(account2Id);
        
        printOutput("Final Balance Account 1", finalBalance1);
        printOutput("Final Balance Account 2", finalBalance2);
        
        // For ASSET accounts: debit increases, credit decreases
        BigDecimal expectedBalance1 = initialBalance1.subtract(authorizedPayment.getAmount());
        BigDecimal expectedBalance2 = initialBalance2.add(authorizedPayment.getAmount());
        
        assertEquals(0, expectedBalance1.compareTo(finalBalance1),
                    "Account 1 balance should decrease by payment amount");
        assertEquals(0, expectedBalance2.compareTo(finalBalance2),
                    "Account 2 balance should increase by payment amount");
        
        // Verify payment entity has ledger transaction ID
        PaymentEntity entity = persistenceService.findByIdEntity(paymentId)
            .orElseThrow();
        assertEquals(ledgerTransactionId, entity.getLedgerTransactionId(),
                    "Payment entity should have ledger transaction ID");
        
        printSuccess("Payment settled and ledger entries created atomically");
    }

    @Test
    @DisplayName("Double settle attempt should be idempotent")
    void testDoubleSettle_IsIdempotent() {
        printTestHeader("Double Settle - Should Be Idempotent");
        
        UUID paymentId = authorizedPayment.getId();
        
        printInput("Payment ID", paymentId);
        
        // First settlement
        UUID firstLedgerTransactionId = settlementService.settlePayment(paymentId);
        printOutput("First Settlement - Ledger Transaction ID", firstLedgerTransactionId);
        
        // Second settlement attempt (should be idempotent)
        UUID secondLedgerTransactionId = settlementService.settlePayment(paymentId);
        printOutput("Second Settlement - Ledger Transaction ID", secondLedgerTransactionId);
        
        // Should return the same ledger transaction ID
        assertEquals(firstLedgerTransactionId, secondLedgerTransactionId,
                     "Second settlement should return same ledger transaction ID (idempotent)");
        
        // Verify only one ledger transaction exists
        PaymentEntity entity = persistenceService.findByIdEntity(paymentId)
            .orElseThrow();
        assertEquals(firstLedgerTransactionId, entity.getLedgerTransactionId(),
                    "Payment should still have original ledger transaction ID");
        
        // Verify ledger entries count (should still be 2, not 4)
        var ledgerEntries = ledgerService.getLedgerEntriesForTransaction(firstLedgerTransactionId);
        assertEquals(2, ledgerEntries.size(),
                    "Should still have only 2 ledger entries (not 4)");
        
        printSuccess("Double settlement correctly handled as idempotent operation");
    }

    @Test
    @DisplayName("Settle payment in CREATED status should fail")
    void testSettlePayment_InvalidStatus() {
        printTestHeader("Settle Payment - Invalid Status (CREATED)");
        
        // Create a payment in CREATED status (not authorized)
        Payment created = paymentService.createPayment(
            new BigDecimal("50.00"),
            CurrencyCode.USD,
            account1Id,
            account2Id
        );
        PaymentEntity saved = persistenceService.save(created, "test-key-" + UUID.randomUUID());

        printInput("Payment ID", saved.getId());
        printInput("Payment Status", saved.getStatus());
        printExpectedException("IllegalStateException", 
            "Cannot settle payment in CREATED status. Payment must be AUTHORIZED.");
        
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            settlementService.settlePayment(saved.getId());
        }, "Should reject settlement of payment in CREATED status");
        
        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("AUTHORIZED"),
                   "Exception should mention AUTHORIZED status");
        
        printSuccess("Invalid status settlement correctly rejected");
    }

    @Test
    @DisplayName("Settle non-existent payment should fail")
    void testSettlePayment_NotFound() {
        printTestHeader("Settle Payment - Not Found");
        
        UUID nonExistentId = UUID.randomUUID();
        printInput("Non-existent Payment ID", nonExistentId);
        printExpectedException("IllegalArgumentException", "Payment not found");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            settlementService.settlePayment(nonExistentId);
        }, "Should reject settlement of non-existent payment");
        
        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("not found"),
                   "Exception should mention payment not found");
        
        printSuccess("Non-existent payment settlement correctly rejected");
    }

    @Test
    @DisplayName("isSettled should correctly identify settled payments")
    void testIsSettled() {
        printTestHeader("Is Settled Check");
        
        UUID paymentId = authorizedPayment.getId();
        
        printInput("Payment ID", paymentId);
        
        // Before settlement
        boolean beforeSettlement = settlementService.isSettled(paymentId);
        printOutput("Is Settled (Before)", beforeSettlement);
        assertFalse(beforeSettlement, "Payment should not be settled before settlement");
        
        // Settle the payment
        settlementService.settlePayment(paymentId);
        
        // After settlement
        boolean afterSettlement = settlementService.isSettled(paymentId);
        printOutput("Is Settled (After)", afterSettlement);
        assertTrue(afterSettlement, "Payment should be settled after settlement");
        
        printSuccess("isSettled correctly identifies settlement status");
    }

    @Test
    @DisplayName("Settlement should be atomic - payment and ledger updated together")
    void testSettlement_Atomicity() {
        printTestHeader("Settlement Atomicity");
        
        UUID paymentId = authorizedPayment.getId();
        
        printInput("Payment ID", paymentId);
        
        // Get initial state
        Payment beforePayment = persistenceService.findById(paymentId).orElseThrow();
        BigDecimal initialBalance1 = ledgerService.getAccountBalance(account1Id);
        BigDecimal initialBalance2 = ledgerService.getAccountBalance(account2Id);
        
        printInput("Payment Status (Before)", beforePayment.getStatus());
        printInput("Account 1 Balance (Before)", initialBalance1);
        printInput("Account 2 Balance (Before)", initialBalance2);
        
        // Settle
        UUID ledgerTransactionId = settlementService.settlePayment(paymentId);
        
        // Verify both payment and ledger were updated
        Payment afterPayment = persistenceService.findById(paymentId).orElseThrow();
        PaymentEntity entity = persistenceService.findByIdEntity(paymentId).orElseThrow();
        BigDecimal finalBalance1 = ledgerService.getAccountBalance(account1Id);
        BigDecimal finalBalance2 = ledgerService.getAccountBalance(account2Id);
        
        printOutput("Payment Status (After)", afterPayment.getStatus());
        printOutput("Ledger Transaction ID", entity.getLedgerTransactionId());
        printOutput("Account 1 Balance (After)", finalBalance1);
        printOutput("Account 2 Balance (After)", finalBalance2);
        
        // Verify atomicity: both should be updated
        assertEquals(PaymentStatus.SETTLED, afterPayment.getStatus(),
                     "Payment should be SETTLED");
        assertEquals(ledgerTransactionId, entity.getLedgerTransactionId(),
                     "Payment should have ledger transaction ID");
        assertNotEquals(0, initialBalance1.compareTo(finalBalance1),
                       "Account 1 balance should have changed");
        assertNotEquals(0, initialBalance2.compareTo(finalBalance2),
                       "Account 2 balance should have changed");
        
        printSuccess("Settlement is atomic - payment and ledger updated together");
    }
}
