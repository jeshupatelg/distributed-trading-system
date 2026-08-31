# Observability (OBS) Stack Deployment Log

## Intent
Deployment run of the Observability (OBS) stack changes for the `distributed-trading-system`, migrating Prometheus configuration to dynamic container discovery and provisioning component-level Grafana dashboards.

---

## Touched Components Progress Matrix

| Component Name | Service Name (Docker) | Layer | State | Logs Verified? | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Telemetry** | `prometheus` | Observability | **ACTIVE** | Yes (Success) | Dynamic container discovery configured and Docker socket mounted. |
| **Dashboard UI** | `grafana` | Observability | **ACTIVE** | Yes (Success) | Component-level metrics provisioning folder & repeatable dashboards added. |
| **Gateway** | `connection-manager-alpaca`| Gateway | **ACTIVE** | Yes (Success) | Ingest (`ticks_received`), broadcast, and dropped ticks telemetry instrumented. |
| **Load Balancer** | `tick-lb` | Gateway | **ACTIVE** | Yes (Success) | Dynamic scrape labels added. |
| **Strategy AAPL** | `signal-gen-aapl` | Algorithmic | **ACTIVE** | Yes (Success) | Dynamic scrape labels added. |
| **Strategy MSFT** | `signal-gen-msft` | Algorithmic | **ACTIVE** | Yes (Success) | Dynamic scrape labels added. |
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
