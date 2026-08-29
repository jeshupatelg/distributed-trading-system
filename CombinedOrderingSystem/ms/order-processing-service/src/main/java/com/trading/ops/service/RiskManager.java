package com.trading.ops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RiskManager {
    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    // Redis State Keys
    public static final String CASH_KEY = "balance:cash";
    public static final String BLOCKED_KEY = "balance:blocked";
    public static final String STARTING_EQUITY_KEY = "balance:starting_equity";
    public static final String PENDING_ORDERS_KEY = "orders:pending";
    public static final String POSITION_KEY_PREFIX = "positions:";
    public static final String LAST_PRICE_KEY_PREFIX = "market:last_price:";
    public static final String KILL_SWITCH_KEY = "system:kill_switch";

    // Redis Config Keys
    public static final String CFG_MAX_DAILY_LOSS = "risk:config:max_daily_loss";
    public static final String CFG_PRICE_COLLAR_PCT = "risk:config:price_collar_pct";
    public static final String CFG_VELOCITY_PER_SEC = "risk:config:velocity_per_sec";
    public static final String CFG_VELOCITY_PER_MIN = "risk:config:velocity_per_min";
    public static final String CFG_MAX_ORDER_QTY = "risk:config:max_order_qty";
    public static final String CFG_MAX_ORDER_VAL = "risk:config:max_order_val";
    public static final String CFG_MAX_CONCENTRATION_PCT = "risk:config:max_concentration_pct";
    public static final String CFG_STOP_LOSS_PCT = "risk:config:stop_loss_pct";

    // Default Fallback Thresholds
    private static final double DEFAULT_STARTING_CASH = 100000.00;
    private static final double DEFAULT_MAX_DAILY_LOSS = 2000.00;
    private static final double DEFAULT_PRICE_COLLAR_PCT = 1.50; // 1.5%
    private static final int DEFAULT_VELOCITY_PER_SEC = 5;
    private static final int DEFAULT_VELOCITY_PER_MIN = 30;
    private static final int DEFAULT_MAX_ORDER_QTY = 500;
    private static final double DEFAULT_MAX_ORDER_VAL = 25000.00;
    private static final double DEFAULT_MAX_CONCENTRATION_PCT = 20.0; // 20%
    private static final double DEFAULT_STOP_LOSS_PCT = 2.0; // 2.0%

    private final StringRedisTemplate redisTemplate;

    public RiskManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record RiskDecision(boolean approved, String reason, double calculatedCost, double stopLossPrice) {}

    /**
     * Executes the comprehensive Phase 1 Pre-Trade Risk Firewall evaluation.
     */
    public synchronized RiskDecision evaluateAndLock(String orderId, String symbol, int qty, double price, String side) {
        double estimatedCost = price * qty;

        // 1. Emergency Kill Switch Gate
        String killSwitch = redisTemplate.opsForValue().get(KILL_SWITCH_KEY);
        if ("true".equalsIgnoreCase(killSwitch)) {
            log.warn("RISK REJECTED: Global Emergency Kill Switch is ACTIVE. Dropping order {} for {}", orderId, symbol);
            return new RiskDecision(false, "GLOBAL_KILL_SWITCH_ACTIVE", estimatedCost, 0.0);
        }

        // Initialize / restore account cache in Redis if needed
        ensureAccountCache();

        // 2. Daily Loss Gate Check
        double maxDailyLoss = getDoubleConfig(CFG_MAX_DAILY_LOSS, DEFAULT_MAX_DAILY_LOSS);
        double startingEquity = getDoubleState(STARTING_EQUITY_KEY, DEFAULT_STARTING_CASH);
        double currentCash = getDoubleState(CASH_KEY, DEFAULT_STARTING_CASH);
        double blockedMargin = getDoubleState(BLOCKED_KEY, 0.0);
        double positionsVal = calculateOpenPositionsValue();
        double currentTotalEquity = currentCash + positionsVal;
        double dailyDrawdown = startingEquity - currentTotalEquity;

        if (dailyDrawdown > maxDailyLoss) {
            log.warn("RISK REJECTED: Daily Loss Gate tripped! Drawdown: ${} > Max Allowed: ${}", dailyDrawdown, maxDailyLoss);
            return new RiskDecision(false, "DAILY_LOSS_LIMIT_EXCEEDED (Drawdown: " + String.format("%.2f", dailyDrawdown) + " > " + maxDailyLoss + ")", estimatedCost, 0.0);
        }

        // 3. Fat-Finger & Price Collar Sanity Check (±1.5% default deviation)
        double collarPct = getDoubleConfig(CFG_PRICE_COLLAR_PCT, DEFAULT_PRICE_COLLAR_PCT);
        String lastPriceKey = LAST_PRICE_KEY_PREFIX + symbol.toUpperCase();
        String refPriceStr = redisTemplate.opsForValue().get(lastPriceKey);
        if (refPriceStr != null) {
            double refPrice = Double.parseDouble(refPriceStr);
            if (refPrice > 0) {
                double deviation = Math.abs(price - refPrice) / refPrice * 100.0;
                if (deviation > collarPct) {
                    log.warn("RISK REJECTED: Price collar violation for {}! Price: {}, Ref: {}, Dev: {}% > Max: {}%",
                            symbol, price, refPrice, String.format("%.2f", deviation), collarPct);
                    return new RiskDecision(false, "PRICE_COLLAR_VIOLATION (" + String.format("%.2f", deviation) + "% > " + collarPct + "%)", estimatedCost, 0.0);
                }
            }
        }
        // Update reference price with current valid tick price
        redisTemplate.opsForValue().set(lastPriceKey, String.valueOf(price));

        // 4. Velocity / Rate Throttling Gate
        int maxSecVelocity = getIntConfig(CFG_VELOCITY_PER_SEC, DEFAULT_VELOCITY_PER_SEC);
        int maxMinVelocity = getIntConfig(CFG_VELOCITY_PER_MIN, DEFAULT_VELOCITY_PER_MIN);
        long epochSec = Instant.now().getEpochSecond();
        long epochMin = epochSec / 60;

        String symbolRateKey = "rate:sec:" + symbol.toUpperCase() + ":" + epochSec;
        String systemRateKey = "rate:min:system:" + epochMin;

        Long symbolCount = redisTemplate.opsForValue().increment(symbolRateKey, 1);
        redisTemplate.expire(symbolRateKey, 2, TimeUnit.SECONDS);

        Long systemCount = redisTemplate.opsForValue().increment(systemRateKey, 1);
        redisTemplate.expire(systemRateKey, 120, TimeUnit.SECONDS);

        if (symbolCount != null && symbolCount > maxSecVelocity) {
            log.warn("RISK REJECTED: Velocity limit exceeded for symbol {}! Count: {}/sec > Max: {}", symbol, symbolCount, maxSecVelocity);
            return new RiskDecision(false, "SYMBOL_VELOCITY_LIMIT_EXCEEDED (" + symbolCount + "/sec > " + maxSecVelocity + ")", estimatedCost, 0.0);
        }

        if (systemCount != null && systemCount > maxMinVelocity) {
            log.warn("RISK REJECTED: System-wide velocity limit exceeded! Count: {}/min > Max: {}", systemCount, maxMinVelocity);
            return new RiskDecision(false, "SYSTEM_VELOCITY_LIMIT_EXCEEDED (" + systemCount + "/min > " + maxMinVelocity + ")", estimatedCost, 0.0);
        }

        // 5. Max Position Sizing & Concentration Gate
        int maxOrderQty = getIntConfig(CFG_MAX_ORDER_QTY, DEFAULT_MAX_ORDER_QTY);
        double maxOrderVal = getDoubleConfig(CFG_MAX_ORDER_VAL, DEFAULT_MAX_ORDER_VAL);
        double maxConcentrationPct = getDoubleConfig(CFG_MAX_CONCENTRATION_PCT, DEFAULT_MAX_CONCENTRATION_PCT);

        if (qty > maxOrderQty) {
            log.warn("RISK REJECTED: Max order quantity exceeded! Qty: {} > Max: {}", qty, maxOrderQty);
            return new RiskDecision(false, "MAX_ORDER_QTY_EXCEEDED (" + qty + " > " + maxOrderQty + ")", estimatedCost, 0.0);
        }

        if (estimatedCost > maxOrderVal) {
            log.warn("RISK REJECTED: Max order value exceeded! Value: ${} > Max: ${}", estimatedCost, maxOrderVal);
            return new RiskDecision(false, "MAX_ORDER_VALUE_EXCEEDED ($" + estimatedCost + " > $" + maxOrderVal + ")", estimatedCost, 0.0);
        }

        if ("BUY".equalsIgnoreCase(side)) {
            String posKey = POSITION_KEY_PREFIX + symbol.toUpperCase();
            String posStr = redisTemplate.opsForValue().get(posKey);
            int currentPos = posStr == null ? 0 : Integer.parseInt(posStr);
            double projectedSymbolVal = (currentPos + qty) * price;
            double maxAllowedSymbolVal = currentTotalEquity * (maxConcentrationPct / 100.0);

            if (projectedSymbolVal > maxAllowedSymbolVal) {
                log.warn("RISK REJECTED: Concentration limit exceeded for {}! Projected: ${} > Max Allowed ({}%): ${}",
                        symbol, projectedSymbolVal, maxConcentrationPct, maxAllowedSymbolVal);
                return new RiskDecision(false, "MAX_CONCENTRATION_EXCEEDED (Projected: $" + String.format("%.2f", projectedSymbolVal) + " > Limit: $" + String.format("%.2f", maxAllowedSymbolVal) + ")", estimatedCost, 0.0);
            }
        }

        // 6. Margin Lock Verification
        double availableMargin = currentCash - blockedMargin;
        if (availableMargin < estimatedCost) {
            log.warn("RISK REJECTED: Insufficient cash margin. Required: ${}, Available: ${}", estimatedCost, availableMargin);
            return new RiskDecision(false, "INSUFFICIENT_MARGIN (Required: $" + estimatedCost + " > Available: $" + availableMargin + ")", estimatedCost, 0.0);
        }

        // Lock margin & mark order as pending in Redis
        redisTemplate.opsForValue().increment(BLOCKED_KEY, estimatedCost);
        redisTemplate.opsForSet().add(PENDING_ORDERS_KEY, orderId);

        // 7. Calculate On-Exchange Hard Stop Loss price
        double stopLossPct = getDoubleConfig(CFG_STOP_LOSS_PCT, DEFAULT_STOP_LOSS_PCT);
        double stopLossPrice = 0.0;
        if ("BUY".equalsIgnoreCase(side)) {
            stopLossPrice = price * (1.0 - (stopLossPct / 100.0));
        } else if ("SELL".equalsIgnoreCase(side)) {
            stopLossPrice = price * (1.0 + (stopLossPct / 100.0));
        }

        log.info("Risk checks PASSED for order {}. Margin locked: ${}, Stop Loss Price: ${}", orderId, estimatedCost, String.format("%.2f", stopLossPrice));
        return new RiskDecision(true, "APPROVED", estimatedCost, stopLossPrice);
    }

    /**
     * Backward-compatible simple validate and lock method.
     */
    public synchronized boolean validateAndLock(String orderId, double estimatedValue) {
        ensureAccountCache();
        double cash = getDoubleState(CASH_KEY, DEFAULT_STARTING_CASH);
        double blocked = getDoubleState(BLOCKED_KEY, 0.0);
        double available = cash - blocked;
        if (available >= estimatedValue) {
            redisTemplate.opsForValue().increment(BLOCKED_KEY, estimatedValue);
            redisTemplate.opsForSet().add(PENDING_ORDERS_KEY, orderId);
            return true;
        }
        return false;
    }

    /**
     * Reverts a margin lock in case of submission failure or cancellation.
     */
    public synchronized void revertLock(String orderId, double estimatedValue) {
        redisTemplate.opsForValue().increment(BLOCKED_KEY, -estimatedValue);
        redisTemplate.opsForSet().remove(PENDING_ORDERS_KEY, orderId);
        log.info("Reverted margin lock for order {}: freed ${}", orderId, estimatedValue);
    }

    /**
     * Triggers the Emergency Global Kill Switch.
     */
    public synchronized void triggerKillSwitch() {
        redisTemplate.opsForValue().set(KILL_SWITCH_KEY, "true");
        log.warn("EMERGENCY KILL SWITCH ACTIVATED in Redis!");
    }

    /**
     * Resets the Global Kill Switch back to normal.
     */
    public synchronized void resetKillSwitch() {
        redisTemplate.opsForValue().set(KILL_SWITCH_KEY, "false");
        log.info("Emergency Kill Switch RESET. System returned to normal operating state.");
    }

    /**
     * Returns full real-time telemetry and risk gate statuses.
     */
    public Map<String, Object> getRiskStatus() {
        ensureAccountCache();
        Map<String, Object> status = new HashMap<>();
        double cash = getDoubleState(CASH_KEY, DEFAULT_STARTING_CASH);
        double blocked = getDoubleState(BLOCKED_KEY, 0.0);
        double startingEquity = getDoubleState(STARTING_EQUITY_KEY, DEFAULT_STARTING_CASH);
        double positionsVal = calculateOpenPositionsValue();
        double totalEquity = cash + positionsVal;
        double dailyDrawdown = startingEquity - totalEquity;
        double maxDailyLoss = getDoubleConfig(CFG_MAX_DAILY_LOSS, DEFAULT_MAX_DAILY_LOSS);
        String killSwitch = redisTemplate.opsForValue().get(KILL_SWITCH_KEY);

        status.put("kill_switch_active", "true".equalsIgnoreCase(killSwitch));
        status.put("cash_balance", cash);
        status.put("blocked_margin", blocked);
        status.put("starting_equity", startingEquity);
        status.put("open_positions_value", positionsVal);
        status.put("total_equity", totalEquity);
        status.put("daily_drawdown", dailyDrawdown);
        status.put("max_daily_loss", maxDailyLoss);
        status.put("daily_loss_pct", maxDailyLoss > 0 ? (dailyDrawdown / maxDailyLoss) * 100.0 : 0.0);
        status.put("config", getRiskConfig());
        return status;
    }

    /**
     * Returns current active dynamic risk configuration parameters.
     */
    public Map<String, Object> getRiskConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_daily_loss", getDoubleConfig(CFG_MAX_DAILY_LOSS, DEFAULT_MAX_DAILY_LOSS));
        config.put("price_collar_pct", getDoubleConfig(CFG_PRICE_COLLAR_PCT, DEFAULT_PRICE_COLLAR_PCT));
        config.put("velocity_per_sec", getIntConfig(CFG_VELOCITY_PER_SEC, DEFAULT_VELOCITY_PER_SEC));
        config.put("velocity_per_min", getIntConfig(CFG_VELOCITY_PER_MIN, DEFAULT_VELOCITY_PER_MIN));
        config.put("max_order_qty", getIntConfig(CFG_MAX_ORDER_QTY, DEFAULT_MAX_ORDER_QTY));
        config.put("max_order_val", getDoubleConfig(CFG_MAX_ORDER_VAL, DEFAULT_MAX_ORDER_VAL));
        config.put("max_concentration_pct", getDoubleConfig(CFG_MAX_CONCENTRATION_PCT, DEFAULT_MAX_CONCENTRATION_PCT));
        config.put("stop_loss_pct", getDoubleConfig(CFG_STOP_LOSS_PCT, DEFAULT_STOP_LOSS_PCT));
        return config;
    }

    /**
     * Updates dynamic risk configuration in Redis.
     */
    public void updateRiskConfig(Map<String, Object> newConfig) {
        if (newConfig.containsKey("max_daily_loss")) {
            redisTemplate.opsForValue().set(CFG_MAX_DAILY_LOSS, String.valueOf(newConfig.get("max_daily_loss")));
        }
        if (newConfig.containsKey("price_collar_pct")) {
            redisTemplate.opsForValue().set(CFG_PRICE_COLLAR_PCT, String.valueOf(newConfig.get("price_collar_pct")));
        }
        if (newConfig.containsKey("velocity_per_sec")) {
            redisTemplate.opsForValue().set(CFG_VELOCITY_PER_SEC, String.valueOf(newConfig.get("velocity_per_sec")));
        }
        if (newConfig.containsKey("velocity_per_min")) {
            redisTemplate.opsForValue().set(CFG_VELOCITY_PER_MIN, String.valueOf(newConfig.get("velocity_per_min")));
        }
        if (newConfig.containsKey("max_order_qty")) {
            redisTemplate.opsForValue().set(CFG_MAX_ORDER_QTY, String.valueOf(newConfig.get("max_order_qty")));
        }
        if (newConfig.containsKey("max_order_val")) {
            redisTemplate.opsForValue().set(CFG_MAX_ORDER_VAL, String.valueOf(newConfig.get("max_order_val")));
        }
        if (newConfig.containsKey("max_concentration_pct")) {
            redisTemplate.opsForValue().set(CFG_MAX_CONCENTRATION_PCT, String.valueOf(newConfig.get("max_concentration_pct")));
        }
        if (newConfig.containsKey("stop_loss_pct")) {
            redisTemplate.opsForValue().set(CFG_STOP_LOSS_PCT, String.valueOf(newConfig.get("stop_loss_pct")));
        }
        log.info("Updated dynamic risk configuration in Redis: {}", newConfig);
    }

    private void ensureAccountCache() {
        if (redisTemplate.opsForValue().get(CASH_KEY) == null) {
            redisTemplate.opsForValue().set(CASH_KEY, String.valueOf(DEFAULT_STARTING_CASH));
            redisTemplate.opsForValue().set(BLOCKED_KEY, "0.0");
            redisTemplate.opsForValue().set(STARTING_EQUITY_KEY, String.valueOf(DEFAULT_STARTING_CASH));
        }
        if (redisTemplate.opsForValue().get(STARTING_EQUITY_KEY) == null) {
            String cash = redisTemplate.opsForValue().get(CASH_KEY);
            redisTemplate.opsForValue().set(STARTING_EQUITY_KEY, cash != null ? cash : String.valueOf(DEFAULT_STARTING_CASH));
        }
    }

    private double calculateOpenPositionsValue() {
        Set<String> keys = redisTemplate.keys(POSITION_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return 0.0;
        double totalVal = 0.0;
        for (String k : keys) {
            String posStr = redisTemplate.opsForValue().get(k);
            if (posStr != null) {
                int qty = Integer.parseInt(posStr);
                String symbol = k.substring(POSITION_KEY_PREFIX.length());
                String lastPriceStr = redisTemplate.opsForValue().get(LAST_PRICE_KEY_PREFIX + symbol);
                double price = lastPriceStr != null ? Double.parseDouble(lastPriceStr) : 100.0;
                totalVal += (qty * price);
            }
        }
        return totalVal;
    }

    private double getDoubleConfig(String key, double fallback) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Double.parseDouble(val) : fallback;
    }

    private int getIntConfig(String key, int fallback) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : fallback;
    }

    private double getDoubleState(String key, double fallback) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Double.parseDouble(val) : fallback;
    }
}

