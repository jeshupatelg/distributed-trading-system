# Configuration Specification: Quant Dashboard Service

This document defines the configuration schema, environment variable inputs, and network endpoints required by the `quant-dashboard` service.

---

## 1. Network & Deployment Ports

By default, the Streamlit service runs on port `8501`.

| Variable Name | Description | Default Value |
| :--- | :--- | :--- |
| `DASHBOARD_HOST` | Host binding for Streamlit server. | `0.0.0.0` |
| `DASHBOARD_PORT` | Port exposed by the container. | `8501` |

---

## 2. Infrastructure Mappings

The service requires read access to Redis (cache) and SQL (database).

### Redis Cache Config
Used to query live account balances and positions.

| Variable Name | Description | Default Value |
| :--- | :--- | :--- |
| `REDIS_HOST` | Hostname of the Redis deployment. | `redis-cache` |
| `REDIS_PORT` | Port of the Redis deployment. | `6379` |
| `REDIS_PASSWORD` | Password for Redis (if enabled). | `""` |

### SQL Database Config
Used to query order execution history and audit logs.

| Variable Name | Description | Default Value |
| :--- | :--- | :--- |
| `DB_HOST` | SQL Hostname. | `postgres-db` |
| `DB_PORT` | SQL Port. | `5432` |
| `DB_NAME` | Database containing trading history. | `trading_db` |
| `DB_USER` | Read-only SQL username. | `dashboard_reader` |
| `DB_PASSWORD` | Database user password. | `read_pass` |

---

## 3. Future Extension Config (Push Triggers)
These variables are documented for the future rollout of order triggers and strategy configuration updates.

| Variable Name | Description | Default Value |
| :--- | :--- | :--- |
| `CONNECTION_MANAGER_ENDPOINT` | Target gRPC host/port of Envoy load balancer proxy. | `tick-lb:50051` |
| `KAFKA_BOOTSTRAP_SERVERS` | Core message broker endpoints for dispatching write alerts. | `kafka:9092` |
