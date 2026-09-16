package com.trading.oms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.shared.config.ProviderConfig;
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

import java.util.List;

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
    private final List<ProviderConfig> providerBeans;

    @Value("${trading.topics.order-complete}")
    private String orderCompleteTopic;

    public OrderResolutionService(TrackedOrderRepository orderRepository, 
                                  StringRedisTemplate redisTemplate,
                                  KafkaTemplate<String, String> kafkaTemplate, 
                                  ObjectMapper objectMapper,
                                  List<ProviderConfig> providerBeans) {
        this.orderRepository = orderRepository;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.providerBeans = providerBeans;
    }

    @Transactional
    public void resolveOrder(String orderId, String status, int filledQty, double filledAvgPrice) {
        TrackedOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Order ID {} not found in database during resolution. Attempting Redis pending set cleanup.", orderId);
            settleCacheOnly(orderId, status, filledQty, filledAvgPrice);
            return;
        }

        if ("COMPLETED".equals(order.getStatus()) || "FAILED".equals(order.getStatus())) {
            log.info("Order ID {} is already resolved (status='{}'). Idempotent skip.", orderId, order.getStatus());
            return;
        }

        double estimatedCost = order.getQty() * order.getLimitPrice();

        // 1. Update Database Status
        order.setStatus(status);
        orderRepository.save(order);
        log.info("Updated order ID {} status in PostgreSQL to '{}'", orderId, status);

        // 2. Settle Redis Caches (Blocked Margin, Cash Balance, Positions, Pending Sets)
        settleCache(order, estimatedCost, status, filledQty, filledAvgPrice);

        // 3. Emit Kafka order-complete-event
        publishOrderCompleteEvent(order, status, filledQty, filledAvgPrice);
    }

    private void publishOrderCompleteEvent(TrackedOrder order, String status, int filledQty, double filledAvgPrice) {
        try {
            OrderCompleteEvent completeEvent = new OrderCompleteEvent(
                order.getOrderId(),
                order.getSymbol(),
                order.getQty(),
                order.getSide(),
                status,
                filledQty,
                filledAvgPrice,
                order.getProvider(),
                order.getStrategy()
            );
            String payload = objectMapper.writeValueAsString(completeEvent);
            kafkaTemplate.send(orderCompleteTopic, order.getOrderId(), payload);
            log.info("Published order-complete-event to topic '{}' for order ID: {}", orderCompleteTopic, order.getOrderId());
        } catch (Exception e) {
            log.error("Failed to publish order-complete-event for order ID: {}", order.getOrderId(), e);
        }
    }

    private void settleCache(TrackedOrder order, double estimatedBlockedMargin, String status, int filledQty, double filledAvgPrice) {
        if (order.getProvider() == null || order.getProvider().isBlank()) {
            log.error("TrackedOrder missing mandatory provider field for orderId: {}", order.getOrderId());
            throw new IllegalArgumentException("TrackedOrder missing mandatory provider for orderId: " + order.getOrderId());
        }
        String provider = order.getProvider().toLowerCase().trim();

        String blockedKey = BLOCKED_KEY + ":" + provider;
        String cashKey = CASH_KEY + ":" + provider;
        String pendingOrdersKey = PENDING_ORDERS_KEY + ":" + provider;

        // Clear blocked margin for provider
        redisTemplate.opsForValue().increment(blockedKey, -estimatedBlockedMargin);
        // redisTemplate.opsForValue().increment(BLOCKED_KEY, -estimatedBlockedMargin); // Obsolete legacy fallback - RiskManager only locks balance:blocked:{provider}

        // SREM orderId from pending set
        redisTemplate.opsForSet().remove(pendingOrdersKey, order.getOrderId());
        // redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY, order.getOrderId()); // Obsolete legacy fallback - RiskManager only adds to orders:pending:{provider}

        if ("COMPLETED".equals(status) && filledQty > 0) {
            double executionCost = filledAvgPrice * filledQty;
            String side = order.getSide().toUpperCase();

            // Settle cash per provider
            if ("BUY".equals(side)) {
                redisTemplate.opsForValue().increment(cashKey, -executionCost);
                // redisTemplate.opsForValue().increment(CASH_KEY, -executionCost); // Obsolete legacy fallback - balance:cash:{provider} is canonical
            } else if ("SELL".equals(side)) {
                redisTemplate.opsForValue().increment(cashKey, executionCost);
                // redisTemplate.opsForValue().increment(CASH_KEY, executionCost); // Obsolete legacy fallback - balance:cash:{provider} is canonical
            }

            // Settle positions per provider
            String posKeyNamespaced = POSITION_KEY_PREFIX + provider + ":" + order.getSymbol().toUpperCase();

            String currentPosStr = redisTemplate.opsForValue().get(posKeyNamespaced);
            int currentPos = currentPosStr == null ? 0 : Integer.parseInt(currentPosStr);
            int newPos = "BUY".equals(side) ? currentPos + filledQty : currentPos - filledQty;

            redisTemplate.opsForValue().set(posKeyNamespaced, String.valueOf(newPos));

            log.info("Settled Redis cache for order {} (provider {}). Mutated cash by ${}, set position for {} to {}", 
                order.getOrderId(), provider, ("BUY".equals(side) ? "-" : "+") + executionCost, order.getSymbol(), newPos);
        } else {
            log.info("Settled Redis cache for failed/canceled order {}: cleared blocked margin and pending status for {}.", order.getOrderId(), provider);
        }
    }

    private void settleCacheOnly(String orderId, String status, int filledQty, double filledAvgPrice) {
        // redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY, orderId); // Obsolete legacy fallback
        if (providerBeans != null) {
            for (ProviderConfig p : providerBeans) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY + ":" + p.getName(), orderId);
                }
            }
        }
        log.info("Cleared order {} from Redis pending sets across configured providers (cache-only recovery).", orderId);
    }
}
