# Envoy Configuration Specification for `tick-lb`

This document details the production-ready Envoy proxy configuration (`envoy.yaml`) for the `tick-lb` Layer 7 load balancer.

## 1. Routing & Proxy Strategy
*   **gRPC Service Matching**: Explicitly matches `/trading.connection.ConnectionManagerService/StreamMarketData` to prevent routing arbitrary/unauthenticated HTTP traffic.
*   **Static Ticker Routing**: Evaluates the `x-ticker` gRPC metadata header to route feeds to specialized strategy clusters.
*   **Timeout Tuning**: Standard HTTP timeouts are disabled (`timeout: 0s`) to prevent disconnecting active gRPC streams, while an `idle_timeout` of `300s` reaps dead connections.
*   **Circuit Breakers**: Configured to support up to `8192` concurrent connections and `65536` concurrent streams.
*   **Active Health Checks**: Configured with gRPC Health Checking (reaps unhealthy signal generator instances within 15 seconds).

---

## 2. Complete Envoy Configuration (`envoy.yaml`)

```yaml
static_resources:
  listeners:
  - name: grpc_listener
    address:
      socket_address:
        address: 0.0.0.0
        port_value: 50051
    filter_chains:
    - filters:
      - name: envoy.filters.network.http_connection_manager
        typed_config:
          "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
          stat_prefix: grpc_ingress
          codec_type: AUTO
          route_config:
            name: tick_routes
            virtual_hosts:
            - name: tick_router
              domains: ["*"]
              routes:
              - match:
                  path: "/trading.connection.ConnectionManagerService/StreamMarketData"
                  headers:
                  - name: "x-ticker"
                    string_match:
                      exact: "AAPL"
                route:
                  cluster: signal-gen-aapl
                  timeout: 0s
                  idle_timeout: 300s
              - match:
                  path: "/trading.connection.ConnectionManagerService/StreamMarketData"
                  headers:
                  - name: "x-ticker"
                    string_match:
                      exact: "MSFT"
                route:
                  cluster: signal-gen-msft
                  timeout: 0s
                  idle_timeout: 300s
              # Fallback route for all other tickers matching the Stream gRPC call
              - match:
                  path: "/trading.connection.ConnectionManagerService/StreamMarketData"
                route:
                  cluster: signal-gen-default
                  timeout: 0s
                  idle_timeout: 300s
          http_filters:
          - name: envoy.filters.http.router
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router

  clusters:
  - name: signal-gen-aapl
    connect_timeout: 0.25s
    type: STRICT_DNS
    lb_policy: ROUND_ROBIN
    http2_protocol_options: {}
    circuit_breakers:
      thresholds:
      - priority: DEFAULT
        max_connections: 8192
        max_requests: 65536
        max_pending_requests: 8192
    health_checks:
    - timeout: 1s
      interval: 5s
      unhealthy_threshold: 3
      healthy_threshold: 2
      grpc_health_check: {}
    load_assignment:
      cluster_name: signal-gen-aapl
      endpoints:
      - lb_endpoints:
        - endpoint:
            address:
              socket_address:
                address: signal-gen-aapl
                port_value: 50051

  - name: signal-gen-msft
    connect_timeout: 0.25s
    type: STRICT_DNS
    lb_policy: ROUND_ROBIN
    http2_protocol_options: {}
    circuit_breakers:
      thresholds:
      - priority: DEFAULT
        max_connections: 8192
        max_requests: 65536
        max_pending_requests: 8192
    health_checks:
    - timeout: 1s
      interval: 5s
      unhealthy_threshold: 3
      healthy_threshold: 2
      grpc_health_check: {}
    load_assignment:
      cluster_name: signal-gen-msft
      endpoints:
      - lb_endpoints:
        - endpoint:
            address:
              socket_address:
                address: signal-gen-msft
                port_value: 50051

  - name: signal-gen-default
    connect_timeout: 0.25s
    type: STRICT_DNS
    lb_policy: ROUND_ROBIN
    http2_protocol_options: {}
    circuit_breakers:
      thresholds:
      - priority: DEFAULT
        max_connections: 8192
        max_requests: 65536
        max_pending_requests: 8192
    health_checks:
    - timeout: 1s
      interval: 5s
      unhealthy_threshold: 3
      healthy_threshold: 2
      grpc_health_check: {}
    load_assignment:
      cluster_name: signal-gen-default
      endpoints:
      - lb_endpoints:
        - endpoint:
            address:
              socket_address:
                address: signal-gen-default
                port_value: 50051
