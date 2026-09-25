package com.trading.shared.state;

import com.trading.shared.config.ProviderConfig;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderStateManagerTest {

    @Mock
    private TradingRedisFacade redisFacade;

    private ProviderConfig alpacaConfig;
    private ProviderConfig binanceConfig;
    private ProviderStateManager stateManager;

    @BeforeEach
    void setUp() {
        alpacaConfig = new ProviderConfig("alpaca", "localhost:50051", "America/New_York", "IEX");
        alpacaConfig.setEnabled(true);
        alpacaConfig.setActive(false);

        binanceConfig = new ProviderConfig("binance", "localhost:50052"); // incomplete config: missing timezone/exchange
        binanceConfig.setEnabled(true);
        binanceConfig.setActive(false);

        stateManager = new ProviderStateManager(redisFacade, List.of(alpacaConfig, binanceConfig));
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
    @DisplayName("Should find provider config case-insensitively")
    void testFindProviderConfig() {
        assertNotNull(stateManager.findProviderConfig("alpaca"));
        assertNotNull(stateManager.findProviderConfig("ALPACA"));
        assertNotNull(stateManager.findProviderConfig("Alpaca"));
        assertNull(stateManager.findProviderConfig("unknown"));
        assertNull(stateManager.findProviderConfig(null));
        assertNull(stateManager.findProviderConfig("  "));
    }

    @Test
    @DisplayName("Should report provider active status correctly")
    void testIsProviderActive() {
        assertFalse(stateManager.isProviderActive("alpaca"));
        alpacaConfig.setActive(true);
        assertTrue(stateManager.isProviderActive("alpaca"));

        // Binance is incomplete, should always be false even if active flag is toggled
        binanceConfig.setActive(true);
        assertFalse(stateManager.isProviderActive("binance"));
        assertFalse(stateManager.isProviderActive("unknown"));
    }

    @Test
    @DisplayName("Should mark provider active when config is complete and Redis state is fully initialized")
    void testMarkProviderActive_Success() {
        when(redisFacade.hasKey(anyString())).thenReturn(true);

        boolean result = stateManager.markProviderActive("alpaca");

        assertTrue(result);
        assertTrue(alpacaConfig.isActive());
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "ACTIVE");
    }

    @Test
    @DisplayName("Should refuse to mark provider active and mark inactive when config is incomplete")
    void testMarkProviderActive_IncompleteConfig() {
        boolean result = stateManager.markProviderActive("binance");

        assertFalse(result);
        assertFalse(binanceConfig.isActive());
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "binance", "INACTIVE");
    }

    @Test
    @DisplayName("Should refuse to mark provider active when Redis keys are missing")
    void testMarkProviderActive_MissingRedisKeys() {
        when(redisFacade.hasKey(anyString())).thenReturn(true);
        when(redisFacade.hasKey("balance:cash:alpaca")).thenReturn(false);

        boolean result = stateManager.markProviderActive("alpaca");

        assertFalse(result);
        assertFalse(alpacaConfig.isActive());
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "INACTIVE");
    }

    @Test
    @DisplayName("Should set active and inactive statuses in Redis and in-memory")
    void testStatusMutation() {
        when(redisFacade.hasKey(anyString())).thenReturn(true);

        stateManager.markProviderActive("alpaca");
        assertTrue(alpacaConfig.isActive());
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "ACTIVE");

        stateManager.markProviderInactive("alpaca");
        assertFalse(alpacaConfig.isActive());
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "INACTIVE");
    }

    @Test
    @DisplayName("Should validate account cache and mark inactive when keys are missing")
    void testEnsureAccountCache() {
        when(redisFacade.hasKey(anyString())).thenReturn(true);
        when(redisFacade.hasKey("balance:starting_equity:alpaca")).thenReturn(false);

        boolean valid = stateManager.ensureAccountCache("alpaca");

        assertFalse(valid);
        verify(redisFacade).setString(RedisKeyDef.PROVIDER_STATUS, "alpaca", "INACTIVE");
    }

    @Test
    @DisplayName("Should format sanitized config string properly")
    void testFormatSanitizedConfig() {
        String formatted = stateManager.formatSanitizedConfig(alpacaConfig);
        assertTrue(formatted.contains("name='alpaca'"));
        assertTrue(formatted.contains("isComplete=true"));
        assertEquals("null", stateManager.formatSanitizedConfig(null));
    }

    @Test
    @DisplayName("Should execute state reconciliation stubs without errors")
    void testReconciliationStubs() {
        assertDoesNotThrow(() -> stateManager.reconcileProviderState("alpaca"));
        assertDoesNotThrow(() -> stateManager.onAccountInfoReceived("alpaca", 100000.0, 50000.0, Map.of("source", "broker_grpc")));
    }
}
