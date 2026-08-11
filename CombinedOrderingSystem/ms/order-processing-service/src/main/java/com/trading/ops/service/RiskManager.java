package com.trading.ops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RiskManager {
    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    private static final String CASH_KEY = "balance:cash";
    private static final String BLOCKED_KEY = "balance:blocked";
    private static final String PENDING_ORDERS_KEY = "orders:pending";
    private static final double DEFAULT_CASH = 100000.00;

    private final StringRedisTemplate redisTemplate;

    public RiskManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Runs risk validation checks, restores account cache in Redis if needed,
     * and performs local margin lock for the order.
     *
     * @param orderId        The unique order ID.
     * @param estimatedValue The estimated dollar value of the order.
     * @return true if order is approved and margin is locked; false otherwise.
     */
    public synchronized boolean validateAndLock(String orderId, double estimatedValue) {
        // 1. Core trading state machine - restore account cache if not initialized
        String cashStr = redisTemplate.opsForValue().get(CASH_KEY);
        if (cashStr == null) {
            log.warn("Redis cash balance not found. Initiating account cache restoration...");
            redisTemplate.opsForValue().set(CASH_KEY, String.valueOf(DEFAULT_CASH));
            redisTemplate.opsForValue().set(BLOCKED_KEY, "0.0");
            cashStr = String.valueOf(DEFAULT_CASH);
            log.info("Restored account cache in Redis with default paper trading balance: ${}", DEFAULT_CASH);
        }

        double cash = Double.parseDouble(cashStr);
        String blockedStr = redisTemplate.opsForValue().get(BLOCKED_KEY);
        double blocked = blockedStr == null ? 0.0 : Double.parseDouble(blockedStr);

        double available = cash - blocked;
        log.info("Pre-Order Risk Check - Cash: ${}, Blocked Margin: ${}, Available Margin: ${}, Order Cost: ${}", 
            cash, blocked, available, estimatedValue);

        if (available >= estimatedValue) {
            // Lock margin
            redisTemplate.opsForValue().increment(BLOCKED_KEY, estimatedValue);
            // SADD orderId to pending set
            redisTemplate.opsForSet().add(PENDING_ORDERS_KEY, orderId);
            log.info("Risk check PASSED. Locked ${} margin and marked order {} as pending in Redis.", estimatedValue, orderId);
            return true;
        } else {
            log.warn("Risk check FAILED. Insufficient margin. Required: ${}, Available: ${}", estimatedValue, available);
            return false;
        }
    }

    /**
     * Reverts a margin lock in case order submission fails.
     */
    public synchronized void revertLock(String orderId, double estimatedValue) {
        redisTemplate.opsForValue().increment(BLOCKED_KEY, -estimatedValue);
        redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY, orderId);
        log.info("Reverted margin lock for order {}: freed ${}", orderId, estimatedValue);
    }
}
