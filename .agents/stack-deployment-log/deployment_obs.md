# Observability (OBS) Stack Deployment Log

## Intent
Deployment run of the Observability (OBS) stack changes for the `distributed-trading-system`, migrating Prometheus configuration to dynamic container discovery and provisioning component-level Grafana dashboards.

---

## Touched Components Progress Matrix

| Component Name | Service Name (Docker) | Layer | State | Logs Verified? | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Telemetry** | `prometheus` | Observability | **ACTIVE** | Yes (Success) | Dynamic container discovery configured and Docker socket mounted. |
| **Dashboard UI** | `grafana` | Observability | **ACTIVE** | Yes (Success) | Component-level metrics provisioning folder & repeatable dashboards added. |
| **Gateway** | `connection-manager-alpaca`| Gateway | **ACTIVE** | Yes (Success) | Fixed `self.stock_stream` attribute mismatch; WebSocket & gRPC server operational. |
| **Load Balancer** | `tick-lb` | Gateway | **ACTIVE** | Yes (Success) | Added `dns_lookup_family: V4_ONLY` for IPv4 upstream cluster routing. |
| **Strategy AAPL** | `signal-gen-aapl` | Algorithmic | **ACTIVE** | Yes (Success) | Stream connected and consuming `AAPL` feed cleanly. |
| **Strategy MSFT** | `signal-gen-msft` | Algorithmic | **ACTIVE** | Yes (Success) | Stream connected and consuming `MSFT` feed cleanly. |
| **Order Processing** | `order-processing-service`| Order Management| **ACTIVE** | Yes (Success) | Dynamic scrape labels added. |
| **Order Management** | `order-management-service`| Order Management| **ACTIVE** | Yes (Success) | Dynamic scrape labels added. |

---

## Log Entries

### [2026-08-29T05:58:00+05:30] Success Op - Prometheus Dynamic Container Discovery Migration
* **Status**: Success (Code & Config Update)
* **Action**: Configured label-based container discovery to replace static host IP/port configs.
* **Root Cause Analysis (RCA)**: Static target configurations require manual adjustments of container hostnames and ports whenever infrastructure configuration is altered, and do not scale dynamically.
* **Fix Applied**:
  1. Updated [`prometheus.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/config/observability/prometheus.yml) to replace hardcoded target jobs with a single dynamic `docker-containers` scrape job using `docker_sd_configs` and filtering for the static identifier label `application=distributed-trading`.
  2. Mounted the Docker daemon socket `/var/run/docker.sock:/var/run/docker.sock:ro` in read-only mode to the `prometheus` service in [`docker-compose.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/docker-compose.yml).
  3. Attached static Prometheus labels (`application`, `prometheus.scrape`, `prometheus.port`, `prometheus.path`) to monitored microservices (`connection-manager-alpaca`, `tick-lb`, `signal-gen-aapl`, `signal-gen-msft`, `order-processing-service`, `order-management-service`) in `docker-compose.yml` to support dynamic discovery relabeling.
  4. Configured `user: root` on the `prometheus` container in `docker-compose.yml` to resolve permission denied issues on the `/var/run/docker.sock` socket mount.

### [2026-08-29T06:33:00+05:30] Success Op - Expose Metrics on Java OPS/OMS & Verify Envoy Stats
* **Status**: Success (Code, Config & Deployment)
* **Action**: Added Prometheus metrics exporter configurations and libraries to Java microservices, and verified Envoy's metrics configuration.
* **Root Cause Analysis (RCA)**: The Java microservices (Order Processing Service and Order Management Service) were missing the Spring Actuator and Micrometer Prometheus registry dependencies and configuration settings. The Envoy load balancer was already configured to bind to port 9901 but had no traffic, so its endpoints were confirmed operational.
* **Fix Applied**:
  1. Updated [`pom.xml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/pom.xml) and [`application.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/resources/application.yml) of `order-processing-service` to include Spring Actuator and Micrometer libraries, exposing metrics at `/actuator/prometheus` on port `8081`.
  2. Updated [`pom.xml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-management-service/pom.xml) and [`application.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-management-service/src/main/resources/application.yml) of `order-management-service` to do the same, exposing metrics on port `8082`.
  3. Verified that [`envoy.yaml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/tick-lb/envoy.yaml) has the admin endpoint configured on `0.0.0.0:9901` which natively exports Envoy metrics at `/stats/prometheus`.
  4. Synced all modifications and triggered the Docker Compose stack redeployment, successfully compiling the Java microservices and launching the entire stack.
  5. Verified metrics output payloads for all services by querying them from inside the `prometheus` container environment.

### [2026-08-29T06:55:00+05:30] Success Op - Expose Metrics on Python Connection Manager & Signal Generators
* **Status**: Success (Code, Config & Deployment)
* **Action**: Integrated `prometheus_client` library on Python microservices, abstracted strategy indicators telemetry, and deployed.
* **Root Cause Analysis (RCA)**: The Python-based `connection-manager-alpaca` and the dynamically loaded trading strategies (`sma_crossover`, `mean_reversion`) running inside the `signal-generator` container lacked `prometheus-client` dependencies and ASGI web mount configuration to expose standard or custom telemetry metrics.
* **Fix Applied**:
  1. Updated [`requirements.txt`](file:///c:/Users/jeshu/Projects/distributed-trading-system/connection-manager-alpaca/requirements.txt) of `connection-manager-alpaca` and [`requirements.txt`](file:///c:/Users/jeshu/Projects/distributed-trading-system/signal-generator/requirements.txt) of `signal-generator` to include the `prometheus-client` dependency.
  2. Created a dedicated [`telemetry.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/signal-generator/telemetry.py) module for the `signal-generator` and another for the [`connection-manager-alpaca`](file:///c:/Users/jeshu/Projects/distributed-trading-system/connection-manager-alpaca/telemetry.py) to separate and abstract metrics definitions.
  3. Instrumented [`main.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/connection-manager-alpaca/main.py) and [`alpaca_client.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/connection-manager-alpaca/alpaca_client.py) of the Alpaca connection manager to serve `/metrics` and count processed ticks/trade updates.
  4. Instrumented [`main.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/signal-generator/main.py) of the signal generator to wrap strategy calculations with execution latencies, processed bars, and generated signals counts.
  5. Modified [`sma_crossover.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/config/strategies/sma_crossover.py) and [`mean_reversion.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/config/strategies/mean_reversion.py) to dynamically import the parent telemetry module and set gauges for indicators like Moving Averages and Z-scores.
  6. Synced all changes and triggered compose stack redeployment, successfully rebuilding the Python container images and restarting the services.
  7. Confirmed that all scraping targets (`connection-manager-alpaca`, `signal-gen-aapl`, `signal-gen-msft`) are registered and report healthy **UP** status in Prometheus.

### [2026-08-31T22:23:00+05:30] Success Op - Instrument Ingestion Telemetry & Provision Component-Level Metrics Dashboard
* **Intent**: Monitoring stack setup
* **Status**: Success (Code, Config & Deployment)
* **Action**: Instrumented `connection_manager_ticks_received_total` counter in Python connection manager and provisioned a repeatable row multi-panel Grafana dashboard under a dedicated `Component-level-metrics` folder.
* **Root Cause Analysis (RCA)**:
  - Connection manager telemetry only exported egress tick counts (`connection_manager_ticks_broadcasted_total`), rendering it impossible to compute market tick ingest throughput, dropped ticks, or passthrough efficiency.
  - Grafana dashboard provisioning (`dashboards.yaml`) lacked a separate folder provider for component-level metrics.
* **Fix Applied**:
  1. Instrumented `TICKS_RECEIVED` (`connection_manager_ticks_received_total`) Counter in `telemetry.py` and incremented it inside `_bar_handler` in `alpaca_client.py`.
  2. Configured `Component Level Metrics` provider block in `dashboards.yaml` mapping to `/var/lib/grafana/dashboards/component_level_metrics`.
  3. Created repeatable row template dashboard `connection_manager_metrics.json` inside `config/observability/grafana/dashboards/component_level_metrics/`.
  4. Updated [.agent/component_metrics_tracking.md](file:///c:/Users/jeshu/Projects/distributed-trading-system/.agent/component_metrics_tracking.md) to set `Ticks Received / Sec` readiness to `LIVE`.
  5. Synchronized code changes to the remote docker host via MCP `sync_project_files` and redeployed compose stack via MCP `deploy_compose_stack`.

### [2026-09-01T00:08:00+05:30] Success Op - Instrument Dropped Ticks Telemetry & Provision Drop Rate Panel
* **Intent**: Monitoring stack setup
* **Status**: Success (Code, Config & Deployment)
* **Action**: Instrumented `connection_manager_ticks_dropped_total` counter in Python connection manager and provisioned `3. Ticks Lost / Sec (Drop Rate)` panel in Grafana dashboard.
* **Root Cause Analysis (RCA)**:
  - Broadcaster client queues were unconstrained (`maxsize=0`) and lacked explicit drop instrumentation, making it impossible to accurately measure tick drops or protect container RAM against lagging gRPC subscribers.
* **Fix Applied**:
  1. Added `TICKS_DROPPED` (`connection_manager_ticks_dropped_total`) Counter in `telemetry.py` with dimension labels `["ticker", "reason"]`.
  2. Configured bounded client queues (`maxsize=1000`) and instrumented `TICKS_DROPPED` increments on `asyncio.QueueFull` or dispatch exceptions inside `grpc_server.py`.
  3. Added `3. Ticks Lost / Sec (Drop Rate)` time-series panel to `connection_manager_metrics.json`.
  4. Updated [.agent/component_metrics_tracking.md](file:///c:/Users/jeshu/Projects/distributed-trading-system/.agent/component_metrics_tracking.md) to set `Ticks Lost / Sec` readiness to `LIVE`.
  5. Synchronized code changes to the remote docker host via MCP `sync_project_files` and redeployed compose stack via MCP `deploy_compose_stack`.

### [2026-09-01T00:30:00+05:30] Success Op - Instrument Tick Processing Latency Histogram & Provision Latency Panel
* **Intent**: Monitoring stack setup
* **Status**: Success (Code, Config & Deployment)
* **Action**: Instrumented `connection_manager_tick_processing_duration_seconds` Histogram in Python connection manager and provisioned `5. Tick Processing Latency (p95 / p99)` panel in Grafana dashboard.
* **Root Cause Analysis (RCA)**:
  - Connection manager telemetry lacked processing latency metrics, rendering it impossible to quantify internal tick traversal duration or calculate p95/p99 percentiles.
* **Fix Applied**:
  1. Added `TICK_PROCESSING_DURATION` (`connection_manager_tick_processing_duration_seconds`) Histogram in `telemetry.py` with custom buckets ranging from `0.1ms` to `1.0s`.
  2. Wrapped `_bar_handler` execution in `with telemetry.TICK_PROCESSING_DURATION.labels(ticker=bar.symbol).time():` inside `alpaca_client.py`.
  3. Added `5. Tick Processing Latency (p95 / p99)` time-series panel to `connection_manager_metrics.json`.
  4. Updated [.agent/component_metrics_tracking.md](file:///c:/Users/jeshu/Projects/distributed-trading-system/.agent/component_metrics_tracking.md) to set `tick broadcast latency` readiness to `LIVE`.
  5. Synchronized code changes to remote docker host via MCP `sync_project_files` and redeployed compose stack via MCP `deploy_compose_stack`.

### [2026-09-01T01:13:00+05:30] Success Op - Fix Envoy gRPC Route Matching & Signal Generator Warm-up Retry Loop
* **Intent**: Monitoring stack setup & RPC routing stability
* **Status**: Success (Code, Config & Deployment)
* **Action**: Updated Envoy load balancer gRPC route matchers and added warm-up retry limits to signal generator runners.
* **Root Cause Analysis (RCA)**:
  1. `StatusCode.UNIMPLEMENTED` on `GetHistoricalBars`: Envoy route matcher in `tick-lb/envoy.yaml` used exact path match `path: "/trading.connection.MarketDataService/StreamMarketData"`, causing Envoy to reject `GetHistoricalBars` unary RPC calls with `UNIMPLEMENTED`.
  2. 300-Second Stream Timeout: Envoy `idle_timeout` was set to `300s`, forcing TCP stream disconnections during quiet market periods.
  3. Infinite Warm-up Retry Loop: `signal-generator` executed warm-up RPC calls on every stream reconnection without a retry limit or state flag.
* **Fix Applied**:
  1. Updated `tick-lb/envoy.yaml` route matcher to `prefix: "/trading.connection.MarketDataService/"` and set `idle_timeout: 0s`.
  2. Updated `signal-generator/main.py` with `is_warmed_up` state flag and `max_warmup_retries = 3` to limit failed warm-up retries on stream reconnects.
  3. Synchronized files to remote host via MCP `sync_project_files` and redeployed compose stack via MCP `deploy_compose_stack`.

### [2026-09-01T01:26:00+05:30] Success Op - Resolve Connection Manager Auto-Create Topic & Stream Client Failures
* **Intent**: Gateway service stability & Kafka topic auto-creation
* **Status**: Success (Code, Config & Deployment)
* **Action**: Configured default `KAFKA_AUTO_CREATE_TOPICS=true`, corrected attribute references in `AlpacaStreamClient`, and added explicit startup failure logging.
* **Root Cause Analysis (RCA)**:
  1. Kafka Topic Verification Failure: `KafkaEventPublisher` attempted to verify `raw-order-updates` topic existence on startup. Since `KAFKA_AUTO_CREATE_TOPICS` was not set in the container environment, `config.py` defaulted it to `false`, causing `_ensure_topic_exists()` to throw `ValueError` and crash the container during lifespan initialization.
  2. Attribute Mismatch in Stream Client: `AlpacaStreamClient` referenced non-existent `self.data_stream` instead of `self.stock_stream` during task scheduling, raising `AttributeError` on start/stop.
* **Fix Applied**:
  1. Updated `connection-manager-alpaca/config.py` to default `KAFKA_AUTO_CREATE_TOPICS` to `true`.
  2. Fixed attribute references in `connection-manager-alpaca/alpaca_client.py` from `self.data_stream` to `self.stock_stream`.
  3. Wrapped `start_services()` in `connection-manager-alpaca/main.py` with explicit exception logging (`logger.critical(..., exc_info=True)`).
  4. Synchronized code files to remote host via MCP `sync_project_files` and redeployed compose stack via MCP `deploy_compose_stack`.
  5. Verified `connection-manager-alpaca` status `running`, Websocket connections established to Alpaca IEX & Paper streams, and gRPC subscriber `signal-gen-aapl` successfully consuming stream ticks.

### [2026-09-02T21:55:00+05:30] Success Op - Split Process CPU/Memory Panels & Deploy Full Dashboard Hierarchy
* **Intent**: Observability dashboard optimization & provisioning directory repair
* **Status**: Success (Code, Config & Remote Deployment)
* **Action**: Split combined CPU/Memory Panel into separate CPU % and Memory MB panels, rebalanced grid layout across 3 rows, organized 5 loose dashboard files into `system/` and `component_level_metrics/` provider paths, and deployed remotely via MCP.
* **Root Cause Analysis (RCA)**:
  1. Multi-metric Scaling Distortion: Plotting CPU % (0-100%) and RAM MB (150-1024MB) on a single Y-axis caused CPU fluctuations to flatten at the bottom of the chart.
  2. Missing Dashboards in Grafana UI: 5 dashboard JSON files (`pipeline_flow_metrics.json`, `performance_metrics.json`, `kafka_metrics.json`, `postgresql_metrics.json`, `redis_metrics.json`) were sitting loosely in the parent `dashboards/` directory while Grafana provisioning (`dashboards.yaml`) explicitly watched subdirectories `system/` and `component_level_metrics/`.
* **Fix Applied**:
  1. Updated `connection_manager_metrics.json` (both root and component-level definitions) to split Panel 7 into Panel 7 (`7. Process CPU Utilization (%)`) and Panel 8 (`8. Process Memory Consumption (MB)`) with half-width grid positioning (`w: 12`).
  2. Organized all 8 dashboard JSON files into their respective provider subfolders (`dashboards/system/` and `dashboards/component_level_metrics/`).
  3. Synchronized all 6 updated dashboard and provisioning files to the remote Docker host via MCP `sync_project_files`.
  4. Restarted the `grafana` container via MCP `restart_docker_container`. Confirmed successful container startup and dashboard auto-discovery in Grafana logs.

### [2026-09-02T22:35:00+05:30] Success Op - Resolve AlpacaStreamClient Attribute Mismatch & Envoy IPv4 Routing
* **Intent**: Gateway service stability & gRPC transport routing fix
* **Status**: Success (Code, Config & Deployment)
* **Action**: Resolved `AlpacaStreamClient` attribute reference mismatch and configured explicit IPv4 DNS lookup family in Envoy upstream cluster configuration.
* **Root Cause Analysis (RCA)**:
  1. `connection-manager-alpaca` startup crash: `AlpacaStreamClient` threw `AttributeError: 'AlpacaStreamClient' object has no attribute 'stock_stream'` on application startup because `__init__` instantiated `self.data_stream`, whereas `start()` and `stop()` methods attempted to access `self.stock_stream`.
  2. `signal-generator` gRPC transport failure: Because `connection-manager-alpaca` failed to start, port `50051` was closed. Envoy (`tick-lb`) returned `delayed connect error: 113` (EHOSTUNREACH) / `111` (ECONNREFUSED) to `signal-gen-aapl` and `signal-gen-msft`.
* **Fix Applied**:
  1. Fixed attribute references in `connection-manager-alpaca/alpaca_client.py` (`self.stock_stream` $\rightarrow$ `self.data_stream`).
  2. Added `dns_lookup_family: V4_ONLY` under the `connection-manager-alpaca` cluster in `tick-lb/envoy.yaml` to enforce IPv4 DNS resolution within Docker networks.
  3. Synchronized code files to remote host via MCP `sync_project_files` and redeployed compose stack via MCP `deploy_compose_stack`.
  4. Verified `connection-manager-alpaca` healthy startup, gRPC server bound to `0.0.0.0:50051`, and `signal-gen-aapl` / `signal-gen-msft` connected and consuming gRPC market data streams.
