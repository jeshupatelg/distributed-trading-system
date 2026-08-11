package com.trading.ops.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.connection.grpc.OrderRequest;
import com.trading.connection.grpc.OrderResponse;
import com.trading.ops.dto.OrderCreateEvent;
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
            double estimatedCost = signal.price() * signal.qty();

            // 1. Run risk validation checks & local margin lock
            boolean riskApproved = riskManager.validateAndLock(clientOrderId, estimatedCost);
            if (!riskApproved) {
                log.warn("Order placement rejected by pre-order risk gate for signal: {}", signal);
                return;
            }

            // 2. Submit transaction payload to designated broker gateway via gRPC
            OrderRequest orderRequest = OrderRequest.newBuilder()
                .setSymbol(signal.symbol())
                .setQty(signal.qty())
                .setSide(action)
                .setOrderType("market") // defaults to market order
                .build();

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
                riskManager.revertLock(clientOrderId, estimatedCost);
            }

        } catch (Exception e) {
            log.error("Error processing trading signal from Kafka: {}", message, e);
        }
    }
}
