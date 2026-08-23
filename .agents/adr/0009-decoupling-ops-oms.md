# ADR 0009: Decoupling Order Processing and Management Services

## Status
Accepted

## Context
The previous "Combined Order Service (COS)" handled both the hot-path latency-sensitive operations (risk validation, cache locks, and order placement) and the cold-path latency-tolerant operations (database persistence, reconciliation, background tracking). As the system scales, these two types of workloads have vastly different resource usage profiles and scaling characteristics. A single thread pool model risks thread exhaustion during periods of heavy database latency, which can bleed into the latency-sensitive hot path.

## Decision
We are decoupling the Combined Order Service into two independent microservices:
1. **Order Processing Service (OPS)**: Strictly responsible for the hot-path execution. It consumes signal events, queries Redis for risk validation, and forwards order placements via gRPC. It does **not** interact with the SQL database, making its execution path deterministic and memory-bound.
2. **Order Management Service (OMS)**: Strictly responsible for the cold-path execution. It consumes the `order-create-event` to persist the initial state asynchronously, consumes `raw-order-update` for resolving orders, settles Redis cache positions, and runs cron jobs for reconciliation.

## Consequences
* **Pros**: 
  - Complete isolation of the hot-path (OPS) from database latency spikes (OMS).
  - OPS can scale independently based on signal throughput.
  - OMS can scale independently based on database connection pools.
* **Cons**:
  - Increased deployment complexity (two separate applications instead of one).
  - Tracing and observability need to span across Kafka rather than in-memory boundaries.
