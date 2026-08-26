# Stack Deployment Log - v2.0.0

## Intent
Second deployment run of the `distributed-trading-system` microservices stack, using the updated remote MCP server and the remote repository located at `/home/jeshu/dist-trading-sys/distributed-trading-system` on the host.

---

## Touched Components Progress Matrix

| Component Name | Service Name (Docker) | Layer | State | Logs Verified? | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Database** | `homeserver-pg` | Infrastructure | **ACTIVE** | Yes (Success) | PostgreSQL verified healthy and running. Mismatched user credentials identified & fixed. |
| **Cache** | `homeserver-redis` | Infrastructure | **ACTIVE** | Yes (Success) | Redis verified healthy and running. |
| **Message Broker** | `kafka` | Infrastructure | **ACTIVE** | Yes (Success) | Kafka verified healthy and running on `kafka_net`. |
| **Gateway** | `connection-manager-alpaca`| Gateway | **ACTIVE** | Yes (Success) | Streaming live ticker data to gRPC consumers. |
| **Load Balancer** | `tick-lb` | Gateway | **ACTIVE** | Yes (Success) | Envoy configured and routing gRPC data streams successfully. |
| **Strategy AAPL** | `signal-gen-aapl` | Algorithmic | **ACTIVE** | Yes (Success) | Subscribed and processing AAPL tick stream. |
| **Strategy MSFT** | `signal-gen-msft` | Algorithmic | **ACTIVE** | Yes (Success) | Subscribed and processing MSFT tick stream. |
| **Order Processing** | `order-processing-service`| Order Management| **ACTIVE** | Yes (Success) | Compiled and started successfully. Subscribed to signal streams. |
| **Order Management** | `order-management-service`| Order Management| **ACTIVE** | Yes (Success) | Database connection verified, scheduled cron running successfully. |
| **Telemetry** | `prometheus` | Observability | **ACTIVE** | Yes (Success) | Reconfigured to host port 9091. Running successfully. |
| **Visualization** | `grafana` | Observability | **ACTIVE** | Yes (Success) | Running successfully. |
| **Dashboard** | `quant-dashboard` | UI / Frontend | **ACTIVE** | Yes (Success) | Deployed last, running Streamlit web server. |

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
* **Output / Verification**:
  * All containers built, running, and whitelisted.
  * **`order-processing-service`**: Started successfully, listening on port 8081.
  * **`order-management-service`**: Connected to database `trading_agent` successfully and executed reconciliation jobs on port 8082.
  * **`quant-dashboard`**: Streamlit application started successfully on port 8501.
  * **`connection-manager-alpaca`**: Fetching live ticks from Alpaca and streaming over gRPC.
  * **`signal-gen-aapl`**: Consuming stream and processing strategy calculations.
