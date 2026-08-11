package com.trading.oms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.oms.dto.OrderCreateEvent;
import com.trading.oms.model.TrackedOrder;
import com.trading.oms.repository.TrackedOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreateConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderCreateConsumer.class);

    private final TrackedOrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderCreateConsumer(TrackedOrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${trading.topics.order-create}", groupId = "oms-group")
    public void consumeOrderCreate(String message) {
        log.info("Received order-create-event from Kafka: {}", message);
        try {
            OrderCreateEvent event = objectMapper.readValue(message, OrderCreateEvent.class);
            if (event.orderId() == null || event.symbol() == null) {
                log.warn("Invalid order-create-event. Skipping: {}", event);
                return;
            }

            // Check if already exists
            if (orderRepository.existsById(event.orderId())) {
                log.info("Order ID {} already exists in database. Skipping creation.", event.orderId());
                return;
            }

            TrackedOrder order = new TrackedOrder();
            order.setOrderId(event.orderId());
            order.setSymbol(event.symbol());
            order.setQty(event.qty());
            order.setSide(event.side());
            order.setOrderType(event.orderType());
            order.setLimitPrice(event.limitPrice());
            order.setStatus("PENDING");
            order.setProvider(event.provider());
            order.setStrategy(event.strategy());
            order.setFilledQty(0);
            order.setFilledAvgPrice(0.0);

            orderRepository.save(order);
            log.info("Saved initial PENDING order record for ID: {} to database.", event.orderId());

        } catch (Exception e) {
            log.error("Failed to process order-create-event from Kafka: {}", message, e);
        }
    }
}
