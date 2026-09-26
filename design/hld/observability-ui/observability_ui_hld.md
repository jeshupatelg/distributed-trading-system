# High-Level Design (HLD): Observability & UI Components

This document describes the high-level architecture, design specifications, and interaction flows for the UI Dashboard (`quant-dashboard`) and the Observability stack (`Prometheus` & `Grafana`) within the `distributed-trading-system`.

---

## 1. Interaction Diagram

The component diagram below shows how the Observability and UI components interface with the core infrastructure, databases, and application services out-of-band to monitor health and render analytics.

Refer to the source diagram at [observability_ui_hld.puml](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/hld/observability-ui/observability_ui_hld.puml).

```mermaid
graph TD
    subgraph Users ["Actor Tier"]
        Trader["Trader / Operator"]
    end

    subgraph UI ["UI & Dashboard Tier"]
        Dashboard["Quant Dashboard (Streamlit)"]
    end

    subgraph Obs ["Observability Tier"]
        Grafana["Grafana Dashboard UI"]
        Prom["Prometheus Daemon"]
    end

    subgraph Core ["Core Microservices"]
        OPS["Order Processing Service"]
        OMS["Order Management Service"]
        CM["Alpaca Connection Manager"]
        LB["Envoy Load Balancer"]
    end

    subgraph Broker ["Event Streaming"]
        Kafka["Kafka Cluster"]
    end

    subgraph State ["State Storage Layer"]
        Redis[("Redis Cache")]
        Postgres[("PostgreSQL DB")]
    end

    %% User Connections
    Trader -->|HTTPS :8501| Dashboard
    Trader -->|HTTPS :3000| Grafana

    %% UI Data Pulls
    Dashboard -->|RESP :6379| Redis
    Dashboard -->|JDBC :5432| Postgres
    Kafka -->|Consume order-complete-events| Dashboard

    %% Telemetry Connections
    Grafana -->|Query REST :9090| Prom
    Prom -->|Scrape HTTP GET| CM
    Prom -->|Scrape HTTP GET| LB
    Prom -->|Scrape HTTP GET| OPS
    Prom -->|Scrape HTTP GET| OMS
    Prom -->|Scrape HTTP GET| Redis
    Prom -->|Scrape HTTP GET| Postgres
    Prom -->|Scrape HTTP GET| Kafka
```

---

## 2. Quant Dashboard Architecture

The `quant-dashboard` is an operator-facing control panel designed with **Streamlit** (Python). 

### Operational Constraints & Boundaries
*   **Read-Only Scope**: In its current deployment phase, the dashboard is strictly limited to read-only/pull operations. Write operations (such as manually executing override orders, or starting/stopping specific strategy instances) are completely disabled.
*   **Out-of-Path Execution**: The dashboard has no connection to the execution hot-path (OPS/OMS gRPC connections). It does not write to the database or Redis cache, preventing UI requests from blocking order execution.

### Data Ingress Interfaces
1.  **Live Position & Balance Cache (Redis)**:
    *   Queries live account cash balance via `GET balance:cash`.
    *   Queries active blocked margin limits via `GET balance:blocked`.
    *   Queries active positions per ticker via `GET positions:<symbol>`.
2.  **Order Audit Trail (Postgres)**:
    *   Queries transactional status records from the `tracked_orders` table (SQL query with pagination) to render historical trade logs.
3.  **Real-Time Fill Events (Kafka)**:
    *   Subscribes as a consumer group to the `order-complete-events` Kafka topic. Renders incoming normalization events (execution status, filled average price, total filled quantity) live on the dashboard UI.

---

## 3. Observability Stack Architecture

The Observability Stack utilizes **Prometheus** (time-series database and collector) and **Grafana** (telemetry dashboard) to monitor the entire trading pipeline.

### Prometheus Telemetry Scraping Model
Prometheus operates via a pull model, executing HTTP GET requests on the metrics endpoints of all whitelisted targets at regular scrape intervals (default: `15s`):

1.  **Java/Spring Boot Actuators (OPS & OMS)**:
    *   Endpoint: `/actuator/prometheus` (ports `8081` and `8082`).
    *   Exposes JVM runtime stats (heap utilization, garbage collection rates, thread pools), Hikari connection pool usage, HTTP handler latency, and Kafka consumer group lags.
2.  **Python Prometheus Clients (Connection Managers & Strategies)**:
    *   Endpoint: `/metrics` (FastAPI ports `8000`, `8001`, `8002`).
    *   Exposes custom application metrics such as WebSocket stream connect states, incoming tick ingestion rate, tick lag (timestamp delta between tick creation and ingestion), and gRPC unary execution success/fail counts.
3.  **Envoy Admin Exporter (tick-lb)**:
    *   Endpoint: `/stats/prometheus` (Envoy admin port `9901`).
    *   Exposes stats for gRPC load balancing, upstream cluster connection pools, dropped requests, and circuit-breaker triggers.
4.  **Database Exporters**:
    *   PostgreSQL: Scraping active database connections, transactions per second, and row locking states.
    *   Redis: Scraping total memory usage, active client connections, RESP command processing rates, and hit/miss ratios.

### Grafana Telemetry Dashboards
Grafana queries Prometheus for real-time visualization and alerting. Exposed boards include:
*   **Pipeline Latency (System Hot-Path)**: Visualizes the time elapsed for a tick to traverse the pipeline: `Tick Created (Alpaca) -> Ingested (CM) -> Routed (Envoy) -> Strategy Triggered (SignalGen) -> Signal Out (Kafka)`.
*   **JVM & Container Health**: Graphs memory leaks, thread starvation, CPU throttling, and connection bottlenecks.
*   **Reconciliation cron lag**: Tracks lag between unresolved transaction counts in Redis and final DB updates in Postgres.

---

## 4. Order Processing (OPS) & Order Management (OMS) Telemetry Architecture

The diagram below details the exact telemetry data paths, Kafka topics, risk evaluation gates, order persistence, and Prometheus pull mechanisms for OPS and OMS.

Refer to the PlantUML definition: [ops_oms_telemetry_flow.puml](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/hld/observability-ui/ops_oms_telemetry_flow.puml).

```mermaid
flowchart TD
    subgraph StrategyTier["Strategy Tier"]
        SigGen["Signal Generator (AAPL / MSFT)"]
    end

    subgraph KafkaTier["Kafka Event Bus"]
        TopSignals["trading-signals"]
        TopOrderCreate["order-create-events"]
        TopOrderReject["order-reject-events"]
        TopRawUpdates["raw-order-updates"]
        TopOrderComplete["order-complete-events"]
    end

    subgraph OPSTier["Order Processing Service (OPS :8081)"]
        SC["SignalConsumer"]
        RM["RiskManager (8 Pre-Trade Risk Gates)"]
        OEC["OrderExecutionClient (gRPC)"]
        OPST["OpsTelemetry (Micrometer Registry)"]
    end

    subgraph OMSTier["Order Management Service (OMS :8082)"]
        OCC["OrderCreateConsumer"]
        OUC["OrderUpdateConsumer"]
        ORS["OrderResolutionService"]
        RecJob["ReconciliationJob (30s)"]
        OMST["OmsTelemetry (Micrometer Registry)"]
    end

    subgraph Persistence["Storage & Broker Gateway"]
        Postgres[("PostgreSQL DB (trading_agent)")]
        Redis[("Redis Cache (Balances & Positions)")]
        Broker["Broker Gateway (connection-manager)"]
    end

    subgraph ObsTier["Observability Stack"]
        Prom["Prometheus Server (:9090)"]
        Grafana["Grafana Dashboards (:3000)"]
    end

    %% Flow connections
    SigGen -->|1. Publish Signal| TopSignals
    TopSignals -->|2. Consume Signal| SC
    SC -->|3. Evaluate Risk| RM
    RM <-->|4. Margin Check & Lock| Redis
    RM -.->|5. Record Risk Metrics| OPST
    SC -->|6A. Approved: Submit| OEC
    SC -->|6B. Rejected: Emit Reject| TopOrderReject
    OEC -->|7. gRPC OrderRequest| Broker
    OEC -->|8. Emit OrderCreate| TopOrderCreate

    TopOrderCreate -->|9. Ingest OrderCreate| OCC
    OCC -->|10. INSERT PENDING Order| Postgres
    OCC -.->|11. Record OrderCreated| OMST

    Broker -->|12. Execution Fill Update| TopRawUpdates
    TopRawUpdates -->|13. Ingest Fill Update| OUC
    OUC -->|14. Resolve Order| ORS
    ORS -->|15. UPDATE Terminal Status| Postgres
    ORS -->|16. Settle Cash & Positions| Redis
    ORS -->|17. Emit OrderComplete| TopOrderComplete
    ORS -.->|18. Record Resolution & Latency| OMST
    RecJob -.->|19. Record Drift & Health| OMST

    %% Prometheus Scrape
    Prom -->|20. HTTP Pull Scrape :8081/actuator/prometheus| OPST
    Prom -->|21. HTTP Pull Scrape :8082/actuator/prometheus| OMST
    Grafana -->|22. PromQL Query REST :9090| Prom
```

