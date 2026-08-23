# ADR 0005: Split Market Data and Order Execution gRPC Services

## Status
Accepted

## Context
Previously, both the live market data streaming endpoint (`StreamMarketData`) and order execution endpoints (`PlaceOrder`, `GetOrderStatus`) were coupled inside a single `ConnectionManagerService` gRPC service contract.

This forced the `signal-generator` microservices—which are purely analytical telemetry consumers—to implement order proxy endpoints (`PlaceOrder` and `GetOrderStatus`) to prevent gRPC servicer compilation errors, violating stateless boundaries and clean Layer 7 interface segregation.

Furthermore, we want to align the system design such that strategy instances operate as **pure gRPC clients** pulling market data and publishing trade signals to Kafka, removing any need to act as gRPC servers.

## Decision
We will split the unified gRPC service contract into two cohesive services:

1.  **`MarketDataService`**: Focuses entirely on price ticks and telemetry streaming.
2.  **`OrderExecutionService`**: Focuses entirely on placing orders and retrieving status updates.

Both services will reside in the same `connection_manager.proto` contract to share common data structures (`MarketDataResponse`, etc.) without path import complexities in the Python runtime.

The microservices will adapt as follows:
*   **`connection-manager-alpaca`**: Registers both `MarketDataService` and `OrderExecutionService` controllers onto its single running gRPC server instance (port `50051`).
*   **`signal-generator`**: Operates as a **pure gRPC client**. It initiates outbound connections to the L7 Envoy load balancer (`tick-lb`), invokes `MarketDataService/StreamMarketData` for its target symbol, and processes bars in the background. It runs no gRPC server and exposes no server ports.
*   **`tick-lb` (Envoy)**: Reconfigured to proxy requests to the broker gateways (`connection-manager-alpaca` cluster) instead of the strategy clusters.

---

## Consequences

### Pros
*   **Decoupled Interface Boundaries**: Strategy engines are completely decoupled from order execution logic and stubs.
*   **Zero Server Overhead on Strategy Engines**: Strategy pods do not run gRPC server threads or expose public gRPC container ports, minimizing their threat surface and memory footprints.
*   **Cohesive Gateways**: The broker gateways implement both services on a shared socket port, keeping them logically segregated but simple to deploy.

### Cons
*   None. This simplifies the strategy execution code significantly.
