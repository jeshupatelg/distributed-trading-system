# Stack Deployment Log - v1.0.0

## Intent
First deployment of the `distributed-trading-system` microservices stack.

---

## Touched Components Progress Matrix

| Component Name | Service Name (Docker) | Layer | State | Logs Verified? | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Database** | `homeserver-pg` | Infrastructure | **ACTIVE** | Yes (Success) | PostgreSQL verified healthy and running. |
| **Cache** | `homeserver-redis` | Infrastructure | **ACTIVE** | Yes (Success) | Redis verified healthy and running. |
| **Message Broker** | `kafka` | Infrastructure | **ACTIVE** | Yes (Success) | Kafka verified healthy and running on `kafka_net`. |
| **Trading Agent (Old)**| `trading-agent` | Legacy | **STOPPED** | Yes (Success) | Stopped to make room for new stack deployment. |
| **Gateway** | `connection-manager-alpaca`| Gateway | *PENDING* | No | Not yet deployed. |
| **Load Balancer** | `tick-lb` | Gateway | *PENDING* | No | Not yet deployed. |
| **Strategy AAPL** | `signal-gen-aapl` | Algorithmic | *PENDING* | No | Not yet deployed. |
| **Strategy MSFT** | `signal-gen-msft` | Algorithmic | *PENDING* | No | Not yet deployed. |
| **Order Processing** | `order-processing-service`| Order Management| *PENDING* | No | Not yet deployed. |
| **Order Management** | `order-management-service`| Order Management| *PENDING* | No | Not yet deployed. |
| **Telemetry** | `prometheus` | Observability | *PENDING* | No | Not yet deployed. |
| **Visualization** | `grafana` | Observability | *PENDING* | No | Not yet deployed. |
| **Dashboard** | `quant-dashboard` | UI / Frontend | *PENDING* | No | To be deployed last. |

---

## Log Entries

### [2026-08-21T03:35:47+05:30] Success Op - Stop Old Trading Agent
* **Status**: Success
* **Action**: Stopped the legacy `trading-agent` container to prepare for the new deployment stack.
* **Output / Verification**:
  ```
  Success: Container 'trading-agent' has been stopped.
  ```

### [2026-08-21T03:47:00+05:30] Deployment Execution Initiated
* **Status**: Starting deployment run.
* **Tasks completed**: 
  * Defined dependency matrix in [deployment_dependency_matrix.md](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/hld/deployment_dependency_matrix.md).
* **Next step**: Verify access/status of Core Infrastructure containers (`homeserver-pg`, `homeserver-redis`, `kafka`).

### [2026-08-21T03:47:05+05:30] Blocked - Missing Whitelist Permissions in MCP Gate
* **Status**: PAUSED / ERROR
* **Action attempted**: Getting logs/status of container `app-database` using MCP tool `get_container_logs`.
* **Error Encountered**: 
  ```
  permission check failed for mcp "remote-docker-gate/get_container_logs": user denied permission for mcp(remote-docker-gate/get_container_logs).
  Alternative instruction: "access for pg/redis/kafka not present in whitelist containers. log upto this point and we'll continue after i add them in whitelist."
  ```
* **Root Cause Analysis (RCA)**:
  The `remote-docker-gate` MCP configuration on the remote host (`gate.yaml`) did not whitelist or grant the necessary permissions for the database (`app-database`/Postgres), cache (`app-cache`/Redis), or message broker (`kafka`) containers to the MCP server. Therefore, the agent could not query, inspect, or manage their deployment status.
* **Potential Fix**:
  The user must update the `gate.yaml` configuration on the remote Docker daemon host to whitelist database, cache, and kafka with appropriate permissions.

### [2026-08-21T03:55:09+05:30] Whitelist Updated & Verification Initiated
* **Status**: Success (Verification)
* **Action**: User updated `gate.yaml` with the correct container names: `homeserver-pg`, `homeserver-redis`, and `kafka`.
* **Sub-tasks completed**:
  * Verified updated whitelist using `list_whitelisted_containers`.
  * Checked logs of the three infrastructure containers.

### [2026-08-21T03:56:00+05:30] Success Op - Core Infrastructure Log Verification
* **Status**: Success
* **Action**: Fetched and verified logs for `homeserver-pg`, `homeserver-redis`, and `kafka` to confirm healthy operational status.
* **Output / Verification**:
  * **`homeserver-pg` Logs**:
    ```
    2026-08-20 19:55:49.194 UTC [35] LOG:  checkpoint starting: time
    2026-08-20 19:55:49.611 UTC [35] LOG:  checkpoint complete: wrote 5 buffers (0.0%); 0 WAL file(s) added...
    ```
  * **`homeserver-redis` Logs**:
    ```
    1:M 12 Aug 2026 09:36:55.676 * Ready to accept connections tcp
    ```
  * **`kafka` Logs**:
    ```
    [2026-08-20 21:38:26,119] INFO [SnapshotGenerator id=1] Creating new KRaft snapshot file...
    [2026-08-20 21:38:26,123] INFO [SnapshotEmitter id=1] Successfully wrote snapshot...
    ```

### [2026-08-21T04:05:20+05:30] Deployment FAILED - Timeout & MCP Server Offline
* **Status**: FAILED
* **Action attempted**: Deploying the stack (excluding `quant-dashboard`) using `deploy_compose_stack`.
* **Error Encountered**:
  ```
  Encountered error in tool execution: MCP tool call to server "remote-docker-gate" timed out after 3m0s: context deadline exceeded
  ```
  Followed by:
  ```
  Encountered error in tool execution: server name remote-docker-gate failed to load: exit status 1
  ```
* **Root Cause Analysis (RCA)**:
  1. **Deployment Timeout**: The docker-compose file specifies building multiple microservices from source (including Java Spring Boot and Python FastAPI applications). Compiling and downloading dependencies inside the remote Docker container environment is resource-intensive and exceeded the 3-minute MCP tool execution timeout.
  2. **MCP Server Offline (Exit Status 1)**: The build process likely exhausted the remote host's resources (CPU/Memory), causing the Docker daemon to freeze or the kernel OOM killer to terminate the `mcp-host-container`. Alternatively, the timeout occurred during a write operation to `gate.yaml`, leaving it in a corrupted state.
* **Potential Fixes**:
  1. **Restart MCP Container**: SSH into the remote host (`192.168.29.96`) and run `docker start mcp-host-container` to restart the MCP server.
  2. **Inspect & Fix `gate.yaml`**: Check the logs of `mcp-host-container` (`docker logs mcp-host-container`) to ensure `gate.yaml` is not corrupted.
  3. **Use Pre-built Images**: Modify `docker-compose.yml` to pull pre-built images from a container registry instead of building from context, eliminating compile-time resource exhaustion on the host.

### [2026-08-21T04:21:40+05:30] Deployment FAILED - Context Path Not Found (Reproduced)
* **Status**: FAILED
* **Action attempted**: Deploying the stack (excluding `quant-dashboard`) using `deploy_compose_stack` (reproduced after MCP server recovery).
* **Error Encountered**:
  ```
  Docker Compose command failed:
  Stdout: 
  Stderr: time="2026-08-20T22:49:40Z" level=warning msg="The \"ALPACA_API_KEY\" variable is not set. Defaulting to a blank string."
  time="2026-08-20T22:49:40Z" level=warning msg="The \"ALPACA_SECRET_KEY\" variable is not set. Defaulting to a blank string."
  time="2026-08-20T22:49:40Z" level=warning msg="/tmp/compose-distributed-trading-system/docker-compose.yml: the attribute `version` is obsolete, it will be ignored, please remove it to avoid potential confusion"
  time="2026-08-20T22:49:40Z" level=warning msg="Docker Compose requires buildx plugin to be installed"
   Image compose-distributed-trading-system-connection-manager-alpaca Building 
  unable to prepare context: path "/tmp/compose-distributed-trading-system/connection-manager-alpaca" not found
  ```
* **Root Cause Analysis (RCA)**:
  The `deploy_compose_stack` MCP tool only takes `compose_content` as a string and writes it to `/tmp/compose-distributed-trading-system/docker-compose.yml`. However, the compose file contains `build` attributes with relative paths (e.g., `context: ./connection-manager-alpaca`). Since these source code folders do not exist in the temporary `/tmp/compose-distributed-trading-system/` directory on the remote host, `docker compose` fails to resolve the build context and immediately aborts the deployment.
* **Potential Fixes**:
  1. **Pre-build Images**: Modify `docker-compose.yml` to pull pre-built images from a container registry (e.g. `image: myregistry/connection-manager-alpaca:latest`) and remove all `build:` configuration blocks.
  2. **Install Buildx**: Install the `docker-buildx-plugin` on the remote host to resolve the buildx warning.
