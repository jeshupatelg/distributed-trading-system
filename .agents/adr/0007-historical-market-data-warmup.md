# ADR 0007: Historical Market Data Warmup for Strategy Initialization

## Status
Accepted

## Context
Algorithmic trading strategies (like SMA Crossover or Mean Reversion) require a sliding window of historical close prices (e.g., 20 or 30 periods) to calculate moving averages and other technical indicator values. 

If a strategy engine (`signal-generator`) boots up with a cold cache (an empty history deque), it must wait for 20 to 30 live ticks before it can produce its first valid trading signal. This cold-start latency is highly undesirable.

Because strategy engines must remain completely decoupled from broker credentials (to allow them to be scaled and deployed without exposing API keys), they cannot connect directly to broker REST history endpoints.

## Decision
We will extend our gRPC architecture to support programmatic strategy warmup at boot:

1.  **gRPC Contract Extension**: Add a unary RPC endpoint `GetHistoricalBars` under `MarketDataService` in the shared Protobuf contract.
2.  **Stateless Gateway Proxying**: Implement this call inside the broker gateways (e.g. `connection-manager-alpaca`) to query historical stock bars from the broker REST SDK (such as `StockHistoricalDataClient` in `alpaca-py`) and map them to Protobuf response structures.
3.  **Strategy Warmup Sequence**:
    *   On boot, the strategy client (`signal-generator`) calculates the minimum history size $N$ it needs from its strategy parameters.
    *   It calls `GetHistoricalBars` via the load balancer (`tick-lb`).
    *   It loops through the returned historical bars and calls `strategy.on_bar()`.
    *   Importantly, it **suppresses/discards** any signal returns generated during this loop to prevent publishing stale, old historical alerts to Kafka.
    *   Once pre-populated, it initiates the live `StreamMarketData` subscription.

---

## Consequences

### Pros
*   **Zero Cold-Start Delay**: Strategy engines are fully warmed up and capable of executing actions from the very first live tick they receive.
*   **Maintained Isolation**: Strategy engines remain completely broker-agnostic and do not require broker credentials or database connection states.
*   **Cohesive Gateways**: Gateways act as the unified proxy for both live telemetry streams and historical queries.

### Cons
*   **Bootstrap Delay**: Minor startup latency increase (under 1 second) while waiting for the gRPC unary warmup query to complete.
