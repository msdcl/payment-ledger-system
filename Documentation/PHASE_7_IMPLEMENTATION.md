# Phase 7: Observability

## Overview

Phase 7 implements **production-grade observability** for the payment ledger system. This includes structured logging, distributed tracing via correlation IDs, Prometheus metrics, and health checks. These capabilities are essential for operating the system at scale and diagnosing issues quickly.

## Goals Achieved

1. ✅ Structured JSON logging with correlation IDs
2. ✅ Request tracing across distributed components
3. ✅ Micrometer metrics for Prometheus integration
4. ✅ Custom health indicators for dependencies
5. ✅ Outbox and Kafka metrics for monitoring

## The Three Pillars of Observability

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Observability Stack                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐              │
│  │     LOGGING     │  │     METRICS     │  │    TRACING      │              │
│  │                 │  │                 │  │                 │              │
│  │  • Structured   │  │  • Counters     │  │  • Correlation  │              │
│  │  • JSON format  │  │  • Gauges       │  │    IDs          │              │
│  │  • MDC context  │  │  • Histograms   │  │  • Request      │              │
│  │  • Log levels   │  │  • Prometheus   │  │    flow         │              │
│  │                 │  │    export       │  │                 │              │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘              │
│           │                    │                    │                        │
│           └────────────────────┴────────────────────┘                        │
│                                │                                             │
│                    ┌───────────┴───────────┐                                 │
│                    │   HEALTH CHECKS       │                                 │
│                    │                       │                                 │
│                    │   • Database          │                                 │
│                    │   • Redis             │                                 │
│                    │   • Kafka             │                                 │
│                    │   • Outbox backlog    │                                 │
│                    └───────────────────────┘                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. Correlation ID Propagation

Every request receives a unique correlation ID that flows through all components:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Extract or generate correlation ID
        String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Store in thread-local and MDC
        CorrelationContext.setCorrelationId(correlationId);
        MDC.put("correlationId", correlationId);

        try {
            // Add to response for client tracking
            ((HttpServletResponse) response).setHeader(CORRELATION_ID_HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
            MDC.clear();
        }
    }
}
```

**Log output with correlation ID:**
```
2024-01-05 10:30:15.123 [http-nio-8050-exec-1] INFO  PaymentController - [abc-123-def] Received payment creation request
2024-01-05 10:30:15.145 [http-nio-8050-exec-1] INFO  PaymentController - [abc-123-def] Payment created successfully
```

### 2. Structured JSON Logging

For production environments, logs are formatted as JSON for easy parsing:

```xml
<!-- logback-spring.xml -->
<springProfile name="production,prod">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>correlationId</includeMdcKeyName>
            <includeMdcKeyName>paymentId</includeMdcKeyName>
            <customFields>{"service":"payment-ledger"}</customFields>
        </encoder>
    </appender>
</springProfile>
```

**JSON log output:**
```json
{
  "@timestamp": "2024-01-05T10:30:15.123Z",
  "level": "INFO",
  "logger_name": "PaymentController",
  "message": "Payment created successfully",
  "service": "payment-ledger",
  "correlationId": "abc-123-def",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 3. Micrometer Metrics

Comprehensive metrics for monitoring payment operations:

```java
@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;

    // Payment operations
    public void recordPaymentCreated(String currency, String status) {
        meterRegistry.counter("payments.created",
                "currency", currency,
                "status", status
        ).increment();
    }

    public void recordPaymentSettled(String currency, String status) {
        meterRegistry.counter("payments.settled",
                "currency", currency,
                "status", status
        ).increment();
    }

    // Latency tracking
    public void recordPaymentLatency(String operation, long durationMs) {
        meterRegistry.timer("payments.latency",
                "operation", operation
        ).record(Duration.ofMillis(durationMs));
    }

    // Idempotency tracking
    public void recordIdempotencyHit() {
        meterRegistry.counter("idempotency.cache", "result", "hit").increment();
    }

    public void recordIdempotencyMiss() {
        meterRegistry.counter("idempotency.cache", "result", "miss").increment();
    }
}
```

**Available metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| `payments.created` | Counter | Payments created by currency/status |
| `payments.settled` | Counter | Payments settled by currency/status |
| `payments.latency` | Timer | Payment operation latency |
| `idempotency.cache` | Counter | Idempotency key hits/misses |
| `outbox.backlog.size` | Gauge | Unpublished events count |
| `outbox.backlog.age.seconds` | Gauge | Oldest event age |
| `outbox.events.published` | Counter | Events published to Kafka |
| `outbox.events.failed` | Counter | Failed event publications |

### 4. Outbox Metrics

Real-time visibility into the transactional outbox:

```java
@Component
public class OutboxMetrics {

    private final AtomicLong backlogSize = new AtomicLong(0);
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong(0);

    @PostConstruct
    public void init() {
        Gauge.builder("outbox.backlog.size", backlogSize, AtomicLong::get)
                .description("Number of unpublished events in the outbox")
                .register(meterRegistry);

        Gauge.builder("outbox.backlog.age.seconds", oldestEventAgeSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished event")
                .register(meterRegistry);
    }

    // Refreshed every 15 seconds
    public void refreshMetrics() {
        backlogSize.set(outboxRepository.countUnpublished());
        // ...
    }
}
```

### 5. Health Indicators

Custom health checks for Kubernetes readiness probes:

```java
@Component("outboxHealth")
public class OutboxHealthIndicator implements HealthIndicator {

    private static final long WARNING_THRESHOLD = 1000;
    private static final long CRITICAL_THRESHOLD = 10000;

    @Override
    public Health health() {
        long backlogSize = outboxRepository.countUnpublished();

        Health.Builder builder = backlogSize < WARNING_THRESHOLD
                ? Health.up()
                : backlogSize < CRITICAL_THRESHOLD
                ? Health.status("WARNING")
                : Health.down();

        return builder
                .withDetail("backlogSize", backlogSize)
                .withDetail("warningThreshold", WARNING_THRESHOLD)
                .withDetail("criticalThreshold", CRITICAL_THRESHOLD)
                .build();
    }
}
```

**Health check response:**
```json
{
  "status": "UP",
  "components": {
    "outboxHealth": {
      "status": "UP",
      "details": {
        "backlogSize": 42,
        "warningThreshold": 1000,
        "criticalThreshold": 10000
      }
    },
    "redisHealth": {
      "status": "DEGRADED",
      "details": {
        "note": "System can operate without Redis using DB fallback"
      }
    },
    "kafkaHealth": {
      "status": "UP",
      "details": {
        "metricsCount": 125
      }
    }
  }
}
```

## Prometheus Integration

### Endpoint Configuration

```properties
# application.properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.prometheus.metrics.export.enabled=true
management.metrics.tags.application=${spring.application.name}
```

### Sample Prometheus Queries

```promql
# Payment creation rate (last 5 minutes)
rate(payments_created_total{status="success"}[5m])

# Payment settlement error rate
sum(rate(payments_settled_total{status="error"}[5m]))
/ sum(rate(payments_settled_total[5m]))

# Outbox backlog size
outbox_backlog_size

# P99 payment creation latency
histogram_quantile(0.99, rate(payments_latency_seconds_bucket{operation="create"}[5m]))

# Idempotency cache hit rate
sum(rate(idempotency_cache_total{result="hit"}[5m]))
/ sum(rate(idempotency_cache_total[5m]))
```

### Alert Rules

```yaml
groups:
  - name: payment-ledger
    rules:
      - alert: HighOutboxBacklog
        expr: outbox_backlog_size > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Outbox backlog is growing"

      - alert: OutboxBacklogCritical
        expr: outbox_backlog_size > 10000
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Outbox backlog critical - Kafka may be down"

      - alert: HighPaymentErrorRate
        expr: |
          sum(rate(payments_created_total{status="error"}[5m]))
          / sum(rate(payments_created_total[5m])) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Payment error rate above 5%"

      - alert: HighPaymentLatency
        expr: |
          histogram_quantile(0.99, rate(payments_latency_seconds_bucket{operation="create"}[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "P99 payment creation latency above 1 second"
```

## Request Flow with Observability

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         Request Lifecycle                                   │
└────────────────────────────────────────────────────────────────────────────┘

 Client
    │
    │ POST /api/payments
    │ X-Correlation-ID: abc-123-def (optional)
    ▼
┌────────────────────────────────────────────────────────────────────────────┐
│ CorrelationIdFilter                                                         │
│                                                                              │
│ • Extract or generate correlation ID                                        │
│ • Store in MDC for logging                                                  │
│ • Add to response header                                                    │
└────────────────────────────────────────────────────────────────────────────┘
    │
    │ MDC: {correlationId: "abc-123-def"}
    ▼
┌────────────────────────────────────────────────────────────────────────────┐
│ PaymentController                                                           │
│                                                                              │
│ • Log: "Received payment creation request" [abc-123-def]                    │
│ • Add paymentId to MDC                                                      │
│ • Record metrics: payments.created, payments.latency                        │
│ • Log: "Payment created successfully" [abc-123-def]                         │
└────────────────────────────────────────────────────────────────────────────┘
    │
    │ MDC: {correlationId: "abc-123-def", paymentId: "pay-456"}
    ▼
┌────────────────────────────────────────────────────────────────────────────┐
│ Metrics Collected                                                           │
│                                                                              │
│ payments_created_total{currency="USD",status="success"} 1                   │
│ payments_latency_seconds{operation="create"} 0.045                          │
│ idempotency_cache_total{result="miss"} 1                                    │
└────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
 Response
    │ 201 Created
    │ X-Correlation-ID: abc-123-def
    ▼
 Client
```

## Files Created

```
src/main/java/com/flagship/payment_ledger/observability/
├── CorrelationContext.java          # Thread-local correlation ID storage
├── CorrelationIdFilter.java         # HTTP filter for correlation propagation
├── PaymentMetrics.java              # Micrometer metrics for payments
├── OutboxMetrics.java               # Outbox-specific metrics
├── MetricsScheduler.java            # Periodic metrics refresh
└── HealthIndicators.java            # Custom health checks

src/main/resources/
├── application.properties           # Updated with actuator config
└── logback-spring.xml               # Structured logging configuration
```

## Configuration

```properties
# Actuator endpoints
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=when-authorized
management.prometheus.metrics.export.enabled=true

# Custom health thresholds
health.outbox.warning-threshold=1000
health.outbox.critical-threshold=10000

# Metrics refresh interval
metrics.refresh.interval=15000

# Logging
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - [%X{correlationId}] %msg%n
```

## Operational Runbook

### Scenario: High Outbox Backlog Alert

1. Check Kafka connectivity:
   ```bash
   curl http://localhost:8050/actuator/health/kafkaHealth
   ```

2. View outbox metrics:
   ```bash
   curl http://localhost:8050/actuator/metrics/outbox.backlog.size
   ```

3. Check for dead letter events:
   ```sql
   SELECT * FROM outbox_events
   WHERE published_at IS NULL
   AND retry_count >= 5;
   ```

4. Verify Kafka topic health:
   ```bash
   kafka-topics.sh --describe --topic payments --bootstrap-server localhost:9092
   ```

### Scenario: Tracing a Failed Payment

1. Get correlation ID from client/logs
2. Search logs:
   ```bash
   grep "abc-123-def" /var/log/payment-ledger/*.log
   ```

3. Or with JSON logs:
   ```bash
   jq 'select(.correlationId == "abc-123-def")' /var/log/payment-ledger/*.json
   ```

## Key Invariants Maintained

1. **Every request has a correlation ID**
   - Generated if not provided
   - Propagated through all components
   - Returned in response headers

2. **All business operations are measured**
   - Creation/settlement counts
   - Latency distributions
   - Error rates

3. **Health reflects actual system state**
   - Dependency connectivity
   - Backlog thresholds
   - Graceful degradation visibility

## Next Phase: Phase 8 - Failure Scenarios

Phase 8 will focus on:
- Testing Redis failure scenarios
- Testing Kafka failure scenarios
- Testing database rollback scenarios
- Documenting failure modes and recovery
