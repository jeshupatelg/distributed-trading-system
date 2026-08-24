# Order Management Service (OMS) LLD

## Overview
The Order Management Service (OMS) is the cold-path execution engine that manages order lifecycles, database persistence, state reconciliation, and fallback audits. Built on Java 21 and Spring Boot, it offloads database latency from the high-frequency trading path.

## Key Design Principles
- **Asynchronous Persistence**: Database writes occur in response to Kafka events.
- **Idempotency checks**: Because order updates (`raw-order-update`) can be redelivered, the OMS verifies the state of an order before processing updates.
- **Background Cron**: Scheduled tasks poll Redis for lingering pending orders and reconcile them via the gRPC/REST proxy mechanism.

## Core Components
1. `OrderCreateConsumer`: Listens to `order-create-event` and saves the initial `PENDING` state to the SQL database.
2. `OrderUpdateConsumer`: Listens to `raw-order-update` from broker gateways.
3. `OrderResolutionService`: Updates SQL (`COMPLETED`/`FAILED`), clears Redis `Blocked Margin`, removes the `orderId` from the Redis pending set, and publishes `order-complete-event`.
4. `ReconciliationJob`: A scheduled cron task that pulls from `orders:pending` (Redis) to identify stalled transactions, queries the target gateway via gRPC `GetOrderStatus`, and resolves them.

## Persistence
- Spring Data JPA is used to interact with PostgreSQL/MySQL.
- Connection pooling is handled by HikariCP to manage multiple concurrent updates efficiently.
