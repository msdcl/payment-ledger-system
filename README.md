# Payment Ledger System

A production-grade payment processing and double-entry ledger system demonstrating financial correctness, idempotency, event sourcing, and distributed systems resilience.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           Payment Ledger Architecture                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  ┌────────────────┐                                                                  │
│  │    Client      │                                                                  │
│  │  Application   │                                                                  │
│  └───────┬────────┘                                                                  │
│          │ POST /api/payments                                                        │
│          │ Idempotency-Key: <uuid>                                                   │
│          │ X-Correlation-ID: <uuid>                                                  │
│          ▼                                                                           │
│  ┌───────────────────────────────────────────────────────────────────────────────┐   │
│  │                         Spring Boot Application                                │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐   │   │
│  │  │  Payment    │  │   Ledger    │  │  Outbox     │  │    Consumer         │   │   │
│  │  │  Controller │──│   Service   │──│  Publisher  │  │    (Idempotent)     │   │   │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘   │   │
│  │         │                │                │                    │              │   │
│  │  ┌──────┴────────────────┴────────────────┴────────────────────┴──────────┐   │   │
│  │  │                     Observability Layer                                 │   │   │
│  │  │  • Correlation IDs  • Metrics  • Health Checks  • Structured Logging   │   │   │
│  │  └─────────────────────────────────────────────────────────────────────────┘   │   │
│  └───────────────────────────────────────────────────────────────────────────────┘   │
│          │                │                │                    │                    │
│          ▼                ▼                ▼                    ▼                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │  PostgreSQL  │  │    Redis     │  │    Kafka     │  │  Prometheus  │              │
│  │              │  │              │  │              │  │              │              │
│  │ • Payments   │  │ • Idempotency│  │ • Events     │  │ • Metrics    │              │
│  │ • Ledger     │  │   cache      │  │   topic      │  │ • Alerts     │              │
│  │ • Outbox     │  │ • Fast path  │  │ • Consumers  │  │              │              │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘              │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

## Key Features

### Financial Correctness
- **Double-Entry Ledger**: Every transaction creates balanced debit and credit entries
- **Atomic Operations**: Payment and ledger updates in single database transactions
- **Audit Trail**: Complete history of all financial movements

### Reliability
- **Idempotency**: Safe request retries via idempotency keys
- **Transactional Outbox**: Reliable event publishing without dual-write problems
- **Graceful Degradation**: System continues operating when Redis or Kafka fail

### Observability
- **Correlation IDs**: Request tracing across all components
- **Prometheus Metrics**: Counters, gauges, and histograms for monitoring
- **Health Checks**: Custom indicators for dependencies
- **Structured Logging**: JSON logging for production environments

## Quick Start

### Prerequisites

- Java 21+
- Docker & Docker Compose
- Gradle 8+

### Start Dependencies

```bash
# Start PostgreSQL, Redis, and Kafka
docker-compose up -d

# Or start individually
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=payment_ledger \
  postgres:16

docker run -d --name redis -p 6379:6379 redis:7

docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_CFG_NODE_ID=0 \
  -e KAFKA_CFG_PROCESS_ROLES=controller,broker \
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093 \
  bitnami/kafka:latest
```

### Run the Application

```bash
# Build and run
./gradlew bootRun

# Or run tests
./gradlew test
```

### Create a Payment

```bash
# Create a payment with idempotency key
curl -X POST http://localhost:8050/api/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "amount": 100.00,
    "currency": "USD",
    "fromAccountId": "550e8400-e29b-41d4-a716-446655440001",
    "toAccountId": "550e8400-e29b-41d4-a716-446655440002"
  }'

# Response:
{
  "id": "550e8400-e29b-41d4-a716-446655440003",
  "amount": 100.00,
  "currency": "USD",
  "status": "CREATED",
  "fromAccountId": "550e8400-e29b-41d4-a716-446655440001",
  "toAccountId": "550e8400-e29b-41d4-a716-446655440002",
  "createdAt": "2024-01-05T10:30:00Z"
}
```

## API Reference

### Create Payment

```
POST /api/payments
Headers:
  - Idempotency-Key: <uuid> (required)
  - X-Correlation-ID: <uuid> (optional, generated if missing)

Request:
{
  "amount": 100.00,
  "currency": "USD",
  "fromAccountId": "<uuid>",
  "toAccountId": "<uuid>"
}

Response: 201 Created
{
  "id": "<uuid>",
  "amount": 100.00,
  "currency": "USD",
  "status": "CREATED",
  ...
}
```

### Get Payment

```
GET /api/payments/{id}

Response: 200 OK
{
  "id": "<uuid>",
  "amount": 100.00,
  "currency": "USD",
  "status": "SETTLED",
  "ledgerTransactionId": "<uuid>",
  ...
}
```

### Health Checks

```
GET /actuator/health

Response:
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "kafka": { "status": "UP" },
    "outboxHealth": {
      "status": "UP",
      "details": { "backlogSize": 0 }
    }
  }
}
```

### Metrics

```
GET /actuator/prometheus

# Sample metrics:
payments_created_total{currency="USD",status="success"} 42
payments_latency_seconds_bucket{operation="create",le="0.1"} 38
outbox_backlog_size 0
idempotency_cache_total{result="hit"} 15
```

## Implementation Phases

| Phase | Name | Description |
|-------|------|-------------|
| 0 | Domain Model | Immutable payment entities, state machine |
| 1 | Persistence | JPA entities, repositories, Flyway migrations |
| 2 | Double-Entry Ledger | Balanced transactions, audit trail |
| 3 | Idempotency | Redis + DB idempotency key handling |
| 4 | Settlement Integration | Atomic payment + ledger operations |
| 5 | Transactional Outbox | Reliable event publishing to Kafka |
| 6 | Consumers | Idempotent event processing |
| 7 | Observability | Metrics, logging, health checks |
| 8 | Failure Scenarios | Resilience testing and documentation |
| 9 | Documentation | This README and interview prep |

## Design Decisions

### Why Transactional Outbox?

Traditional dual-write pattern:
```
1. Save to database ✅
2. Send to Kafka ❌ (fails)
Result: Data inconsistency - payment saved but no event
```

Transactional outbox pattern:
```
1. Save payment + event to database (atomic) ✅
2. Background publisher sends to Kafka ✅
Result: Guaranteed consistency - event always matches data
```

### Why Double-Entry Ledger?

Single-entry system:
```
Account A: balance -= 100  (what if this fails?)
Account B: balance += 100  (orphaned credit!)
```

Double-entry system:
```
Transaction (atomic):
  - Debit Entry: Account A, -100
  - Credit Entry: Account B, +100
  - Sum of all entries = 0 (always balanced)
```

### Why Idempotency Keys?

Without idempotency:
```
Client: POST /payments  →  Server: creates payment
Client: (timeout, no response)
Client: POST /payments  →  Server: creates ANOTHER payment
Result: Duplicate charge!
```

With idempotency:
```
Client: POST /payments (Idempotency-Key: abc)  →  Server: creates payment
Client: (timeout, no response)
Client: POST /payments (Idempotency-Key: abc)  →  Server: returns SAME payment
Result: Safe retry, no duplicate!
```

## Failure Modes

| Component | Failure | System Behavior | Data Impact |
|-----------|---------|-----------------|-------------|
| Redis | Down | Falls back to database lookup | None |
| Kafka | Down | Events queue in outbox | None (delayed delivery) |
| Database | Transaction fail | Full rollback | None |
| App Crash | Mid-operation | Uncommitted work rolled back | None |

## Configuration

```properties
# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/payment_ledger
spring.datasource.username=postgres
spring.datasource.password=postgres

# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Kafka
spring.kafka.bootstrap-servers=localhost:9092
kafka.topic.payments=payments

# Outbox
outbox.publisher.enabled=true
outbox.publisher.poll-interval-ms=1000
outbox.publisher.batch-size=100

# Observability
management.endpoints.web.exposure.include=health,metrics,prometheus
```

## Project Structure

```
src/main/java/com/flagship/payment_ledger/
├── payment/                    # Payment domain
│   ├── Payment.java           # Immutable domain model
│   ├── PaymentEntity.java     # JPA entity
│   ├── PaymentController.java # REST API
│   ├── PaymentService.java    # Business logic
│   └── event/                 # Payment events
├── ledger/                    # Double-entry ledger
│   ├── LedgerEntry.java      # Individual entries
│   ├── LedgerTransaction.java # Balanced transactions
│   └── LedgerService.java    # Transaction posting
├── outbox/                    # Transactional outbox
│   ├── OutboxEvent.java      # Event model
│   ├── OutboxService.java    # Event storage
│   └── OutboxPublisher.java  # Kafka publisher
├── consumer/                  # Event consumers
│   ├── IdempotentEventProcessor.java
│   └── PaymentEventConsumer.java
├── observability/            # Monitoring
│   ├── CorrelationIdFilter.java
│   ├── PaymentMetrics.java
│   └── HealthIndicators.java
└── config/                   # Configuration
    ├── KafkaConfig.java
    └── RedisConfig.java
```

## Testing

```bash
# All tests
./gradlew test

# Unit tests only
./gradlew test --tests "*Test"

# Integration tests
./gradlew test --tests "*IntegrationTest"

# Failure scenario tests
./gradlew test --tests "*.failure.*"
```

## Monitoring & Alerts

### Key Metrics to Watch

| Metric | Alert Threshold | Action |
|--------|-----------------|--------|
| `outbox.backlog.size` | > 1000 | Check Kafka connectivity |
| `outbox.backlog.age.seconds` | > 300 | Publisher may be stuck |
| `payments.created{status=error}` | Rate > 5% | Investigate errors |
| `payments.latency` | P99 > 1s | Performance degradation |

### Grafana Dashboard

Import the dashboard from `Documentation/grafana-dashboard.json` for:
- Payment creation rate and latency
- Outbox backlog and publishing rate
- Idempotency cache hit rate
- Health status overview

## Contributing

1. Fork the repository
2. Create a feature branch
3. Write tests for your changes
4. Ensure all tests pass
5. Submit a pull request

## License

MIT License - see LICENSE file for details.
