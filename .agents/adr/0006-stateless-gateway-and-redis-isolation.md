# ADR 0006: Stateless Broker Gateways and Isolated Redis Caching

## Status
Accepted

## Context
In the original High-Level Design (HLD) workflows, the Connection Manager was responsible for directly establishing sessions and writing account/cache configurations into the Shared Cache Layer (Redis) during boot (Step 2.a/b).

However, introducing direct cache writes from broker gateways couples them to downstream state engines and data layers. In alignment with our strict repository-level design rules (`AGENTS.md`), all broker gateways must remain completely stateless. They must focus exclusively on protocol translation (gRPC/REST) and live event forwarding, while all mutations to the RDBMS and Redis caches belong exclusively to the Order Management modules.

## Decision
1.  **Stateless Gateways**: Remove all direct Redis cache connection, initialization, and write interactions from the `connection-manager-alpaca` gateway and any future broker integration adapters.
2.  **Centralized Cache Management**: Assign the responsibility of initiating and managing account cache restoration in Redis solely to the **Combined Order Service (COS)**. COS will serve as the single source of truth for cache validation, triggering cache settlement and state loading during its bootstrap lifecycle or pre-order risk validation checks.

---

## Consequences

### Pros
*   **Clear State Separation**: Broker gateways remain simple, lightweight, and completely stateless.
*   **Centralized Mutation Rules**: Lock, margin, and position calculations are centralized inside COS, eliminating concurrent write collisions in Redis from different gateway services.
*   **Decoupled Infrastructure**: Gateway containers do not require Redis client libraries or Redis endpoint configurations.

### Cons
*   None.
