package com.flagship.payment_ledger.consumer;

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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 Tests: Idempotent Event Processing
 *
 * These tests verify that:
 * - Events are processed exactly once
 * - Duplicate events are safely ignored
 * - Concurrent processing of the same event is handled safely
 * - Failed processing is recorded correctly
 * - Different consumer groups can process the same event independently
 */
@SpringBootTest
@Testcontainers
class IdempotentEventProcessorTest {

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
        // Disable Kafka for these tests
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
        registry.add("consumer.enabled", () -> "false");
        registry.add("outbox.publisher.enabled", () -> "false");
    }

    @Autowired
    private IdempotentEventProcessor eventProcessor;

    @Autowired
    private ProcessedEventRepository repository;

    private static final String CONSUMER_GROUP = "test-consumer";
    private static final String EVENT_TYPE = "TestEvent";
    private static final String AGGREGATE_TYPE = "TestAggregate";

    @BeforeEach
    void setUp() {
        // Clean up processed events from previous tests
        repository.deleteAll();
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
    @DisplayName("First event processing should execute handler and return true")
    void testFirstEventProcessing_ExecutesHandler() {
        printTestHeader("First Event Processing - Executes Handler");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        AtomicInteger handlerCallCount = new AtomicInteger(0);

        System.out.println("Event ID: " + eventId);
        System.out.println("Aggregate ID: " + aggregateId);

        boolean processed = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            () -> {
                handlerCallCount.incrementAndGet();
                System.out.println("Handler executed!");
            }
        );

        System.out.println("Processed: " + processed);
        System.out.println("Handler call count: " + handlerCallCount.get());

        assertTrue(processed, "First event should be processed");
        assertEquals(1, handlerCallCount.get(), "Handler should be called once");

        // Verify record was created
        assertTrue(repository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP),
                "Processing record should exist");

        printSuccess("First event processed and recorded correctly");
    }

    @Test
    @DisplayName("Duplicate event should NOT execute handler and return false")
    void testDuplicateEvent_SkipsHandler() {
        printTestHeader("Duplicate Event - Skips Handler");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        AtomicInteger handlerCallCount = new AtomicInteger(0);

        System.out.println("Event ID: " + eventId);

        // First processing
        boolean first = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            handlerCallCount::incrementAndGet
        );

        // Second processing (duplicate)
        boolean second = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            handlerCallCount::incrementAndGet
        );

        // Third processing (duplicate)
        boolean third = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            handlerCallCount::incrementAndGet
        );

        System.out.println("First processed: " + first);
        System.out.println("Second processed: " + second);
        System.out.println("Third processed: " + third);
        System.out.println("Handler call count: " + handlerCallCount.get());

        assertTrue(first, "First event should be processed");
        assertFalse(second, "Duplicate should be skipped");
        assertFalse(third, "Duplicate should be skipped");
        assertEquals(1, handlerCallCount.get(), "Handler should only be called once");

        printSuccess("Duplicate events correctly skipped");
    }

    @Test
    @DisplayName("Same event can be processed by different consumer groups")
    void testDifferentConsumerGroups_ProcessSameEvent() {
        printTestHeader("Different Consumer Groups - Process Same Event");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        AtomicInteger totalCalls = new AtomicInteger(0);

        String consumerGroup1 = "notification-service";
        String consumerGroup2 = "analytics-service";
        String consumerGroup3 = "audit-service";

        System.out.println("Event ID: " + eventId);

        // Each consumer group processes the same event
        boolean processed1 = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, consumerGroup1,
            totalCalls::incrementAndGet
        );

        boolean processed2 = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, consumerGroup2,
            totalCalls::incrementAndGet
        );

        boolean processed3 = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, consumerGroup3,
            totalCalls::incrementAndGet
        );

        System.out.println("Consumer 1 processed: " + processed1);
        System.out.println("Consumer 2 processed: " + processed2);
        System.out.println("Consumer 3 processed: " + processed3);
        System.out.println("Total handler calls: " + totalCalls.get());

        assertTrue(processed1, "Consumer group 1 should process");
        assertTrue(processed2, "Consumer group 2 should process");
        assertTrue(processed3, "Consumer group 3 should process");
        assertEquals(3, totalCalls.get(), "Each consumer group should process once");

        printSuccess("Different consumer groups processed the same event independently");
    }

    @Test
    @DisplayName("Failed processing should record failure and rethrow exception")
    void testFailedProcessing_RecordsFailure() {
        printTestHeader("Failed Processing - Records Failure");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String errorMessage = "Simulated processing failure";

        System.out.println("Event ID: " + eventId);

        // Process with a failing handler
        assertThrows(RuntimeException.class, () -> {
            eventProcessor.processEvent(
                eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
                () -> {
                    throw new RuntimeException(errorMessage);
                }
            );
        });

        // Verify failure was recorded
        ProcessedEventEntity entity = repository.findById(eventId).orElseThrow();

        System.out.println("Processing result: " + entity.getProcessingResult());
        System.out.println("Error message: " + entity.getErrorMessage());

        assertEquals(ProcessedEvent.ProcessingResult.FAILED, entity.getProcessingResult(),
                "Result should be FAILED");
        assertEquals(errorMessage, entity.getErrorMessage(),
                "Error message should be recorded");

        printSuccess("Failed processing recorded correctly");
    }

    @Test
    @DisplayName("Concurrent processing of same event should only execute once")
    void testConcurrentProcessing_OnlyOnce() throws InterruptedException {
        printTestHeader("Concurrent Processing - Only Once");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        AtomicInteger handlerCallCount = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        System.out.println("Event ID: " + eventId);
        System.out.println("Concurrent threads: " + threadCount);

        // Submit concurrent processing attempts
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    boolean processed = eventProcessor.processEvent(
                        eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
                        () -> {
                            handlerCallCount.incrementAndGet();
                            // Simulate some work
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    );
                    if (processed) {
                        processedCount.incrementAndGet();
                    } else {
                        skippedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Expected for some threads due to race condition
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all to complete
        doneLatch.await();
        executor.shutdown();

        System.out.println("Handler call count: " + handlerCallCount.get());
        System.out.println("Processed count: " + processedCount.get());
        System.out.println("Skipped count: " + skippedCount.get());

        // Due to transaction isolation, exactly one should succeed
        assertEquals(1, handlerCallCount.get(),
                "Handler should be called exactly once");

        printSuccess("Concurrent processing handled correctly - only one execution");
    }

    @Test
    @DisplayName("skipEvent should prevent future processing")
    void testSkipEvent_PreventsFutureProcessing() {
        printTestHeader("Skip Event - Prevents Future Processing");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        AtomicInteger handlerCallCount = new AtomicInteger(0);

        System.out.println("Event ID: " + eventId);

        // Skip the event
        eventProcessor.skipEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            "Not relevant to this consumer"
        );

        // Try to process the same event
        boolean processed = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            handlerCallCount::incrementAndGet
        );

        System.out.println("Processed after skip: " + processed);
        System.out.println("Handler call count: " + handlerCallCount.get());

        assertFalse(processed, "Skipped event should not be processed");
        assertEquals(0, handlerCallCount.get(), "Handler should not be called");

        // Verify skip was recorded
        ProcessedEventEntity entity = repository.findById(eventId).orElseThrow();
        assertEquals(ProcessedEvent.ProcessingResult.SKIPPED, entity.getProcessingResult());

        printSuccess("Skipped event prevents future processing");
    }

    @Test
    @DisplayName("isAlreadyProcessed should return correct status")
    void testIsAlreadyProcessed() {
        printTestHeader("Is Already Processed Check");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        System.out.println("Event ID: " + eventId);

        // Before processing
        boolean beforeProcessing = eventProcessor.isAlreadyProcessed(eventId, CONSUMER_GROUP);
        System.out.println("Before processing: " + beforeProcessing);
        assertFalse(beforeProcessing, "Should not be processed yet");

        // Process the event
        eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            () -> {}
        );

        // After processing
        boolean afterProcessing = eventProcessor.isAlreadyProcessed(eventId, CONSUMER_GROUP);
        System.out.println("After processing: " + afterProcessing);
        assertTrue(afterProcessing, "Should be marked as processed");

        // Different consumer group
        boolean differentGroup = eventProcessor.isAlreadyProcessed(eventId, "other-group");
        System.out.println("Different group: " + differentGroup);
        assertFalse(differentGroup, "Different group should not be affected");

        printSuccess("isAlreadyProcessed returns correct status");
    }

    @Test
    @DisplayName("processEventWithResult should return result or skip status")
    void testProcessEventWithResult() {
        printTestHeader("Process Event With Result");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        System.out.println("Event ID: " + eventId);

        // First processing
        IdempotentEventProcessor.ProcessingResult<String> result1 =
            eventProcessor.processEventWithResult(
                eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
                () -> "Hello, World!"
            );

        // Duplicate processing
        IdempotentEventProcessor.ProcessingResult<String> result2 =
            eventProcessor.processEventWithResult(
                eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
                () -> "Should not execute"
            );

        System.out.println("First result - processed: " + result1.wasProcessed() + ", value: " + result1.getValue());
        System.out.println("Second result - processed: " + result2.wasProcessed() + ", value: " + result2.getValue());

        assertTrue(result1.wasProcessed(), "First should be processed");
        assertEquals("Hello, World!", result1.getValue());

        assertTrue(result2.wasSkipped(), "Second should be skipped");
        assertNull(result2.getValue(), "Skipped result should have null value");

        printSuccess("processEventWithResult works correctly");
    }

    @Test
    @DisplayName("Simulating consumer crash and replay scenario")
    void testConsumerCrashAndReplay() {
        printTestHeader("Consumer Crash and Replay Scenario");

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        AtomicInteger handlerCallCount = new AtomicInteger(0);

        System.out.println("Event ID: " + eventId);
        System.out.println("Scenario: Consumer processes event, then crashes and replays");

        // First processing (before crash)
        boolean firstProcess = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            () -> {
                handlerCallCount.incrementAndGet();
                System.out.println("Processing event (before crash)");
            }
        );

        System.out.println("First process result: " + firstProcess);

        // Simulate consumer crash and restart
        System.out.println("--- Consumer crashed and restarted ---");

        // Replay the same event (Kafka redelivers after crash)
        boolean replayProcess = eventProcessor.processEvent(
            eventId, EVENT_TYPE, AGGREGATE_TYPE, aggregateId, CONSUMER_GROUP,
            () -> {
                handlerCallCount.incrementAndGet();
                System.out.println("Processing event (replay after crash)");
            }
        );

        System.out.println("Replay process result: " + replayProcess);
        System.out.println("Total handler calls: " + handlerCallCount.get());

        assertTrue(firstProcess, "First processing should succeed");
        assertFalse(replayProcess, "Replay should be detected and skipped");
        assertEquals(1, handlerCallCount.get(), "Handler should only execute once");

        printSuccess("Consumer crash and replay handled correctly - no duplicate processing");
    }
}
