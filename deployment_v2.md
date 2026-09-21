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