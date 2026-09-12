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
| **Visualization** | `grafana` | Observability | **ACTIVE** | Yes (Success) | Configured on network `gateway_net` for API Gateway routing. |
| **Dashboard** | `quant-dashboard` | UI / Frontend | **ACTIVE** | Yes (Success) | Configured with subpath `/dashboard/` on network `gateway_net`. |

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

### [2026-08-27T01:53:00+05:30] Hotfix 7: Spring Cloud API Gateway Subpath & Network Integration
* **Issue**: Dashboard iframe was pointing to `http://localhost:3000`, requiring port forwarding from user endpoints and failing when exposed via the Spring Cloud API Gateway.
* **Root Cause Analysis (RCA)**: To route both the dashboard and Grafana through the remote gateway without explicit external port exposures, they must join the same external `gateway_net` network and support base url subpaths `/dashboard` and `/grafana`.
* **Fix Applied**:
  1. Attached both `quant-dashboard` and `grafana` containers to the external `gateway_net` network in [`docker-compose.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/docker-compose.yml#L179-L219).
  2. Set `STREAMLIT_SERVER_BASE_URL_PATH=dashboard` in the environment block of `quant-dashboard` in `docker-compose.yml`.
  3. Changed `grafana_url` inside [`quant-dashboard/app.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/quant-dashboard/app.py#L370) to use a relative subpath URL `"/grafana/"` so the browser resolves it relative to the gateway address automatically.

### [2026-08-30T03:07:00+05:30] Hotfix 8: Grafana Subpath Routing Mismatch
* **Issue**: Accessing Grafana through the gateway subpath `/grafana` returned the error `If you're seeing this Grafana has failed to load its application files`.
* **Root Cause Analysis (RCA)**: Grafana was not configured to serve from a subpath or run with the `/grafana/` root URL inside its docker container environment. Consequently, it expected traffic on the root path `/` and generated absolute asset links, leading to Javascript loading failures in the client's browser.
* **Fix Applied**:
  1. Configured the Grafana service in [`docker-compose.yml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/docker-compose.yml) to serve from the subpath by injecting variables `GF_SERVER_ROOT_URL=/grafana/` and `GF_SERVER_SERVE_FROM_SUB_PATH=true`.
  2. Synced the configuration and redeployed the Docker Compose stack.

### [2026-09-05T03:45:00+05:30] Deployment 9: Decoupled Out-of-Band Price Cache Microservice (ADR 0012)
* **Issue**: `OPS` inline price writes created stale price risks during low signal volume or risk rejection periods, while inline writes on hot-path order execution coupled concerns unnecessarily.
* **Root Cause Analysis (RCA)**: Strategy signals are generated at low rates compared to exchange market ticks. When `RiskManager` rejected orders early or when no signals occurred, `market:last_price:<SYMBOL>` remained frozen in Redis, corrupting pre-trade Price Collar checks and real-time portfolio equity valuation.
* **Fix Applied**:
  1. Refactored [`RiskManager.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/java/com/trading/ops/service/RiskManager.java#L102) to remove inline Redis price write operations (`redisTemplate.opsForValue().set(...)`), converting `OPS` to a pure reader of Redis reference prices.
  2. Created dedicated microservice `price-cache-service/` (`main.py`, `config.py`, `Dockerfile`) in Python with `FLUSH_INTERVAL_SEC` (0.5s default) micro-batching via pipelined Redis `MSET`.
  3. Integrated multi-provider discovery matching `PROVIDER_<NAME>_ENDPOINT` environment variables.
  4. Documented architectural decision in [ADR 0012](file:///c:/Users/jeshu/Projects/distributed-trading-system/.agents/adr/0012-decoupled-price-cache-service.md) and updated HLD diagrams in [`hld.puml`](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/hld/hld.puml) and [`hld.md`](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/hld/hld.md).

### [2026-09-05T04:25:00+05:30] Deployment 10: Double-Loop Phase 2 Outer-Loop Git Reconciliation & Redeployment
* **Issue**: Staging Phase 1 inner-loop file syncs required formal production git reconciliation and hard reset to `origin/master`.
* **Root Cause Analysis (RCA)**: Inner-loop file syncs bypass git version control on the remote host for rapid iteration. Per the Double-Loop Deployment Strategy, after inner-loop verification, changes must be committed, pushed to origin, and reconciled on the host via `git_sync_and_deploy`.
* **Fix Applied**:
  1. Committed local changes (`commit e1893cf` and `commit 6ff26ca`) including `price-cache-service` microservice, `OPS` refactoring, and host port mapping update (`8084:8080` in `docker-compose.yml`).
  2. Pushed local `master` branch to `origin/master` (`https://github.com/jeshupatelg/distributed-trading-system.git`).
  3. Executed atomic outer-loop tool `git_sync_and_deploy(project_name="distributed-trading-system", branch="master")` to perform git fetch, `git reset --hard origin/master`, and clean stack redeployment.
  4. Verified container status (`price-cache-service`, `order-processing-service`, etc.) and confirmed healthy gRPC tick streaming, health probe (`200 OK`), and Prometheus metrics scraping.

### [2026-09-09T00:07:00+05:30] Deployment 11: Extensible CapStrategy Framework & Dynamic Position Sizing
* **Issue**: All trading signals generated by `signal-generator` had hardcoded `qty: 100`, causing high-priced stock signals (AAPL, MSFT) to trigger `MAX_CONCENTRATION_EXCEEDED` and `INSUFFICIENT_MARGIN` Pre-Trade Risk Engine rejections in `OPS`.
* **Root Cause Analysis (RCA)**: Quantitative trading strategies (`sma_crossover.py` and `mean_reversion.py`) hardcoded `"qty": 100` in their signal dictionary payloads. 100 shares of MSFT ($512–$632) produced a single-order notional of $51,000–$63,000, exceeding portfolio concentration caps ($19,432) and free cash margin ($16,321).
* **Fix Applied**:
  1. Built extensible `CapStrategy` framework inside `config/cap_strategy/`:
     - `base_cap_strategy.py`: Abstract interface (`BaseCapStrategy`).
     - `factory.py`: Unified `CapStrategyFactory` supporting 4 sizing options (`fixed_unit`, `fixed_dollar`, `equity_percentage`, `volatility_adjusted`).
     - Child strategy implementations (`fixed_unit_cap.py`, `fixed_dollar_cap.py`, `equity_percentage_cap.py`, `volatility_adjusted_cap.py`) and config modules in `config/cap_strategy/configs/`.
  2. Updated `sma_crossover.py` and `mean_reversion.py` to calculate signal quantities via `self.cap_strategy.calculate_qty(symbol, close, action, bar)`.
  3. Configured `STRATEGY_PARAMS_JSON` in `docker-compose.yml` with `FixedDollarCapStrategy` ($5,000.00 cash allocation per trade) and mounted `./config/cap_strategy:/app/cap_strategy:ro`.
  4. Executed Double-Loop Deployment (`commit afec406` pushed to `origin/master` and `git_sync_and_deploy` executed). Verified `signal-gen-aapl` emits `qty=15` ($4,912) and `signal-gen-msft` emits `qty=9` ($4,605), passing Pre-Trade Risk Manager validation.

### [2026-09-09T00:18:00+05:30] Deployment 12: Alpaca SDK Order Class Alignment (OTO for Single Stop-Loss Attachments)
* **Issue**: gRPC `PlaceOrder` calls routed from `order-processing-service` to `connection-manager-alpaca` failed with Pydantic validation error: `Value error, bracket orders require take_profit.limit_price`.
* **Root Cause Analysis (RCA)**: `AlpacaRestClient.submit_order` in `connection-manager-alpaca/alpaca_client.py` set `order_class = OrderClass.BRACKET` whenever either `stop_loss_data` or `take_profit_data` was present. Alpaca SDK strictly requires both `take_profit.limit_price` and `stop_loss.stop_price` for `OrderClass.BRACKET`. When `RiskManager` attached a single Stop Loss without a Take Profit target, the SDK rejected the request.
* **Fix Applied**:
  1. Updated `submit_order` in [`connection-manager-alpaca/alpaca_client.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/connection-manager-alpaca/alpaca_client.py#L80-L85):
     - Sets `OrderClass.BRACKET` only when *both* `stop_loss_data` and `take_profit_data` are present.
     - Sets `OrderClass.OTO` (One-Triggers-Other) when only a single attachment (e.g. Stop Loss) is present.
     - Sets `None` when neither is present.
  2. Executed Double-Loop Deployment (Phase 1 inner loop sync & Phase 2 outer loop git reconciliation).

### [2026-09-12T01:45:00+05:30] Deployment 13: Provider-Namespaced Redis Keys & Multi-Broker Risk Isolation
* **Issue**: Global Redis keys (e.g. `balance:cash`, `risk:config:max_order_val`) mixed account state across brokers, causing currency unit conflicts (e.g. $ USD vs ₹ INR) and cross-broker risk gate rejections when integrating international brokers like MegaBull.
* **Root Cause Analysis (RCA)**: `RiskManager` in `OPS`, `OrderResolutionService` in `OMS`, and `Quant Dashboard` accessed non-namespaced Redis keys. Adding Indian market paper trading brokers (MegaBull) required isolation of balance, open position, pending order, and risk rule limit keys by broker provider.
* **Fix Applied**:
  1. Updated [`RiskManager.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/java/com/trading/ops/service/RiskManager.java): Dynamic keyspace namespacing (`getProviderKey(baseKey, provider)` -> `balance:cash:alpaca`, `risk:config:max_order_val:megabull`). Omitted/blank provider defaults to `"alpaca"`.
  2. Updated [`SignalConsumer.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/java/com/trading/ops/consumer/SignalConsumer.java): Passed extracted `provider` string from signal events into `evaluateAndLock` and `revertLock`.
  3. Updated [`OrderResolutionService.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-management-service/src/main/java/com/trading/oms/service/OrderResolutionService.java): Mutates provider-namespaced Redis balance and position keys upon order settlement (`balance:cash:<provider>`, `positions:<provider>:<symbol>`), retaining unnamespaced fallbacks.
  4. Updated [`app.py`](file:///c:/Users/jeshu/Projects/distributed-trading-system/quant-dashboard/app.py): Added broker provider selector dropdown, updated balance/position metric queries to check namespaced Redis keys, and updated risk config form to write to `risk:config:<rule>:<provider>`.
  5. Synchronized files to remote host via `sync_project_files` and redeployed stack via `deploy_compose_stack`.

### [2026-09-12T02:05:00+05:30] Deployment 14: Mandatory Provider Validation & Autowired Provider Beans (OPS & OMS)
* **Issue**: Hardcoded `"alpaca"` fallback defaults allowed signal payloads without a designated `provider` field to execute implicitly, while `Environment.getProperty(...)` calls bypassed Spring `@Configuration` bean injection.
* **Root Cause Analysis (RCA)**: To enforce multi-broker safety and isolation, signals missing provider metadata must fail immediately. Hardcoded default string literals in code prevented explicit validation and multi-provider looping.
* **Fix Applied**:
  1. Removed all `"alpaca"` fallback literals in OPS (`SignalConsumer.java`, `RiskManager.java`, `OrderExecutionClient.java`, `RiskAdminController.java`) and OMS (`OrderResolutionService.java`, `ReconciliationClient.java`, `ReconciliationJob.java`).
  2. Enforced that incoming signals or orders without a non-blank `provider` field throw an immediate `IllegalArgumentException`.
  3. Created `ProviderConfig.java` and `@ConfigurationProperties(prefix = "trading") ProviderConfiguration.java` in both OPS and OMS to produce `List<ProviderConfig>` beans from `trading.providers`.
  4. Updated `OrderExecutionClient` and `ReconciliationClient` to autowire `List<ProviderConfig>` and resolve gRPC endpoints from provider beans.
  5. Implemented provider bean looping:
     - `@PostConstruct` in `RiskManager` loops over all configured provider beans to initialize/seed account Redis caches at startup.
     - Emergency kill switch in `RiskAdminController` loops over all configured provider beans when triggering global order cancellations and position liquidations.
     - Pending order resolution and cache cleanup in `OrderResolutionService` loops across registered provider beans.
  6. Synchronized 11 modified/new Java files to remote host via `sync_project_files` and successfully redeployed the compose stack via `deploy_compose_stack`.

### [2026-09-12T02:14:00+05:30] Deployment 15: Promoted ProviderConfig & ProviderConfiguration to Shared Models Library
* **Issue**: Duplicate `ProviderConfig.java` and `ProviderConfiguration.java` classes existed independently across `order-processing-service` and `order-management-service`.
* **Root Cause Analysis (RCA)**: Provider configuration and bean mapping are core domain concepts shared between microservices. Keeping duplicate copies in service modules violates DRY principles.
* **Fix Applied**:
  1. Relocated `ProviderConfig.java` and `ProviderConfiguration.java` into [`CombinedOrderingSystem/libs/shared-models`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/libs/shared-models/src/main/java/com/trading/shared/config/) under package `com.trading.shared.config`.
  2. Updated `shared-models/pom.xml` to include `spring-boot-autoconfigure` (provided scope).
  3. Removed duplicate configuration files from OPS (`com.trading.ops.config`) and OMS (`com.trading.oms.config`).
  4. Updated [`OrderProcessingApplication.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/java/com/trading/ops/OrderProcessingApplication.java) and [`OrderManagementApplication.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-management-service/src/main/java/com/trading/oms/OrderManagementApplication.java) to `@Import({SharedAppConfig.class, ProviderConfiguration.class})`.
  5. Updated imports in `RiskManager`, `OrderExecutionClient`, `RiskAdminController`, `OrderResolutionService`, and `ReconciliationClient`.
  6. Synchronized file changes and deletions to remote host via `sync_project_files` and redeployed full stack via `deploy_compose_stack`.

### [2026-09-12T05:40:00+05:30] Deployment 16: Hot-Path Account Cache Cleanup & Removal of Synthetic Cash Defaults
* **Issue**: `RiskManager.evaluateAndLock` and `validateAndLock` called `ensureAccountCache(prov)` inline on every order evaluation. If cash keys were missing in Redis, it automatically seeded a synthetic default cash balance of `$100,000.00` (`DEFAULT_STARTING_CASH`), risking false positive margin approvals in live production environments.
* **Root Cause Analysis (RCA)**: Hot-path pre-trade risk evaluations must not silently create synthetic money or alter account balance state when balance keys are missing. Uninitialized or inactive provider caches should result in a fail-closed order rejection.
* **Fix Applied**:
  1. Updated [`RiskManager.java`](file:///c:/Users/jeshu/Projects/distributed-trading-system/CombinedOrderingSystem/ms/order-processing-service/src/main/java/com/trading/ops/service/RiskManager.java):
     - Removed inline `ensureAccountCache(prov)` calls from `evaluateAndLock`, `validateAndLock`, and `getRiskStatus`.
     - Removed `DEFAULT_STARTING_CASH = 100000.00;` static constant.
     - In `evaluateAndLock`, if `redisTemplate.opsForValue().get(cashKey) == null`, the engine immediately rejects the order with `RiskDecision(false, "PROVIDER_UNINITIALIZED_OR_INACTIVE", "PROVIDER_HEALTH", estimatedCost, 0.0)`.
     - Updated `ensureAccountCache` so it only populates provider balance keys if legacy `balance:cash` key exists, avoiding synthetic default seeding.
  2. Synchronized `RiskManager.java` to remote host via `sync_project_files` and redeployed full stack via `deploy_compose_stack`.
  3. Verified all 18 whitelisted containers are healthy and running cleanly.

