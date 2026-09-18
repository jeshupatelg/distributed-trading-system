package com.trading.shared.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedisKeyBuilderTest {

    @Test
    @DisplayName("Should build global key without parameters")
    void testGlobalKey() {
        String key = RedisKeyBuilder.key(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL);
        assertEquals("system:kill_switch", key);
    }

    @Test
    @DisplayName("Should build provider key with lowercase normalization")
    void testProviderKey() {
        String key = RedisKeyBuilder.key(RedisKeyDef.BALANCE_CASH, "ALPACA");
        assertEquals("balance:cash:alpaca", key);

        String blockedKey = RedisKeyBuilder.key(RedisKeyDef.BALANCE_BLOCKED, "  MegaBull  ");
        assertEquals("balance:blocked:megabull", blockedKey);
    }

    @Test
    @DisplayName("Should build symbol key with uppercase normalization")
    void testSymbolKey() {
        String key = RedisKeyBuilder.keyForSymbol(RedisKeyDef.MARKET_LAST_PRICE_GLOBAL, "aapl");
        assertEquals("market:last_price:AAPL", key);
    }

    @Test
    @DisplayName("Should build provider and symbol composite key with normalization")
    void testProviderAndSymbolKey() {
        String key = RedisKeyBuilder.key(RedisKeyDef.POSITIONS, "Alpaca", "msft");
        assertEquals("positions:alpaca:MSFT", key);

        String priceKey = RedisKeyBuilder.key(RedisKeyDef.MARKET_LAST_PRICE_PROVIDER, "ZERODHA", "reliance");
        assertEquals("market:last_price:zerodha:RELIANCE", priceKey);
    }

    @Test
    @DisplayName("Should build sliding velocity window key with epoch timestamp")
    void testVelocityEpochKey() {
        long epochSec = 1726615000L;
        String key = RedisKeyBuilder.key(RedisKeyDef.RISK_VELOCITY_SEC, "alpaca", epochSec);
        assertEquals("risk:velocity:sec:alpaca:1726615000", key);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException if provider is missing for PROVIDER scope")
    void testMissingProviderThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RedisKeyBuilder.of(RedisKeyDef.BALANCE_CASH).build()
        );
        assertTrue(ex.getMessage().contains("requires parameter 'provider'"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException if symbol is missing for PROVIDER_AND_SYMBOL scope")
    void testMissingSymbolThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RedisKeyBuilder.of(RedisKeyDef.POSITIONS).provider("alpaca").build()
        );
        assertTrue(ex.getMessage().contains("requires parameter 'symbol'"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for blank parameters")
    void testBlankParameterThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                RedisKeyBuilder.of(RedisKeyDef.BALANCE_CASH).provider("   ").build()
        );
    }
}
