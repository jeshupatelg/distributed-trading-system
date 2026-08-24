# High-Level Design (HLD) Document: Distributed Trading System

This document specifies the system architecture and event-driven data flow of the distributed algorithmic trading platform.

---

## 1. System Architecture Overview

The system is designed as an event-driven, hybrid-stack microservices architecture. It decouples high-frequency market data streaming, algorithmic signal generation, transaction execution management, and user metrics tracking. 

Crucially, the API ingress adapters are isolated into **Broker Connection Gateways** (e.g. `connection-manager-alpaca`, `connection-manager-x`), allowing you to integrate new brokers without modifying strategy calculations or downstream execution logic.

Refer to the PlantUML architecture diagram at [hld.puml](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/hld/hld.puml) for the component mapping.

### Core Component Roles:
1.  **Connection Manager (Alpaca / X) (Python)**: The stateless credentials gateways. Each instance handles authentication, establishes sessions with its specific broker API, converts streaming ticks to gRPC streams, and proxies order operations. They maintain no connection or state mutations in Redis or SQL databases.
2.  **Shared Cache Layer (Redis)**: Holds low-latency operational state (balances, positions, blocked margin, and pending order IDs).
3.  **SQL Database (RDBMS)**: The persistent transactional storage of historical order records (`tracked_orders`) and execution activity logs.
4.  **Signal Generators (Python)**: Scalable, ticker-specific indicator processors that calculate algorithmic metrics and publish signals to Kafka. Due to static L7 routing, they are decoupled from specific broker SDK layers.
5. **Order Processing Service (OPS) (Java/Spring Boot)**: Contains the core trading state machine (hot-path).
    *   **OPS Core (Low-Latency Pool)**: Runs risk validation checks, acts as the sole initiator of account cache restoration in Redis (either during service bootstrap or as a pre-order fallback risk validation check), and executes orders through the target connection manager.
6. **Order Management Service (OMS) (Java/Spring Boot)**: Manages order lifecycles and fallback reconciliation (cold-path).
    *   **OMS Tracker (Background Pool)**: Handles database persistence, cache settlement upon updates, and cron-based reconciliation.
7.  **Quant/UI Dashboard Service (Streamlit/Python)**: The operator frontend. In its initial phase, it is strictly scoped to **read-only / pull operations**. It queries live balance/positions from the Redis Cache layer, reads historical logs/orders from the SQL Database, and hosts a user interface showing provider connectivity. Future write updates (triggers to start/stop strategies or override orders) are stubbed out as future enhancement points.
8.  **Observability Stack (Prometheus / Grafana)**: The system telemetry and alert manager. It runs out-of-band to monitor service performance and health. Prometheus dynamically scrapes metrics from JVM/Spring Boot endpoints, Python services, tick-lb, and Kafka, exposing visual metrics on Grafana.

---

## 2. Step-by-Step System Workflows

The numbered steps correspond to the lifecycle flows illustrated in the HLD diagram.

### Phase A: Market Data Streaming & Signal Calculation
*   **Step 1.a/b [Establish Session]**: At startup, `connection-manager-alpaca` (or `connection-manager-x`) authenticates and opens WebSocket/REST sessions with the respective broker endpoints.
*   **Step 2.a/b [Stream Ticks]**: The connection managers serialize incoming tick data into binary Protocol Buffers and stream it to the `Load Balancer` via gRPC.
*   **Step 3 [Route Stream]**: The `Load Balancer` performs static Layer-7 routing based on the `x-ticker` header, distributing the tick feeds to corresponding specialized `Signal Generator` replicas over gRPC.
*   **Step 4 [Publish Signal]**: The `Signal Generators` update rolling indicators and publish a `signal-event` to the `Kafka Cluster`.

### Phase B: Order Placement & Pre-Order Risk Gate
*   **Step 5 [Consume Signal]**: The `Order Processing Service (OPS)` consumes the `signal-event` from Kafka.
*   **Step 6 [Cache Lock]**: The `OPS Core` thread pool in `OPS` queries Redis for cash and active blocked margin. (If the cache is not initialized, OPS acts as the sole initiator of account cache restoration to load state from the SQL Database or via REST query from the broker). If validated, it:
    *   Adds the estimated order value to `Blocked Margin` (local lock).
    *   Saves the `orderId` to the Redis Set (`orders:pending`).
*   **Step 7.a/b [Submit Order]**: `OPS` submits the transaction payload to the designated broker gateway (e.g., `connection-manager-alpaca`) via gRPC Unary (`PlaceOrder`).
*   **Step 8 [Publish Create Event]**: `OPS` publishes the `order-create-event` to Kafka.
*   **Step 8.5 [Async DB Log]**: A background consumer in `Order Management Service (OMS)` consumes `order-create-event` from Kafka and writes an initial database record (`status = PENDING`) to the `SQL Database` asynchronously, removing disk write latency from the hot path.


### Phase C: Live Order Resolution & Cache Settlement
*   **Step 9.a/b [WebSocket Update]**: The broker API executes the order and streams the fill status over the active WebSocket back to its connection manager.
*   **Step 10.a/b [Forward Update]**: The connection manager forwards it to Kafka as a `raw-order-update` event.
*   **Step 11 [Consume Update]**: `OMS` consumes `raw-order-update` from Kafka.
*   **Step 12 [Settle Cache]**: `OMS` updates the settled cash/positions in Redis, clears the blocked margin, and runs `SREM` to remove the `orderId` from the `orders:pending` set.
*   **Step 12.5 [Update DB]**: `OMS` updates the SQL transaction row status to `COMPLETED` or `FAILED`.
*   **Step 13 [Normalize Event]**: `OMS` publishes the final `order-complete-event` to Kafka using the `orderId` as an idempotent key.

### Phase D: Fallback Reconciliation (Cron)
*   **Step 14 [Read Pending Set]**: The background tracker in `OMS` calls `SMEMBERS orders:pending` to retrieve unresolved transactions.
*   **Step 14.5 [Proxy Query]**: `OMS` sends a gRPC query (`GetOrderStatus`) to the target connection manager.
*   **Step 15 [Execute REST Query]**: The connection manager proxies the REST call (`GET /v2/orders/{id}`) to the broker, returning the state to `OMS` to execute resolution.

### Phase E: UI Monitoring & Dashboard
*   **Step 16 [Read Live Cache]**: The `Dashboard` queries live account balances and position allocations directly from `Redis`.
*   **Step 16.5 [Query DB History]**: The `Dashboard` queries historical records and audit logs from the `SQL Database`.
*   **Step 17 [Consume Fills]**: The `Dashboard` consumes live `order-complete-events` from Kafka to update charts.
*   **Step 18 [HTTPS Access]**: The trader logs in via secure HTTPS to view the interface.

### Phase F: System Telemetry & Observability (Installation Scope)
*   **Step 19 [Scrape Metrics]**: The Prometheus daemon periodically queries metric endpoints (e.g. `/actuator/prometheus` or `/metrics`) exposed by Java microservices, Python gateways, tick-lb, and databases.
*   **Step 20 [Render Dashboards]**: Operators query Grafana's visualization panels which query Prometheus for real-time memory, latency, and throughput statistics.

