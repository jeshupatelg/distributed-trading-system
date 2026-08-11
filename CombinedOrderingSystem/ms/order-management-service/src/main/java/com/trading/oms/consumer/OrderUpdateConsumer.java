package com.trading.oms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.oms.dto.RawOrder;
import com.trading.oms.dto.RawOrderUpdate;
import com.trading.oms.service.OrderResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderUpdateConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderUpdateConsumer.class);

    private final OrderResolutionService resolutionService;
    private final ObjectMapper objectMapper;

    public OrderUpdateConsumer(OrderResolutionService resolutionService, ObjectMapper objectMapper) {
        this.resolutionService = resolutionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${trading.topics.raw-updates}", groupId = "oms-group")
    public void consumeOrderUpdate(String message) {
        log.info("Received raw-order-update event from Kafka: {}", message);
        try {
            RawOrderUpdate update = objectMapper.readValue(message, RawOrderUpdate.class);
            if (update.order() == null || update.order().id() == null) {
                log.warn("Invalid raw-order-update payload. Missing order or order ID. Skipping: {}", update);
                return;
            }

            RawOrder rawOrder = update.order();
            String orderId = rawOrder.id();
            String status = rawOrder.status() != null ? rawOrder.status().toLowerCase() : "";

            int filledQty = 0;
            if (rawOrder.filledQty() != null && !rawOrder.filledQty().isEmpty()) {
                filledQty = (int) Double.parseDouble(rawOrder.filledQty());
            }

            double filledAvgPrice = 0.0;
            if (rawOrder.filledAvgPrice() != null && !rawOrder.filledAvgPrice().isEmpty()) {
                filledAvgPrice = Double.parseDouble(rawOrder.filledAvgPrice());
            }

            // Map broker status to terminal statuses
            if ("filled".equals(status) || "completed".equals(status)) {
                resolutionService.resolveOrder(orderId, "COMPLETED", filledQty, filledAvgPrice);
            } else if ("canceled".equals(status) || "rejected".equals(status) || "expired".equals(status)) {
                resolutionService.resolveOrder(orderId, "FAILED", filledQty, filledAvgPrice);
            } else {
                log.info("Order {} is in non-terminal status '{}'. Skipping resolution.", orderId, status);
            }

        } catch (Exception e) {
            log.error("Failed to process raw-order-update event from Kafka: {}", message, e);
        }
    }
}
