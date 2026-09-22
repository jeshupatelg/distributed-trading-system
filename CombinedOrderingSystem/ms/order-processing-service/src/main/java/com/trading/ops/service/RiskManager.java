package com.trading.ops.service;

import com.trading.shared.config.ProviderConfig;
import com.trading.shared.redis.MissingRedisStateException;
import com.trading.shared.redis.RedisKeyBuilder;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import com.trading.shared.state.ProviderStateManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final TradingRedisFacade redisFacade;
    private final ProviderStateManager providerStateManager;
    private final List<ProviderConfig> providerBeans;
    private final OrderExecutionClient orderExecutionClient;

    public RiskManager(TradingRedisFacade redisFacade,
                       ProviderStateManager providerStateManager,
                       List<ProviderConfig> providerBeans,
                       @org.springframework.context.annotation.Lazy OrderExecutionClient orderExecutionClient) {
        this.redisFacade = redisFacade;
        this.providerStateManager = providerStateManager;
        this.providerBeans = providerBeans;
        this.orderExecutionClient = orderExecutionClient;
    }

    @PostConstruct
    public void initAccountCaches() {
        if (providerBeans != null && !providerBeans.isEmpty()) {
            for (ProviderConfig p : providerBeans) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    String prov = normalizeProvider(p.getName());
                    if (!p.isConfigComplete()) {
                        p.setActive(false);
                        providerStateManager.markProviderInactive(prov);
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
                            providerStateManager.markProviderActive(prov);
                            log.info("Provider '{}' connection manager & account cache are ACTIVE.", prov);
                        }
                    } else {
                        p.setActive(false);
                        providerStateManager.markProviderInactive(prov);
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
        String prov = normalizeProvider(provider);
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
        providerStateManager.markProviderInactive(prov);
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
        providerStateManager.markProviderActive(prov);
        log.info("Marked provider '{}' ACTIVE in-memory and in Redis.", prov);
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        return provider.toLowerCase().trim();
    }

    /**
     * Executes the comprehensive Phase 1 Pre-Trade Risk Firewall evaluation.
     * All Redis keys are namespaced by the broker provider in accordance with ADR-005.
     */
    public synchronized RiskDecision evaluateAndLock(String orderId, String symbol, int qty, double price, String side, String provider) {
        String prov = normalizeProvider(provider);
        double estimatedCost = price * qty;

        // 1. Emergency Kill Switch Gate (Global OR Provider-specific)
        boolean globalKill = redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL);
        boolean provKill = redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_PROVIDER, prov);
        if (globalKill || provKill) {
            log.warn("RISK REJECTED: Emergency Kill Switch is ACTIVE for provider {}. Dropping order {} for {}", prov, orderId, symbol);
            return new RiskDecision(false, "KILL_SWITCH_ACTIVE", "KILL_SWITCH", estimatedCost, 0.0);
        }

        ProviderConfig config = findProviderConfig(prov);
        String cashKey = RedisKeyBuilder.key(RedisKeyDef.BALANCE_CASH, prov);

        // Fast in-memory check (< 1µs) on ProviderConfig active flag and complete configuration
        if (config == null || !config.isConfigComplete() || !config.isActive() || !redisFacade.hasKey(cashKey)) {
            log.warn("RISK REJECTED: Provider '{}' configuration incomplete, inactive, or balance cache missing in Redis. Config: {}. Rejecting order {}", prov, formatSanitizedConfig(config), orderId);
            return new RiskDecision(false, "PROVIDER_UNINITIALIZED_OR_INACTIVE", "PROVIDER_HEALTH", estimatedCost, 0.0);
        }

        // 2. Daily Loss Gate Check (Strictly Fail-Fast if limits/balances missing)
        try {
            double maxDailyLoss = redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS, prov);
            double startingEquity = redisFacade.getDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, prov);
            double currentCash = redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, prov);
            double blockedMargin = redisFacade.getDouble(RedisKeyDef.BALANCE_BLOCKED, prov);
            double positionsVal = calculateOpenPositionsValue(prov);
            double totalEquity = currentCash + positionsVal;
            double currentDailyDrawdown = startingEquity - totalEquity;

            if (maxDailyLoss > 0 && currentDailyDrawdown >= maxDailyLoss) {
                log.warn("RISK REJECTED: Daily Loss Limit Breach for provider {}. StartingEquity={}, TotalEquity={}, Drawdown={}, Cap={}",
                    prov, startingEquity, totalEquity, currentDailyDrawdown, maxDailyLoss);
                return new RiskDecision(false, "DAILY_LOSS_LIMIT_EXCEEDED", "DAILY_LOSS", estimatedCost, 0.0);
            }

            // 3. Price Collar Check (Deviation Check against Redis Reference Price)
            try {
                double refPrice = redisFacade.getMarketPrice(prov, symbol);
                double priceCollarPct = redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_PRICE_COLLAR_PCT, prov);
                double maxAllowedDiff = refPrice * (priceCollarPct / 100.0);
                if (Math.abs(price - refPrice) > maxAllowedDiff) {
                    log.warn("RISK REJECTED: Price Collar Violation for provider {} symbol {}. Signal Price={}, Ref Price={}, Max Collar Pct={}%",
                        prov, symbol, price, refPrice, priceCollarPct);
                    return new RiskDecision(false, "PRICE_COLLAR_VIOLATION", "PRICE_COLLAR", estimatedCost, 0.0);
                }
            } catch (MissingRedisStateException e) {
                log.warn("Market reference price missing for provider '{}' symbol '{}': {}. Bypassing price collar check.",
                    prov, symbol, e.getMessage());
            }

            // 4. Velocity Rate Limiting Gates (Per Second & Per Minute Window)
            int maxPerSec = redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_SEC, prov);
            int maxPerMin = redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_MIN, prov);
            long nowSec = Instant.now().getEpochSecond();
            long nowMin = nowSec / 60;

            String secKey = "risk:velocity:sec:" + prov + ":" + nowSec;
            String minKey = "risk:velocity:min:" + prov + ":" + nowMin;

            Long secCount = redisFacade.increment(secKey, 1L);
            if (secCount != null && secCount == 1) redisFacade.expire(secKey, Duration.ofSeconds(2));

            Long minCount = redisFacade.increment(minKey, 1L);
            if (minCount != null && minCount == 1) redisFacade.expire(minKey, Duration.ofSeconds(120));

            if (secCount != null && secCount > maxPerSec) {
                log.warn("RISK REJECTED: Velocity Gate (Sec) Exceeded for provider {}. Count={}, Cap={}", prov, secCount, maxPerSec);
                return new RiskDecision(false, "VELOCITY_PER_SECOND_EXCEEDED", "VELOCITY_SEC", estimatedCost, 0.0);
            }

            if (minCount != null && minCount > maxPerMin) {
                log.warn("RISK REJECTED: Velocity Gate (Min) Exceeded for provider {}. Count={}, Cap={}", prov, minCount, maxPerMin);
                return new RiskDecision(false, "VELOCITY_PER_MINUTE_EXCEEDED", "VELOCITY_MIN", estimatedCost, 0.0);
            }

            // 5. Single Order Limits (Qty & Value Cap)
            int maxOrderQty = redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_MAX_ORDER_QTY, prov);
            double maxOrderVal = redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL, prov);

            if (qty > maxOrderQty) {
                log.warn("RISK REJECTED: Max Order Qty Violation for provider {}. Qty={}, Cap={}", prov, qty, maxOrderQty);
                return new RiskDecision(false, "MAX_ORDER_QTY_EXCEEDED", "SINGLE_ORDER_LIMIT", estimatedCost, 0.0);
            }

            if (estimatedCost > maxOrderVal) {
                log.warn("RISK REJECTED: Max Order Value Violation for provider {}. Value={}, Cap={}", prov, estimatedCost, maxOrderVal);
                return new RiskDecision(false, "MAX_ORDER_VALUE_EXCEEDED", "SINGLE_ORDER_LIMIT", estimatedCost, 0.0);
            }

            // 6. Portfolio Concentration Limit Gate
            double maxConcentrationPct = redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_CONCENTRATION_PCT, prov);
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
            String blockedKey = RedisKeyBuilder.key(RedisKeyDef.BALANCE_BLOCKED, prov);
            redisFacade.increment(blockedKey, estimatedCost);
            redisFacade.addToSet(RedisKeyDef.ORDERS_PENDING, prov, orderId);

            // Compute Dynamic Stop Loss Price
            double stopLossPct = redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_STOP_LOSS_PCT, prov);
            double stopLossPrice = 0.0;
            if ("BUY".equalsIgnoreCase(side)) {
                stopLossPrice = price * (1.0 - (stopLossPct / 100.0));
            } else if ("SELL".equalsIgnoreCase(side)) {
                stopLossPrice = price * (1.0 + (stopLossPct / 100.0));
            }

            log.info("Risk checks PASSED for order {} (provider {}). Margin locked: {}, Stop Loss Price: {}", orderId, prov, estimatedCost, String.format("%.2f", stopLossPrice));
            return new RiskDecision(true, "APPROVED", "NONE", estimatedCost, stopLossPrice);

        } catch (MissingRedisStateException e) {
            log.warn("RISK REJECTED: Mandatory Redis risk/account state missing for provider '{}': {}. Rejecting order {}",
                prov, e.getMessage(), orderId);
            return new RiskDecision(false, "MISSING_RISK_STATE: " + e.getMessage(), "RISK_CONFIGURATION", estimatedCost, 0.0);
        }
    }

    public synchronized boolean validateAndLock(String orderId, double estimatedValue, String provider) {
        String prov = normalizeProvider(provider);
        try {
            double cash = redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, prov);
            double blocked = redisFacade.getDouble(RedisKeyDef.BALANCE_BLOCKED, prov);
            double available = cash - blocked;
            if (available >= estimatedValue) {
                String blockedKey = RedisKeyBuilder.key(RedisKeyDef.BALANCE_BLOCKED, prov);
                redisFacade.increment(blockedKey, estimatedValue);
                redisFacade.addToSet(RedisKeyDef.ORDERS_PENDING, prov, orderId);
                return true;
            }
            return false;
        } catch (MissingRedisStateException e) {
            log.warn("validateAndLock failed: Missing state in Redis for provider '{}': {}", prov, e.getMessage());
            return false;
        }
    }

    /**
     * Reverts a margin lock in case of submission failure or cancellation.
     */
    public synchronized void revertLock(String orderId, double estimatedValue, String provider) {
        String prov = normalizeProvider(provider);
        String blockedKey = RedisKeyBuilder.key(RedisKeyDef.BALANCE_BLOCKED, prov);
        redisFacade.increment(blockedKey, -estimatedValue);
        redisFacade.removeFromSet(RedisKeyDef.ORDERS_PENDING, prov, orderId);
        log.info("Reverted margin lock for order {} (provider {}): freed {}", orderId, prov, estimatedValue);
    }

    public synchronized void triggerKillSwitch() {
        triggerKillSwitch(null);
    }

    public synchronized void triggerKillSwitch(String provider) {
        if (provider == null || provider.isBlank()) {
            redisFacade.setBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL, true);
            log.warn("EMERGENCY GLOBAL KILL SWITCH ACTIVATED in Redis!");
        } else {
            String prov = normalizeProvider(provider);
            redisFacade.setBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_PROVIDER, prov, true);
            log.warn("EMERGENCY KILL SWITCH ACTIVATED for provider {} in Redis!", prov);
        }
    }

    public synchronized void resetKillSwitch() {
        resetKillSwitch(null);
    }

    public synchronized void resetKillSwitch(String provider) {
        if (provider == null || provider.isBlank()) {
            redisFacade.setBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL, false);
            log.info("Global Emergency Kill Switch RESET.");
        } else {
            String prov = normalizeProvider(provider);
            redisFacade.setBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_PROVIDER, prov, false);
            log.info("Emergency Kill Switch RESET for provider {}.", prov);
        }
    }

    public Map<String, Object> getRiskStatus(String provider) {
        String prov = normalizeProvider(provider);
        Map<String, Object> status = new HashMap<>();

        double cash = redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, prov);
        double blocked = redisFacade.getDouble(RedisKeyDef.BALANCE_BLOCKED, prov);
        double startingEquity = redisFacade.getDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, prov);
        double positionsVal = calculateOpenPositionsValue(prov);
        double totalEquity = cash + positionsVal;
        double dailyDrawdown = startingEquity - totalEquity;
        double maxDailyLoss = redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS, prov);

        boolean globalKill = redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL);
        boolean provKill = redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_PROVIDER, prov);

        status.put("provider", prov);
        status.put("kill_switch_active", globalKill || provKill);
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
        config.put("max_daily_loss", redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS, prov));
        config.put("price_collar_pct", redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_PRICE_COLLAR_PCT, prov));
        config.put("velocity_per_sec", redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_SEC, prov));
        config.put("velocity_per_min", redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_MIN, prov));
        config.put("max_order_qty", redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_MAX_ORDER_QTY, prov));
        config.put("max_order_val", redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL, prov));
        config.put("max_concentration_pct", redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_CONCENTRATION_PCT, prov));
        config.put("stop_loss_pct", redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_STOP_LOSS_PCT, prov));
        return config;
    }

    public void updateRiskConfig(Map<String, Object> newConfig, String provider) {
        String prov = normalizeProvider(provider);
        if (newConfig.containsKey("max_daily_loss")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS, prov, String.valueOf(newConfig.get("max_daily_loss")));
        }
        if (newConfig.containsKey("price_collar_pct")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_PRICE_COLLAR_PCT, prov, String.valueOf(newConfig.get("price_collar_pct")));
        }
        if (newConfig.containsKey("velocity_per_sec")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_SEC, prov, String.valueOf(newConfig.get("velocity_per_sec")));
        }
        if (newConfig.containsKey("velocity_per_min")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_MIN, prov, String.valueOf(newConfig.get("velocity_per_min")));
        }
        if (newConfig.containsKey("max_order_qty")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_MAX_ORDER_QTY, prov, String.valueOf(newConfig.get("max_order_qty")));
        }
        if (newConfig.containsKey("max_order_val")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL, prov, String.valueOf(newConfig.get("max_order_val")));
        }
        if (newConfig.containsKey("max_concentration_pct")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_MAX_CONCENTRATION_PCT, prov, String.valueOf(newConfig.get("max_concentration_pct")));
        }
        if (newConfig.containsKey("stop_loss_pct")) {
            redisFacade.setString(RedisKeyDef.RISK_CONFIG_STOP_LOSS_PCT, prov, String.valueOf(newConfig.get("stop_loss_pct")));
        }
        log.info("Updated dynamic risk configuration in Redis for provider {}: {}", prov, newConfig);
    }

    private boolean ensureAccountCache(String provider) {
        String prov = normalizeProvider(provider);
        List<RedisKeyDef> missing = providerStateManager.getMissingRequiredProviderKeys(prov);

        if (!missing.isEmpty()) {
            log.warn("Account state init-check FAILED for provider '{}'. Missing mandatory non-defaultable Redis keys: {}. Marking provider INACTIVE.",
                prov, missing);
            markProviderInactive(prov);
            return false;
        }
        return true;
    }

    private double calculateOpenPositionsValue(String provider) {
        String prov = normalizeProvider(provider);
        String posPrefix = "positions:" + prov + ":";
        Set<String> keys = redisFacade.redisTemplate().keys(posPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return 0.0;
        }
        double totalVal = 0.0;
        for (String k : keys) {
            String posStr = redisFacade.redisTemplate().opsForValue().get(k);
            if (posStr != null) {
                int qty = Integer.parseInt(posStr.trim());
                String symbol = k.substring(posPrefix.length()).toUpperCase();
                try {
                    double price = redisFacade.getMarketPrice(prov, symbol);
                    totalVal += (qty * price);
                } catch (MissingRedisStateException e) {
                    log.warn("Missing market reference price for open position calculation of symbol '{}' (provider '{}'): {}",
                        symbol, prov, e.getMessage());
                }
            }
        }
        return totalVal;
    }
}
