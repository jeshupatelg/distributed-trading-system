package com.trading.oms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.oms.dto.OrderCompleteEvent;
import com.trading.oms.model.TrackedOrder;
import com.trading.oms.repository.TrackedOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderResolutionService {
    private static final Logger log = LoggerFactory.getLogger(OrderResolutionService.class);

    private static final String CASH_KEY = "balance:cash";
    private static final String BLOCKED_KEY = "balance:blocked";
    private static final String PENDING_ORDERS_KEY = "orders:pending";
    private static final String POSITION_KEY_PREFIX = "positions:";

    private final TrackedOrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${trading.topics.order-complete}")
    private String orderCompleteTopic;

    public OrderResolutionService(TrackedOrderRepository orderRepository, 
                                  StringRedisTemplate redisTemplate,
                                  KafkaTemplate<String, String> kafkaTemplate, 
                                  ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves an order lifecycle by updating the DB, settling the Redis cache,
     * and emitting a normalized order-complete event.
     */
    @Transactional
    public synchronized void resolveOrder(String orderId, String terminalStatus, int filledQty, double filledAvgPrice) {
        log.info("Resolving order {} with status={}, filledQty={}, filledAvgPrice={}", 
            orderId, terminalStatus, filledQty, filledAvgPrice);

        // 1. Validate order ID idempotency to avoid double-processing
        TrackedOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Order {} not found in database. Settle cache fallback will still run.", orderId);
            // Settle cache fallback to prevent permanent margin block
            settleCacheOnly(orderId, terminalStatus, filledQty, filledAvgPrice);
            return;
        }

        String currentStatus = order.getStatus();
        if ("COMPLETED".equals(currentStatus) || "FAILED".equals(currentStatus)) {
            log.info("Order {} is already in terminal status '{}'. Skipping duplicate processing.", orderId, currentStatus);
            return;
        }

        // 2. Update SQL transaction row status
        order.setStatus(terminalStatus);
        order.setFilledQty(filledQty);
        order.setFilledAvgPrice(filledAvgPrice);
        orderRepository.save(order);
        log.info("Updated order {} in database to {}", orderId, terminalStatus);

        // 3. Settle cash/positions in Redis & clear blocked margin
        double estimatedValue = order.getLimitPrice() * order.getQty();
        settleCache(order, estimatedValue, terminalStatus, filledQty, filledAvgPrice);

        // 4. Publish normalized order-complete-event to Kafka
        try {
            OrderCompleteEvent completeEvent = new OrderCompleteEvent(
                orderId,
                order.getSymbol(),
                order.getQty(),
                order.getSide(),
                terminalStatus,
                filledQty,
                filledAvgPrice,
                order.getProvider(),
                order.getStrategy()
            );
            String payload = objectMapper.writeValueAsString(completeEvent);
            kafkaTemplate.send(orderCompleteTopic, orderId, payload);
            log.info("Published order-complete-event to topic '{}' for order ID: {}", orderCompleteTopic, orderId);
        } catch (Exception e) {
            log.error("Failed to publish order-complete-event for order ID: {}", orderId, e);
        }
    }

    private void settleCache(TrackedOrder order, double estimatedBlockedMargin, String status, int filledQty, double filledAvgPrice) {
        // Clear blocked margin
        redisTemplate.opsForValue().increment(BLOCKED_KEY, -estimatedBlockedMargin);
        // SREM orderId from pending set
        redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY, order.getOrderId());

        if ("COMPLETED".equals(status) && filledQty > 0) {
            double executionCost = filledAvgPrice * filledQty;
            String side = order.getSide().toUpperCase();

            // Settle cash
            if ("BUY".equals(side)) {
                redisTemplate.opsForValue().increment(CASH_KEY, -executionCost);
            } else if ("SELL".equals(side)) {
                redisTemplate.opsForValue().increment(CASH_KEY, executionCost);
            }

            // Settle positions
            String positionKey = POSITION_KEY_PREFIX + order.getSymbol();
            String currentPosStr = redisTemplate.opsForValue().get(positionKey);
            int currentPos = currentPosStr == null ? 0 : Integer.parseInt(currentPosStr);
            int newPos = "BUY".equals(side) ? currentPos + filledQty : currentPos - filledQty;
            redisTemplate.opsForValue().set(positionKey, String.valueOf(newPos));

            log.info("Settled Redis cache for order {}. Mutated cash by ${}, set position for {} to {}", 
                order.getOrderId(), ("BUY".equals(side) ? "-" : "+") + executionCost, order.getSymbol(), newPos);
        } else {
            log.info("Setted Redis cache for failed/canceled order {}: cleared blocked margin and pending status.", order.getOrderId());
        }
    }

    private void settleCacheOnly(String orderId, String status, int filledQty, double filledAvgPrice) {
        redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY, orderId);
        log.info("Cleared order {} from Redis pending set (cache-only recovery).", orderId);
    }
}
