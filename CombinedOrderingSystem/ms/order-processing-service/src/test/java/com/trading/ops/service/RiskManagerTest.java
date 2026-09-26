package com.trading.ops.service;

import com.trading.shared.config.ProviderConfig;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import com.trading.shared.state.PositionStateManager;
import com.trading.shared.state.ProviderStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskManagerTest {

    @Mock
    private TradingRedisFacade redisFacade;

    @Mock
    private PositionStateManager positionStateManager;

    @Mock
    private ProviderStateManager providerStateManager;

    @Mock
    private com.trading.ops.telemetry.OpsTelemetry opsTelemetry;

    private RiskManager riskManager;
    private ProviderConfig alpacaConfig;

    @BeforeEach
    void setUp() {
        riskManager = new RiskManager(redisFacade, positionStateManager, providerStateManager, opsTelemetry);

        alpacaConfig = new ProviderConfig("alpaca", "localhost:50051", "America/New_York", "IEX");
        alpacaConfig.setEnabled(true);
        alpacaConfig.setActive(true);
    }

    @Test
    @DisplayName("Should reject order when provider is inactive or uninitialized")
    void testEvaluateAndLock_ProviderInactive() {
        when(redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL)).thenReturn(false);
        when(redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_PROVIDER, "alpaca")).thenReturn(false);
        when(providerStateManager.findProviderConfig("alpaca")).thenReturn(alpacaConfig);

        alpacaConfig.setActive(false); // Inactive

        RiskManager.RiskDecision decision = riskManager.evaluateAndLock("ord-1", "AAPL", 10, 150.0, "BUY", "alpaca");

        assertFalse(decision.approved());
        assertEquals("PROVIDER_UNINITIALIZED_OR_INACTIVE", decision.reason());
        assertEquals("PROVIDER_HEALTH", decision.riskGateLevel());
    }

    @Test
    @DisplayName("Should approve order and lock margin when all risk gates pass")
    void testEvaluateAndLock_Approved() {
        when(redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL)).thenReturn(false);
        when(redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_PROVIDER, "alpaca")).thenReturn(false);
        when(providerStateManager.findProviderConfig("alpaca")).thenReturn(alpacaConfig);
        when(redisFacade.hasKey("balance:cash:alpaca")).thenReturn(true);

        when(redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS, "alpaca")).thenReturn(5000.0);
        when(redisFacade.getDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, "alpaca")).thenReturn(100000.0);
        when(redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, "alpaca")).thenReturn(90000.0);
        when(redisFacade.getDouble(RedisKeyDef.BALANCE_BLOCKED, "alpaca")).thenReturn(0.0);
        when(positionStateManager.calculateOpenPositionsValue("alpaca")).thenReturn(10000.0);

        when(redisFacade.getMarketPrice("alpaca", "AAPL")).thenReturn(150.0);
        when(redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_PRICE_COLLAR_PCT, "alpaca")).thenReturn(5.0);

        when(redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_SEC, "alpaca")).thenReturn(10);
        when(redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_VELOCITY_PER_MIN, "alpaca")).thenReturn(100);
        when(redisFacade.increment(startsWith("risk:velocity:sec:alpaca:"), eq(1L))).thenReturn(1L);
        when(redisFacade.increment(startsWith("risk:velocity:min:alpaca:"), eq(1L))).thenReturn(1L);

        when(redisFacade.getInteger(RedisKeyDef.RISK_CONFIG_MAX_ORDER_QTY, "alpaca")).thenReturn(1000);
        when(redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL, "alpaca")).thenReturn(50000.0);
        when(redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_MAX_CONCENTRATION_PCT, "alpaca")).thenReturn(20.0);
        when(redisFacade.getDouble(RedisKeyDef.RISK_CONFIG_STOP_LOSS_PCT, "alpaca")).thenReturn(2.0);

        RiskManager.RiskDecision decision = riskManager.evaluateAndLock("ord-1", "AAPL", 10, 150.0, "BUY", "alpaca");

        assertTrue(decision.approved());
        assertEquals("APPROVED", decision.reason());
        assertEquals(1500.0, decision.calculatedCost());
        assertEquals(147.0, decision.stopLossPrice(), 0.001); // 150 * (1 - 0.02)
        verify(redisFacade).increment("balance:blocked:alpaca", 1500.0);
        verify(redisFacade).addToSet(RedisKeyDef.ORDERS_PENDING, "alpaca", "ord-1");
    }

    @Test
    @DisplayName("Should reject order when global kill switch is active")
    void testEvaluateAndLock_KillSwitchActive() {
        when(redisFacade.getBoolean(RedisKeyDef.SYSTEM_KILL_SWITCH_GLOBAL)).thenReturn(true);

        RiskManager.RiskDecision decision = riskManager.evaluateAndLock("ord-1", "AAPL", 10, 150.0, "BUY", "alpaca");

        assertFalse(decision.approved());
        assertEquals("KILL_SWITCH_ACTIVE", decision.reason());
        assertEquals("KILL_SWITCH", decision.riskGateLevel());
    }

    @Test
    @DisplayName("Should delegate markProviderActive and markProviderInactive to ProviderStateManager")
    void testDelegationToProviderStateManager() {
        riskManager.markProviderActive("alpaca");
        verify(providerStateManager).markProviderActive("alpaca");

        riskManager.markProviderInactive("alpaca");
        verify(providerStateManager).markProviderInactive("alpaca");

        riskManager.findProviderConfig("alpaca");
        verify(providerStateManager).findProviderConfig("alpaca");
    }
}
