# Observability (OBS) Stack Deployment Log

## Intent
Deployment run of the Observability (OBS) stack changes for the `distributed-trading-system`, migrating Prometheus configuration to dynamic container discovery.

---

## Touched Components Progress Matrix

| Component Name | Service Name (Docker) | Layer | State | Logs Verified? | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Telemetry** | `prometheus` | Observability | **ACTIVE** | Yes (Success) | Dynamic container discovery configured and Docker socket mounted. |
| **Gateway** | `connection-manager-alpaca`| Gateway | **ACTIVE** | Yes (Success) | Dynamic scrape labels added. |
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
