# ADR 0004: Specialized gRPC Routing & Resiliency for `tick-lb`

## Status
Accepted

## Context
In our system design, the `Signal Generator` components are heterogeneous and specialized by ticker (e.g., `SignalGen-AAPL` runs an AAPL-specific strategy, and `SignalGen-MSFT` runs an MSFT-specific strategy). 

To route the high-frequency gRPC price tick streams to the correct strategy engine, we need a Layer 7 proxy (`tick-lb`). However, standard HTTP proxies will disconnect long-lived gRPC streams due to default timeouts, and can freeze or drop packets under high concurrency without explicit connection pool configurations and active health checks.

## Decision
We will deploy **Envoy Proxy** as the `tick-lb` load balancer with the following strict L7 specifications:

1.  **gRPC Path Restriction**: Match requests strictly on the service endpoint path `/trading.connection.ConnectionManagerService/StreamMarketData` and extract the `x-ticker` metadata header.
2.  **Explicit Routing Table**:
    *   `x-ticker: AAPL` ──> `signal-gen-aapl` cluster.
    *   `x-ticker: MSFT` ──> `signal-gen-msft` cluster.
    *   Fallback ──> `signal-gen-default` cluster.
3.  **Timeout Tuning**: Disable default request timeouts (`timeout: 0s`) to support persistent streaming, and enable a `300s` idle timeout to reap dead connection leaks.
4.  **Circuit Breaking & Scaling**: Set custom thresholds to support up to `8192` concurrent connections and `65536` concurrent streams.
5.  **gRPC Active Health Checking**: Configure active `grpc_health_check` background checking every 5 seconds to ensure traffic is never routed to a crashed/frozen strategy pod.

---

## Consequences

### Pros
* **Isolation of Concerns**: Neither the connection managers nor the strategy engines need to know the target network IP mappings.
* **Resilient Scaling**: Adding or scaling strategy replicas is handled dynamically via `STRICT_DNS` and active health checking.
* **Data Flow Reliability**: Circuit breakers and timeouts protect the system from resource exhaustion under volatile high-throughput market periods (e.g., market open).

### Cons
* **Gateway Resource Cost**: Introduces a minor L7 proxy network hop (typically sub-millisecond) and requires running the Envoy container.
