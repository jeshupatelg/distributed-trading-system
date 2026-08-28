# Deployment Log - v2

## Deployment Action: Fix Order Processing & Management Kafka Consumers

### 1. Root Cause Analysis (RCA)
- **Problem**: Strategy generator instances (such as `signal-gen-aapl`) successfully generated and published trading signals to the Kafka topic `trading-signals`. However, zero orders were placed or displayed on the UI dashboard (`tracked_orders` SQL table remained empty).
- **Underlying Cause**:
  - The Spring Boot microservices `order-processing-service` (OPS) and `order-management-service` (OMS) declare `@KafkaListener` annotations on `SignalConsumer`, `OrderCreateConsumer`, and `OrderUpdateConsumer`.
  - In `CombinedOrderingSystem/libs/shared-models/src/main/java/com/trading/shared/config/SharedAppConfig.java`, custom Kafka infrastructure beans were defined under `@Configuration` without the `@EnableKafka` annotation.
  - Because `spring-boot-starter-kafka` autoconfiguration was not present and `@EnableKafka` was omitted, Spring never registered `KafkaListenerAnnotationBeanPostProcessor`.
  - As a result, neither service initiated a Kafka listener container for `trading-signals`, `order-create-events`, or `raw-order-updates`, leaving consumer groups (`ops-group`, `oms-group`) completely inactive.

### 2. Specific Fix Applied
- Added `@EnableKafka` to `SharedAppConfig.java` in `com.trading.shared.config`.
- Added `@EnableKafka` directly to `OrderProcessingApplication.java` and `OrderManagementApplication.java`.
- Synchronized code changes to the remote docker host environment and redeployed the `distributed-trading-system` compose stack.

### 3. Verification & Results
- Verified Kafka consumer groups (`ops-group` and `oms-group`) active and listening.
- Verified signal consumption from `trading-signals` topic.
- Verified order placement through broker gateway and persistence into PostgreSQL `tracked_orders` table.
- Verified orders populated and visible on the UI dashboard.