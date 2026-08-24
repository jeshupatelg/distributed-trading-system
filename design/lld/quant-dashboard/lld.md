# Low-Level Design (LLD) Document: Quant Dashboard Service

This document defines the class structure, modules, read-only cache-database queries, and future execution hooks of the `quant-dashboard` service.

---

## 1. Component Overview

The `quant-dashboard` is designed as a modular **Streamlit** (Python) web application. Streamlit handles both the UI elements (widgets, layout, and plots) and user interactions on a unified Python backend. 

To ensure safety and performance isolation, the dashboard is **strictly read-only** for its initial release, querying Redis and SQL databases to fetch states without committing mutations.

```
+-------------------------------------------------------------+
|                       Streamlit UI                          |
|  +----------------+  +----------------+  +----------------+ |
|  | PortfolioView  |  |   OrderView    |  |  ProviderView  | |
|  +----------------+  +----------------+  +----------------+ |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                     Data Connection Layer                   |
|  +----------------+  +----------------+  +----------------+ |
|  |  RedisClient   |  |   DBClient     |  |   gRPCClient   | |
|  | (Pull Cache)   |  | (Pull History) |  | (Push Stub)    | |
|  +----------------+  +----------------+  +----------------+ |
+------------------------------+------------------------------+
```

---

## 2. Component Design Details

### A. AppLayout (`app.py`)
- Sets up multi-page navigation (Account, Orders, Providers, Telemetry).
- Instantiates database connection pools during startup using `@st.cache_resource` to prevent connection exhaustion.
- Drives the main pull-based polling loop (using Streamlit’s periodic refresh or `st.rerun()` with a sleep duration, e.g., 5 seconds).

### B. PortfolioView
- **Data Pull**: Calls `RedisClient` to pull live positions and cash metrics.
- **Operations**:
  - Pulls string value of key `account:balance`.
  - Pulls string value of key `account:blocked_margin`.
  - Reads keys matching position hashes `position:*` (e.g. `position:AAPL`, `position:MSFT`) representing tick allocations.
- **Visuals**: Plots dynamic pie charts using Plotly to show equity distribution.

### C. OrderView
- **Data Pull**: Calls `DBClient` to query the relational database for recent orders.
- **Query**:
  ```sql
  SELECT order_id, ticker, quantity, side, status, timestamp, filled_qty, limit_price
  FROM tracked_orders
  ORDER BY timestamp DESC
  LIMIT 100;
  ```
- **Visuals**: Displays data in an interactive table (`st.dataframe`) supporting pagination, column sorting, and filter text boxes (filtering by status, ticker, or buy/sell side).

### D. ProviderView
- **Data Pull**: Checks connectivity status of connection managers.
- **Initial Scope**: Sends HTTP health check probes (`GET /health`) or parses available system logs to determine if `connection-manager-alpaca` is healthy.

---

## 3. Read-Only Guard & State Isolation
To enforce the **Stateless Gateway** and **Order Management State Isolation** rule (RDBMS mutations belong strictly to OMS):
- **User Role**: The dashboard connects to the PostgreSQL database using a dedicated read-only database user credentials (`dashboard_user`) with permissions limited to `SELECT` on specific tables.
- **No SQL Mutations**: No `INSERT`, `UPDATE`, or `DELETE` statements exist in the dashboard query files.
- **Local Cache Only**: Streamlit session state stores layout choices but does not store or modify transactional data locally.

---

## 4. Future Extension: Push Triggers (Enhancement Hooks)
While currently scoped as read-only, the dashboard structure includes specific placeholder files and functions to allow future write actions:

### A. Strategy Controls (Start/Stop/Edit)
- **Stub Location**: `actions/strategy_triggers.py`
- **Design Pattern**:
  - UI displays input inputs (`fast_period`, `slow_period`) and a Toggle button.
  - When toggled, the handler triggers a gRPC client wrapper.
  - The client submits a `UpdateStrategyRequest` payload containing strategy configurations to the Envoy load-balancer endpoint (`tick-lb:50051`) dynamically.

### B. Manual Order Trigger
- **Stub Location**: `actions/order_submission.py`
- **Design Pattern**:
  - UI renders an order execution form (Ticker, Quantity, Side, Limit/Market).
  - Clicking "Submit Order" calls a gRPC Unary method on `Order Processing Service (OPS)` to request pre-order validation and execution.
  - Direct DB mutations are avoided; it relies entirely on OPS to publish `order-create-event` to Kafka.
