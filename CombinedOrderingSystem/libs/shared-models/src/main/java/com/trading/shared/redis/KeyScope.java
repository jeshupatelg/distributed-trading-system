package com.trading.shared.redis;

/**
 * Defines the structural parameter scoping requirements for Redis keys.
 */
public enum KeyScope {
    /**
     * Key is globally unique across the entire distributed system (e.g. system:kill_switch).
     * Requires no dynamic parameters.
     */
    GLOBAL,

    /**
     * Key is namespaced strictly by the broker provider (e.g. balance:cash:alpaca).
     * Requires parameter: provider.
     */
    PROVIDER,

    /**
     * Key is namespaced strictly by ticker symbol (e.g. market:last_price:AAPL).
     * Requires parameter: symbol.
     */
    SYMBOL,

    /**
     * Key is composite namespaced by broker provider and ticker symbol (e.g. positions:alpaca:AAPL).
     * Requires parameters: provider, symbol.
     */
    PROVIDER_AND_SYMBOL,

    /**
     * Key is sliding window rate-limiting timestamp key (e.g. risk:velocity:sec:alpaca:1726615000).
     * Requires parameters: provider, epochTime.
     */
    PROVIDER_AND_EPOCH
}
