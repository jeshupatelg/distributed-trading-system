# ADR 0002: Dynamic Row Replication and Multi-Panel Templates for Broker Connection Managers

## Status
Approved

## Context
As the trading platform scales to support multiple broker gateways (`connection-manager-alpaca`, `connection-manager-ibkr`, `connection-manager-coinbase`, or multi-region replicas), manually duplicating Grafana dashboard panels or maintaining separate dashboard files for each broker adapter creates severe maintenance overhead and violates DRY principles. 

We needed an architectural pattern that allows:
1. Dynamic discovery of all active broker connection manager instances.
2. Generating a complete, multi-panel monitoring suite (Ingest Rate, Egress Rate, Passthrough Efficiency, Trade Updates, CPU/RAM) for each broker instance automatically without manual dashboard edits.

## Decision
1. **Dynamic Prometheus Template Variable**:
   * Created a dynamic Grafana variable `$provider` backed by PromQL query:
     `label_values(connection_manager_ticks_broadcasted_total, instance)`
   * This query evaluates live Prometheus targets and automatically discovers all running connection manager containers.

2. **Row-Level Replication (`type: "row"`, `repeat: "provider"`)**:
   * Configured a Grafana Row Panel with `title: "Provider Gateway: ${provider}"` and set `repeat: "provider"`.
   * Enclosed a standardized multi-panel template beneath this row containing:
     - **Panel 0**: Broker Connection Liveness Status (`CONNECTED` / `DISCONNECTED`)
     - **Panel 1**: Ingest Rate (`Ticks Received / Sec`)
     - **Panel 2**: Egress Rate (`Ticks Broadcast / Sec`)
     - **Panel 3**: Drop Rate (`Ticks Lost / Sec`)
     - **Panel 4**: Passthrough Efficiency (%)
     - **Panel 5**: Tick Processing Latency (p95 / p99)
     - **Panel 6**: Order Updates Received (Kafka events)
     - **Panel 7**: Container CPU Utilization (%)
     - **Panel 8**: Container Memory Consumption (MB)

3. **Scoped Query Templating**:
   * Every metric query within the repeatable template scopes its PromQL expression using `{instance=~"${provider}"}` so each generated row panel strictly isolates metrics belonging to its target broker gateway.

## Consequences
* **Zero-Touch Scaling**: Deploying a new connection-manager adapter container automatically registers with Prometheus service discovery and triggers Grafana to instantiate a full, dedicated 5-panel row section for that broker instantly.
* **Standardized Observability**: Guarantees that every broker gateway adheres to the exact same monitoring view, threshold alerts, and health metrics across the enterprise.
* **Elimination of Panel Duplication**: Reduces Grafana JSON complexity by storing a single template definition that expands dynamically at runtime based on active infrastructure.
