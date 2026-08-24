# Order Processing Service (OPS) LLD

## Overview
The Order Processing Service (OPS) is the hot-path execution engine for the Distributed Trading System. Built on Java 21 and Spring Boot, it evaluates trading signals, validates risk against a Redis cache, and places orders over gRPC to stateless Connection Managers.

## Key Design Principles
- **No Database Access**: OPS does not contain JDBC or JPA dependencies. It relies entirely on Redis for state and Kafka for event emission.
- **Dedicated OPS Core Pool**: High-throughput virtual threads (Java 21) or fixed bounded thread pools are used strictly for order processing to avoid starvation.
- **Idempotency**: Signal events from Kafka are deduplicated using a Redis lookup or bloom filter if necessary.

## Core Components
1. `SignalConsumer`: Listens to `signal-event` Kafka topic.
2. `RiskManager`: Evaluates cache locks (`Blocked Margin`, cash balance).
3. `OrderSubmissionClient`: Uses gRPC to send `PlaceOrder` to the respective Broker Gateway.
4. `EventPublisher`: Publishes `order-create-event` to Kafka.

## Fallback
If the Redis cache is empty (e.g., service restart), OPS requests state restoration, though the actual persistent fetch is managed elsewhere or queried via synchronous broker fallback.
