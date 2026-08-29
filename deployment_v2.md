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