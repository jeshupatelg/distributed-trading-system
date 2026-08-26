# Stack Deployment Log - v2.0.0

## Intent
Second deployment run of the `distributed-trading-system` microservices stack, using the updated remote MCP server and the remote repository located at `/home/jeshu/dist-trading-sys/distributed-trading-system` on the host.

---

## Touched Components Progress Matrix

| Component Name | Service Name (Docker) | Layer | State | Logs Verified? | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Database** | `homeserver-pg` | Infrastructure | **ACTIVE** | Yes (Success) | PostgreSQL verified healthy and running. Mismatched user credentials identified & fixed. |
| **Cache** | `homeserver-redis` | Infrastructure | **ACTIVE** | Yes (Success) | Redis verified healthy and running. |
| **Message Broker** | `kafka` | Infrastructure | **ACTIVE** | Yes (Success) | Kafka verified healthy and running on `kafka_net`. Checked dynamically. |
| **Gateway** | `connection-manager-alpaca`| Gateway | **ACTIVE** | Yes (Success) | Streaming live ticker data to gRPC consumers. |
| **Load Balancer** | `tick-lb` | Gateway | **ACTIVE** | Yes (Success) | Envoy configured and routing gRPC data streams successfully. |
| **Strategy AAPL** | `signal-gen-aapl` | Algorithmic | **ACTIVE** | Yes (Success) | Subscribed and processing AAPL tick stream. |
| **Strategy MSFT** | `signal-gen-msft` | Algorithmic | **ACTIVE** | Yes (Success) | Subscribed and processing MSFT tick stream. |
| **Order Processing** | `order-processing-service`| Order Management| **ACTIVE** | Yes (Success) | Compiled and started successfully. Subscribed to signal streams. |
| **Order Management** | `order-management-service`| Order Management| **ACTIVE** | Yes (Success) | Database connection verified, scheduled cron running successfully. |
| **Telemetry** | `prometheus` | Observability | **ACTIVE** | Yes (Success) | Reconfigured to host port 9091. Running successfully. |
| **Visualization** | `grafana` | Observability | **ACTIVE** | Yes (Success) | Running successfully. Embedding and anonymous auth enabled. |
| **Dashboard** | `quant-dashboard` | UI / Frontend | **ACTIVE** | Yes (Success) | Added separate System Control Center page. Dynamic Kafka health check running. Embedded Grafana iframe. |

---

## Log Entries

### [2026-08-25T01:58:30+05:30] Success Op - remote-docker-gate_non_cont Verification
* **Status**: Success
* **Action**: Verified the `remote-docker-gate_non_cont` MCP server is active by listing whitelisted containers.

### [2026-08-25T01:58:35+05:30] Success Op - Core Infrastructure Log Verification
* **Status**: Success
* **Action**: Fetched and verified logs for `homeserver-pg`, `homeserver-redis`, and `kafka` to confirm healthy operational status.

### [2026-08-25T02:00:17+05:30] Deployment FAILED - MCP Tool Permission Timeout
* **Status**: FAILED
* **Action attempted**: Deploying the stack (excluding dashboard) by calling `deploy_compose_stack` with project name `distributed-trading-system` and modified compose content.

### [2026-08-25T02:04:00+05:30] Dockerfile Fixes Implemented (Local Workspace)
* **Status**: Resolved (Code Fix)
* **Action**: Pushed fixes locally and pushed to GitHub for maven dependencies build.

### [2026-08-25T02:13:56+05:30] Remote Repository Synced
* **Status**: Success (Code Pull)
* **Action**: User successfully executed `git pull` on the remote host in `/home/jeshu/dist-trading-sys/distributed-trading-system` to sync the Dockerfile build fixes.

### [2026-08-25T02:15:36+05:30] Deployment FAILED - MCP Tool Timeout (Context Deadline Exceeded)
* **Status**: FAILED
* **Action attempted**: Called `deploy_compose_stack` directly from parent agent with `project_dir` set to the remote repository.

### [2026-08-25T02:25:22+05:30] Deployment FAILED - MCP Connection Dropped (EOF / Server Crash)
* **Status**: FAILED
* **Action attempted**: Retried `deploy_compose_stack` to leverage Docker build cache layers.

### [2026-08-25T02:31:00+05:30] Host Build FAILED - Port 9090 In Use (Prometheus)
* **Status**: FAILED
* **Action attempted**: User ran `docker compose up --build -d` directly on the host machine.
* **Fix Applied**: Updated the host port mapping for `prometheus` in `docker-compose.yml` from `9090:9090` to `9091:9090` to resolve the port conflict. Pushed the fix to GitHub.

### [2026-08-25T02:40:00+05:30] Deployment FAILED - Envoy Config Initialization Error (tick-lb)
* **Status**: FAILED
* **Action attempted**: Started Envoy container after port remapping.
* **Fix Applied**: Updated `tick-lb/envoy.yaml` to change the resource monitor name to `"envoy.resource_monitors.global_downstream_max_connections"`. Pushed the fix to GitHub.

### [2026-08-25T02:47:00+05:30] DB Connection FAILED - Postgres Role Mismatch
* **Status**: FAILED
* **Action attempted**: `order-management-service` connection to remote database.
* **Fix Applied**: Updated database configurations in `docker-compose.yml` for `order-management-service` and `quant-dashboard` to map to `trading_agent` with user `admin`/`admin`. Pushed the updates to GitHub.

### [2026-08-27T00:45:00+05:30] Success Op - Project-Name Migration Verification
* **Status**: Success (Verification)
* **Action**: Checked the updated MCP server configurations and schemas. Verified that project-name based mapping is active and functional.

### [2026-08-27T00:49:00+05:30] Success Op - Compose Deployment Started (Excluding Dashboard)
* **Status**: Success (Partially Operational)
* **Action**: Deployed the stack (excluding dashboard) via MCP server.

### [2026-08-27T00:51:00+05:30] Java Services FAILED - UnsatisfiedDependencyException
* **Status**: FAILED (Crash Loop)
* **Action attempted**: Started Java services (`order-processing-service` and `order-management-service`).
* **Fix Applied**:
  1. Updated `libs/shared-models/pom.xml` to include Spring context, Spring Kafka, and Jackson dependencies with `provided` scope.
  2. Created [`SharedAppConfig.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/libs/shared-models/src/main/java/com/trading/shared/config/SharedAppConfig.java) in `shared-models` to define `ObjectMapper`, `KafkaTemplate`, `ConsumerFactory`, and `ConcurrentKafkaListenerContainerFactory` beans, dynamically injecting `groupId` from client microservices values.
  3. Imported `SharedAppConfig` in [`OrderProcessingApplication.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/java/com/trading/ops/OrderProcessingApplication.java) and [`OrderManagementApplication.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-management-service/src/main/java/com/trading/oms/OrderManagementApplication.java).

### [2026-08-27T01:04:00+05:30] Success Op - In-Place Remote Host Sync
* **Status**: Success (In-Place Sync)
* **Action**: Synchronized 6 modified Java configuration and pom files directly to the remote repository on the host using the `sync_project_files` tool. Synchronized the `docker-compose.yml` file.

### [2026-08-27T01:09:47+05:30] Success Op - Full Stack Deployment Completed
* **Status**: Success (Fully Operational)
* **Action**: Called `deploy_compose_stack` via MCP mapping to deploy the full stack.

---

## Post-Deploy Hotfix Log entries (UI/Dashboard Iterations)

### [2026-08-27T01:27:00+05:30] Hotfix 1: Qualitative Color Sequence AttributeError (app.py)
* **Issue**: Streamlit crashed when loading the Assets & Portfolio page.
* **Root Cause Analysis (RCA)**: The Plotly Express interface on line 153 attempted to use `px.colors.qualitative.Slate` which does not exist in the qualitative color module of `_plotly_utils`.
* **Fix Applied**: Changed the qualitative color scheme sequence to `px.colors.qualitative.Set2` in [`quant-dashboard/app.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/quant-dashboard/app.py).

### [2026-08-27T01:28:44+05:30] Hotfix 2: Plotly Bar Chart Empty Dataframe ValueError (app.py)
* **Issue**: Streamlit crashed again on load when no positions were present in the Redis cache.
* **Root Cause Analysis (RCA)**: If the Redis keyspace contains no keys matching `position:*`, the `positions` dictionary evaluates to `{}`. Consequently, `pd.DataFrame()` initialized an empty frame with no column headings, causing `px.bar` to crash with `ValueError: Value of 'x' is not the name of a column in 'data_frame'. Expected one of [] but received: Ticker`.
* **Fix Applied**: Added a check `if positions:` to guard the `px.bar` call, falling back to a Streamlit information notification (`st.info("No active positions found in the cache.")`) if the positions list is empty.

### [2026-08-27T01:32:00+05:30] Hotfix 3: SQL Order History Column Mismatch (app.py)
* **Issue**: The Order History page threw a database exception: `column "ticker" does not exist`.
* **Root Cause Analysis (RCA)**: The dashboard SQL query select statement expected the column names `ticker`, `quantity`, and `timestamp` from `tracked_orders`. However, checking the database schema inside the PostgreSQL container via `psql -c "\d tracked_orders"` revealed the actual columns are `symbol`, `qty`, and `created_at`.
* **Fix Applied**: Updated the SQL query statement to select columns using aliases mapping to the expected variable names: `SELECT order_id, symbol AS ticker, qty AS quantity, side, status, created_at AS timestamp, limit_price FROM tracked_orders ORDER BY created_at DESC LIMIT 100;`.

### [2026-08-27T01:35:00+05:30] Hotfix 4: Sidebar Missing Kafka Status & Dedicated Control Center Page
* **Issue**: The System Control Center section in the dashboard sidebar lacked an active status check for the Kafka message broker, and the user requested a separate dedicated page.
* **Root Cause Analysis (RCA)**: Since the dashboard lacks a Kafka library wrapper, connectivity was not tracked. Furthermore, the widgets were cramped inside the sidebar.
* **Fix Applied**:
  1. Implemented a lightweight, socket-based connection check function `check_kafka_connection()` in `app.py` that queries `KAFKA_BOOTSTRAP_SERVERS` directly on port `9092` with a 2-second timeout (cached with a 10-second TTL to prevent overhead).
  2. Added the status indicators in the sidebar for Redis, PostgreSQL, and Kafka.
  3. Created a dedicated `"System Control Center"` page as the default homepage option, featuring status cards showing host details and online/offline indicators for all three infrastructure dependencies.

### [2026-08-27T01:38:00+05:30] Hotfix 5: Sidebar Status Cleanup & Kafka Offline Status Fix
* **Issue**:
  1. Sidebar connection indicators were redundant now that the dedicated Control Center page was in place.
  2. The Kafka Broker showed as offline on the Control Center page.
* **Root Cause Analysis (RCA)**:
  1. Status widgets occupied significant sidebar space and were redundant.
  2. The dashboard container lacked the `KAFKA_BOOTSTRAP_SERVERS` environment variable, falling back to `localhost:9092` which inside the container could not resolve the Kafka broker.
* **Fix Applied**:
  1. Removed the subheader and Redis/DB/Kafka connection status alerts from the sidebar in [`quant-dashboard/app.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/quant-dashboard/app.py#L79-L98).
  2. Added `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` to the environment block of the `quant-dashboard` service in [`docker-compose.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/docker-compose.yml#L176).

### [2026-08-27T01:42:00+05:30] Hotfix 6: Native Grafana UI Embedding (Iframe)
* **Issue**: Telemetry page loaded static mock chart plots rather than the live operational dashboards.
* **Root Cause Analysis (RCA)**: Embedding Grafana via standard iframe results in cross-origin blocks (Clickjacking guards) and user login prompts unless embedding and anonymous reader policies are configured.
* **Fix Applied**:
  1. Updated the `grafana` service in [`docker-compose.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/docker-compose.yml#L198) to inject environment configurations: `GF_SECURITY_ALLOW_EMBEDDING=true`, `GF_AUTH_ANONYMOUS_ENABLED=true`, and `GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer`.
  2. Replaced the telemetry mock metrics logic in [`quant-dashboard/app.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/quant-dashboard/app.py#L366-L376) to call `st.components.v1.iframe(grafana_url)` targeting the user's mapped local Grafana interface (`http://localhost:3000`).
