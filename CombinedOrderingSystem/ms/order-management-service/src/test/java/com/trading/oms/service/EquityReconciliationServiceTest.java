package com.trading.oms.service;

import com.trading.connection.grpc.AccountDetailsResponse;
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

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EquityReconciliationServiceTest {

    @Mock
    private ReconciliationClient reconciliationClient;

    @Mock
    private TradingRedisFacade redisFacade;

    @Mock
    private PositionStateManager positionStateManager;

    @Mock
    private ProviderStateManager providerStateManager;

    private ProviderConfig providerConfig;
    private final LocalDate testDate = LocalDate.of(2026, 9, 27);

    @BeforeEach
    void setUp() {
        providerConfig = new ProviderConfig();
        providerConfig.setName("alpaca");
        providerConfig.setExchange("NASDAQ");
        providerConfig.setTimezone("America/New_York");
        providerConfig.setEndpoint("connection-manager-alpaca:50051");
        providerConfig.setActive(true);
    }

    @Test
    @DisplayName("Should skip daily reconciliation if provider is INACTIVE in memory")
    void testReconcileDailyStartingEquity_InactiveInMemory() {
        when(providerStateManager.isProviderActive("alpaca")).thenReturn(false);

        EquityReconciliationService service = new EquityReconciliationService(
                reconciliationClient, redisFacade, positionStateManager, providerStateManager, true);

        service.reconcileDailyStartingEquity(providerConfig, testDate);

        verify(reconciliationClient, never()).getAccountDetails(anyString());
        verify(redisFacade, never()).setDouble(eq(RedisKeyDef.BALANCE_STARTING_EQUITY), anyString(), anyDouble());
    }

    @Test
    @DisplayName("Should skip daily reconciliation if provider is INACTIVE in Redis status")
    void testReconcileDailyStartingEquity_InactiveInRedis() {
        when(providerStateManager.isProviderActive("alpaca")).thenReturn(true);
        when(providerStateManager.getProviderStatus("alpaca")).thenReturn("INACTIVE");

        EquityReconciliationService service = new EquityReconciliationService(
                reconciliationClient, redisFacade, positionStateManager, providerStateManager, true);

        service.reconcileDailyStartingEquity(providerConfig, testDate);

        verify(reconciliationClient, never()).getAccountDetails(anyString());
    }

    @Test
    @DisplayName("Should update starting equity and cash when provider is ACTIVE and broker gRPC succeeds")
    void testReconcileDailyStartingEquity_ActiveBrokerSuccess() {
        when(providerStateManager.isProviderActive("alpaca")).thenReturn(true);
        when(providerStateManager.getProviderStatus("alpaca")).thenReturn("ACTIVE");

        AccountDetailsResponse response = AccountDetailsResponse.newBuilder()
                .setEquity(105000.0)
                .setCash(50000.0)
                .build();
        when(reconciliationClient.getAccountDetails("alpaca")).thenReturn(response);

        EquityReconciliationService service = new EquityReconciliationService(
                reconciliationClient, redisFacade, positionStateManager, providerStateManager, true);

        service.reconcileDailyStartingEquity(providerConfig, testDate);

        verify(redisFacade).setDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, "alpaca", 105000.0);
        verify(redisFacade).setDouble(RedisKeyDef.BALANCE_CASH, "alpaca", 50000.0);
        verify(redisFacade).setString(RedisKeyDef.BALANCE_LAST_RESET_DATE, "alpaca", testDate.toString());
    }

    @Test
    @DisplayName("Should execute fallback calculation when broker gRPC fails and fallback is enabled")
    void testReconcileDailyStartingEquity_BrokerFails_FallbackEnabled() {
        when(providerStateManager.isProviderActive("alpaca")).thenReturn(true);
        when(providerStateManager.getProviderStatus("alpaca")).thenReturn("ACTIVE");
        when(reconciliationClient.getAccountDetails("alpaca")).thenThrow(new RuntimeException("gRPC Connection Error"));
        when(redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, "alpaca")).thenReturn(40000.0);
        when(positionStateManager.calculateOpenPositionsValue("alpaca")).thenReturn(15000.0);

        EquityReconciliationService service = new EquityReconciliationService(
                reconciliationClient, redisFacade, positionStateManager, providerStateManager, true);

        service.reconcileDailyStartingEquity(providerConfig, testDate);

        verify(redisFacade).setDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, "alpaca", 55000.0);
        verify(redisFacade).setString(RedisKeyDef.BALANCE_LAST_RESET_DATE, "alpaca", testDate.toString());
    }

    @Test
    @DisplayName("Should skip fallback calculation when broker gRPC fails and fallback is DISABLED")
    void testReconcileDailyStartingEquity_BrokerFails_FallbackDisabled() {
        when(providerStateManager.isProviderActive("alpaca")).thenReturn(true);
        when(providerStateManager.getProviderStatus("alpaca")).thenReturn("ACTIVE");
        when(reconciliationClient.getAccountDetails("alpaca")).thenThrow(new RuntimeException("gRPC Connection Error"));

        EquityReconciliationService service = new EquityReconciliationService(
                reconciliationClient, redisFacade, positionStateManager, providerStateManager, false);

        service.reconcileDailyStartingEquity(providerConfig, testDate);

        verify(positionStateManager, never()).calculateOpenPositionsValue(anyString());
        verify(redisFacade, never()).setDouble(eq(RedisKeyDef.BALANCE_STARTING_EQUITY), anyString(), anyDouble());
    }

    @Test
    @DisplayName("Should abort fallback calculation without corrupting starting equity when calculateOpenPositionsValue throws MissingRedisStateException")
    void testReconcileDailyStartingEquity_BrokerFails_FallbackThrowsMissingRedisState() {
        when(providerStateManager.isProviderActive("alpaca")).thenReturn(true);
        when(providerStateManager.getProviderStatus("alpaca")).thenReturn("ACTIVE");
        when(reconciliationClient.getAccountDetails("alpaca")).thenThrow(new RuntimeException("gRPC Connection Error"));
        when(redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, "alpaca")).thenReturn(40000.0);
        when(positionStateManager.calculateOpenPositionsValue("alpaca")).thenThrow(
                new com.trading.shared.redis.MissingRedisStateException(RedisKeyDef.MARKET_LAST_PRICE_PROVIDER, "market:last_price:alpaca:NVDA")
        );

        EquityReconciliationService service = new EquityReconciliationService(
                reconciliationClient, redisFacade, positionStateManager, providerStateManager, true);

        // Should not bubble up exception or set starting equity
        service.reconcileDailyStartingEquity(providerConfig, testDate);

        verify(redisFacade, never()).setDouble(eq(RedisKeyDef.BALANCE_STARTING_EQUITY), anyString(), anyDouble());
        verify(redisFacade, never()).setString(eq(RedisKeyDef.BALANCE_LAST_RESET_DATE), anyString(), anyString());
    }
}
