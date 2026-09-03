package com.trading.ops.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.connection.grpc.OrderRequest;
import com.trading.connection.grpc.OrderResponse;
import com.trading.ops.dto.OrderCreateEvent;
import com.trading.ops.dto.OrderRejectEvent;
import com.trading.ops.dto.SignalEvent;
import com.trading.ops.service.OrderExecutionClient;
import com.trading.ops.service.RiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SignalConsumer {
    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    private final RiskManager riskManager;
    private final OrderExecutionClient executionClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${trading.topics.order-create}")
    private String orderCreateTopic;

    @Value("${trading.topics.order-reject:order-reject-events}")
    private String orderRejectTopic;

    public SignalConsumer(RiskManager riskManager, 
                          OrderExecutionClient executionClient,
                          KafkaTemplate<String, String> kafkaTemplate, 
                          ObjectMapper objectMapper) {
        this.riskManager = riskManager;
        this.executionClient = executionClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${trading.topics.signals}", groupId = "ops-group")
    public void consumeSignal(String message) {
        log.info("Received raw signal event from Kafka: {}", message);
        try {
            SignalEvent signal = objectMapper.readValue(message, SignalEvent.class);
            if (signal.symbol() == null || signal.action() == null || signal.qty() <= 0) {
                log.warn("Invalid signal event payload received. Ignoring: {}", signal);
                return;
            }

            String provider = signal.provider() != null ? signal.provider() : "alpaca";
            String action = signal.action().toUpperCase();
            if (!action.equals("BUY") && !action.equals("SELL")) {
                log.warn("Unknown signal action: {}. Expected BUY or SELL. Ignoring.", action);
                return;
            }

            // Generate unique order ID
            String clientOrderId = UUID.randomUUID().toString();

            // 1. Run comprehensive Pre-Trade Risk Engine evaluation & margin lock
            RiskManager.RiskDecision decision = riskManager.evaluateAndLock(
                clientOrderId,
                signal.symbol(),
                signal.qty(),
                signal.price(),
                action
            );

            if (!decision.approved()) {
                log.warn("ORDER REJECTED by Pre-Trade Risk Engine. Reason: {}, Gate: {}, Signal: {}", 
                    decision.reason(), decision.riskGateLevel(), signal);
                try {
                    OrderRejectEvent rejectEvent = new OrderRejectEvent(
                        clientOrderId,
                        signal.symbol(),
                        signal.qty(),
                        action,
                        signal.price(),
                        decision.calculatedCost(),
                        provider,
                        signal.strategy(),
                        decision.reason(),
                        decision.riskGateLevel(),
                        System.currentTimeMillis()
                    );
                    String rejectPayload = objectMapper.writeValueAsString(rejectEvent);
                    kafkaTemplate.send(orderRejectTopic, clientOrderId, rejectPayload);
                    log.info("Published order-reject-event to Kafka topic '{}' for order ID: {}", orderRejectTopic, clientOrderId);
                } catch (Exception ex) {
                    log.error("Failed to publish order-reject-event to Kafka", ex);
                }
                return;
            }

            // 2. Submit transaction payload to designated broker gateway via gRPC (with on-exchange Hard Stop Loss)
            OrderRequest.Builder orderRequestBuilder = OrderRequest.newBuilder()
                .setSymbol(signal.symbol())
                .setQty(signal.qty())
                .setSide(action)
                .setOrderType("market") // defaults to market order
                .setProductType("DAY"); // Indian broker compatible ("CNC", "MIS", "DAY")

            if (decision.stopLossPrice() > 0) {
                orderRequestBuilder.setStopLossPrice(decision.stopLossPrice());
            }

            OrderRequest orderRequest = orderRequestBuilder.build();

            try {
                OrderResponse response = executionClient.placeOrder(provider, orderRequest);
                String brokerOrderId = response.getOrderId();
                if (brokerOrderId == null || brokerOrderId.isEmpty()) {
                    brokerOrderId = clientOrderId;
                }

                log.info("Successfully executed order on broker. Broker Order ID: {}, Status: {}", 
                    brokerOrderId, response.getStatus());

                // 3. Publish order-create-event to Kafka
                OrderCreateEvent createEvent = new OrderCreateEvent(
                    brokerOrderId,
                    signal.symbol(),
                    signal.qty(),
                    action,
                    "market",
                    signal.price(),
                    provider,
                    signal.strategy()
                );

                String eventPayload = objectMapper.writeValueAsString(createEvent);
                kafkaTemplate.send(orderCreateTopic, brokerOrderId, eventPayload);
                log.info("Published order-create-event to Kafka topic '{}' for order ID: {}", orderCreateTopic, brokerOrderId);

            } catch (Exception e) {
                log.error("Order submission failed. Reverting risk margin lock for order ID: {}", clientOrderId, e);
                riskManager.revertLock(clientOrderId, decision.calculatedCost());
                try {
                    OrderRejectEvent rejectEvent = new OrderRejectEvent(
                        clientOrderId,
                        signal.symbol(),
                        signal.qty(),
                        action,
                        signal.price(),
                        decision.calculatedCost(),
                        provider,
                        signal.strategy(),
                        "Broker transmission error: " + e.getMessage(),
                        "BROKER_ERROR",
                        System.currentTimeMillis()
                    );
                    kafkaTemplate.send(orderRejectTopic, clientOrderId, objectMapper.writeValueAsString(rejectEvent));
                } catch (Exception ex) {
                    log.error("Failed to publish broker rejection event", ex);
                }
            }

        } catch (Exception e) {
            log.error("Error processing trading signal from Kafka: {}", message, e);
        }
    }
}
