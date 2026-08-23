# ADR 0001: Use Envoy Proxy for Layer 7 Load Balancing

## Status
Accepted

## Context
The distributed trading system uses **gRPC (HTTP/2)** as the primary communication protocol between key internal microservices (e.g., `Connection-Manager`, `Signal Generators`, and `Combined Order Service`).

gRPC streams (such as live price ticks) utilize a single, long-lived TCP connection. Standard Layer-4 (L4) load balancers (like default Kubernetes ClusterIP Services or basic TCP proxies) only load-balance the initial TCP connection. This leads to connection pinning/stickiness: once a connection is established, all requests and streamed candles are routed to a single replica, leaving other replicas completely idle.

To prevent resource imbalance and ensure true horizontal scalability, we require a **Layer-7 (L7) application load balancer** that parses HTTP/2 frames and distributes individual streams and requests.

## Decision
We will use **Envoy Proxy** as the standard L7 load balancer for both local development (Docker Compose) and cloud deployment (Kubernetes). 

### Rationale
1. **gRPC First-Class Citizen**: Envoy has native, low-latency support for HTTP/2 multiplexing and gRPC routing.
2. **xDS Dynamic APIs**: Envoy can load routing tables and endpoint lists dynamically on the fly without service restarts.
3. **Observability**: Exposes detailed metrics for monitoring gRPC request/response counters and latencies.
4. **Transition Continuity**: Provides a consistent load-balancing logic that runs locally in Docker and integrates natively in Kubernetes service meshes.

---

## Technical Specifications

### 1. Local Development Config (Docker Compose)
In Docker Compose, Envoy runs as a standalone gateway container. Service discovery is configured statically using Docker's internal DNS resolving.

#### A. Envoy Configuration (`envoy.yaml`)
Create this file and mount it into the Envoy container:
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
          stat_prefix: grpc_router
          route_config:
            name: local_route
            virtual_hosts:
            - name: local_service
              domains: ["*"]
              routes:
              - match:
                  prefix: "/"
                route:
                  cluster: signal_generator_cluster
          http_filters:
          - name: envoy.filters.http.router
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router

  clusters:
  - name: signal_generator_cluster
    connect_timeout: 0.25s
    type: STRICT_DNS  # Resolves container IPs via Docker DNS
    lb_policy: ROUND_ROBIN
    http2_protocol_options: {}  # Enforces HTTP/2
    load_assignment:
      cluster_name: signal_generator_cluster
      endpoints:
      - lb_endpoints:
        - endpoint:
            address:
              socket_address:
                address: signal-generator  # Docker Compose service name
                port_value: 50051
```

#### B. Docker Compose Definition (`docker-compose.yml`)
```yaml
version: '3.8'
services:
  connection-manager:
    build: ./connection-manager
    environment:
      - PORT_GRPC=50051

  signal-generator:
    build: ./signal-generator
    deploy:
      replicas: 3 # Scale to 3 instances

  envoy-lb:
    image: envoyproxy/envoy:v1.28.0
    volumes:
      - ./envoy.yaml:/etc/envoy/envoy.yaml:ro
    ports:
      - "50051:50051" # Expose the gRPC load balancer port
    depends_on:
      - signal-generator
```

---

### 2. Kubernetes Config (Istio Service Mesh)
In Kubernetes, raw Envoy configurations are externalized. We deploy **Istio** as the service mesh. Istio injects an Envoy sidecar proxy into each application Pod and pushes configurations dynamically via the EDS (Endpoint Discovery Service) API.

#### A. Destination Rule (`destination-rule.yaml`)
Configures the load-balancing policy (e.g., distributing requests based on resource load/least requests) on Envoy sidecars:
```yaml
apiVersion: networking.istio.io/v1alpha3
kind: DestinationRule
metadata:
  name: signal-generator-destination
  namespace: trading
spec:
  host: signal-generator-service
  trafficPolicy:
    loadBalancer:
      simple: LEAST_REQUEST  # Envoy routes to the replica with the fewest active streams
```

#### B. Virtual Service (`virtual-service.yaml`)
Defines the routing rules and request policies:
```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: signal-generator-virtual
  namespace: trading
spec:
  hosts:
  - signal-generator-service
  http:
  - route:
    - destination:
        host: signal-generator-service
        port:
          number: 50051
      timeout: 2.0s  # Enforces a 2s timeout on the stream connection
```

---

## Consequences

### Pros
* **True L7 Balancing**: Solves HTTP/2 stream pinning, distributing trading signals and price feeds equally across replicas.
* **Low Latency**: Envoy adds negligible latency overhead (< 1ms).
* **Decoupled Infrastructure**: In Kubernetes, you manage routing policies dynamically via K8s CRDs without maintaining any application-specific proxy configurations.

### Cons
* **Kubernetes Sidecar Tax**: Every pod runs an Envoy container, adding a memory footprint of ~30MB–60MB per pod.
* **Istio Dependency**: Requires installing and maintaining the Istio control plane (`istiod`) inside the cluster.
