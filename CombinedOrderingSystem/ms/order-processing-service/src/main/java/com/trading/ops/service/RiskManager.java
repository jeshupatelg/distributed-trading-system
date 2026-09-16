package com.trading.ops.service;

import com.trading.shared.config.ProviderConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RiskManager {
    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    // Redis Base State Key Templates
    public static final String CASH_KEY = "balance:cash";
    public static final String BLOCKED_KEY = "balance:blocked";
    public static final String STARTING_EQUITY_KEY = "balance:starting_equity";
    public static final String PENDING_ORDERS_KEY = "orders:pending";
    public static final String POSITION_KEY_PREFIX = "positions:";
    public static final String LAST_PRICE_KEY_PREFIX = "market:last_price:";
    public static final String KILL_SWITCH_KEY = "system:kill_switch";
    public static final String PROVIDER_STATUS_KEY_PREFIX = "provider:status:";

    // Redis Base Config Key Templates
    public static final String CFG_MAX_DAILY_LOSS = "risk:config:max_daily_loss";
    public static final String CFG_PRICE_COLLAR_PCT = "risk:config:price_collar_pct";
    public static final String CFG_VELOCITY_PER_SEC = "risk:config:velocity_per_sec";
    public static final String CFG_VELOCITY_PER_MIN = "risk:config:velocity_per_min";
    public static final String CFG_MAX_ORDER_QTY = "risk:config:max_order_qty";
    public static final String CFG_MAX_ORDER_VAL = "risk:config:max_order_val";
    public static final String CFG_MAX_CONCENTRATION_PCT = "risk:config:max_concentration_pct";
    public static final String CFG_STOP_LOSS_PCT = "risk:config:stop_loss_pct";

    // Default Fallback Thresholds
    private static final double DEFAULT_MAX_DAILY_LOSS = 2000.00;
    private static final double DEFAULT_PRICE_COLLAR_PCT = 1.50; // 1.5%
    private static final int DEFAULT_VELOCITY_PER_SEC = 5;
    private static final int DEFAULT_VELOCITY_PER_MIN = 30;
    private static final int DEFAULT_MAX_ORDER_QTY = 500;
    private static final double DEFAULT_MAX_ORDER_VAL = 25000.00;
    private static final double DEFAULT_MAX_CONCENTRATION_PCT = 20.0; // 20%
    private static final double DEFAULT_STOP_LOSS_PCT = 2.0; // 2.0%

    private final StringRedisTemplate redisTemplate;
    private final List<ProviderConfig> providerBeans;
    private final OrderExecutionClient orderExecutionClient;

    public RiskManager(StringRedisTemplate redisTemplate, List<ProviderConfig> providerBeans, @org.springframework.context.annotation.Lazy OrderExecutionClient orderExecutionClient) {
        this.redisTemplate = redisTemplate;
        this.providerBeans = providerBeans;
        this.orderExecutionClient = orderExecutionClient;
    }

    @PostConstruct
    public void initAccountCaches() {
        if (providerBeans != null && !providerBeans.isEmpty()) {
            for (ProviderConfig p : providerBeans) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    String prov = p.getName().toLowerCase().trim();
                    if (!p.isConfigComplete()) {
                        p.setActive(false);
                        redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "INACTIVE");
                        log.warn("Provider '{}' configuration is INCOMPLETE (missing endpoint, timezone, or exchange). Config: {}. Marking INACTIVE.", prov, formatSanitizedConfig(p));
                        continue;
                    }
                    log.info("Proactively probing provider connection manager health on startup: '{}'", prov);
                    boolean healthy = orderExecutionClient != null && orderExecutionClient.checkProviderHealth(prov);
                    if (healthy) {
                        log.info("Provider '{}' connection manager is reachable. Performing Redis account cache init-check.", prov);
                        boolean cacheValid = ensureAccountCache(prov);
                        if (cacheValid) {
                            p.setActive(true);
                            redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "ACTIVE");
                            log.info("Provider '{}' connection manager & account cache are ACTIVE.", prov);
                        }
                    } else {
                        p.setActive(false);
                        redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "INACTIVE");
                        log.warn("Provider '{}' connection manager is UNREACHABLE/UNHEALTHY. Config: {}. Status set to INACTIVE.", prov, formatSanitizedConfig(p));
                    }
                }
            }
        }
    }

    public record RiskDecision(boolean approved, String reason, String riskGateLevel, double calculatedCost, double stopLossPrice) {}

    public ProviderConfig findProviderConfig(String provider) {
        if (provider == null || provider.isBlank() || providerBeans == null) {
            return null;
        }
        String prov = provider.toLowerCase().trim();
        for (ProviderConfig p : providerBeans) {
            if (prov.equalsIgnoreCase(p.getName())) {
                return p;
            }
        }
        return null;
    }

    private String formatSanitizedConfig(ProviderConfig config) {
        if (config == null) {
            return "null";
        }
        return "ProviderConfig{name='" + config.getName() + '\'' +
                ", timezone='" + config.getTimezone() + '\'' +
                ", exchange='" + config.getExchange() + '\'' +
                ", enabled=" + config.isEnabled() +
                ", active=" + config.isActive() +
                ", isComplete=" + config.isConfigComplete() + '}';
    }

    public void markProviderInactive(String provider) {
        String prov = normalizeProvider(provider);
        ProviderConfig config = findProviderConfig(prov);
        if (config != null) {
            config.setActive(false);
        }
        redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "INACTIVE");
        log.warn("Marked provider '{}' INACTIVE in-memory and in Redis. Config: {}", prov, formatSanitizedConfig(config));
    }

    public void markProviderActive(String provider) {
        String prov = normalizeProvider(provider);
        if (!ensureAccountCache(prov)) {
            log.warn("Cannot mark provider '{}' ACTIVE: Redis account cache init-check failed.", prov);
            return;
        }
        ProviderConfig config = findProviderConfig(prov);
        if (config != null) {
            config.setActive(true);
        }
        redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "ACTIVE");
        log.info("Marked provider '{}' ACTIVE in-memory and in Redis.", prov);
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        return provider.toLowerCase().trim();
    }

    private String getProviderKey(String baseKey, String provider) {
        return baseKey + ":" + normalizeProvider(provider);
    }

    /**
     * Executes the comprehensive Phase 1 Pre-Trade Risk Firewall evaluation.
     * All Redis keys are namespaced by the broker provider (e.g. balance:cash:alpaca, risk:config:max_order_val:megabull).
     */
    public synchronized RiskDecision evaluateAndLock(String orderId, String symbol, int qty, double price, String side, String provider) {
        String prov = normalizeProvider(provider);
        double estimatedCost = price * qty;

        // 1. Emergency Kill Switch Gate (Global OR Provider-specific)
        String globalKill = redisTemplate.opsForValue().get(KILL_SWITCH_KEY);
        String provKill = redisTemplate.opsForValue().get(getProviderKey(KILL_SWITCH_KEY, prov));
        if ("true".equalsIgnoreCase(globalKill) || "true".equalsIgnoreCase(provKill)) {
            log.warn("RISK REJECTED: Emergency Kill Switch is ACTIVE for provider {}. Dropping order {} for {}", prov, orderId, symbol);
            return new RiskDecision(false, "KILL_SWITCH_ACTIVE", "KILL_SWITCH", estimatedCost, 0.0);
        }

        ProviderConfig config = findProviderConfig(prov);
        String cashKey = getProviderKey(CASH_KEY, prov);
        String blockedKey = getProviderKey(BLOCKED_KEY, prov);
        String startingEquityKey = getProviderKey(STARTING_EQUITY_KEY, prov);
        String pendingOrdersKey = getProviderKey(PENDING_ORDERS_KEY, prov);

        // Fast in-memory check (< 1µs) on ProviderConfig active flag and complete configuration
        if (config == null || !config.isConfigComplete() || !config.isActive() || redisTemplate.opsForValue().get(cashKey) == null) {
            log.warn("RISK REJECTED: Provider '{}' configuration incomplete, inactive, or balance cache missing in Redis. Config: {}. Rejecting order {}", prov, formatSanitizedConfig(config), orderId);
            return new RiskDecision(false, "PROVIDER_UNINITIALIZED_OR_INACTIVE", "PROVIDER_HEALTH", estimatedCost, 0.0);
        }

        // 2. Daily Loss Gate Check
        double maxDailyLoss = getDoubleConfig(getProviderKey(CFG_MAX_DAILY_LOSS, prov), DEFAULT_MAX_DAILY_LOSS);
        double startingEquity = getDoubleState(startingEquityKey, 0.0);
        double currentCash = getDoubleState(cashKey, 0.0);
        double blockedMargin = getDoubleState(blockedKey, 0.0);
        double positionsVal = calculateOpenPositionsValue(prov);
        double totalEquity = currentCash + positionsVal;
        double currentDailyDrawdown = startingEquity - totalEquity;

        if (maxDailyLoss > 0 && currentDailyDrawdown >= maxDailyLoss) {
            log.warn("RISK REJECTED: Daily Loss Limit Breach for provider {}. StartingEquity={}, TotalEquity={}, Drawdown={}, Cap={}",
                prov, startingEquity, totalEquity, currentDailyDrawdown, maxDailyLoss);
            return new RiskDecision(false, "DAILY_LOSS_LIMIT_EXCEEDED", "DAILY_LOSS", estimatedCost, 0.0);
        }

        // 3. Price Collar Check (Stale / Excessive Deviation Check against Redis Reference Price)
        String lastPriceKey = LAST_PRICE_KEY_PREFIX + prov + ":" + symbol;
        String refPriceStr = redisTemplate.opsForValue().get(lastPriceKey);
        if (refPriceStr == null) {
            refPriceStr = redisTemplate.opsForValue().get(LAST_PRICE_KEY_PREFIX + symbol);
        }
        if (refPriceStr != null) {
            double refPrice = Double.parseDouble(refPriceStr);
            double priceCollarPct = getDoubleConfig(getProviderKey(CFG_PRICE_COLLAR_PCT, prov), DEFAULT_PRICE_COLLAR_PCT);
            double maxAllowedDiff = refPrice * (priceCollarPct / 100.0);
            if (Math.abs(price - refPrice) > maxAllowedDiff) {
                log.warn("RISK REJECTED: Price Collar Violation for provider {} symbol {}. Signal Price={}, Ref Price={}, Max Collar Pct={}%",
                    prov, symbol, price, refPrice, priceCollarPct);
                return new RiskDecision(false, "PRICE_COLLAR_VIOLATION", "PRICE_COLLAR", estimatedCost, 0.0);
            }
        }

        // 4. Velocity Rate Limiting Gates (Per Second & Per Minute Window)
        int maxPerSec = getIntConfig(getProviderKey(CFG_VELOCITY_PER_SEC, prov), DEFAULT_VELOCITY_PER_SEC);
        int maxPerMin = getIntConfig(getProviderKey(CFG_VELOCITY_PER_MIN, prov), DEFAULT_VELOCITY_PER_MIN);
        long nowSec = Instant.now().getEpochSecond();
        long nowMin = nowSec / 60;

        String secKey = "risk:velocity:sec:" + prov + ":" + nowSec;
        String minKey = "risk:velocity:min:" + prov + ":" + nowMin;

        Long secCount = redisTemplate.opsForValue().increment(secKey);
        if (secCount != null && secCount == 1) redisTemplate.expire(secKey, Duration.ofSeconds(2));

        Long minCount = redisTemplate.opsForValue().increment(minKey);
        if (minCount != null && minCount == 1) redisTemplate.expire(secKey, Duration.ofSeconds(120));

        if (secCount != null && secCount > maxPerSec) {
            log.warn("RISK REJECTED: Velocity Gate (Sec) Exceeded for provider {}. Count={}, Cap={}", prov, secCount, maxPerSec);
            return new RiskDecision(false, "VELOCITY_PER_SECOND_EXCEEDED", "VELOCITY_SEC", estimatedCost, 0.0);
        }

        if (minCount != null && minCount > maxPerMin) {
            log.warn("RISK REJECTED: Velocity Gate (Min) Exceeded for provider {}. Count={}, Cap={}", prov, minCount, maxPerMin);
            return new RiskDecision(false, "VELOCITY_PER_MINUTE_EXCEEDED", "VELOCITY_MIN", estimatedCost, 0.0);
        }

        // 5. Single Order Limits (Qty & Value Cap)
        int maxOrderQty = getIntConfig(getProviderKey(CFG_MAX_ORDER_QTY, prov), DEFAULT_MAX_ORDER_QTY);
        double maxOrderVal = getDoubleConfig(getProviderKey(CFG_MAX_ORDER_VAL, prov), DEFAULT_MAX_ORDER_VAL);

        if (qty > maxOrderQty) {
            log.warn("RISK REJECTED: Max Order Qty Violation for provider {}. Qty={}, Cap={}", prov, qty, maxOrderQty);
            return new RiskDecision(false, "MAX_ORDER_QTY_EXCEEDED", "SINGLE_ORDER_LIMIT", estimatedCost, 0.0);
        }

        if (estimatedCost > maxOrderVal) {
            log.warn("RISK REJECTED: Max Order Value Violation for provider {}. Value={}, Cap={}", prov, estimatedCost, maxOrderVal);
            return new RiskDecision(false, "MAX_ORDER_VALUE_EXCEEDED", "SINGLE_ORDER_LIMIT", estimatedCost, 0.0);
        }

        // 6. Portfolio Concentration Limit Gate
        double maxConcentrationPct = getDoubleConfig(getProviderKey(CFG_MAX_CONCENTRATION_PCT, prov), DEFAULT_MAX_CONCENTRATION_PCT);
        if (totalEquity > 0) {
            double maxAllowedSymbolValue = totalEquity * (maxConcentrationPct / 100.0);
            if (estimatedCost > maxAllowedSymbolValue) {
                log.warn("RISK REJECTED: Portfolio Concentration Cap Violation for provider {}. Order Cost={}, Max Symbol Alloc={} ({}% of Equity {})",
                    prov, estimatedCost, maxAllowedSymbolValue, maxConcentrationPct, totalEquity);
                return new RiskDecision(false, "MAX_CONCENTRATION_EXCEEDED", "PORTFOLIO_CONCENTRATION", estimatedCost, 0.0);
            }
        }

        // 7. Margin Availability & Account Balance Locking Gate
        double availableCash = currentCash - blockedMargin;
        if (availableCash < estimatedCost) {
            log.warn("RISK REJECTED: Insufficient Margin for provider {}. Required={}, AvailableCash={} (Cash={}, Blocked={})",
                prov, estimatedCost, availableCash, currentCash, blockedMargin);
            return new RiskDecision(false, "INSUFFICIENT_MARGIN", "MARGIN_LOCK", estimatedCost, 0.0);
        }

        // All Risk Gates Passed! Execute Margin Lock in Redis.
        redisTemplate.opsForValue().increment(blockedKey, estimatedCost);
        redisTemplate.opsForSet().add(pendingOrdersKey, orderId);

        // Compute Dynamic Stop Loss Price
        double stopLossPct = getDoubleConfig(getProviderKey(CFG_STOP_LOSS_PCT, prov), DEFAULT_STOP_LOSS_PCT);
        double stopLossPrice = 0.0;
        if ("BUY".equalsIgnoreCase(side)) {
            stopLossPrice = price * (1.0 - (stopLossPct / 100.0));
        } else if ("SELL".equalsIgnoreCase(side)) {
            stopLossPrice = price * (1.0 + (stopLossPct / 100.0));
        }

        log.info("Risk checks PASSED for order {} (provider {}). Margin locked: {}, Stop Loss Price: {}", orderId, prov, estimatedCost, String.format("%.2f", stopLossPrice));
        return new RiskDecision(true, "APPROVED", "NONE", estimatedCost, stopLossPrice);
    }

    public synchronized boolean validateAndLock(String orderId, double estimatedValue, String provider) {
        String prov = normalizeProvider(provider);
        String cashKey = getProviderKey(CASH_KEY, prov);
        String blockedKey = getProviderKey(BLOCKED_KEY, prov);
        String pendingKey = getProviderKey(PENDING_ORDERS_KEY, prov);

        double cash = getDoubleState(cashKey, 0.0);
        double blocked = getDoubleState(blockedKey, 0.0);
        double available = cash - blocked;
        if (available >= estimatedValue) {
            redisTemplate.opsForValue().increment(blockedKey, estimatedValue);
            redisTemplate.opsForSet().add(pendingKey, orderId);
            return true;
        }
        return false;
    }

    /**
     * Reverts a margin lock in case of submission failure or cancellation.
     */
    public synchronized void revertLock(String orderId, double estimatedValue, String provider) {
        String prov = normalizeProvider(provider);
        String blockedKey = getProviderKey(BLOCKED_KEY, prov);
        String pendingKey = getProviderKey(PENDING_ORDERS_KEY, prov);

        redisTemplate.opsForValue().increment(blockedKey, -estimatedValue);
        redisTemplate.opsForSet().remove(pendingKey, orderId);
        log.info("Reverted margin lock for order {} (provider {}): freed {}", orderId, prov, estimatedValue);
    }

    public synchronized void triggerKillSwitch() {
        triggerKillSwitch(null);
    }

    public synchronized void triggerKillSwitch(String provider) {
        if (provider == null || provider.isBlank()) {
            redisTemplate.opsForValue().set(KILL_SWITCH_KEY, "true");
            log.warn("EMERGENCY GLOBAL KILL SWITCH ACTIVATED in Redis!");
        } else {
            String provKey = getProviderKey(KILL_SWITCH_KEY, provider);
            redisTemplate.opsForValue().set(provKey, "true");
            log.warn("EMERGENCY KILL SWITCH ACTIVATED for provider {} in Redis!", provider);
        }
    }

    public synchronized void resetKillSwitch() {
        resetKillSwitch(null);
    }

    public synchronized void resetKillSwitch(String provider) {
        if (provider == null || provider.isBlank()) {
            redisTemplate.opsForValue().set(KILL_SWITCH_KEY, "false");
            log.info("Global Emergency Kill Switch RESET.");
        } else {
            String provKey = getProviderKey(KILL_SWITCH_KEY, provider);
            redisTemplate.opsForValue().set(provKey, "false");
            log.info("Emergency Kill Switch RESET for provider {}.", provider);
        }
    }

    public Map<String, Object> getRiskStatus(String provider) {
        String prov = normalizeProvider(provider);
        Map<String, Object> status = new HashMap<>();
        String cashKey = getProviderKey(CASH_KEY, prov);
        String blockedKey = getProviderKey(BLOCKED_KEY, prov);
        String startingEquityKey = getProviderKey(STARTING_EQUITY_KEY, prov);

        double cash = getDoubleState(cashKey, 0.0);
        double blocked = getDoubleState(blockedKey, 0.0);
        double startingEquity = getDoubleState(startingEquityKey, 0.0);
        double positionsVal = calculateOpenPositionsValue(prov);
        double totalEquity = cash + positionsVal;
        double dailyDrawdown = startingEquity - totalEquity;
        double maxDailyLoss = getDoubleConfig(getProviderKey(CFG_MAX_DAILY_LOSS, prov), DEFAULT_MAX_DAILY_LOSS);
        String killSwitch = redisTemplate.opsForValue().get(getProviderKey(KILL_SWITCH_KEY, prov));
        String globalKill = redisTemplate.opsForValue().get(KILL_SWITCH_KEY);

        status.put("provider", prov);
        status.put("kill_switch_active", "true".equalsIgnoreCase(killSwitch) || "true".equalsIgnoreCase(globalKill));
        status.put("cash_balance", cash);
        status.put("blocked_margin", blocked);
        status.put("starting_equity", startingEquity);
        status.put("open_positions_value", positionsVal);
        status.put("total_equity", totalEquity);
        status.put("daily_drawdown", dailyDrawdown);
        status.put("max_daily_loss", maxDailyLoss);
        status.put("daily_loss_pct", maxDailyLoss > 0 ? (dailyDrawdown / maxDailyLoss) * 100.0 : 0.0);
        status.put("config", getRiskConfig(prov));
        return status;
    }

    public Map<String, Object> getRiskConfig(String provider) {
        String prov = normalizeProvider(provider);
        Map<String, Object> config = new HashMap<>();
        config.put("max_daily_loss", getDoubleConfig(getProviderKey(CFG_MAX_DAILY_LOSS, prov), DEFAULT_MAX_DAILY_LOSS));
        config.put("price_collar_pct", getDoubleConfig(getProviderKey(CFG_PRICE_COLLAR_PCT, prov), DEFAULT_PRICE_COLLAR_PCT));
        config.put("velocity_per_sec", getIntConfig(getProviderKey(CFG_VELOCITY_PER_SEC, prov), DEFAULT_VELOCITY_PER_SEC));
        config.put("velocity_per_min", getIntConfig(getProviderKey(CFG_VELOCITY_PER_MIN, prov), DEFAULT_VELOCITY_PER_MIN));
        config.put("max_order_qty", getIntConfig(getProviderKey(CFG_MAX_ORDER_QTY, prov), DEFAULT_MAX_ORDER_QTY));
        config.put("max_order_val", getDoubleConfig(getProviderKey(CFG_MAX_ORDER_VAL, prov), DEFAULT_MAX_ORDER_VAL));
        config.put("max_concentration_pct", getDoubleConfig(getProviderKey(CFG_MAX_CONCENTRATION_PCT, prov), DEFAULT_MAX_CONCENTRATION_PCT));
        config.put("stop_loss_pct", getDoubleConfig(getProviderKey(CFG_STOP_LOSS_PCT, prov), DEFAULT_STOP_LOSS_PCT));
        return config;
    }

    public void updateRiskConfig(Map<String, Object> newConfig, String provider) {
        String prov = normalizeProvider(provider);
        if (newConfig.containsKey("max_daily_loss")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_MAX_DAILY_LOSS, prov), String.valueOf(newConfig.get("max_daily_loss")));
        }
        if (newConfig.containsKey("price_collar_pct")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_PRICE_COLLAR_PCT, prov), String.valueOf(newConfig.get("price_collar_pct")));
        }
        if (newConfig.containsKey("velocity_per_sec")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_VELOCITY_PER_SEC, prov), String.valueOf(newConfig.get("velocity_per_sec")));
        }
        if (newConfig.containsKey("velocity_per_min")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_VELOCITY_PER_MIN, prov), String.valueOf(newConfig.get("velocity_per_min")));
        }
        if (newConfig.containsKey("max_order_qty")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_MAX_ORDER_QTY, prov), String.valueOf(newConfig.get("max_order_qty")));
        }
        if (newConfig.containsKey("max_order_val")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_MAX_ORDER_VAL, prov), String.valueOf(newConfig.get("max_order_val")));
        }
        if (newConfig.containsKey("max_concentration_pct")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_MAX_CONCENTRATION_PCT, prov), String.valueOf(newConfig.get("max_concentration_pct")));
        }
        if (newConfig.containsKey("stop_loss_pct")) {
            redisTemplate.opsForValue().set(getProviderKey(CFG_STOP_LOSS_PCT, prov), String.valueOf(newConfig.get("stop_loss_pct")));
        }
        log.info("Updated dynamic risk configuration in Redis for provider {}: {}", prov, newConfig);
    }

    private boolean ensureAccountCache(String provider) {
        String prov = normalizeProvider(provider);
        String cashKey = getProviderKey(CASH_KEY, prov);
        String blockedKey = getProviderKey(BLOCKED_KEY, prov);
        String startingEquityKey = getProviderKey(STARTING_EQUITY_KEY, prov);

        String cash = redisTemplate.opsForValue().get(cashKey);
        String blocked = redisTemplate.opsForValue().get(blockedKey);
        String startingEquity = redisTemplate.opsForValue().get(startingEquityKey);

        if (cash == null || blocked == null || startingEquity == null) {
            log.warn("Account cache init-check FAILED for provider '{}'. Missing Redis state keys (cash={}, blocked={}, startingEquity={}). Marking provider INACTIVE.",
                prov, cash != null, blocked != null, startingEquity != null);
            markProviderInactive(prov);
            return false;
        }
        return true;
    }

    private double calculateOpenPositionsValue(String provider) {
        String prov = normalizeProvider(provider);
        Set<String> keys = redisTemplate.keys(POSITION_KEY_PREFIX + prov + ":*");
        if (keys == null || keys.isEmpty()) {
            keys = redisTemplate.keys(POSITION_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) return 0.0;
        }
        double totalVal = 0.0;
        for (String k : keys) {
            String posStr = redisTemplate.opsForValue().get(k);
            if (posStr != null) {
                int qty = Integer.parseInt(posStr);
                String symbol = k.replace(POSITION_KEY_PREFIX + prov + ":", "").replace(POSITION_KEY_PREFIX, "");
                String lastPriceKey = LAST_PRICE_KEY_PREFIX + prov + ":" + symbol;
                String lastPriceStr = redisTemplate.opsForValue().get(lastPriceKey);
                if (lastPriceStr == null) {
                    lastPriceStr = redisTemplate.opsForValue().get(LAST_PRICE_KEY_PREFIX + symbol);
                }
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
