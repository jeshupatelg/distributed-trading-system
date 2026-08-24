# Low-Level Design: tick-lb

## 1. Overview
`tick-lb` is an Envoy-based Layer 7 application load balancer. It acts as the gateway proxy between broker connection managers (like `connection-manager-alpaca`) and specialized signal generator instances. 

Its primary responsibility is to parse HTTP/2 frames, evaluate the `x-ticker` gRPC metadata header, and route individual gRPC market data streams to the appropriate target engine.

---

## 2. Proxy Routing Specifications
Routing matches are constrained to the specific gRPC service definition to prevent processing arbitrary HTTP network requests.

*   **Service Endpoint**: `/trading.connection.ConnectionManagerService/StreamMarketData`
*   **Routing Logic**:
    *   `x-ticker` matches `"AAPL"` exactly ──> routes to cluster `signal-gen-aapl`.
    *   `x-ticker` matches `"MSFT"` exactly ──> routes to cluster `signal-gen-msft`.
    *   No header matches ──> routes to fallback cluster `signal-gen-default`.

---

## 3. High-Throughput Resilience Configuration

### A. Connection Pooling & Circuit Breaking
High-frequency market data tick streams create a high volume of concurrent HTTP/2 frames. Default Envoy limits are overridden to prevent queue bottlenecks:
*   **Max Connections**: `8192` (Max concurrent TCP connections)
*   **Max Requests**: `65536` (Max active concurrent gRPC streams/requests)
*   **Max Pending Requests**: `8192` (Maximum queued requests allowed while awaiting connections)

### B. Connection Timeout & Keep-Alives
*   **Connection Timeout (`connect_timeout`)**: `0.25s` (Ensures fast failover if a container is unreachable).
*   **Route Timeout (`timeout`)**: `0s` (Disables standard HTTP request timeouts, as gRPC streams are long-lived bidirectional connections).
*   **Idle Timeout (`idle_timeout`)**: `300s` (Reaps hung/dead connections after 5 minutes of inactivity to prevent connection leaks).

### C. Active Health Checks
To prevent black-holing traffic when a signal generator replica freezes without closing its socket, Envoy runs active background checks:
*   **Protocol**: gRPC Health Checking Protocol (`envoy.health_checkers.grpc`).
*   **Interval**: `5s` (Pings every backend instance).
*   **Timeout**: `1s` (Must respond within 1 second).
*   **Fail Threshold**: `3` consecutive failures flags the instance as unhealthy (removing it from the load balancer pool).
*   **Recovery Threshold**: `2` consecutive successes restores the instance.

---

## 4. Stateless Gateway Compliance
*   `tick-lb` does not write to RDBMS or caching layers.
*   It functions entirely at the network L7 layer, keeping it fully compliant with stateless proxy guidelines.
