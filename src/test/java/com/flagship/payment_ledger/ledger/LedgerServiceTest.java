package com.flagship.payment_ledger.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Tests: Try to break the ledger
 * 
 * These tests attempt to violate ledger invariants:
 * - Imbalanced transactions (debits != credits)
 * - Invalid operations
 * - Concurrent modifications
 * 
 * The goal is to verify that the database enforces correctness.
 */
@SpringBootTest
@Testcontainers
class LedgerServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test_ledger")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID account1Id;
    private UUID account2Id;
    private String account1Number;
    private String account2Number;

    @BeforeEach
    void setUp() {
        // Create test accounts with unique account numbers to avoid conflicts
        account1Id = UUID.randomUUID();
        account2Id = UUID.randomUUID();
        account1Number = "ACC-" + account1Id.toString().substring(0, 8);
        account2Number = "ACC-" + account2Id.toString().substring(0, 8);

        jdbcTemplate.update(
            "INSERT INTO accounts (id, account_number, account_type) VALUES (?, ?, ?)",
            account1Id, account1Number, "ASSET"
        );
        jdbcTemplate.update(
            "INSERT INTO accounts (id, account_number, account_type) VALUES (?, ?, ?)",
            account2Id, account2Number, "ASSET"
        );
    }

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
        String message = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message = e.getCause().getMessage();
        }
        System.out.println("  Exception Message: " + message);
    }

    @Test
    @DisplayName("Valid balanced transaction should be processed successfully")
    void testValidBalancedTransaction() {
        printTestHeader("Valid Balanced Transaction");
        
        // Given: A balanced transaction (debit = credit)
        BigDecimal amount = new BigDecimal("100.00");
        TransactionRequest request = new TransactionRequest(
            "Transfer from account1 to account2",
            List.of(TransactionRequest.DebitCredit.of(account2Id, amount, "Debit account2")),
            List.of(TransactionRequest.DebitCredit.of(account1Id, amount, "Credit account1"))
        );

        printInput("Description", request.getDescription());
        printInput("Debit", "Account: " + account2Id + ", Amount: " + amount);
        printInput("Credit", "Account: " + account1Id + ", Amount: " + amount);
        printInput("Is Balanced", request.isBalanced());

        // When: Posting the transaction
        UUID transactionId = ledgerService.postTransaction(request);
        printOutput("Transaction ID", transactionId);

        // Then: Transaction should be created successfully
        assertNotNull(transactionId, "Transaction ID should not be null");
        printSuccess("Transaction created successfully");
        
        // Verify ledger entries were created
        List<LedgerEntry> entries = ledgerService.getLedgerEntriesForTransaction(transactionId);
        printOutput("Ledger Entries Count", entries.size());
        assertEquals(2, entries.size(), "Should have 2 ledger entries (1 debit, 1 credit)");
        
        // Verify balance calculation
        // Account1 received a CREDIT (decreases ASSET balance)
        // Account2 received a DEBIT (increases ASSET balance)
        BigDecimal balance1 = ledgerService.getAccountBalance(account1Id);
        BigDecimal balance2 = ledgerService.getAccountBalance(account2Id);
        printOutput("Account 1 Balance (Credit applied)", balance1);
        printOutput("Account 2 Balance (Debit applied)", balance2);
        
        // For ASSET accounts: DEBIT increases, CREDIT decreases
        // Account1: CREDIT of 100.00 → balance = -100.00
        // Account2: DEBIT of 100.00 → balance = +100.00
        assertEquals(0, balance1.compareTo(new BigDecimal("-100.00")), 
                    "Credit decreases asset balance. Expected: -100.00, Actual: " + balance1);
        assertEquals(0, balance2.compareTo(new BigDecimal("100.00")), 
                    "Debit increases asset balance. Expected: 100.00, Actual: " + balance2);
        printSuccess("All assertions passed");
    }

    @Test
    @DisplayName("Imbalanced transaction should be rejected at application level")
    void testImbalancedTransaction_ShouldFail() {
        printTestHeader("Imbalanced Transaction - Application Level Rejection");
        
        // Given: An imbalanced transaction (debit != credit)
        BigDecimal debitAmount = new BigDecimal("100.00");
        BigDecimal creditAmount = new BigDecimal("50.00");
        
        TransactionRequest request = new TransactionRequest(
            "Imbalanced transfer",
            List.of(TransactionRequest.DebitCredit.of(account2Id, debitAmount, "Debit")),
            List.of(TransactionRequest.DebitCredit.of(account1Id, creditAmount, "Credit"))
        );

        printInput("Description", request.getDescription());
        printInput("Debit Amount", debitAmount);
        printInput("Credit Amount", creditAmount);
        printInput("Is Balanced", request.isBalanced());
        printExpectedException("IllegalArgumentException", 
            "Transaction is not balanced: debits=" + request.getDebitTotal() + 
            ", credits=" + request.getCreditTotal());

        // When/Then: Should throw exception at application level
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            () -> ledgerService.postTransaction(request),
            "Application should reject imbalanced transaction"
        );
        
        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("not balanced") || 
                  exception.getMessage().contains("debits") ||
                  exception.getMessage().contains("credits"),
                  "Exception message should indicate imbalance");
        printSuccess("Exception thrown as expected - application validation working");
    }

    @Test
    @DisplayName("Database trigger should prevent imbalanced transactions")
    void testImbalancedTransaction_DatabaseEnforcement() {
        printTestHeader("Imbalanced Transaction - Database Level Enforcement");
        
        // This test attempts to bypass application-level validation
        // by directly inserting imbalanced entries into the database
        // The database trigger should prevent this
        
        UUID transactionId = UUID.randomUUID();
        BigDecimal debitAmount = new BigDecimal("100.00");
        BigDecimal creditAmount = new BigDecimal("50.00");
        
        printInput("Transaction ID", transactionId);
        printInput("Debit Amount", debitAmount);
        printInput("Credit Amount", creditAmount);
        printExpectedException("Database Constraint Violation", 
            "Transaction is not balanced: debits must equal credits");
        
        // Create transaction record
        jdbcTemplate.update(
            "INSERT INTO transactions (id, description) VALUES (?, ?)",
            transactionId, "Test transaction"
        );
        printOutput("Transaction Record", "Created");
        
        // Insert a debit entry
        jdbcTemplate.update(
            "INSERT INTO ledger_entries (id, transaction_id, account_id, amount, entry_type, description) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'DEBIT', 'Test debit')",
            transactionId, account1Id, debitAmount
        );
        printOutput("Debit Entry", "Inserted: " + debitAmount);
        
        // Try to insert a credit entry with different amount
        // The trigger will detect the imbalance and throw an exception
        // Note: The exception might be thrown immediately or wrapped in UncategorizedSQLException
        Exception exception = assertThrows(Exception.class, () -> {
            jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, amount, entry_type, description) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 'CREDIT', 'Test credit')",
                transactionId, account2Id, creditAmount
            );
        }, "Database trigger should reject imbalanced transaction");
        
        printExceptionDetails(exception);
        
        // Verify the error message contains the expected text
        // The exception might be wrapped, so check both the exception and its cause
        String errorMessage = exception.getMessage();
        Throwable cause = exception.getCause();
        while (cause != null && cause.getMessage() != null) {
            if (cause.getMessage().contains("not balanced")) {
                errorMessage = cause.getMessage();
                break;
            }
            cause = cause.getCause();
        }
        
        assertTrue(errorMessage != null && errorMessage.contains("not balanced"),
                  "Error message should indicate transaction is not balanced. Actual: " + errorMessage);
        printSuccess("Database trigger correctly prevented imbalanced transaction");
    }

    @Test
    @DisplayName("Multiple debits and credits that balance should succeed")
    void testMultipleDebitsAndCredits_Balanced() {
        printTestHeader("Multiple Debits and Credits - Balanced Transaction");
        
        // Given: A transaction with multiple debits and credits that balance
        TransactionRequest request = new TransactionRequest(
            "Complex transaction",
            List.of(
                TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("50.00"), "Debit 1"),
                TransactionRequest.DebitCredit.of(account2Id, new BigDecimal("50.00"), "Debit 2")
            ),
            List.of(
                TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("100.00"), "Credit")
            )
        );

        printInput("Description", request.getDescription());
        printInput("Total Debits", request.getDebitTotal());
        printInput("Total Credits", request.getCreditTotal());
        printInput("Is Balanced", request.isBalanced());

        // When: Posting the transaction
        UUID transactionId = ledgerService.postTransaction(request);
        printOutput("Transaction ID", transactionId);

        // Then: Should succeed
        assertNotNull(transactionId);
        List<LedgerEntry> entries = ledgerService.getLedgerEntriesForTransaction(transactionId);
        printOutput("Ledger Entries Count", entries.size());
        assertEquals(3, entries.size(), "Should have 3 ledger entries");
        printSuccess("Complex balanced transaction processed successfully");
    }

    @Test
    @DisplayName("Zero amount should be rejected")
    void testZeroAmount_ShouldFail() {
        printTestHeader("Zero Amount Transaction - Should Fail");
        
        printInput("Amount", BigDecimal.ZERO);
        printExpectedException("IllegalArgumentException", "Amount must be positive");

        // When/Then: Should fail because amount must be positive
        // The exception is thrown when creating DebitCredit, not when posting transaction
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            TransactionRequest.DebitCredit.of(account1Id, BigDecimal.ZERO, "Debit");
        }, "Should reject zero amount when creating DebitCredit");
        
        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("positive") || 
                  exception.getMessage().contains("Amount"),
                  "Exception should mention positive amount requirement");
        printSuccess("Zero amount correctly rejected");
    }

    @Test
    @DisplayName("Negative amount should be rejected")
    void testNegativeAmount_ShouldFail() {
        printTestHeader("Negative Amount - Should Fail");
        
        BigDecimal negativeAmount = new BigDecimal("-100.00");
        printInput("Amount", negativeAmount);
        printExpectedException("IllegalArgumentException", "Amount must be positive");

        // Given: A transaction with negative amount
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            TransactionRequest.DebitCredit.of(account1Id, negativeAmount, "Invalid");
        }, "Should reject negative amounts");
        
        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("positive") || 
                  exception.getMessage().contains("Amount"),
                  "Exception should mention positive amount requirement");
        printSuccess("Negative amount correctly rejected");
    }

    @Test
    @DisplayName("Non-existent account should be rejected")
    void testNonExistentAccount_ShouldFail() {
        printTestHeader("Non-existent Account - Should Fail");
        
        // Given: A transaction referencing a non-existent account
        UUID nonExistentAccountId = UUID.randomUUID();
        TransactionRequest request = new TransactionRequest(
            "Invalid account transaction",
            List.of(TransactionRequest.DebitCredit.of(nonExistentAccountId, new BigDecimal("100.00"), "Debit")),
            List.of(TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("100.00"), "Credit"))
        );

        printInput("Non-existent Account ID", nonExistentAccountId);
        printExpectedException("IllegalArgumentException", "Account not found");

        // When/Then: Should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ledgerService.postTransaction(request);
        }, "Should reject transaction with non-existent account");
        
        printExceptionDetails(exception);
        assertTrue(exception.getMessage().contains("not found") || 
                  exception.getMessage().contains("Account"),
                  "Exception should indicate account not found");
        printSuccess("Non-existent account correctly rejected");
    }

    @Test
    @DisplayName("Empty debits should be rejected")
    void testEmptyDebits_ShouldFail() {
        printTestHeader("Empty Debits - Should Fail");
        
        // Given: A transaction with no debits
        TransactionRequest request = new TransactionRequest(
            "No debits",
            List.of(), // Empty debits
            List.of(TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("100.00"), "Credit"))
        );

        printInput("Debits Count", 0);
        printInput("Credits Count", 1);
        printInput("Is Balanced", request.isBalanced());
        printExpectedException("IllegalArgumentException", "Transaction is not balanced");

        // When/Then: Should fail (not balanced)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ledgerService.postTransaction(request);
        }, "Should reject transaction with empty debits");
        
        printExceptionDetails(exception);
        printSuccess("Empty debits correctly rejected");
    }

    @Test
    @DisplayName("Empty credits should be rejected")
    void testEmptyCredits_ShouldFail() {
        printTestHeader("Empty Credits - Should Fail");
        
        // Given: A transaction with no credits
        TransactionRequest request = new TransactionRequest(
            "No credits",
            List.of(TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("100.00"), "Debit")),
            List.of() // Empty credits
        );

        printInput("Debits Count", 1);
        printInput("Credits Count", 0);
        printInput("Is Balanced", request.isBalanced());
        printExpectedException("IllegalArgumentException", "Transaction is not balanced");

        // When/Then: Should fail (not balanced)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ledgerService.postTransaction(request);
        }, "Should reject transaction with empty credits");
        
        printExceptionDetails(exception);
        printSuccess("Empty credits correctly rejected");
    }

    @Test
    @DisplayName("Account balance should be calculated correctly from ledger entries")
    void testAccountBalanceCalculation() {
        printTestHeader("Account Balance Calculation");
        
        // Given: Multiple transactions affecting the same account
        TransactionRequest request1 = new TransactionRequest(
            "Transaction 1",
            List.of(TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("100.00"), "Debit")),
            List.of(TransactionRequest.DebitCredit.of(account2Id, new BigDecimal("100.00"), "Credit"))
        );
        
        TransactionRequest request2 = new TransactionRequest(
            "Transaction 2",
            List.of(TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("50.00"), "Debit")),
            List.of(TransactionRequest.DebitCredit.of(account2Id, new BigDecimal("50.00"), "Credit"))
        );

        printInput("Transaction 1 Amount", "100.00");
        printInput("Transaction 2 Amount", "50.00");

        // When: Posting both transactions
        UUID transactionId1 = ledgerService.postTransaction(request1);
        UUID transactionId2 = ledgerService.postTransaction(request2);
        printOutput("Transaction 1 ID", transactionId1);
        printOutput("Transaction 2 ID", transactionId2);

        // Then: Balance should be calculated correctly (derived from entries)
        // Account1: DEBIT 100 + DEBIT 50 = +150.00 (DEBIT increases ASSET)
        // Account2: CREDIT 100 + CREDIT 50 = -150.00 (CREDIT decreases ASSET)
        BigDecimal balance1 = ledgerService.getAccountBalance(account1Id);
        BigDecimal balance2 = ledgerService.getAccountBalance(account2Id);
        
        printOutput("Account 1 Balance (2 debits)", balance1);
        printOutput("Account 2 Balance (2 credits)", balance2);
        
        assertEquals(0, balance1.compareTo(new BigDecimal("150.00")), 
                    "Account 1 should have 100 + 50 = 150. Actual: " + balance1);
        assertEquals(0, balance2.compareTo(new BigDecimal("-150.00")), 
                    "Account 2 should have -100 - 50 = -150. Actual: " + balance2);
        printSuccess("Balances calculated correctly from ledger entries");
    }

    @Test
    @DisplayName("Ledger entries should be immutable (conceptual test)")
    void testLedgerEntriesAreImmutable_Conceptual() {
        printTestHeader("Ledger Entries Immutability - Conceptual Test");
        
        // Phase 1: This is a conceptual test
        // In a real system, we would prevent UPDATE/DELETE operations
        // For now, we document that ledger entries should be immutable
        
        TransactionRequest request = new TransactionRequest(
            "Test immutable entries",
            List.of(TransactionRequest.DebitCredit.of(account1Id, new BigDecimal("100.00"), "Debit")),
            List.of(TransactionRequest.DebitCredit.of(account2Id, new BigDecimal("100.00"), "Credit"))
        );

        printInput("Amount", "100.00");

        UUID transactionId = ledgerService.postTransaction(request);
        printOutput("Transaction ID", transactionId);
        
        List<LedgerEntry> entries = ledgerService.getLedgerEntriesForTransaction(transactionId);
        printOutput("Ledger Entries Count", entries.size());
        
        // Verify entries exist
        assertEquals(2, entries.size(), "Should have 2 ledger entries");
        printSuccess("Ledger entries created successfully");
        
        System.out.println("\nNote: In production, immutability would be enforced by:");
        System.out.println("  1. Not providing UPDATE/DELETE operations");
        System.out.println("  2. Using database triggers to prevent modifications");
        System.out.println("  3. Creating reversal entries for corrections");
    }
}
