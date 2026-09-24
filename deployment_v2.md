<<<<<<< Updated upstream
# Deployment Log - v2

## Deployment Action 1: Fix Order Processing & Management Kafka Consumers
- **Problem**: Strategy generator instances successfully generated and published trading signals to the Kafka topic `trading-signals`. However, zero orders were placed or displayed on the UI dashboard.
- **Root Cause**: Missing `@EnableKafka` annotation in `SharedAppConfig.java` caused Spring to ignore `@KafkaListener` annotations.
- **Fix**: Added `@EnableKafka` to `SharedAppConfig.java`, `OrderProcessingApplication.java`, and `OrderManagementApplication.java`.

## Deployment Action 2: Phase 1 Pre-Trade Risk Engine & Global Emergency Kill Switch
- **Objective**: Implement comprehensive pre-trade risk controls, loss gates, price collars, rate throttling, max sizing/concentration, on-exchange hard stop loss attachment, and a global emergency kill switch with full GUI interactivity and Indian exchange/broker compatibility.
- **Key Modules Modified & Added**:
  1. **Proto Schema (connection_manager.proto)**:
     - Updated `OrderRequest` with `stop_loss_price`, `take_profit_price`, and `product_type` (supporting Indian CNC/MIS/NRML and Global DAY).
     - Added `CancelAllOrders` and `CloseAllPositions` RPCs to `OrderExecutionService`.
  2. **Broker Gateway (connection-manager-alpaca)**:
     - Updated `submit_order` in `alpaca_client.py` to submit native bracket orders with on-exchange hard stop loss.
     - Added `cancel_all_orders` and `close_all_positions` methods.
     - Updated `grpc_server.py` with `CancelAllOrders` and `CloseAllPositions` servicer handlers.
  3. **Order Processing Service (order-processing-service)**:
     - Upgraded `RiskManager.java` to evaluate 6 pre-trade risk gates:
       1. Global Emergency Kill Switch Gate (`system:kill_switch`).
       2. Daily Loss Gate (evaluating real-time cumulative drawdown vs `risk:config:max_daily_loss`).
       3. Fat-Finger & Price Collar Gate (rejecting prices deviating > 1.5% from market tick).
       4. Velocity Rate Throttler (max 5 orders/sec per symbol; max 30 orders/min system-wide).
       5. Max Position Sizing & Concentration Gate (max 500 qty, max ,000 value, max 20% equity concentration).
       6. Cash Margin Verification & Reservation.
       7. Automatic dynamic stop loss price calculation.
     - Updated `SignalConsumer.java` to pass stop loss prices to the broker and handle pre-trade rejections.
     - Added `OrderExecutionClient.java` emergency methods.
     - Added `RiskAdminController.java` REST API for kill-switch and dynamic configuration.
  4. **Quant Dashboard (quant-dashboard)**:
     - Added new **"Risk Engine & Controls"** page.
     - Added interactive form to modify all risk limits in real-time (persisting directly to Redis).
     - Added 1-click **Emergency Global Kill Switch** and **Reset Lockdown** controls.
     - Added currency toggle (₹ INR / $ USD) and Indian broker/exchange compatibility indicators.

## Deployment Action 3: Multi-Channel Notification Microservice (Telegram, ntfy, Evolution API WhatsApp)
- **Objective**: Implement real-time multi-channel notifications for failed orders (orders rejected by pre-trade risk gates), executed orders, fills, and emergency lockdowns with granular GUI controls.
- **Root Cause / Need**: Operators previously had no proactive alerting when signals failed risk validation (e.g. fat-finger price collar, velocity throttle, daily drawdown) or when orders executed, requiring manual inspection of logs.
- **Key Modules Modified & Added**:
  1. **Order Processing Service (`order-processing-service`)**:
     - Created `OrderRejectEvent.java` DTO encapsulating rejected order metadata, exact failure reason, risk gate level, and timestamp.
     - Updated `RiskManager.java` so `RiskDecision` classifies rejections across 8 gate levels (`KILL_SWITCH`, `DAILY_LOSS_GATE`, `PRICE_COLLAR`, `VELOCITY_THROTTLER`, `MAX_ORDER_QTY`, `MAX_ORDER_VALUE`, `PORTFOLIO_CONCENTRATION`, `INSUFFICIENT_MARGIN`).
     - Updated `SignalConsumer.java` to publish `OrderRejectEvent` to Kafka topic `order-reject-events` on any pre-trade rejection or broker transmission failure.
     - Added `order-reject` topic configuration to `application.yml`.
  2. **Notification Microservice (`notification-service`)**:
     - Created dedicated standalone container listening to Kafka topics (`order-reject-events`, `order-create-events`, `order-complete-events`).
     - Implemented multi-channel dispatchers:
       - `channels/evolution_api.py`: WhatsApp messaging via Evolution API v2 (`http://192.168.29.96:3015/`).
       - `channels/telegram.py`: HTML formatted Telegram Bot alerts.
       - `channels/ntfy.py`: Push alerts to ntfy server with urgency and tag headers.
     - Built `formatter.py` template engine providing structured trade details and intuitive explanation statements for every risk gate.
     - Created `main.py` FastAPI server with health check, status, and test dispatch endpoints.
     - Built multi-stage `Dockerfile` on port `8085`.
  3. **Quant Dashboard (`quant-dashboard`)**:
     - Added dedicated **"Notification Center"** page.
     - Added granular alert event checkboxes (Risk Rejections, Placements, Fills, Kill Switch).
     - Added credential & endpoint forms for Evolution API, Telegram, and ntfy with 1-click Redis persistence (`notify:config:*`).
     - Added live channel test dispatch button.
  4. **Docker Compose (`docker-compose.yml`)**:
     - Added `notification-service` container definition on port `8085:8085` joined to `default` (kafka_net) and `gateway_net`.
- **Deployment & Verification**:
  - Pushed git commits to `master` and built/deployed stack to Docker host at `192.168.29.96`.
  - Created topic `order-reject-events` in Kafka cluster.
  - Verified `notification-service` status: Up and Healthy on port 8085.
  - Verified `quant-dashboard` status: Up and Healthy on port 8501 (`/dashboard/`).
  - Verified `order-processing-service` actively publishes `OrderRejectEvent` to `order-reject-events`.
  - Verified live dispatch and response from `/api/v1/notify/test`.

## Deployment Action 4: Telegram Topic ID (`message_thread_id`) Integration
- **Objective**: Add first-class support for Telegram forum topics (`message_thread_id`) across Notification Service and Quant Dashboard.
- **Root Cause / Requirement**: When operating Telegram alerts within supergroups with forum topics enabled, notifications without a `message_thread_id` are delivered to the root or "General" topic. Operators require routing notifications directly to dedicated topic threads (e.g. Alerts, Fills, Rejections).
- **Key Modules Modified**:
  1. **Telegram Channel (`notification-service/channels/telegram.py`)**:
     - Updated `send_telegram()` to accept optional `message_thread_id: int | str | None = None`.
     - Validated and injected `"message_thread_id": int(...)` into the Telegram Bot API payload when present.
  2. **Notification Configuration (`notification-service/config.py`)**:
     - Added `TELEGRAM_TOPIC_ID` from environment variable.
     - Merged dynamic Redis override `notify:config:telegram:topic_id` in `get_active_config()`.
  3. **Event Consumer (`notification-service/consumer.py`)**:
     - Forwarded `cfg.get("telegram_topic_id")` to `send_telegram()` for risk rejections, order placements, and completions.
  4. **FastAPI Endpoints (`notification-service/main.py`)**:
     - Exposed `topic_id` in `/api/v1/notify/status`.
     - Added `telegram_topic_id` field to `TestNotificationRequest` and passed it to `send_telegram()` in `/api/v1/notify/test`.
  5. **Docker Compose (`docker-compose.yml`)**:
     - Added `TELEGRAM_TOPIC_ID=${TELEGRAM_TOPIC_ID:-}` to `notification-service` environment.
  6. **Quant Dashboard (`quant-dashboard/app.py`)**:
     - Added `Telegram Topic ID / Thread ID (Optional)` input field under Telegram settings card.
     - Persisted value to Redis key `notify:config:telegram:topic_id`.
     - Displayed configured Topic ID in Top Status Card.
     - Included `telegram_topic_id` in Live Channel Test dispatch.
- **Deployment Strategy**:
  - Inner-loop deployment via `sync_project_files` and `deploy_compose_stack` (no git commits per user instruction).

## Deployment Action 5: Fix Over-Aggressive Provider Inactivation in OPS
- **Objective**: Prevent order-level broker rejections (e.g. insufficient available shares) from falsely triggering provider-wide inactivation.
- **Root Cause**: `OrderExecutionClient.placeOrder()` caught all `StatusRuntimeException` exceptions indiscriminately and called `riskManager.markProviderInactive(provider)`. When Alpaca rejected an order with `code: 40310000` (`insufficient qty available for order`), the gateway wrapped the exception as `StatusRuntimeException: INTERNAL`, causing OPS to mark `alpaca` as `INACTIVE` in-memory and in Redis. This halted all subsequent trading across all symbols.
- **Key Modules Modified**:
  1. **`OrderExecutionClient.java`**:
     - Constrained `riskManager.markProviderInactive(provider)` to only execute when the gRPC status code is `UNAVAILABLE` (genuine gateway/network offline state).
     - Order-level application and broker rejections continue to be handled properly by `SignalConsumer` (reverting the margin reservation and publishing `OrderRejectEvent` to Kafka).
- **Verification**:
  - Verified `order-processing-service` rebuild and startup.
  - Confirmed `alpaca` connection manager probed as `HEALTHY` and account cache verified as `ACTIVE`.

## Deployment Action 6: Redis Keyspace Centralization & Service Migration (Phase 4)
- **Objective**: Centralize the Redis keyspace across Order Processing Service (OPS), Order Management Service (OMS), and Quant Dashboard in accordance with ADR-005. Eliminate decentralized string keys, remove dangerous in-code fallbacks and arbitrary `$100.0` price defaults, enforce fail-fast validation for uninitialized margin/loss keys, and prevent cross-provider positions leakage.
- **Root Cause / Problem Statement**:
  1. Multiple microservices accessed Redis using raw string concatenations and unnamespaced legacy keys (`balance:cash`, `balance:blocked`, `risk:config:max_daily_loss`).
  2. `quant-dashboard` performed dual-writes on form submission to both namespaced and unnamespaced keys (`risk:config:<param>` and `risk:config:<param>:<provider>`).
  3. `RiskManager` fell back to a default price of `$100.00` if market price was missing, creating fictitious margin allocations.
  4. `RiskManager` scanned `positions:*` across all providers if provider keys were absent, leaking cross-broker position sizes into drawdown calculations.
- **Key Modules Modified**:
  1. **OPS (`order-processing-service`)**:
     - `OrderProcessingApplication.java`: Imported `SharedRedisConfiguration.class`.
     - `RiskManager.java`: Replaced raw string constants with `RedisKeyDef` / `RedisKeyBuilder` / `TradingRedisFacade`. Implemented fail-fast checks on `BALANCE_CASH`, `BALANCE_BLOCKED`, `BALANCE_STARTING_EQUITY`, `RISK_CONFIG_MAX_DAILY_LOSS`, and `RISK_CONFIG_MAX_ORDER_VAL`. Replaced `$100.0` price fallback with ADR-005 compliant `getMarketPrice(provider, symbol)`. Fixed velocity minute window expiration timer bug (`minKey`).
  2. **OMS (`order-management-service`)**:
     - `OrderManagementApplication.java`: Imported `SharedRedisConfiguration.class`.
     - `OrderResolutionService.java`: Replaced raw template calls with `TradingRedisFacade` for margin release, cash adjustments, and position settlement. Cleaned legacy comments.
     - `EquityReconciliationService.java`: Replaced raw keys and template calls with `TradingRedisFacade` and ADR market price resolution.
     - `DailyEquityRefreshJob.java`: Replaced template calls with `TradingRedisFacade` for `BALANCE_LAST_RESET_DATE`.
     - `ProviderHealthCheckJob.java`: Replaced raw keys with `TradingRedisFacade` for `PROVIDER_STATUS`.
  3. **Quant Dashboard (`quant-dashboard`)**:
     - `app.py`: Eliminated dual-write loop on form submission (`risk:config`). Removed unnamespaced fallbacks (`balance:cash`, `balance:starting_equity`, `balance:blocked`). Scoped open positions strictly to `positions:{prov_key}:*`. Updated Portfolio & Assets page to support provider context switching and live Redis market price resolution.
- **Deployment Strategy**:
  - Outer-loop `git_sync_and_deploy` to synchronize Git repository on remote Docker host and reconcile compose stacks.
  - Follow with inner-loop double-loop deployment testing and log diagnostics.
- **Diagnostics, RCA & Fixes Applied During Double-Loop**:
  1. **Spring Boot `TradingRedisFacade` Bean Missing Failure**:
     - *Symptoms*: Both `order-processing-service` and `order-management-service` crashed on startup with `UnsatisfiedDependencyException: No qualifying bean of type 'com.trading.shared.redis.TradingRedisFacade' available`.
     - *Root Cause Analysis (RCA)*: `SharedRedisConfiguration.java` was annotated with `@ConditionalOnBean(StringRedisTemplate.class)`. In Spring Boot, evaluating `@ConditionalOnBean` inside user-imported configuration classes runs before Spring's `RedisAutoConfiguration` has registered `StringRedisTemplate`. Consequently, the condition evaluated to `false` and skipped creating `TradingRedisFacade` and `RedisDefaultsInitializer`.
     - *Fix*: Removed `@ConditionalOnBean(StringRedisTemplate.class)` from bean provider methods in `SharedRedisConfiguration.java`, allowing Spring to naturally resolve `StringRedisTemplate` during bean instantiation. Pushed commit `e0730e5` and synced via `sync_project_files`.
  2. **Quant Dashboard Container Healthcheck Failure**:
     - *Symptoms*: `quant-dashboard` container reported `unhealthy` with exit code 1 on healthcheck curl.
     - *Root Cause Analysis (RCA)*: The Dockerfile healthcheck queried `http://localhost:8501/_stcore/health` without considering `STREAMLIT_SERVER_BASE_URL_PATH=dashboard`, producing HTTP 404.
     - *Fix*: Updated `quant-dashboard/Dockerfile` to dynamically inspect `STREAMLIT_SERVER_BASE_URL_PATH` (querying `http://localhost:8501/${STREAMLIT_SERVER_BASE_URL_PATH}/_stcore/health`). Pushed commit `33de1c6`.
- **Verification & Live Telemetry Evidence**:
  - **`order-processing-service`**: Started in 21.6 seconds. `TradingRedisFacade` instantiated. `RedisDefaultsInitializer` non-destructively seeded 13 defaults to `system:defaults:*`. Kafka consumer group `ops-group` listening on `trading-signals`.
  - **`order-management-service`**: Started in 24.1 seconds. `TradingRedisFacade` instantiated. Probed gRPC `connection-manager-alpaca:50051` and verified `alpaca` as `HEALTHY`, setting `provider:status:alpaca` to `ACTIVE`. Scheduled reconciliation completed cleanly.
  - **`quant-dashboard`**: Container state `running` and `healthy`. Streamlit dashboard serving on port `8501/dashboard`.
  - **Live Redis Telemetry**:
    - Confirmed non-destructive seeding of 13 keys in `system:defaults:*`: `positions`, `orders:pending`, `provider:status`, `system:kill_switch`, `system:kill_switch:provider`, `risk:velocity_sec`, `risk:velocity_min`, `risk:velocity_per_sec`, `risk:velocity_per_min`, `risk:max_order_qty`, `risk:price_collar_pct`, `risk:stop_loss_pct`, `risk:max_concentration_pct`.
    - Live canonical keys confirmed active: `balance:cash:alpaca`, `balance:blocked:alpaca`, `balance:starting_equity:alpaca`, `balance:last_reset_date:alpaca`, `provider:status:alpaca`, `positions:alpaca:AAPL`, `positions:alpaca:MSFT`.
    - Unnamespaced legacy keys confirmed isolated for Phase 5 eviction: `balance:cash`, `balance:blocked`, `risk:config:max_daily_loss`, `risk:config:max_order_val`, `positions:AAPL`, `positions:MSFT`.
  - **Container Fleet**: All 20/20 whitelisted containers confirmed `running` and healthy on remote Docker daemon.

---

### Deployment Action 7: ProviderStateManager Safety Gates, Non-Defaultable Keys Check & State Flapping Prevention (2026-09-23)
- **Commit**: `cdb8c7c`
- **Scope & Components Modified**:
  1. **Shared Models (`libs/shared-models`)**:
     - `ProviderStateManager.java`: Introduced centralized manager enforcing all 6 non-defaultable provider-scoped keys (`balance:cash:{p}`, `balance:blocked:{p}`, `balance:starting_equity:{p}`, `balance:last_reset_date:{p}`, `risk:config:max_daily_loss:{p}`, `risk:config:max_order_val:{p}`). Added stub hooks (`reconcileProviderState`, `onAccountInfoReceived`) for future direct broker query state reconciliation.
     - `ProviderStateManagerTest.java`: Added 5 unit tests covering complete state validation, missing key detection, active/inactive mutations, and broker reconciliation stubs.
     - `TradingRedisFacade.java`: Modernized to Java 21 `record TradingRedisFacade(StringRedisTemplate redisTemplate)` with compact constructor null-validation.
     - `SharedRedisConfiguration.java`: Registered `ProviderStateManager` Spring bean.
  2. **Order Processing Service (`ms/order-processing-service`)**:
     - `RiskManager.java`: Injected `ProviderStateManager`. Refactored `ensureAccountCache` to enforce `stateManager.isProviderStateComplete(provider)`. Guarded order evaluation hot paths (`evaluateAndLock`, `validateAndLock`) with defensive `try-catch (MissingRedisStateException e)` blocks to safely handle unexpected state loss. Updated all calls to `redisFacade.redisTemplate()`.
     - `SignalConsumer.java`: Removed hardcoded in-code fallback `:order-reject-events` from `@Value("${trading.topics.order-reject}")`.
     - `RiskAdminController.java`: Modernized `providerBeans.get(0)` to Java 21 `SequencedCollection.getFirst()`.
  3. **Order Management Service (`ms/order-management-service`)**:
     - `ProviderHealthCheckJob.java`: Injected `ProviderStateManager`. State promotion to `ACTIVE` now requires **both** gRPC probe reachability (`HEALTHY`) **and** complete Redis non-defaultable keys (`providerStateManager.isProviderStateComplete(provider)`).
     - `application.yml`: Explicitly declared `trading.health-check.interval-ms: 15000` and `trading.reconciliation.interval-ms: 30000`.
     - `OrderCreateConsumer.java`: Extracted clean order entity mapping helper method `getTrackedOrderFromOrderCreateEvent(event)`.
     - `EquityReconciliationService.java`: Updated all calls to `redisFacade.redisTemplate()`.
- **Root Cause Analysis (RCA)**:
  - *Symptom / Logic Gap*: Previously, `RiskManager.ensureAccountCache` checked only `balance:cash`, leaving unverified whether critical risk keys (`risk:config:max_daily_loss`, `risk:config:max_order_val`) existed in Redis. Concurrently, OMS `ProviderHealthCheckJob` marked providers `ACTIVE` whenever the gRPC connection manager responded, ignoring Redis keyspace completeness.
  - *Consequence*: OMS would prematurely mark a provider `ACTIVE`. Once a trading signal arrived, OPS hot-path order validation would execute, fail fast on missing risk keys (`risk:config:max_order_val`), throw `MissingRedisStateException`, reject the order, and set `provider:status` to `INACTIVE`. On the subsequent 15-second tick, OMS would re-probe gRPC, see the connection manager healthy, and flip the status back to `ACTIVE`. This caused an unmitigated ping-pong state flapping loop.
- **Fix & Safety Gates Implemented**:
  - Encapsulated provider state verification in `ProviderStateManager`.
  - State promotion to `ACTIVE` in both OPS startup probing and OMS health check is strictly blocked until all 6 non-defaultable keys are validated.
  - Wrapped order validation with defensive exception handling in `RiskManager`.
- **Deployment & Telemetry Evidence**:
  - **Git Sync**: Remote host repository synchronized to commit `cdb8c7c` via `git_sync_remote`.
  - **Docker Compose Rebuild**: Both `order-processing-service` and `order-management-service` packaged and redeployed via multi-stage Temurin 21 images.
  - **`order-processing-service` Startup**:
    - Bootstrap time: 16.701 seconds.
    - Proactive probe: Connection manager `alpaca` reported `HEALTHY` (channel state: `IDLE`).
    - `ProviderStateManager` validated Redis account cache and marked `alpaca` as `ACTIVE`.
    - Kafka consumer group `ops-group` listening on `trading-signals` (partitions 0, 1, 2).
  - **`order-management-service` Startup**:
    - Bootstrap time: 19.663 seconds.
    - `ProviderHealthCheckJob` verified gRPC and Redis keyspace completeness via `ProviderStateManager`, confirming `alpaca` as `ACTIVE`.
    - Scheduled reconciliation job executed cleanly (0 pending orders).
    - Status remained stable at `ACTIVE` with zero flapping observed.
    - Kafka consumer group `oms-group` listening on `raw-order-updates` and `order-create-events`.
  - **Container Fleet**: All 20/20 whitelisted containers confirmed `running` and healthy on remote Docker daemon.
=======
# Deployment Log - v2

## Deployment Action 1: Fix Order Processing & Management Kafka Consumers
- **Problem**: Strategy generator instances successfully generated and published trading signals to the Kafka topic `trading-signals`. However, zero orders were placed or displayed on the UI dashboard.
- **Root Cause**: Missing `@EnableKafka` annotation in `SharedAppConfig.java` caused Spring to ignore `@KafkaListener` annotations.
- **Fix**: Added `@EnableKafka` to `SharedAppConfig.java`, `OrderProcessingApplication.java`, and `OrderManagementApplication.java`.

## Deployment Action 2: Phase 1 Pre-Trade Risk Engine & Global Emergency Kill Switch
- **Objective**: Implement comprehensive pre-trade risk controls, loss gates, price collars, rate throttling, max sizing/concentration, on-exchange hard stop loss attachment, and a global emergency kill switch with full GUI interactivity and Indian exchange/broker compatibility.
- **Key Modules Modified & Added**:
  1. **Proto Schema (connection_manager.proto)**:
     - Updated `OrderRequest` with `stop_loss_price`, `take_profit_price`, and `product_type` (supporting Indian CNC/MIS/NRML and Global DAY).
     - Added `CancelAllOrders` and `CloseAllPositions` RPCs to `OrderExecutionService`.
  2. **Broker Gateway (connection-manager-alpaca)**:
     - Updated `submit_order` in `alpaca_client.py` to submit native bracket orders with on-exchange hard stop loss.
     - Added `cancel_all_orders` and `close_all_positions` methods.
     - Updated `grpc_server.py` with `CancelAllOrders` and `CloseAllPositions` servicer handlers.
  3. **Order Processing Service (order-processing-service)**:
     - Upgraded `RiskManager.java` to evaluate 6 pre-trade risk gates:
       1. Global Emergency Kill Switch Gate (`system:kill_switch`).
       2. Daily Loss Gate (evaluating real-time cumulative drawdown vs `risk:config:max_daily_loss`).
       3. Fat-Finger & Price Collar Gate (rejecting prices deviating > 1.5% from market tick).
       4. Velocity Rate Throttler (max 5 orders/sec per symbol; max 30 orders/min system-wide).
       5. Max Position Sizing & Concentration Gate (max 500 qty, max ,000 value, max 20% equity concentration).
       6. Cash Margin Verification & Reservation.
       7. Automatic dynamic stop loss price calculation.
     - Updated `SignalConsumer.java` to pass stop loss prices to the broker and handle pre-trade rejections.
     - Added `OrderExecutionClient.java` emergency methods.
     - Added `RiskAdminController.java` REST API for kill-switch and dynamic configuration.
  4. **Quant Dashboard (quant-dashboard)**:
     - Added new **"Risk Engine & Controls"** page.
     - Added interactive form to modify all risk limits in real-time (persisting directly to Redis).
     - Added 1-click **Emergency Global Kill Switch** and **Reset Lockdown** controls.
     - Added currency toggle (₹ INR / $ USD) and Indian broker/exchange compatibility indicators.

## Deployment Action 3: Multi-Channel Notification Microservice (Telegram, ntfy, Evolution API WhatsApp)
- **Objective**: Implement real-time multi-channel notifications for failed orders (orders rejected by pre-trade risk gates), executed orders, fills, and emergency lockdowns with granular GUI controls.
- **Root Cause / Need**: Operators previously had no proactive alerting when signals failed risk validation (e.g. fat-finger price collar, velocity throttle, daily drawdown) or when orders executed, requiring manual inspection of logs.
- **Key Modules Modified & Added**:
  1. **Order Processing Service (`order-processing-service`)**:
     - Created `OrderRejectEvent.java` DTO encapsulating rejected order metadata, exact failure reason, risk gate level, and timestamp.
     - Updated `RiskManager.java` so `RiskDecision` classifies rejections across 8 gate levels (`KILL_SWITCH`, `DAILY_LOSS_GATE`, `PRICE_COLLAR`, `VELOCITY_THROTTLER`, `MAX_ORDER_QTY`, `MAX_ORDER_VALUE`, `PORTFOLIO_CONCENTRATION`, `INSUFFICIENT_MARGIN`).
     - Updated `SignalConsumer.java` to publish `OrderRejectEvent` to Kafka topic `order-reject-events` on any pre-trade rejection or broker transmission failure.
     - Added `order-reject` topic configuration to `application.yml`.
  2. **Notification Microservice (`notification-service`)**:
     - Created dedicated standalone container listening to Kafka topics (`order-reject-events`, `order-create-events`, `order-complete-events`).
     - Implemented multi-channel dispatchers:
       - `channels/evolution_api.py`: WhatsApp messaging via Evolution API v2 (`http://192.168.29.96:3015/`).
       - `channels/telegram.py`: HTML formatted Telegram Bot alerts.
       - `channels/ntfy.py`: Push alerts to ntfy server with urgency and tag headers.
     - Built `formatter.py` template engine providing structured trade details and intuitive explanation statements for every risk gate.
     - Created `main.py` FastAPI server with health check, status, and test dispatch endpoints.
     - Built multi-stage `Dockerfile` on port `8085`.
  3. **Quant Dashboard (`quant-dashboard`)**:
     - Added dedicated **"Notification Center"** page.
     - Added granular alert event checkboxes (Risk Rejections, Placements, Fills, Kill Switch).
     - Added credential & endpoint forms for Evolution API, Telegram, and ntfy with 1-click Redis persistence (`notify:config:*`).
     - Added live channel test dispatch button.
  4. **Docker Compose (`docker-compose.yml`)**:
     - Added `notification-service` container definition on port `8085:8085` joined to `default` (kafka_net) and `gateway_net`.
- **Deployment & Verification**:
  - Pushed git commits to `master` and built/deployed stack to Docker host at `192.168.29.96`.
  - Created topic `order-reject-events` in Kafka cluster.
  - Verified `notification-service` status: Up and Healthy on port 8085.
  - Verified `quant-dashboard` status: Up and Healthy on port 8501 (`/dashboard/`).
  - Verified `order-processing-service` actively publishes `OrderRejectEvent` to `order-reject-events`.
  - Verified live dispatch and response from `/api/v1/notify/test`.

## Deployment Action 4: Telegram Topic ID (`message_thread_id`) Integration
- **Objective**: Add first-class support for Telegram forum topics (`message_thread_id`) across Notification Service and Quant Dashboard.
- **Root Cause / Requirement**: When operating Telegram alerts within supergroups with forum topics enabled, notifications without a `message_thread_id` are delivered to the root or "General" topic. Operators require routing notifications directly to dedicated topic threads (e.g. Alerts, Fills, Rejections).
- **Key Modules Modified**:
  1. **Telegram Channel (`notification-service/channels/telegram.py`)**:
     - Updated `send_telegram()` to accept optional `message_thread_id: int | str | None = None`.
     - Validated and injected `"message_thread_id": int(...)` into the Telegram Bot API payload when present.
  2. **Notification Configuration (`notification-service/config.py`)**:
     - Added `TELEGRAM_TOPIC_ID` from environment variable.
     - Merged dynamic Redis override `notify:config:telegram:topic_id` in `get_active_config()`.
  3. **Event Consumer (`notification-service/consumer.py`)**:
     - Forwarded `cfg.get("telegram_topic_id")` to `send_telegram()` for risk rejections, order placements, and completions.
  4. **FastAPI Endpoints (`notification-service/main.py`)**:
     - Exposed `topic_id` in `/api/v1/notify/status`.
     - Added `telegram_topic_id` field to `TestNotificationRequest` and passed it to `send_telegram()` in `/api/v1/notify/test`.
  5. **Docker Compose (`docker-compose.yml`)**:
     - Added `TELEGRAM_TOPIC_ID=${TELEGRAM_TOPIC_ID:-}` to `notification-service` environment.
  6. **Quant Dashboard (`quant-dashboard/app.py`)**:
     - Added `Telegram Topic ID / Thread ID (Optional)` input field under Telegram settings card.
     - Persisted value to Redis key `notify:config:telegram:topic_id`.
     - Displayed configured Topic ID in Top Status Card.
     - Included `telegram_topic_id` in Live Channel Test dispatch.
- **Deployment Strategy**:
  - Inner-loop deployment via `sync_project_files` and `deploy_compose_stack` (no git commits per user instruction).

## Deployment Action 5: Fix Over-Aggressive Provider Inactivation in OPS
- **Objective**: Prevent order-level broker rejections (e.g. insufficient available shares) from falsely triggering provider-wide inactivation.
- **Root Cause**: `OrderExecutionClient.placeOrder()` caught all `StatusRuntimeException` exceptions indiscriminately and called `riskManager.markProviderInactive(provider)`. When Alpaca rejected an order with `code: 40310000` (`insufficient qty available for order`), the gateway wrapped the exception as `StatusRuntimeException: INTERNAL`, causing OPS to mark `alpaca` as `INACTIVE` in-memory and in Redis. This halted all subsequent trading across all symbols.
- **Key Modules Modified**:
  1. **`OrderExecutionClient.java`**:
     - Constrained `riskManager.markProviderInactive(provider)` to only execute when the gRPC status code is `UNAVAILABLE` (genuine gateway/network offline state).
     - Order-level application and broker rejections continue to be handled properly by `SignalConsumer` (reverting the margin reservation and publishing `OrderRejectEvent` to Kafka).
- **Verification**:
  - Verified `order-processing-service` rebuild and startup.
  - Confirmed `alpaca` connection manager probed as `HEALTHY` and account cache verified as `ACTIVE`.

## Deployment Action 6: Redis Keyspace Centralization & Service Migration (Phase 4)
- **Objective**: Centralize the Redis keyspace across Order Processing Service (OPS), Order Management Service (OMS), and Quant Dashboard in accordance with ADR-005. Eliminate decentralized string keys, remove dangerous in-code fallbacks and arbitrary `$100.0` price defaults, enforce fail-fast validation for uninitialized margin/loss keys, and prevent cross-provider positions leakage.
- **Root Cause / Problem Statement**:
  1. Multiple microservices accessed Redis using raw string concatenations and unnamespaced legacy keys (`balance:cash`, `balance:blocked`, `risk:config:max_daily_loss`).
  2. `quant-dashboard` performed dual-writes on form submission to both namespaced and unnamespaced keys (`risk:config:<param>` and `risk:config:<param>:<provider>`).
  3. `RiskManager` fell back to a default price of `$100.00` if market price was missing, creating fictitious margin allocations.
  4. `RiskManager` scanned `positions:*` across all providers if provider keys were absent, leaking cross-broker position sizes into drawdown calculations.
- **Key Modules Modified**:
  1. **OPS (`order-processing-service`)**:
     - `OrderProcessingApplication.java`: Imported `SharedRedisConfiguration.class`.
     - `RiskManager.java`: Replaced raw string constants with `RedisKeyDef` / `RedisKeyBuilder` / `TradingRedisFacade`. Implemented fail-fast checks on `BALANCE_CASH`, `BALANCE_BLOCKED`, `BALANCE_STARTING_EQUITY`, `RISK_CONFIG_MAX_DAILY_LOSS`, and `RISK_CONFIG_MAX_ORDER_VAL`. Replaced `$100.0` price fallback with ADR-005 compliant `getMarketPrice(provider, symbol)`. Fixed velocity minute window expiration timer bug (`minKey`).
  2. **OMS (`order-management-service`)**:
     - `OrderManagementApplication.java`: Imported `SharedRedisConfiguration.class`.
     - `OrderResolutionService.java`: Replaced raw template calls with `TradingRedisFacade` for margin release, cash adjustments, and position settlement. Cleaned legacy comments.
     - `EquityReconciliationService.java`: Replaced raw keys and template calls with `TradingRedisFacade` and ADR market price resolution.
     - `DailyEquityRefreshJob.java`: Replaced template calls with `TradingRedisFacade` for `BALANCE_LAST_RESET_DATE`.
     - `ProviderHealthCheckJob.java`: Replaced raw keys with `TradingRedisFacade` for `PROVIDER_STATUS`.
  3. **Quant Dashboard (`quant-dashboard`)**:
     - `app.py`: Eliminated dual-write loop on form submission (`risk:config`). Removed unnamespaced fallbacks (`balance:cash`, `balance:starting_equity`, `balance:blocked`). Scoped open positions strictly to `positions:{prov_key}:*`. Updated Portfolio & Assets page to support provider context switching and live Redis market price resolution.
- **Deployment Strategy**:
  - Outer-loop `git_sync_and_deploy` to synchronize Git repository on remote Docker host and reconcile compose stacks.
  - Follow with inner-loop double-loop deployment testing and log diagnostics.
- **Diagnostics, RCA & Fixes Applied During Double-Loop**:
  1. **Spring Boot `TradingRedisFacade` Bean Missing Failure**:
     - *Symptoms*: Both `order-processing-service` and `order-management-service` crashed on startup with `UnsatisfiedDependencyException: No qualifying bean of type 'com.trading.shared.redis.TradingRedisFacade' available`.
     - *Root Cause Analysis (RCA)*: `SharedRedisConfiguration.java` was annotated with `@ConditionalOnBean(StringRedisTemplate.class)`. In Spring Boot, evaluating `@ConditionalOnBean` inside user-imported configuration classes runs before Spring's `RedisAutoConfiguration` has registered `StringRedisTemplate`. Consequently, the condition evaluated to `false` and skipped creating `TradingRedisFacade` and `RedisDefaultsInitializer`.
     - *Fix*: Removed `@ConditionalOnBean(StringRedisTemplate.class)` from bean provider methods in `SharedRedisConfiguration.java`, allowing Spring to naturally resolve `StringRedisTemplate` during bean instantiation. Pushed commit `e0730e5` and synced via `sync_project_files`.
  2. **Quant Dashboard Container Healthcheck Failure**:
     - *Symptoms*: `quant-dashboard` container reported `unhealthy` with exit code 1 on healthcheck curl.
     - *Root Cause Analysis (RCA)*: The Dockerfile healthcheck queried `http://localhost:8501/_stcore/health` without considering `STREAMLIT_SERVER_BASE_URL_PATH=dashboard`, producing HTTP 404.
     - *Fix*: Updated `quant-dashboard/Dockerfile` to dynamically inspect `STREAMLIT_SERVER_BASE_URL_PATH` (querying `http://localhost:8501/${STREAMLIT_SERVER_BASE_URL_PATH}/_stcore/health`). Pushed commit `33de1c6`.
- **Verification & Live Telemetry Evidence**:
  - **`order-processing-service`**: Started in 21.6 seconds. `TradingRedisFacade` instantiated. `RedisDefaultsInitializer` non-destructively seeded 13 defaults to `system:defaults:*`. Kafka consumer group `ops-group` listening on `trading-signals`.
  - **`order-management-service`**: Started in 24.1 seconds. `TradingRedisFacade` instantiated. Probed gRPC `connection-manager-alpaca:50051` and verified `alpaca` as `HEALTHY`, setting `provider:status:alpaca` to `ACTIVE`. Scheduled reconciliation completed cleanly.
  - **`quant-dashboard`**: Container state `running` and `healthy`. Streamlit dashboard serving on port `8501/dashboard`.
  - **Live Redis Telemetry**:
    - Confirmed non-destructive seeding of 13 keys in `system:defaults:*`: `positions`, `orders:pending`, `provider:status`, `system:kill_switch`, `system:kill_switch:provider`, `risk:velocity_sec`, `risk:velocity_min`, `risk:velocity_per_sec`, `risk:velocity_per_min`, `risk:max_order_qty`, `risk:price_collar_pct`, `risk:stop_loss_pct`, `risk:max_concentration_pct`.
    - Live canonical keys confirmed active: `balance:cash:alpaca`, `balance:blocked:alpaca`, `balance:starting_equity:alpaca`, `balance:last_reset_date:alpaca`, `provider:status:alpaca`, `positions:alpaca:AAPL`, `positions:alpaca:MSFT`.
    - Unnamespaced legacy keys confirmed isolated for Phase 5 eviction: `balance:cash`, `balance:blocked`, `risk:config:max_daily_loss`, `risk:config:max_order_val`, `positions:AAPL`, `positions:MSFT`.
  - **Container Fleet**: All 20/20 whitelisted containers confirmed `running` and healthy on remote Docker daemon.

---

### Deployment Action 8: Trading Web Cockpit & Node.js BFF Gateway (ADR 0003) (2026-09-23)
- **Objective**: Deploy a dedicated, full-stack Node.js (TypeScript + Fastify) BFF Proxy Gateway and modern React 18 Web Trading Cockpit (`web-app`) bound to host port `3030:3030` under ADR 0003. Enforce strict perimeter isolation for all internal trading microservices while providing real-time risk controls, emergency kill-switch, TradingView Lightweight Charts, and order audit feeds.
- **Root Cause & Architectural Decision (ADR 0003)**:
  1. Direct external exposure of internal trading microservices (OPS, OMS, Price Cache, Notification Service) creates an unnecessary attack surface and high network chattiness.
  2. Under Rule 6, all client interfaces (desktop, mobile PWA) must communicate exclusively through the `web-app` BFF gateway on port `3030`.
  3. OMS does not currently expose a REST order query endpoint; per user directive, OMS was left unmodified and an in-app amber warning was added to the audit feed indicating offline fallback mode.
- **Key Modules Added & Configured**:
  1. **ADR 0003 (`design/adr/0003-frontend-proxy-gateway-bff.md`)**: Formally recorded the Frontend Proxy Gateway pattern and strict perimeter rules.
  2. **Repository Agent Guidelines (`.agents/AGENTS.md`)**: Added Rule 6 and updated the `developer` persona prompt.
  3. **Web Application & BFF (`web-app/`)**:
     - `server/index.ts`: Fastify Node.js server proxying OPS risk/kill-switch endpoints, multiplexing WebSocket tick streams on `/ws`, and serving the compiled Vite React frontend on port `3030`.
     - `src/App.tsx`, `Header.tsx`, `RiskCenter.tsx`, `ChartSection.tsx`, `OrderBook.tsx`, `TelemetryMatrix.tsx`: High-density dark-theme trading cockpit with TradingView Lightweight Charts (Apache 2.0, zero accounts needed), slide-to-confirm kill-switch, and OMS offline fallback warning banner.
     - `Dockerfile`: Multi-stage build producing a lean Alpine production container.
  4. **Docker Compose (`docker-compose.yml`)**: Registered `web-app` service on port `3030:3030` connected to `default` (internal `kafka_net`) and `gateway_net`.
- **Diagnostics, RCA & Fixes Applied During Double-Loop**:
  1. **Node.js ESM Meta-Property Compilation (`import.meta`)**:
     - *Symptom*: `tsc -p tsconfig.server.json` failed during Docker build with `error TS1470: The 'import.meta' meta-property is not allowed in files which will build into CommonJS output`.
     - *RCA*: `web-app/package.json` was missing `"type": "module"`, causing Node and TypeScript to interpret the package as CommonJS.
     - *Fix*: Added `"type": "module"` to `web-app/package.json`.
  2. **`@fastify/websocket` v10 Connection Signature**:
     - *Symptom*: When browser connected to `/ws`, Node threw `TypeError: Cannot read properties of undefined (reading 'on')` and `(reading 'send')`.
     - *RCA*: In `@fastify/websocket` v10+, the route handler passes the WebSocket instance directly (`socket`) rather than the v7 legacy wrapper `connection.socket`.
     - *Fix*: Resolved WebSocket defensively with `const ws = connection?.socket ?? connection;`, guarded interval emissions with `if (ws.readyState !== 1) return;`, wrapped sends with try/catch, and attached `ws.on('error')` and `ws.on('close')`.
- **Live Verification**:
  - In-container WebSocket connection test confirmed: `WS_CONNECTED` and tick emission `{"type":"tick","payload":{"symbol":"AAPL","price":224.56}}`.
  - Verified container logs confirm clean handshake and graceful connection termination without errors.

---

### Deployment Action 9: Live Market Price Synchronization with Redis Cache (2026-09-23)
- **Objective**: Synchronize `web-app` TradingView Lightweight Charts with real-time live market prices populated by `price-cache-service` in Redis, eliminating mock random-walk pricing.
- **Root Cause Analysis (RCA)**:
  1. The initial version of `web-app` used simulated client-side candlesticks (`basePrice = 440` for MSFT and `220` for AAPL in `ChartSection.tsx`) and an in-memory random walk in `server/index.ts` (~448 for MSFT), causing the chart to display mock prices (~$446.51) rather than true market data.
  2. The system already ingests real-time Alpaca market feeds via `connection-manager-alpaca` and flushes them to Redis via `price-cache-service` under `market:last_price:AAPL` (~$336.87) and `market:last_price:MSFT` (~$499.06).
- **Fix Applied**:
  1. **Node.js Fastify BFF (`web-app/server/index.ts`)**:
     - Configured `ioredis` client connecting to `REDIS_HOST:REDIS_PORT` (`host.docker.internal:6379`) with auto-reconnection and fallback safeguards.
     - Implemented `getLivePricesFromRedis(symbols: string[])` querying `market:last_price:<SYMBOL>` via Redis MGET.
     - Added REST proxy route `GET /api/v1/market/prices` for initial snapshot retrieval.
     - Updated WebSocket `/ws` loop to stream real live prices directly from Redis every second.
  2. **TradingView Lightweight Chart Component (`web-app/src/components/ChartSection.tsx`)**:
     - Re-anchored initial candle generation to the live Redis price (e.g. ~$499 for MSFT, ~$336 for AAPL).
     - Aggregated live incoming ticks into genuine 1-minute candlestick bars (`open`, `high`, `low`, `close`), ensuring smooth updates without timestamp collisions.
     - Added real-time "Redis Live" pulse status badge and live indicator above the chart.

---

### Deployment Action 10: Multi-Panel Trading Terminal Desktop Grid Layout (2026-09-24)
- **Objective**: Reorganize the Web Trading Cockpit from a vertically stacked narrow column into a professional, high-density multi-panel Desktop Grid Layout. Move the live chart to the top-left, fill the top-right with informative posture cards, dock the order book across the bottom, and isolate administrative form tuning strictly to the "Risk & Safety Gates" tab.
- **Root Cause & UX Optimization**:
  1. The previous layout stacked 3 large cards containing interactive form inputs (`Dynamic Risk Tuning`) on top of the chart, forcing the chart down and cluttering the live monitoring viewport.
  2. The page container was constrained to `max-w-7xl`, leaving large empty margins on modern desktop displays.
- **Fix Applied**:
  1. **New Component `InformativeRiskCards.tsx`**: Created a dedicated read-only informative sidebar displaying:
     - Gate 1: Daily Loss Threshold & Drawdown Gauge + Circuit Breaker status badge (`ARMED / NORMAL`).
     - Gate 2: Price Collar Firewall indicator (`±1.5%` max tick deviation).
     - Gate 3: Capital & Account Posture (Cash balance `$170,533.88`, Blocked margin `$0.00`, venue status).
  2. **Grid Layout in `App.tsx`**:
     - Upgraded container to `max-w-[1720px] w-full`.
     - Structured Cockpit top section as a 12-column grid (`xl:grid-cols-12`):
       - **Top Left (`xl:col-span-8`)**: TradingView Lightweight Candlestick Chart.
       - **Top Right (`xl:col-span-4`)**: `InformativeRiskCards`.
     - Docked `OrderBook` cleanly across the full width below the grid.
     - Preserved single-column auto-stacking on mobile and portrait viewports (`< xl`).
  3. **Configuration Isolation**:
     - Dedicated the "Risk & Safety Gates" tab exclusively to interactive risk configuration tuning (`RiskCenter.tsx`).

---

### Deployment Action 11: Direct PostgreSQL Read-Only Connection for Live Order Audit Trail (2026-09-24)
- **Objective**: Connect the `web-app` Node.js Fastify BFF directly to PostgreSQL (`trading_agent` database, `tracked_orders` table) via a capped read-only connection pool. Feed the web client with real trade execution history while placing zero load on OMS and avoiding any code modifications to `order-management-service`.
- **Root Cause & Architectural Decision**:
  1. `order-management-service` previously lacked a public REST order query endpoint, causing the web UI to operate in offline fallback mode with an amber warning banner.
  2. Modifying OMS was deferred per user instruction. However, PostgreSQL already holds the complete production audit trail written by OMS in `tracked_orders`.
  3. Direct read-only database querying by the BFF container provides instantaneous access to full historical orders without touching Spring Boot code or increasing OMS CPU/thread utilization. If PG load increases under high throughput, a read replica can be attached seamlessly.
- **Fix Applied**:
  1. **Dependencies (`web-app/package.json`)**: Added `pg` (`^8.12.0`) and `@types/pg` (`^8.11.6`).
  2. **Docker Compose (`docker-compose.yml`)**: Configured database environment variables (`DB_HOST=host.docker.internal`, `DB_PORT=5432`, `DB_NAME=trading_agent`, `DB_USER=admin`, `DB_PASSWORD=admin`) for `web-app`.
  3. **Node.js BFF Server (`web-app/server/index.ts`)**:
     - Initialized PostgreSQL `Pool` with strict pool cap (`max: 5`) and idle timeout to prevent connection starvation.
     - Implemented `GET /api/v1/orders` executing parameterized SQL against `tracked_orders` with symbol/provider filters, ordering by `created_at DESC LIMIT 50`.
     - Added PostgreSQL read-pool health check (`SELECT 1`) to `GET /api/v1/system/health`.
  4. **Frontend UI (`OrderBook.tsx` & `trading.ts`)**:
     - Expanded `Order["status"]` union with `COMPLETED` and `FAILED`.
     - Replaced the offline warning banner with a sleek green `PostgreSQL Direct Read Pool (SQL LIVE FEED)` status ribbon.
     - Added filter chips (`ALL`, `COMPLETED`, `FILLED`, `REJECTED`, `PENDING`) and display of strategy names and execution timestamps.

---

### Deployment Action 12: Streamline Cockpit View with Compact Recent Orders (2026-09-24)
- **Objective**: Replace the heavy 50-event "Execution Audit Trail & Order Feed" table from the main Trading Cockpit tab with a compact, dedicated "Recent Orders" component showing the latest 5 orders. Preserve the full 50+ event audit trail, filters, and SQL diagnostics in the dedicated "Order Audit Trail" tab.
- **Root Cause & UX Optimization**:
  1. The full OrderBook component in the main Cockpit view was vertically lengthy (displaying up to 50 rows plus full diagnostic diagnostics, ribbons, and filter chips), forcing excessive page scrolling under the chart.
  2. Traders need quick visibility into recent execution status without cognitive overload in the primary monitoring dashboard, while retaining the ability to jump directly into the detailed audit log when needed.
- **Fix Applied**:
  1. **New Component (`web-app/src/components/RecentOrders.tsx`)**:
     - Built a compact 5-order execution table displaying Time, truncated Order ID, Symbol, Side badge (`BUY`/`SELL`), Quantity, Price, Status badge (`COMPLETED`/`FILLED`/`FAILED`/`PENDING`), and Strategy.
     - Added a clean "View full audit trail →" action button that switches navigation directly to the "Order Audit Trail" tab.
  2. **Dashboard Navigation (`web-app/src/App.tsx`)**:
     - Replaced `<OrderBook orders={allOrders} />` with `<RecentOrders orders={allOrders} onViewAll={() => setActiveTab("orders")} />` in the `cockpit` tab.
     - Kept `<OrderBook orders={allOrders} />` in the `orders` tab for deep auditing and filtering.
  3. **Verification**:
     - Built frontend bundle `dist/assets/index-DC1UhpRu.js`.
     - Deployed via `deploy_compose_stack` and restarted `web-app` container on remote daemon.
     - Verified clean HTTP 200 responses and live WebSocket connectivity.

---

### Deployment Action 13: Expandable Order ID Drawers & Table Streamlining (2026-09-24)
- **Objective**: Remove the "Latest 5" tag from Recent Orders, remove the `Order ID` column from the main collapsed table rows in both Recent Orders and Order Audit Trail, and make rows interactively expandable to reveal the full un-truncated Order ID with a one-click Copy button and detailed execution diagnostics.
- **Root Cause & UX Optimization**:
  1. Displaying long UUIDs directly in table columns consumed significant horizontal space, causing truncated ellipsis values (`9faf47bb...`) that could not be read or easily copied.
  2. The "Latest 5" pill tag was redundant alongside the clear section title and navigation controls.
  3. Having rows expandable on-click provides a clean high-level view while allowing immediate drill-down into full UUIDs, exact UTC timestamps, broker venues, strategies, fill metrics, and rejection diagnostics without cluttering the primary grid.
- **Fix Applied**:
  1. **Recent Orders (`web-app/src/components/RecentOrders.tsx`)**:
     - Removed the `"Latest 5"` tag from the card header.
     - Removed the `Order ID` column from the table headers and collapsed row cells.
     - Added an interactive chevron indicator (`ChevronRight` / `ChevronDown`) in the first column and `cursor-pointer` row toggle.
     - Implemented an animated/styled expandable drawer displaying:
       - Full un-truncated Order ID (`order.orderId`) with select-all styling.
       - One-click **Copy** button with temporary `Copied` confirmation feedback.
       - Complete ISO UTC timestamp.
       - Strategy, Broker Venue, Filled / Total Quantity, and Filled Average Price metric tiles.
       - Highlighted failure/rejection diagnostic banner if present.
  2. **Order Audit Trail (`web-app/src/components/OrderBook.tsx`)**:
     - Applied the identical expandable row architecture and removed the `Order ID` column from the table headers and collapsed row cells.
     - Maintained status filter chips, events count, and PostgreSQL direct read status indicator.
  3. **Verification**:
     - Built bundle `dist/assets/index-Yqj-NCs0.js`.
     - Deployed via `deploy_compose_stack` and restarted `web-app` container.
     - Verified HTTP 200 and live WebSocket stream on `http://192.168.29.96:3030`.

---

### Deployment Action 14: Server-Side Order Filtering & Fixed 25-Row Pagination (2026-09-24)
- **Objective**: Implement comprehensive database-level order filtering (Date presets, Status, Symbol, Side, Strategy) and fixed 25-record pagination with navigation controls (`[Previous] Page X of Y [Next]`) on the Order Audit Trail view. Exclude Order ID search per user directive while preserving expandable row UUID inspections.
- **Root Cause & Architectural Decision**:
  1. Client-side filtering of only the initial 50 rows prevented operators from auditing older historical orders or filtering by specific dates and execution outcomes.
  2. Large result queries without pagination degrade database throughput and browser memory.
  3. Server-side SQL parameterization (`WHERE` + `LIMIT 25 OFFSET $offset`) ensures sub-millisecond execution times on PostgreSQL `tracked_orders` regardless of database growth.
- **Fix Applied**:
  1. **Node.js BFF Server (`web-app/server/index.ts`)**:
     - Parameterized `GET /api/v1/orders` with `symbol`, `side`, `status`, `strategy`, `provider`, `dateRange` (`today`, `24h`, `7d`, `30d`, `all`), `page`, and `limit` (fixed at 25).
     - Executed concurrent SQL `COUNT(*)` to return accurate total records and total page counts.
     - Sliced records cleanly with `ORDER BY created_at DESC LIMIT $limit OFFSET $offset`.
     - Attached response headers: `X-Total-Count`, `X-Page`, `X-Total-Pages`, `X-Data-Source: PostgreSQL-Read-Direct`.
  2. **TypeScript Types (`web-app/src/types/trading.ts`)**:
     - Added `PaginationInfo` and `PaginatedOrdersResponse` models.
  3. **Frontend UI (`OrderBook.tsx`)**:
     - Built responsive dark-theme Filter Bar above the table:
       - Date Range: `All Time`, `Today`, `Last 24 Hours`, `Last 7 Days`, `Last 30 Days`.
       - Status: `All Statuses`, `Completed (Success)`, `Failed / Rejected`, `Pending`.
       - Symbol: `All Symbols`, `AAPL`, `MSFT`.
       - Side: `All Sides`, `BUY`, `SELL`.
       - Strategy: `All Strategies`, `SmaCrossover`, `MeanReversion`.
       - Reset Filters button (reverts all active dropdowns to default and resets page to 1).
     - Fixed page size at 25 orders per page (no page selector dropdown per instruction).
     - Built bottom Pagination Bar showing `Showing X-Y of Z orders`, `[< Previous]` button, `Page X of Y` indicator, and `[Next >]` button.
     - Preserved clean collapsed table view (no Order ID column) with interactive chevron drawer revealing the full un-truncated Order ID with 1-click Copy button.
  4. **Verification**:
     - Built bundle `dist/assets/index-Jj0t4qx9.js`.
     - Deployed via `deploy_compose_stack` and restarted `web-app` container.
     - Tested API endpoints:
       - `GET /api/v1/orders?page=1&limit=25` $\rightarrow$ `total: 172`, `totalPages: 7`, response time `3ms`.
       - `GET /api/v1/orders?symbol=AAPL&dateRange=7d&page=1&limit=25` $\rightarrow$ `total: 56`, `totalPages: 3`.
       - `GET /api/v1/orders?status=FAILED&page=1&limit=25` $\rightarrow$ `total: 0`, `totalPages: 1`.


