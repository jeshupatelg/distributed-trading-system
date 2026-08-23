# ADR 0003: Multi-Broker Routing and Audit Strategy

## Status
Accepted

## Context
As we expand to support multiple broker providers (e.g., Alpaca and Broker X) running concurrently, the system must address two challenges:
1.  **Order Execution Routing**: How does the Combined Order Service (COS) determine which broker gateway should receive an execution request when a signal triggers?
2.  **Strategy Divergence**: If we trade the same ticker (e.g., `AAPL`) across different brokers, we may run different strategy engines (e.g., one optimized for Alpaca's zero-commission structures and another for Broker X's execution latency). We need a way to distinguish which data feed initiated the signal.

## Decision
We will implement the following hybrid routing and audit strategy:

1.  **Add `provider` Field to gRPC Ticks**: 
    We will modify the shared gRPC schema (`connection_manager.proto`) to include a `string provider` field in `MarketDataResponse`, `OrderResponse`, and `OrderStatusResponse`.
    *   `connection-manager-alpaca` will stamp `"alpaca"` on all price ticks and orders.
    *   `connection-manager-x` will stamp `"broker-x"`.
2.  **Signal Propagation**:
    The `Signal Generator` will preserve the `provider` string from incoming ticks and include it in the `signal-event` published to Kafka (e.g., `x-provider: alpaca`).
3.  **Static Ticker Routing Table in COS**:
    `COS` will maintain a static configuration table mapping tickers (and optionally provider keys) to the correct target connection manager gRPC endpoint.
    ```yaml
    # application.yml
    trading:
      routing:
        AAPL: connection-manager-alpaca
        TCS: connection-manager-x
    ```

---

## Consequences

### Pros
* **Audit Traceability**: Every transaction log, database record, and Kafka signal contains an explicit stamp of the broker that supplied the data, making it simple to calculate metrics like slippage or broker-specific latency.
* **Support for Strategy Divergence**: If the same ticker is traded across multiple brokers, the `provider` field allows the system to distinguish between `SignalGen-AAPL-Alpaca` and `SignalGen-AAPL-X` outputs.
* **Calculation/Execution Decoupling**: Signal generators remain purely analytical. They do not contain any routing rules; they simply forward the metadata header.

### Cons
* Minimal byte payload size increase inside the high-frequency gRPC stream messages.
