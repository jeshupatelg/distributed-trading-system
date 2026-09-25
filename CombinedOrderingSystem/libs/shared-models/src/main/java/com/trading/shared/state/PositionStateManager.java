package com.trading.shared.state;

import com.trading.shared.redis.MissingRedisStateException;
import com.trading.shared.redis.RedisKeyBuilder;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Centralized manager service managing portfolio position reads, mutations, settlements,
 * and market valuation across the Distributed Trading System.
 */
public class PositionStateManager {

    private static final Logger log = LoggerFactory.getLogger(PositionStateManager.class);

    private final TradingRedisFacade redisFacade;

    public PositionStateManager(TradingRedisFacade redisFacade) {
        if (redisFacade == null) {
            throw new IllegalArgumentException("TradingRedisFacade must not be null");
        }
        this.redisFacade = redisFacade;
    }

    /**
     * Retrieves the current position quantity (in shares) for a provider and symbol.
     * Defaults to 0 if the position key is absent in Redis.
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @param symbol   Ticker symbol (e.g. "AAPL")
     * @return Current share position
     */
    public int getPosition(String provider, String symbol) {
        String prov = normalizeProvider(provider);
        String sym = normalizeSymbol(symbol);
        Integer pos = redisFacade.getInteger(RedisKeyDef.POSITIONS, prov, sym);
        return pos != null ? pos : 0;
    }

    /**
     * Scans and returns all open positions for a provider.
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @return Map of symbol to position quantity (in shares)
     */
    public Map<String, Integer> getOpenPositions(String provider) {
        String prov = normalizeProvider(provider);
        String pattern = RedisKeyBuilder.pattern(RedisKeyDef.POSITIONS, prov);
        String prefix = RedisKeyBuilder.prefix(RedisKeyDef.POSITIONS, prov);

        Set<String> keys = redisFacade.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> positions = new HashMap<>();
        for (String k : keys) {
            String val = redisFacade.getString(k);
            if (val != null && !val.isBlank()) {
                try {
                    int qty = Integer.parseInt(val.trim());
                    String symbol = k.substring(prefix.length()).toUpperCase();
                    positions.put(symbol, qty);
                } catch (NumberFormatException e) {
                    log.warn("Corrupt position quantity at Redis key '{}': {}", k, val);
                }
            }
        }
        return positions;
    }

    /**
     * Calculates the total dollar market value of all open positions for a given provider
     * using live or global reference prices from Redis.
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @return Total aggregate market value of all open positions
     */
    public double calculateOpenPositionsValue(String provider) {
        String prov = normalizeProvider(provider);
        Map<String, Integer> positions = getOpenPositions(prov);
        if (positions.isEmpty()) {
            return 0.0;
        }

        double totalVal = 0.0;
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            String symbol = entry.getKey();
            int qty = entry.getValue();
            try {
                double price = redisFacade.getMarketPrice(prov, symbol);
                totalVal += (qty * price);
            } catch (MissingRedisStateException e) {
                log.warn("Missing market reference price for open position calculation of symbol '{}' (provider '{}'): {}",
                        symbol, prov, e.getMessage());
            }
        }
        return totalVal;
    }

    /**
     * Computes the market value of a single symbol position for a given provider.
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @param symbol   Ticker symbol (e.g. "AAPL")
     * @return Market value of the position (shares * market price)
     */
    public double getPositionMarketValue(String provider, String symbol) {
        int qty = getPosition(provider, symbol);
        if (qty == 0) {
            return 0.0;
        }
        double price = redisFacade.getMarketPrice(provider, symbol);
        return qty * price;
    }

    /**
     * Performs atomic position settlement on order execution fill.
     * BUY increments position; SELL decrements position.
     *
     * @param provider  Broker provider (e.g. "alpaca")
     * @param symbol    Ticker symbol (e.g. "AAPL")
     * @param side      Order side ("BUY" or "SELL")
     * @param filledQty Executed quantity
     * @return Newly updated position in shares
     */
    public int settlePosition(String provider, String symbol, String side, int filledQty) {
        String prov = normalizeProvider(provider);
        String sym = normalizeSymbol(symbol);
        int currentPos = getPosition(prov, sym);
        int newPos = "BUY".equalsIgnoreCase(side) ? currentPos + filledQty : currentPos - filledQty;

        setPosition(prov, sym, newPos);
        log.info("Settled position for provider '{}', symbol '{}' ({}, filledQty={}): {} -> {}",
                prov, sym, side.toUpperCase(), filledQty, currentPos, newPos);
        return newPos;
    }

    /**
     * Explicitly sets the position quantity in Redis.
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @param symbol   Ticker symbol (e.g. "AAPL")
     * @param quantity New share count
     */
    public void setPosition(String provider, String symbol, int quantity) {
        String prov = normalizeProvider(provider);
        String sym = normalizeSymbol(symbol);
        redisFacade.setInteger(RedisKeyDef.POSITIONS, prov, sym, quantity);
    }

    /**
     * Checks if a provider currently holds an open (non-zero) position in a symbol.
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @param symbol   Ticker symbol (e.g. "AAPL")
     * @return true if position != 0
     */
    public boolean isPositionOpen(String provider, String symbol) {
        return getPosition(provider, symbol) != 0;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        return provider.toLowerCase().trim();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol must not be null or blank");
        }
        return symbol.toUpperCase().trim();
    }
}
