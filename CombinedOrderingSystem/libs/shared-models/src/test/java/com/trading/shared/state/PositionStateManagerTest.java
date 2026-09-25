package com.trading.shared.state;

import com.trading.shared.redis.MissingRedisStateException;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PositionStateManagerTest {

    @Mock
    private TradingRedisFacade redisFacade;

    private PositionStateManager positionStateManager;

    @BeforeEach
    void setUp() {
        positionStateManager = new PositionStateManager(redisFacade);
    }

    @Test
    void getPosition_existingAndAbsent() {
        when(redisFacade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "AAPL")).thenReturn(100);
        when(redisFacade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "MSFT")).thenReturn(null);

        assertEquals(100, positionStateManager.getPosition("alpaca", "AAPL"));
        assertEquals(0, positionStateManager.getPosition("alpaca", "MSFT"));
    }

    @Test
    void getOpenPositions_parsesMultipleSymbols() {
        when(redisFacade.keys("positions:alpaca:*")).thenReturn(Set.of(
                "positions:alpaca:AAPL",
                "positions:alpaca:MSFT"
        ));
        when(redisFacade.getString("positions:alpaca:AAPL")).thenReturn("50");
        when(redisFacade.getString("positions:alpaca:MSFT")).thenReturn("150");

        Map<String, Integer> positions = positionStateManager.getOpenPositions("alpaca");

        assertEquals(2, positions.size());
        assertEquals(50, positions.get("AAPL"));
        assertEquals(150, positions.get("MSFT"));
    }

    @Test
    void getOpenPositions_emptyKeys() {
        when(redisFacade.keys("positions:alpaca:*")).thenReturn(Set.of());

        Map<String, Integer> positions = positionStateManager.getOpenPositions("alpaca");

        assertTrue(positions.isEmpty());
    }

    @Test
    void calculateOpenPositionsValue_computesTotalMarketValue() {
        when(redisFacade.keys("positions:alpaca:*")).thenReturn(Set.of(
                "positions:alpaca:AAPL",
                "positions:alpaca:MSFT"
        ));
        when(redisFacade.getString("positions:alpaca:AAPL")).thenReturn("10");
        when(redisFacade.getString("positions:alpaca:MSFT")).thenReturn("20");

        when(redisFacade.getMarketPrice("alpaca", "AAPL")).thenReturn(150.0);
        when(redisFacade.getMarketPrice("alpaca", "MSFT")).thenReturn(300.0);

        // 10 * 150 = 1500; 20 * 300 = 6000 -> total = 7500
        double totalValue = positionStateManager.calculateOpenPositionsValue("alpaca");

        assertEquals(7500.0, totalValue, 0.001);
    }

    @Test
    void calculateOpenPositionsValue_handlesMissingMarketPriceGracefully() {
        when(redisFacade.keys("positions:alpaca:*")).thenReturn(Set.of(
                "positions:alpaca:AAPL",
                "positions:alpaca:NVDA"
        ));
        when(redisFacade.getString("positions:alpaca:AAPL")).thenReturn("10");
        when(redisFacade.getString("positions:alpaca:NVDA")).thenReturn("5");

        when(redisFacade.getMarketPrice("alpaca", "AAPL")).thenReturn(150.0);
        when(redisFacade.getMarketPrice("alpaca", "NVDA")).thenThrow(
                new MissingRedisStateException(RedisKeyDef.MARKET_LAST_PRICE_PROVIDER, "market:last_price:alpaca:NVDA")
        );

        // NVDA omitted due to missing price; AAPL evaluated (10 * 150 = 1500)
        double totalValue = positionStateManager.calculateOpenPositionsValue("alpaca");

        assertEquals(1500.0, totalValue, 0.001);
    }

    @Test
    void settlePosition_buyIncrementsAndSellDecrements() {
        // Current position = 100
        when(redisFacade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "AAPL")).thenReturn(100);

        int newPosBuy = positionStateManager.settlePosition("alpaca", "AAPL", "BUY", 50);
        assertEquals(150, newPosBuy);
        verify(redisFacade).setInteger(RedisKeyDef.POSITIONS, "alpaca", "AAPL", 150);

        // Current position = 150
        when(redisFacade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "AAPL")).thenReturn(150);

        int newPosSell = positionStateManager.settlePosition("alpaca", "AAPL", "SELL", 30);
        assertEquals(120, newPosSell);
        verify(redisFacade).setInteger(RedisKeyDef.POSITIONS, "alpaca", "AAPL", 120);
    }

    @Test
    void isPositionOpen_checksNonZero() {
        when(redisFacade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "AAPL")).thenReturn(25);
        when(redisFacade.getInteger(RedisKeyDef.POSITIONS, "alpaca", "GOOG")).thenReturn(0);

        assertTrue(positionStateManager.isPositionOpen("alpaca", "AAPL"));
        assertFalse(positionStateManager.isPositionOpen("alpaca", "GOOG"));
    }
}
