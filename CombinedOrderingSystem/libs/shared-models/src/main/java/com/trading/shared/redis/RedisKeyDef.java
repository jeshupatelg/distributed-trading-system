package com.trading.shared.redis;

/**
 * Authoritative Single Source of Truth (SSOT) defining all Redis keys, scopes, data types,
 * and defaulting policies for the Distributed Trading System.
 */
public enum RedisKeyDef {

    // --- 1. Account Balances & Equity Domain ---
    BALANCE_CASH(
            "balance:cash:%s",
            KeyScope.PROVIDER,
            Double.class,
            false,
            null,
            null,
            "Live cash balance for broker provider"
    ),
    BALANCE_BLOCKED(
            "balance:blocked:%s",
            KeyScope.PROVIDER,
            Double.class,
            false,
            null,
            null,
            "Reserved/blocked margin for active working orders per provider (Fail-Fast: must be explicitly initialized to 0.0)"
    ),
    BALANCE_STARTING_EQUITY(
            "balance:starting_equity:%s",
            KeyScope.PROVIDER,
            Double.class,
            false,
            null,
            null,
            "Opening equity baseline captured at daily rollover for drawdown calculation"
    ),
    BALANCE_LAST_RESET_DATE(
            "balance:last_reset_date:%s",
            KeyScope.PROVIDER,
            String.class,
            false,
            null,
            null,
            "ISO date string recording the last equity rollover date"
    ),

    // --- 2. Portfolio Positions Domain ---
    POSITIONS(
            "positions:%s:%s",
            KeyScope.PROVIDER_AND_SYMBOL,
            Integer.class,
            true,
            "system:defaults:positions",
            "0",
            "Current position quantity in shares per provider and symbol (Sparse keyspace default = 0)"
    ),

    // --- 3. Order Tracking Domain ---
    ORDERS_PENDING(
            "orders:pending:%s",
            KeyScope.PROVIDER,
            String.class,
            true,
            "system:defaults:orders:pending",
            "",
            "Set of order IDs pending exchange execution per provider"
    ),

    // --- 4. Market Data Domain ---
    MARKET_LAST_PRICE_PROVIDER(
            "market:last_price:%s:%s",
            KeyScope.PROVIDER_AND_SYMBOL,
            Double.class,
            false,
            null,
            null,
            "Latest market reference price per broker provider and symbol (Primary price)"
    ),
    MARKET_LAST_PRICE_GLOBAL(
            "market:last_price:%s",
            KeyScope.SYMBOL,
            Double.class,
            false,
            null,
            null,
            "Global fallback market reference price written by price-cache-service (ADR-authorized fallback)"
    ),

    // --- 5. System & Provider Lifecycle Domain ---
    SYSTEM_KILL_SWITCH_GLOBAL(
            "system:kill_switch",
            KeyScope.GLOBAL,
            Boolean.class,
            true,
            "system:defaults:system:kill_switch",
            "false",
            "Global emergency circuit breaker toggle halting all order creation"
    ),
    SYSTEM_KILL_SWITCH_PROVIDER(
            "system:kill_switch:%s",
            KeyScope.PROVIDER,
            Boolean.class,
            true,
            "system:defaults:system:kill_switch:provider",
            "false",
            "Provider-level emergency circuit breaker toggle"
    ),
    PROVIDER_STATUS(
            "provider:status:%s",
            KeyScope.PROVIDER,
            String.class,
            true,
            "system:defaults:provider:status",
            "INACTIVE",
            "Provider connection health state (ACTIVE / INACTIVE / DEGRADED)"
    ),

    // --- 6. Pre-Trade Risk Configuration Domain ---
    RISK_CONFIG_MAX_DAILY_LOSS(
            "risk:config:max_daily_loss:%s",
            KeyScope.PROVIDER,
            Double.class,
            false,
            null,
            null,
            "Daily maximum loss limit in broker currency (Currency-dependent: Fail-Fast, no default permitted)"
    ),
    RISK_CONFIG_PRICE_COLLAR_PCT(
            "risk:config:price_collar_pct:%s",
            KeyScope.PROVIDER,
            Double.class,
            true,
            "system:defaults:risk:price_collar_pct",
            "1.50",
            "Maximum allowable percentage deviation from reference price (Default = 1.50%)"
    ),
    RISK_CONFIG_VELOCITY_PER_SEC(
            "risk:config:velocity_per_sec:%s",
            KeyScope.PROVIDER,
            Integer.class,
            true,
            "system:defaults:risk:velocity_per_sec",
            "5",
            "Rate limiter capping order throughput per second per provider (Default = 5)"
    ),
    RISK_CONFIG_VELOCITY_PER_MIN(
            "risk:config:velocity_per_min:%s",
            KeyScope.PROVIDER,
            Integer.class,
            true,
            "system:defaults:risk:velocity_per_min",
            "30",
            "Rate limiter capping order throughput per minute per provider (Default = 30)"
    ),
    RISK_CONFIG_MAX_ORDER_QTY(
            "risk:config:max_order_qty:%s",
            KeyScope.PROVIDER,
            Integer.class,
            true,
            "system:defaults:risk:max_order_qty",
            "500",
            "Maximum share quantity allowable for a single order (Default = 500)"
    ),
    RISK_CONFIG_MAX_ORDER_VAL(
            "risk:config:max_order_val:%s",
            KeyScope.PROVIDER,
            Double.class,
            false,
            null,
            null,
            "Maximum monetary value allowable for a single order (Currency-dependent: Fail-Fast, no default permitted)"
    ),
    RISK_CONFIG_MAX_CONCENTRATION_PCT(
            "risk:config:max_concentration_pct:%s",
            KeyScope.PROVIDER,
            Double.class,
            true,
            "system:defaults:risk:max_concentration_pct",
            "20.0",
            "Maximum allowable portfolio equity percentage in any single ticker (Default = 20.0%)"
    ),
    RISK_CONFIG_STOP_LOSS_PCT(
            "risk:config:stop_loss_pct:%s",
            KeyScope.PROVIDER,
            Double.class,
            true,
            "system:defaults:risk:stop_loss_pct",
            "2.0",
            "Default percentage distance for automatic stop loss orders (Default = 2.0%)"
    ),

    // --- 7. Velocity Sliding Windows Domain ---
    RISK_VELOCITY_SEC(
            "risk:velocity:sec:%s:%s",
            KeyScope.PROVIDER_AND_EPOCH,
            Integer.class,
            true,
            "system:defaults:risk:velocity_sec",
            "0",
            "Second sliding window order counter with 2s TTL"
    ),
    RISK_VELOCITY_MIN(
            "risk:velocity:min:%s:%s",
            KeyScope.PROVIDER_AND_EPOCH,
            Integer.class,
            true,
            "system:defaults:risk:velocity_min",
            "0",
            "Minute sliding window order counter with 120s TTL"
    );

    private final String format;
    private final KeyScope scope;
    private final Class<?> dataType;
    private final boolean defaultAllowed;
    private final String defaultRedisKey;
    private final String defaultSeedValue;
    private final String description;

    RedisKeyDef(String format, KeyScope scope, Class<?> dataType, boolean defaultAllowed,
                String defaultRedisKey, String defaultSeedValue, String description) {
        this.format = format;
        this.scope = scope;
        this.dataType = dataType;
        this.defaultAllowed = defaultAllowed;
        this.defaultRedisKey = defaultRedisKey;
        this.defaultSeedValue = defaultSeedValue;
        this.description = description;
    }

    public String getFormat() {
        return format;
    }

    public KeyScope getScope() {
        return scope;
    }

    public Class<?> getDataType() {
        return dataType;
    }

    public boolean isDefaultAllowed() {
        return defaultAllowed;
    }

    public String getDefaultRedisKey() {
        return defaultRedisKey;
    }

    public String getDefaultSeedValue() {
        return defaultSeedValue;
    }

    public String getDescription() {
        return description;
    }
}
