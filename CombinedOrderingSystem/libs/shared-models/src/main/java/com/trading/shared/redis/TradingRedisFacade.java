package com.trading.shared.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * Enterprise client facade wrapping Spring's StringRedisTemplate.
 * Provides typed accessors, automated fail-fast validation for strict keys,
 * centralized Redis-backed defaulting, and authorized ADR market price resolution.
 */
public class TradingRedisFacade {

    private static final Logger log = LoggerFactory.getLogger(TradingRedisFacade.class);

    private final StringRedisTemplate redisTemplate;

    public TradingRedisFacade(StringRedisTemplate redisTemplate) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("StringRedisTemplate must not be null");
        }
        this.redisTemplate = redisTemplate;
    }

    // =========================================================================
    // Typed Getters with Defaulting & Fail-Fast
    // =========================================================================

    public Double getDouble(RedisKeyDef keyDef) {
        String key = RedisKeyBuilder.key(keyDef);
        return getDouble(key, keyDef);
    }

    public Double getDouble(RedisKeyDef keyDef, String provider) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        return getDouble(key, keyDef);
    }

    public Double getDouble(RedisKeyDef keyDef, String provider, String symbol) {
        String key = RedisKeyBuilder.key(keyDef, provider, symbol);
        return getDouble(key, keyDef);
    }

    public Double getDouble(String resolvedKey, RedisKeyDef keyDef) {
        String val = redisTemplate.opsForValue().get(resolvedKey);
        if (val != null) {
            return Double.parseDouble(val.trim());
        }
        return resolveDefaultAsDouble(resolvedKey, keyDef);
    }

    public Integer getInteger(RedisKeyDef keyDef, String provider) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        return getInteger(key, keyDef);
    }

    public Integer getInteger(RedisKeyDef keyDef, String provider, String symbol) {
        String key = RedisKeyBuilder.key(keyDef, provider, symbol);
        return getInteger(key, keyDef);
    }

    public Integer getInteger(String resolvedKey, RedisKeyDef keyDef) {
        String val = redisTemplate.opsForValue().get(resolvedKey);
        if (val != null) {
            return Integer.parseInt(val.trim());
        }
        return resolveDefaultAsInteger(resolvedKey, keyDef);
    }

    public Boolean getBoolean(RedisKeyDef keyDef) {
        String key = RedisKeyBuilder.key(keyDef);
        return getBoolean(key, keyDef);
    }

    public Boolean getBoolean(RedisKeyDef keyDef, String provider) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        return getBoolean(key, keyDef);
    }

    public Boolean getBoolean(String resolvedKey, RedisKeyDef keyDef) {
        String val = redisTemplate.opsForValue().get(resolvedKey);
        if (val != null) {
            return Boolean.parseBoolean(val.trim());
        }
        return resolveDefaultAsBoolean(resolvedKey, keyDef);
    }

    public String getString(RedisKeyDef keyDef, String provider) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        return getString(key, keyDef);
    }

    public String getString(String resolvedKey, RedisKeyDef keyDef) {
        String val = redisTemplate.opsForValue().get(resolvedKey);
        if (val != null) {
            return val;
        }
        return resolveDefaultAsString(resolvedKey, keyDef);
    }

    // =========================================================================
    // ADR-Authorized Market Price Resolution
    // =========================================================================

    /**
     * Resolves the market reference price for a given provider and symbol in accordance with ADR-005.
     * 1. Checks provider-namespaced key: market:last_price:<provider>:<symbol>
     * 2. If absent, checks global reference key: market:last_price:<symbol>
     * 3. If both absent, throws MissingRedisStateException (Fail-Fast: no arbitrary $100 price substitution).
     *
     * @param provider Broker provider (e.g. "alpaca")
     * @param symbol   Ticker symbol (e.g. "AAPL")
     * @return Resolved reference price
     * @throws MissingRedisStateException if neither provider nor global price exists in Redis
     */
    public double getMarketPrice(String provider, String symbol) {
        String providerKey = RedisKeyBuilder.key(RedisKeyDef.MARKET_LAST_PRICE_PROVIDER, provider, symbol);
        String val = redisTemplate.opsForValue().get(providerKey);
        if (val != null) {
            return Double.parseDouble(val.trim());
        }

        String globalKey = RedisKeyBuilder.keyForSymbol(RedisKeyDef.MARKET_LAST_PRICE_GLOBAL, symbol);
        val = redisTemplate.opsForValue().get(globalKey);
        if (val != null) {
            return Double.parseDouble(val.trim());
        }

        throw new MissingRedisStateException(
                String.format("Market reference price missing for symbol '%s' (checked provider '%s' and global fallback '%s')",
                        symbol, providerKey, globalKey)
        );
    }

    // =========================================================================
    // Typed Setters & Increments
    // =========================================================================

    public void setDouble(RedisKeyDef keyDef, String provider, double value) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    public void setDouble(String resolvedKey, double value) {
        redisTemplate.opsForValue().set(resolvedKey, String.valueOf(value));
    }

    public void setInteger(RedisKeyDef keyDef, String provider, int value) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    public void setInteger(RedisKeyDef keyDef, String provider, String symbol, int value) {
        String key = RedisKeyBuilder.key(keyDef, provider, symbol);
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    public void setInteger(String resolvedKey, int value) {
        redisTemplate.opsForValue().set(resolvedKey, String.valueOf(value));
    }

    public void setBoolean(RedisKeyDef keyDef, boolean value) {
        String key = RedisKeyBuilder.key(keyDef);
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    public void setBoolean(RedisKeyDef keyDef, String provider, boolean value) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    public void setString(RedisKeyDef keyDef, String provider, String value) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        redisTemplate.opsForValue().set(key, value);
    }

    public void setString(String resolvedKey, String value) {
        redisTemplate.opsForValue().set(resolvedKey, value);
    }

    public Double increment(String resolvedKey, double delta) {
        return redisTemplate.opsForValue().increment(resolvedKey, delta);
    }

    public Long increment(String resolvedKey, long delta) {
        return redisTemplate.opsForValue().increment(resolvedKey, delta);
    }

    public void expire(String resolvedKey, Duration duration) {
        redisTemplate.expire(resolvedKey, duration);
    }

    // =========================================================================
    // Set Operations (Orders Pending)
    // =========================================================================

    public void addToSet(RedisKeyDef keyDef, String provider, String member) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        redisTemplate.opsForSet().add(key, member);
    }

    public boolean isMemberOfSet(RedisKeyDef keyDef, String provider, String member) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        Boolean res = redisTemplate.opsForSet().isMember(key, member);
        return Boolean.TRUE.equals(res);
    }

    public void removeFromSet(RedisKeyDef keyDef, String provider, String member) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        redisTemplate.opsForSet().remove(key, member);
    }

    public Set<String> getSetMembers(RedisKeyDef keyDef, String provider) {
        String key = RedisKeyBuilder.key(keyDef, provider);
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members != null ? members : Collections.emptySet();
    }

    // =========================================================================
    // Generic Key Operations
    // =========================================================================

    public boolean hasKey(String resolvedKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(resolvedKey));
    }

    public boolean delete(String resolvedKey) {
        return Boolean.TRUE.equals(redisTemplate.delete(resolvedKey));
    }

    // =========================================================================
    // Key-Value Primitives for Domain Managers
    // =========================================================================

    public Set<String> keys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys != null ? keys : Collections.emptySet();
    }

    public String getString(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    // =========================================================================
    // Internal Default Resolution & Fail-Fast Helpers
    // =========================================================================

    private Double resolveDefaultAsDouble(String resolvedKey, RedisKeyDef keyDef) {
        String raw = resolveDefaultString(resolvedKey, keyDef);
        return Double.parseDouble(raw.trim());
    }

    private Integer resolveDefaultAsInteger(String resolvedKey, RedisKeyDef keyDef) {
        String raw = resolveDefaultString(resolvedKey, keyDef);
        return Integer.parseInt(raw.trim());
    }

    private Boolean resolveDefaultAsBoolean(String resolvedKey, RedisKeyDef keyDef) {
        String raw = resolveDefaultString(resolvedKey, keyDef);
        return Boolean.parseBoolean(raw.trim());
    }

    private String resolveDefaultAsString(String resolvedKey, RedisKeyDef keyDef) {
        return resolveDefaultString(resolvedKey, keyDef);
    }

    private String resolveDefaultString(String resolvedKey, RedisKeyDef keyDef) {
        if (!keyDef.isDefaultAllowed()) {
            throw new MissingRedisStateException(keyDef, resolvedKey);
        }

        String defaultRedisKey = keyDef.getDefaultRedisKey();
        if (defaultRedisKey != null) {
            String val = redisTemplate.opsForValue().get(defaultRedisKey);
            if (val != null) {
                return val;
            }
        }

        // Fallback to static seed value defined on the key enum
        if (keyDef.getDefaultSeedValue() != null) {
            return keyDef.getDefaultSeedValue();
        }

        throw new MissingRedisStateException(
                String.format("Key '%s' allows defaults, but neither default key '%s' nor static seed exists",
                        resolvedKey, defaultRedisKey)
        );
    }
}
