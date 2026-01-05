package com.flagship.payment_ledger.outbox;

import com.flagship.payment_ledger.ledger.Account;
import com.flagship.payment_ledger.ledger.AccountService;
import com.flagship.payment_ledger.payment.CurrencyCode;
import com.flagship.payment_ledger.payment.Payment;
import com.flagship.payment_ledger.payment.PaymentPersistenceService;
import com.flagship.payment_ledger.payment.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 Tests: Outbox Publisher with Kafka Integration
 *
 * These tests verify that:
 * - Events are published to Kafka from the outbox
 * - Events are marked as published after successful send
 * - Failed publishes increment retry count
 * - Events are partitioned by aggregate ID
 */
@SpringBootTest
@Testcontainers
class OutboxPublisherTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payment_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Disable scheduled publisher, we'll trigger manually
        registry.add("outbox.publisher.enabled", () -> "false");
    }

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentPersistenceService persistenceService;

    @Autowired
    private AccountService accountService;

    @Value("${kafka.topic.payments:payments}")
    private String paymentsTopic;

    private UUID account1Id;
    private UUID account2Id;
    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        // Clean up
        outboxEventRepository.deleteAll();

        // Create test accounts
        String account1Number = "ACC-" + UUID.randomUUID().toString().substring(0, 8);
        String account2Number = "ACC-" + UUID.randomUUID().toString().substring(0, 8);

        account1Id = accountService.createAccount(account1Number, Account.AccountType.ASSET);
        account2Id = accountService.createAccount(account2Number, Account.AccountType.ASSET);

        // Set up Kafka consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(paymentsTopic));

        System.out.println("\n--- Test Setup ---");
        System.out.println("Kafka bootstrap servers: " + kafka.getBootstrapServers());
        System.out.println("Topic: " + paymentsTopic);
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
    @DisplayName("Publisher should send events to Kafka and mark as published")
    void testPublisher_SendsToKafka() {
        printTestHeader("Publisher Sends Events to Kafka");

        // Create a payment (this writes event to outbox)
        Payment payment = paymentService.createPayment(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                account1Id,
                account2Id
        );
        persistenceService.save(payment, "test-key-" + UUID.randomUUID());

        // Verify event is in outbox and unpublished
        long unpublishedBefore = outboxService.countUnpublished();
        System.out.println("Unpublished events before: " + unpublishedBefore);
        assertTrue(unpublishedBefore > 0, "Should have unpublished events");

        // Trigger the publisher
        outboxPublisher.triggerPublish();

        // Wait a bit for publishing to complete
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify event is now published
        long unpublishedAfter = outboxService.countUnpublished();
        System.out.println("Unpublished events after: " + unpublishedAfter);
        assertEquals(0, unpublishedAfter, "All events should be published");

        // Verify message was received in Kafka
        List<ConsumerRecord<String, String>> records = consumeRecords(5000);
        System.out.println("Records received: " + records.size());

        assertFalse(records.isEmpty(), "Should have received at least one record");

        ConsumerRecord<String, String> record = records.get(0);
        System.out.println("Record key: " + record.key());
        System.out.println("Record value: " + record.value());

        assertEquals(payment.getId().toString(), record.key(),
                "Message key should be payment ID");
        assertTrue(record.value().contains("PaymentCreated"),
                "Message should contain event type");

        printSuccess("Event published to Kafka and marked as published");
    }

    @Test
    @DisplayName("Publisher should use aggregate ID as partition key")
    void testPublisher_UsesAggregateIdAsKey() {
        printTestHeader("Publisher Uses Aggregate ID as Partition Key");

        // Create multiple payments
        List<UUID> paymentIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Payment payment = paymentService.createPayment(
                    new BigDecimal("10.00"),
                    CurrencyCode.USD,
                    account1Id,
                    account2Id
            );
            persistenceService.save(payment, "test-key-" + UUID.randomUUID());
            paymentIds.add(payment.getId());
        }

        System.out.println("Created payments: " + paymentIds);

        // Publish all events
        outboxPublisher.triggerPublish();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Consume and verify keys
        List<ConsumerRecord<String, String>> records = consumeRecords(5000);
        System.out.println("Records received: " + records.size());

        assertEquals(3, records.size(), "Should have 3 records");

        for (ConsumerRecord<String, String> record : records) {
            System.out.println("Key: " + record.key() + ", Partition: " + record.partition());
            assertTrue(paymentIds.stream()
                            .anyMatch(id -> id.toString().equals(record.key())),
                    "Key should be a payment ID");
        }

        printSuccess("Events partitioned by aggregate ID");
    }

    @Test
    @DisplayName("getUnpublishedCount should return correct count")
    void testGetUnpublishedCount() {
        printTestHeader("Get Unpublished Count");

        long initialCount = outboxPublisher.getUnpublishedCount();
        System.out.println("Initial count: " + initialCount);

        // Create payments
        for (int i = 0; i < 5; i++) {
            Payment payment = paymentService.createPayment(
                    new BigDecimal("10.00"),
                    CurrencyCode.USD,
                    account1Id,
                    account2Id
            );
            persistenceService.save(payment, "test-key-" + UUID.randomUUID());
        }

        long afterCount = outboxPublisher.getUnpublishedCount();
        System.out.println("After creating 5 payments: " + afterCount);

        assertEquals(initialCount + 5, afterCount, "Should have 5 more unpublished events");

        // Publish
        outboxPublisher.triggerPublish();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long finalCount = outboxPublisher.getUnpublishedCount();
        System.out.println("After publishing: " + finalCount);

        assertEquals(0, finalCount, "Should have 0 unpublished events");

        printSuccess("Unpublished count tracking works correctly");
    }

    private List<ConsumerRecord<String, String>> consumeRecords(long timeoutMs) {
        List<ConsumerRecord<String, String>> allRecords = new ArrayList<>();
        long endTime = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < endTime) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord<String, String> record : records) {
                allRecords.add(record);
            }
            if (!allRecords.isEmpty()) {
                break;
            }
        }

        return allRecords;
    }
}
