package com.trading.shared.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingRedisFacadeTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private TradingRedisFacade facade;

    @BeforeEach
    void setUp() {
        facade = new TradingRedisFacade(redisTemplate);
    }

    @Test
    @DisplayName("Should return parsed double when key exists in Redis")
    void testGetDoubleExistingKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("balance:cash:alpaca")).thenReturn("150000.50");

        Double cash = facade.getDouble(RedisKeyDef.BALANCE_CASH, "alpaca");
        assertNotNull(cash);
        assertEquals(150000.50, cash, 0.001);
    }

    @Test
    @DisplayName("Should FAIL-FAST with MissingRedisStateException when strict key (balance:cash) is missing")
    void testFailFastOnMissingCash() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("balance:cash:alpaca")).thenReturn(null);

        MissingRedisStateException ex = assertThrows(MissingRedisStateException.class, () ->
                facade.getDouble(RedisKeyDef.BALANCE_CASH, "alpaca")
        );
        assertTrue(ex.getMessage().contains("isDefaultAllowed=false"));
        assertEquals(RedisKeyDef.BALANCE_CASH, ex.getKeyDef());
    }

    @Test
    @DisplayName("Should FAIL-FAST with MissingRedisStateException when strict key (balance:blocked) is missing")
    void testFailFastOnMissingBlockedBalance() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("balance:blocked:alpaca")).thenReturn(null);

        MissingRedisStateException ex = assertThrows(MissingRedisStateException.class, () ->
                facade.getDouble(RedisKeyDef.BALANCE_BLOCKED, "alpaca")
        );
        assertTrue(ex.getMessage().contains("isDefaultAllowed=false"));
        assertEquals(RedisKeyDef.BALANCE_BLOCKED, ex.getKeyDef());
    }

    @Test
    @DisplayName("Should FAIL-FAST with MissingRedisStateException when currency-dependent risk limits are missing")
    void testFailFastOnMissingCurrencyRiskLimits() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("risk:config:max_daily_loss:alpaca")).thenReturn(null);

        assertThrows(MissingRedisStateException.class, () ->
                facade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS, "alpaca")
        );

        when(valueOperations.get("risk:config:max_order_val:alpaca")).thenReturn(null);
        assertThrows(MissingRedisStateException.class, () ->
                facade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL, "alpaca")
        );
    }

    @Test
    @DisplayName("Should fetch centralized Redis default when key allows default and is missing")
    void testDefaultResolutionFromRedisDefaultsKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Primary key is null
        when(valueOperations.get("positions:alpaca:NVDA")).thenReturn(null);
        // system:defaults:positions returns "0"
        when(valueOperations.get("system:defaults:positions")).thenReturn("0");

        Integer pos = facade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "NVDA");
        assertNotNull(pos);
        assertEquals(0, pos);
    }

    @Test
    @DisplayName("Should fallback to static enum seed value when Redis default key is also missing")
    void testDefaultResolutionFallbackToSeed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Primary key is null
        when(valueOperations.get("risk:config:price_collar_pct:alpaca")).thenReturn(null);
        // system:defaults:risk:price_collar_pct is also null
        when(valueOperations.get("system:defaults:risk:price_collar_pct")).thenReturn(null);

        // Fallback to static seed (1.50)
        Double collar = facade.getDouble(RedisKeyDef.RISK_CONFIG_PRICE_COLLAR_PCT, "alpaca");
        assertNotNull(collar);
        assertEquals(1.50, collar, 0.001);
    }

    @Test
    @DisplayName("ADR Market Price: Should return provider price when present")
    void testMarketPriceProviderPresent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("market:last_price:alpaca:AAPL")).thenReturn("245.50");

        double price = facade.getMarketPrice("alpaca", "AAPL");
        assertEquals(245.50, price, 0.001);
        verify(valueOperations, never()).get("market:last_price:AAPL");
    }

    @Test
    @DisplayName("ADR Market Price: Should fallback to global price when provider price is absent")
    void testMarketPriceFallbackToGlobal() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("market:last_price:alpaca:AAPL")).thenReturn(null);
        when(valueOperations.get("market:last_price:AAPL")).thenReturn("245.75");

        double price = facade.getMarketPrice("alpaca", "AAPL");
        assertEquals(245.75, price, 0.001);
    }

    @Test
    @DisplayName("ADR Market Price: Should FAIL-FAST when both provider and global prices are absent")
    void testMarketPriceMissingBothFailsFast() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("market:last_price:alpaca:AAPL")).thenReturn(null);
        when(valueOperations.get("market:last_price:AAPL")).thenReturn(null);

        assertThrows(MissingRedisStateException.class, () ->
                facade.getMarketPrice("alpaca", "AAPL")
        );
    }

    @Test
    @DisplayName("Should manage orders:pending set correctly")
    void testSetOperations() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        String key = "orders:pending:alpaca";

        facade.addToSet(RedisKeyDef.ORDERS_PENDING, "alpaca", "ord-123");
        verify(setOperations).add(key, "ord-123");

        when(setOperations.isMember(key, "ord-123")).thenReturn(true);
        assertTrue(facade.isMemberOfSet(RedisKeyDef.ORDERS_PENDING, "alpaca", "ord-123"));

        facade.removeFromSet(RedisKeyDef.ORDERS_PENDING, "alpaca", "ord-123");
        verify(setOperations).remove(key, "ord-123");

        when(setOperations.members(key)).thenReturn(Set.of("ord-456"));
        assertEquals(Set.of("ord-456"), facade.getSetMembers(RedisKeyDef.ORDERS_PENDING, "alpaca"));
    }
}
