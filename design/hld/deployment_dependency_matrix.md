# Deployment Dependency Matrix

This document defines the deployment dependency matrix and execution order for the `distributed-trading-system` stack.

## 1. System Components & Dependencies

The system services are categorized into layers. Services in higher layers depend on the running and healthy state of services in lower layers.

| Component Name | Service Name (Docker) | Dependencies | Role |
| :--- | :--- | :--- | :--- |
| **Database** | `homeserver-pg` | None | PostgreSQL persistent transactional storage (running on host/external) |
| **Cache** | `homeserver-redis` | None | Redis low-latency operational state (running on host/external) |
| **Message Broker** | `kafka` | None (External network `kafka_net`) | Event-driven streaming backbone |
| **Gateway** | `connection-manager-alpaca` | `kafka` | Python gateway to Alpaca API |
| **Load Balancer** | `tick-lb` | `connection-manager-alpaca` | Envoy L7 gRPC load balancer |
| **Strategy AAPL** | `signal-gen-aapl` | `tick-lb`, `kafka` | Indicator generator for AAPL ticker |
| **Strategy MSFT** | `signal-gen-msft` | `tick-lb`, `kafka` | Indicator generator for MSFT ticker |
| **Order Processing** | `order-processing-service` | `tick-lb`, `homeserver-redis`, `kafka` | Hot-path order placement state machine |
| **Order Management** | `order-management-service` | `order-processing-service`, `homeserver-redis`, `homeserver-pg`, `kafka` | Cold-path transaction reconciliation & persistence |
| **Dashboard** | `quant-dashboard` | `homeserver-redis`, `homeserver-pg` | User-facing dashboard (deployed last) |
| **Telemetry** | `prometheus` | All microservices | Metrics scraping |
| **Visualization** | `grafana` | `prometheus` | Metrics dashboard |

---

## 2. Recommended Deployment Order (Phased Stack Launch)

Based on the dependency matrix, components should be deployed sequentially in the following phases:

### Phase 1: Core Infrastructure
1. **`homeserver-pg`** (Postgres)
2. **`homeserver-redis`** (Redis)
3. **`kafka`** (Ensure Kafka is up and running on the external `kafka_net` network)

### Phase 2: Ingress & Routing Gateways
4. **`connection-manager-alpaca`**
5. **`tick-lb`** (Envoy)

### Phase 3: Hot-Path Strategy & Order Placement
6. **`signal-gen-aapl`** and **`signal-gen-msft`**
7. **`order-processing-service`** (OPS)

### Phase 4: Cold-Path Order Reconciliation
8. **`order-management-service`** (OMS)

### Phase 5: Monitoring & Visualization (Telemetry)
9. **`prometheus`**
10. **`grafana`**

### Phase 6: Frontend Interface (Last)
11. **`quant-dashboard`**
