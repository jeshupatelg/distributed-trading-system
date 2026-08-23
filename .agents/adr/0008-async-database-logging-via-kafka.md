# ADR 0008: Asynchronous SQL Database Logging via Kafka

## Status
Accepted

## Context
Writing synchronously to a relational database (PostgreSQL/RDBMS) on the critical execution path (the `OPS Core` thread pool in the Combined Order Service / OMS) introduces disk and network I/O blockages. In high-frequency trading contexts, these synchronous database writes limit system throughput and increase tick-to-order latencies.

The initial database write (saving the `PENDING` state of an order in the `tracked_orders` table) is not needed by the core execution state machine (which relies on Redis memory keys). Its primary use is for historical logging and cron reconciliation.

The OMS already publishes an `order-create-event` to Kafka immediately after submitting an order to the gateway.

## Decision
1.  **Remove Hot-Path SQL Writes**: Remove the synchronous call to `TrackedOrderRepository.save()` from the `OPS Core` thread pool execution path.
2.  **Async Logging via Kafka**: Utilize the existing `order-create-event` Kafka message to write the `PENDING` order log to the SQL database asynchronously.
3.  **Introduce Background Consumer**: Add a background consumer component `OrderCreationDbConsumer` (running in the OMS background executor pool) to listen to the `order-create-event` topic and write the initial `PENDING` state to the SQL database.
4.  **Synchronous Order Updates**: Keep order completion updates (marking rows `COMPLETED` or `FAILED` in the database upon receiving raw execution callbacks) as synchronous operations. These writes are triggered by broker gateway WebSocket callbacks and do not block the hot placement path.

---

## Consequences

### Pros
*   **Reduced Order Latency**: Order submission latency is reduced to RAM-speed validation checks (Redis) and the outbound gRPC gateway call.
*   **Increased Fault Tolerance**: Kafka acts as a message queue buffer. If the RDBMS experiences connection pool exhaustion or a short outage, order creation events are safely preserved in Kafka and written once the database recovers.
*   **Centralized Read-Path**: UI dashboards query active pending states directly from the low-latency Redis cache, removing dependency on the SQL database for real-time reads.

### Cons
*   **Eventual Consistency**: There is a minor eventual-consistency lag (microsecond scale) before a newly placed order is queryable in the SQL database.
