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