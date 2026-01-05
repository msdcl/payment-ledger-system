package com.flagship.payment_ledger.observability;

import com.flagship.payment_ledger.outbox.OutboxEventRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Custom health indicators for the payment ledger system.
 *
 * Phase 7: Observability
 *
 * These health checks determine if the service is ready to accept traffic.
 * Used by Kubernetes readiness probes and load balancers.
 */
public class HealthIndicators {

    /**
     * Health indicator for the outbox backlog.
     * Unhealthy if too many events are waiting to be published.
     */
    @Component("outboxHealth")
    public static class OutboxHealthIndicator implements HealthIndicator {

        private final OutboxEventRepository outboxRepository;
        private static final long BACKLOG_WARNING_THRESHOLD = 1000;
        private static final long BACKLOG_CRITICAL_THRESHOLD = 10000;

        public OutboxHealthIndicator(OutboxEventRepository outboxRepository) {
            this.outboxRepository = outboxRepository;
        }

        @Override
        public Health health() {
            try {
                long backlogSize = outboxRepository.countUnpublished();

                Health.Builder builder = backlogSize < BACKLOG_WARNING_THRESHOLD
                        ? Health.up()
                        : backlogSize < BACKLOG_CRITICAL_THRESHOLD
                        ? Health.status("WARNING")
                        : Health.down();

                return builder
                        .withDetail("backlogSize", backlogSize)
                        .withDetail("warningThreshold", BACKLOG_WARNING_THRESHOLD)
                        .withDetail("criticalThreshold", BACKLOG_CRITICAL_THRESHOLD)
                        .build();

            } catch (Exception e) {
                return Health.down()
                        .withDetail("error", e.getMessage())
                        .build();
            }
        }
    }

    /**
     * Health indicator for Redis connectivity.
     * Used for idempotency key fast-path.
     */
    @Component("redisHealth")
    public static class RedisHealthIndicator implements HealthIndicator {

        private final StringRedisTemplate redisTemplate;

        public RedisHealthIndicator(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @Override
        public Health health() {
            try {
                var connectionFactory = redisTemplate.getConnectionFactory();
                if (connectionFactory == null) {
                    return Health.status("DEGRADED")
                            .withDetail("error", "No connection factory configured")
                            .withDetail("note", "System can operate without Redis using DB fallback")
                            .build();
                }

                var connection = connectionFactory.getConnection();
                if (connection == null) {
                    return Health.status("DEGRADED")
                            .withDetail("error", "Unable to obtain connection")
                            .withDetail("note", "System can operate without Redis using DB fallback")
                            .build();
                }

                String result = connection.ping();

                if ("PONG".equals(result)) {
                    return Health.up()
                            .withDetail("response", result)
                            .build();
                } else {
                    return Health.down()
                            .withDetail("response", result != null ? result : "null")
                            .build();
                }

            } catch (Exception e) {
                // Redis being down is acceptable (fallback to DB)
                return Health.status("DEGRADED")
                        .withDetail("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        .withDetail("note", "System can operate without Redis using DB fallback")
                        .build();
            }
        }
    }

    /**
     * Health indicator for Kafka connectivity.
     */
    @Component("kafkaHealth")
    public static class KafkaHealthIndicator implements HealthIndicator {

        private final KafkaTemplate<String, String> kafkaTemplate;

        public KafkaHealthIndicator(KafkaTemplate<String, String> kafkaTemplate) {
            this.kafkaTemplate = kafkaTemplate;
        }

        @Override
        public Health health() {
            try {
                if (kafkaTemplate == null) {
                    return Health.down()
                            .withDetail("error", "KafkaTemplate not configured")
                            .build();
                }

                // Check if we can get cluster metadata
                var metrics = kafkaTemplate.metrics();
                if (metrics == null) {
                    return Health.down()
                            .withDetail("error", "Unable to retrieve Kafka metrics")
                            .build();
                }

                boolean hasConnections = !metrics.isEmpty();

                if (hasConnections) {
                    return Health.up()
                            .withDetail("metricsCount", metrics.size())
                            .build();
                } else {
                    return Health.down()
                            .withDetail("error", "No Kafka connections established")
                            .build();
                }

            } catch (Exception e) {
                return Health.down()
                        .withDetail("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        .build();
            }
        }
    }
}
