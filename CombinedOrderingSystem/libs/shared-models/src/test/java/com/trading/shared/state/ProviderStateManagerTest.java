package com.trading.shared.state;

import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderStateManagerTest {

    @Mock
    private TradingRedisFacade redisFacade;

    private ProviderStateManager stateManager;

    @BeforeEach
    void setUp() {
        stateManager = new ProviderStateManager(redisFacade);
    }

    @Test
    @DisplayName("Should identify all 6 non-defaultable provider-scoped keys")
    void testGetRequiredProviderKeyDefs() {
        List<RedisKeyDef> requiredDefs = stateManager.getRequiredProviderKeyDefs();
        assertEquals(6, requiredDefs.size());
        assertTrue(requiredDefs.contains(RedisKeyDef.BALANCE_CASH));
        assertTrue(requiredDefs.contains(RedisKeyDef.BALANCE_BLOCKED));
        assertTrue(requiredDefs.contains(RedisKeyDef.BALANCE_STARTING_EQUITY));
        assertTrue(requiredDefs.contains(RedisKeyDef.BALANCE_LAST_RESET_DATE));
        assertTrue(requiredDefs.contains(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS));
        assertTrue(requiredDefs.contains(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL));
    }

    @Test
    @DisplayName("Should report fully initialized when all required keys exist in Redis")
    void testFullyInitializedWhenAllKeysExist() {
        when(redisFacade.hasKey(anyString())).thenReturn(true);

        assertTrue(stateManager.isProviderStateFullyInitialized("alpaca"));
        assertTrue(stateManager.getMissingRequiredProviderKeys("alpaca").isEmpty());
    }

    @Test
    @DisplayName("Should detect missing keys when max_daily_loss and max_order_val are absent")
    void testDetectMissingKeys() {
        when(redisFacade.hasKey("balance:cash:alpaca")).thenReturn(true);
        when(redisFacade.hasKey("balance:blocked:alpaca")).thenReturn(true);
        when(redisFacade.hasKey("balance:starting_equity:alpaca")).thenReturn(true);
        when(redisFacade.hasKey("balance:last_reset_date:alpaca")).thenReturn(true);
        when(redisFacade.hasKey("risk:config:max_daily_loss:alpaca")).thenReturn(false);
        when(redisFacade.hasKey("risk:config:max_order_val:alpaca")).thenReturn(false);

        List<RedisKeyDef> missing = stateManager.getMissingRequiredProviderKeys("alpaca");
        assertEquals(2, missing.size());
        assertTrue(missing.contains(RedisKeyDef.RISK_CONFIG_MAX_DAILY_LOSS));
        assertTrue(missing.contains(RedisKeyDef.RISK_CONFIG_MAX_ORDER_VAL));
        assertFalse(stateManager.isProviderStateFullyInitialized("alpaca"));
    }

    @Test
    @DisplayName("Should set active and inactive statuses in Redis")
    void testStatusMutation() {
        stateManager.markProviderActive("alpaca");
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "ACTIVE");

        stateManager.markProviderInactive("alpaca");
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "INACTIVE");
    }

    @Test
    @DisplayName("Should execute state reconciliation stubs without errors")
    void testReconciliationStubs() {
        assertDoesNotThrow(() -> stateManager.reconcileProviderState("alpaca"));
        assertDoesNotThrow(() -> stateManager.onAccountInfoReceived("alpaca", 100000.0, 50000.0, Map.of("source", "broker_grpc")));
    }
}
