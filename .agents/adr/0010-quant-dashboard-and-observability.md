# ADR 0010: Quant Dashboard and Observability Installation

## Status
Accepted

## Context
As the distributed trading system scales, operator visibility is required to trace system performance and state. Specifically, the following views are needed:
1. **Graphical analysis** of account indicators (balance, equity, margins) and order stats (creation and fill rates).
2. **Provider View** showing the status/connectivity of stateless broker connection managers.
3. **Order View** displaying recent order entries and active processing status.
4. **Performance Details** of the system containers, Kafka lag, and JVM statistics.

Instead of writing a custom React/HTML/JS frontend and associated REST endpoints from scratch, we need a rapid, low-code tool that integrates cleanly with our Python-heavy components (like connection gateways and signal generators). Additionally, system telemetry should be handled by a dedicated, standard observability stack (Prometheus + Grafana) rather than ad-hoc custom logging.

## Decision
We will execute the following design:
1. **Quant Dashboard**: Built using **Streamlit** (Python).
   - Scoped strictly to **read-only / pull-based** actions for the initial release.
   - It will query current balance and positions directly from the **Redis Cache** layer.
   - It will query historical order records directly from the **RDBMS (SQL)** database.
   - We will implement explicit comments and disabled button stubs showing where future **push triggers** (e.g., calling gRPC methods to toggle strategies, placing new orders) can be wired.
2. **Observability Stack**:
   - Scope is restricted to **installation-only** at this stage.
   - We will deploy **Prometheus** and **Grafana** as containerized services in `docker-compose.yml`.
   - We will create a skeleton `config/observability/prometheus.yml` configuring Prometheus to scrape its own metrics and setting up placeholders for our other microservices.

## Consequences
* **Pros**:
  - **Speed**: Streamlit requires no custom HTML/CSS/JS or frontend-backend API bridging code.
  - **Simplicity**: Dashboard queries can reuse standard Python libraries (`redis-py`, `psycopg2`).
  - **Decoupled Architecture**: Observability is handled out-of-band by Prometheus scraping standard metrics endpoints rather than mixing business UI with system performance monitoring.
* **Cons**:
  - Streamlit re-runs scripts on user interaction, which isn't optimal for highly dynamic sub-second UI elements. However, this is perfectly suited for periodic pull-based metrics (e.g. 5-second refreshes) and general operators.
  - A pull-based read-only dashboard does not allow order cancellation or strategy hot-reloading yet (to be implemented via the designated enhancement hooks).
