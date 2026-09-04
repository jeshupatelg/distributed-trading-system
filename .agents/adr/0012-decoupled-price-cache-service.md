# ADR 0012: Decoupled Out-of-Band Market Price Caching Service

## Status
Accepted

## Context
Previously, the Order Processing Service (`OPS`) updated the Redis reference price key (`market:last_price:<SYMBOL>`) inline inside `RiskManager.evaluateAndLock()` whenever a signal passed (or failed) pre-trade risk checks.

However, coupling Redis price mutations to order signal consumption introduced two significant operational gaps:
1. **Signal Rate vs. Tick Rate Disparity**: Trading strategy engines generate signals infrequently (e.g., once every N minutes or hours), while exchange market ticks stream continuously at high frequencies. Between signals, cached prices in Redis remained frozen.
2. **Stale Prices During Inactive Trading Periods**: If signals were rejected early by risk gates or if trading was quiet, `market:last_price:<SYMBOL>` was never updated. This distorted downstream pre-trade Fat-Finger & Price Collar sanity checks and portfolio equity / daily drawdown calculations in both `RiskManager` and the operator `Quant Dashboard`.

Furthermore, forcing hot-path order execution components (OPS) or cold-path reconciliation engines (OMS) to ingest raw market streams for cache updating violates domain isolation boundaries and risks CPU/thread starvation.

## Decision
1. **Decouple Price Writes from OPS**: Remove inline Redis reference price write mutations (`redisTemplate.opsForValue().set(lastPriceKey, price)`) from `RiskManager.java`. OPS operates strictly as a **pure reader** of Redis price data for pre-trade risk checks and portfolio equity valuation.
2. **Dedicated Out-of-Band Price Cache Microservice (`price-cache-service`)**: Introduce a dedicated, lightweight Python microservice responsible for continuously subscribing to gRPC market data streams (`MarketDataService/StreamMarketData`) from broker Connection Managers.
3. **Configurable In-Memory Micro-Batching & Throttling**: Buffer raw market ticks in local RAM (`Map<Symbol, LatestPrice>`) and flush deduplicated price updates to Redis using a single pipelined `MSET` payload. Make flush frequency (`FLUSH_INTERVAL_SEC`, default: `0.5s`) and batch size (`MAX_BATCH_SIZE`, default: `100`) fully configurable.
4. **Multi-Provider Discovery Strategy**: Align provider gateway discovery with `OPS` and `OMS` configuration standards by dynamically discovering environment variables matching `PROVIDER_<NAME>_ENDPOINT` (e.g., `PROVIDER_ALPACA_ENDPOINT=connection-manager-alpaca:50051`, `PROVIDER_X_ENDPOINT=connection-manager-x:50051`).

---

## Consequences

### Pros
* **Ultra-Low Latency & High Freshness**: `market:last_price:<SYMBOL>` in Redis is updated every 500ms directly from exchange tick streams, providing accurate reference prices for risk validation and telemetry without stale pricing gaps.
* **Deterministic Hot-Path Execution**: OPS order processing latency is decoupled from market tick processing, maintaining deterministic execution bounds.
* **Near-Zero Redis Load**: Micro-batching reduces 5,000+ individual Redis `SET` ops/sec down to 2 pipelined `MSET` ops/sec.
* **Blast Radius Isolation**: Out-of-band market data streaming failures will not crash or stall order submission (OPS) or database reconciliation (OMS).

### Cons
* Introduces one additional lightweight service container (`price-cache-service`) to deploy and monitor.
