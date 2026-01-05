package com.flagship.payment_ledger.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka configuration for Phase 5.
 *
 * Configures:
 * - Kafka topic for payment events
 * - KafkaTemplate for sending messages
 */
@Configuration
public class KafkaConfig {

    @Value("${kafka.topic.payments:payments}")
    private String paymentsTopic;

    /**
     * Creates the payments topic if it doesn't exist.
     * Uses 3 partitions for parallel processing.
     */
    @Bean
    public NewTopic paymentsTopic() {
        return TopicBuilder.name(paymentsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
