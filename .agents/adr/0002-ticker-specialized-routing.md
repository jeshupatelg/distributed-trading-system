# ADR 0002: Ticker-Specialized Routing for Heterogeneous Strategy Deployments

## Status
Accepted

## Context
In our system design, the `Signal Generator` components are not homogeneous replicas. Different tickers require distinct, specialized trading strategies (e.g., `AAPL` runs a customized SMA Crossover, while `MSFT` runs a Mean Reversion strategy with distinct indicator parameters). 

Because different container replicas run different code or configurations, the cluster is **heterogeneous**. Dynamic load-balancing policies like Consistent Hashing assume all targets are symmetric (functionally identical) and cannot guarantee that the `AAPL` price feed is sent to the specific container running the Apple strategy.

To ensure correct routing, we require a **Static, Header-Based Routing** mechanism where the load balancer routes price feeds strictly based on the ticker symbol.

## Decision
We will configure **Static Header-Based Routing** in the Layer 7 Load Balancer (Envoy / Istio).

1.  **Metadata Headers**: The `Connection-Manager` will attach an `x-ticker` header to all outgoing gRPC streams (e.g., `x-ticker: AAPL`).
2.  **Explicit Routing Maps**: The load balancer will evaluate the `x-ticker` header value and forward the stream to the corresponding specialized container cluster.

---

## Technical Specifications

### 1. Docker Compose Configuration
We define separate target service blocks in the compose file and route to them using explicit header matches in Envoy.

#### A. Docker Compose (`docker-compose.yml`)
```yaml
version: '3.8'
services:
  connection-manager:
    build: ./connection-manager

  # Specialized strategy container for AAPL
  signal-gen-aapl:
    build: ./signal-generator
    environment:
      - STRATEGY_TYPE=SMA_CROSSOVER
      - TICKER=AAPL

  # Specialized strategy container for MSFT
  signal-gen-msft:
    build: ./signal-generator
    environment:
      - STRATEGY_TYPE=MEAN_REVERSION
      - TICKER=MSFT

  envoy-lb:
    image: envoyproxy/envoy:v1.28.0
    volumes:
      - ./envoy.yaml:/etc/envoy/envoy.yaml:ro
    ports:
      - "50051:50051"
```

#### B. Envoy Configuration (`envoy.yaml`)
```yaml
static_resources:
  listeners:
  - name: grpc_listener
    address:
      socket_address: { address: 0.0.0.0, port_value: 50051 }
    filter_chains:
    - filters:
      - name: envoy.filters.network.http_connection_manager
        typed_config:
          "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
          stat_prefix: grpc_router
          route_config:
            name: local_route
            virtual_hosts:
            - name: local_service
              domains: ["*"]
              routes:
              # Route AAPL to signal-gen-aapl
              - match:
                  prefix: "/"
                  headers:
                  - name: "x-ticker"
                    exact_match: "AAPL"
                route:
                  cluster: aapl_strategy_cluster
              # Route MSFT to signal-gen-msft
              - match:
                  prefix: "/"
                  headers:
                  - name: "x-ticker"
                    exact_match: "MSFT"
                route:
                  cluster: msft_strategy_cluster
          http_filters:
          - name: envoy.filters.http.router
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router

  clusters:
  - name: aapl_strategy_cluster
    connect_timeout: 0.25s
    type: STRICT_DNS
    lb_policy: ROUND_ROBIN
    http2_protocol_options: {}
    load_assignment:
      cluster_name: aapl_strategy_cluster
      endpoints:
      - lb_endpoints:
        - endpoint:
            address:
              socket_address: { address: signal-gen-aapl, port_value: 50051 }

  - name: msft_strategy_cluster
    connect_timeout: 0.25s
    type: STRICT_DNS
    lb_policy: ROUND_ROBIN
    http2_protocol_options: {}
    load_assignment:
      cluster_name: msft_strategy_cluster
      endpoints:
      - lb_endpoints:
        - endpoint:
            address:
              socket_address: { address: signal-gen-msft, port_value: 50051 }
```

---

### 2. Kubernetes Configuration (Istio)
In Kubernetes, we deploy separate Deployments and separate Services for each ticker strategy to isolate resources.

#### A. Virtual Service (`virtual-service.yaml`)
The routing matches the gRPC request header and directs it to the correct K8s Service:
```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: signal-generator-router
  namespace: trading
spec:
  hosts:
  - signal-generator-entrypoint-service
  http:
  # Route AAPL to the AAPL service
  - match:
    - headers:
        x-ticker:
          exact: AAPL
    route:
    - destination:
        host: signal-generator-aapl-service
        port:
          number: 50051
  # Route MSFT to the MSFT service
  - match:
    - headers:
        x-ticker:
          exact: MSFT
    route:
    - destination:
        host: signal-generator-msft-service
        port:
          number: 50051
```

---

## Consequences

### Pros
* **Complete Strategy Customization**: Enables deploying highly tailored logic (different libraries, parameters, and algorithms) per ticker.
* **Failure Isolation**: If the `MSFT` strategy container crashes (e.g., due to an indicator calculation error), the `AAPL` pipeline continues executing unaffected.
* **Isolated Caching**: Each container only bootstraps and caches historical data for its target ticker, drastically reducing memory usage per container.

### Cons
* **Configuration Overhead**: Adding a new ticker requires deploying a new service block and updating the Load Balancer routing rules (YAML change).
* **Heterogeneous Cluster Maintenance**: You manage multiple distinct container deployments rather than scaling a single unified deployment.
