# ADR 0003: Frontend Proxy Gateway (BFF) Architecture for Client Applications

## Status
Approved

## Context
Client-facing user interfaces (such as web dashboards, future mobile applications, and trading terminals) require access to real-time risk metrics, emergency controls, order lifecycle feeds, and portfolio telemetry. Previously, operational access was handled by internal microservices directly or through direct database connections in Streamlit (`quant-dashboard`).

Allowing client frontends to connect directly to internal microservices (`order-processing-service`, `order-management-service`, `price-cache-service`, etc.) introduces severe security and architectural risks:
1. **Excessive Attack Surface**: Every internal service would require public host port exposure, individual TLS termination, CORS headers, rate limiting, and redundant authentication verification.
2. **Network Chattiness & Inefficiency**: Aggregating data across multiple microservices over external cellular or residential networks introduces latency and connection overhead.
3. **Decoupling Violation**: Internal refactoring (e.g., protocol migrations or schema adjustments) would directly break client applications.

## Decision
1. **Frontend Proxy Container (BFF - Backend For Frontend)**: Introduce a dedicated Node.js (TypeScript) `web-app` container acting as both the high-performance Trading Web Cockpit (React + Vite) and the Backend-For-Frontend proxy layer (Fastify).
2. **Strict Perimeter Isolation**: All client interfaces (web browsers, mobile PWA, future native apps) must communicate exclusively with the `web-app` container on its exposed host port (`3030`).
3. **Internal Microservices Remain Private**: Microservice endpoints for order execution, order management, price caching, and notifications must remain bound to internal Docker networks (`kafka_net` / internal bridge) and must never be exposed publicly.
4. **Mandatory User Approval for Endpoint Exposure**: Any requirement to open or expose a public port or endpoint on any other internal service requires explicit user permission.
5. **Data Aggregation & Streaming**: The BFF layer queries internal microservices concurrently, aggregates payloads, formats UI view models, and multiplexes real-time WebSocket streams for market ticks and order events. If any internal endpoint is not yet available, the proxy must provide a clean fallback/stub and request user approval before modifying backend services.

## Consequences
* **Hardened Security**: Only one single port (`3030`) is exposed externally for client interaction.
* **Optimal Client Performance**: Clients make unified, aggregated requests, eliminating multi-roundtrip network waterfalls.
* **Preserved Microservice Autonomy**: Core trading engines (Java OPS/OMS, Python gateways) remain focused on execution and risk logic without UI formatting or public authentication overhead.
