package com.trading.oms.service;

import com.trading.connection.grpc.AccountDetailsResponse;
import com.trading.shared.config.ProviderConfig;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import com.trading.shared.state.PositionStateManager;
import com.trading.shared.state.ProviderStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class EquityReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(EquityReconciliationService.class);

    private final ReconciliationClient reconciliationClient;
    private final TradingRedisFacade redisFacade;
    private final PositionStateManager positionStateManager;
    private final ProviderStateManager providerStateManager;
    private final boolean fallbackEnabled;

    public EquityReconciliationService(ReconciliationClient reconciliationClient,
                                       TradingRedisFacade redisFacade,
                                       PositionStateManager positionStateManager,
                                       ProviderStateManager providerStateManager,
                                       @Value("${trading.equity-reconciliation.fallback-enabled:true}") boolean fallbackEnabled) {
        this.reconciliationClient = reconciliationClient;
        this.redisFacade = redisFacade;
        this.positionStateManager = positionStateManager;
        this.providerStateManager = providerStateManager;
        this.fallbackEnabled = fallbackEnabled;
    }

    /**
     * Executes official daily equity rollover for a provider using direct broker gRPC query.
     * Overwrites starting_equity in Redis with ground-truth broker equity, resyncs cash balance,
     * and updates last_reset_date.
     *
     * @param providerConfig The provider configuration object
     * @param rolloverDate   The local date of the provider's exchange region
     */
    public void reconcileDailyStartingEquity(ProviderConfig providerConfig, LocalDate rolloverDate) {
        if (providerConfig == null || providerConfig.getName() == null || providerConfig.getName().isBlank()) {
            return;
        }

        String prov = providerConfig.getName().toLowerCase().trim();

        // Check if provider is ACTIVE in-memory and in Redis
        boolean isActiveInMemory = providerStateManager != null && providerStateManager.isProviderActive(prov);
        String redisStatus = providerStateManager != null ? providerStateManager.getProviderStatus(prov) : null;
        boolean isActiveInRedis = "ACTIVE".equalsIgnoreCase(redisStatus);

        if (!isActiveInMemory || !isActiveInRedis) {
            log.warn("Skipping daily equity reconciliation for provider '{}': provider is INACTIVE (in-memory active={}, redis status='{}').",
                prov, isActiveInMemory, redisStatus);
            return;
        }

        try {
            log.info("Querying ground-truth account details from broker via gRPC for provider '{}' (date: {})", prov, rolloverDate);
            AccountDetailsResponse account = reconciliationClient.getAccountDetails(prov);

            double brokerEquity = account.getEquity();
            double brokerCash = account.getCash();

            if (brokerEquity > 0.0) {
                redisFacade.setDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, prov, brokerEquity);
                redisFacade.setDouble(RedisKeyDef.BALANCE_CASH, prov, brokerCash);
                redisFacade.setString(RedisKeyDef.BALANCE_LAST_RESET_DATE, prov, rolloverDate.toString());

                log.info("BROKER-DIRECT DAILY EQUITY RESET SUCCESSFUL for provider '{}' ({}): starting_equity={}, cash={} for date {}",
                    prov, providerConfig.getExchange(), brokerEquity, brokerCash, rolloverDate);
                return;
            } else {
                log.warn("Broker gRPC returned 0.0 equity for provider '{}'.", prov);
            }
        } catch (Exception e) {
            log.error("Broker gRPC GetAccountDetails failed for provider '{}': {}", prov, e.getMessage());
        }

        // Fallback internal calculation if broker gRPC call fails (and fallback is enabled)
        if (fallbackEnabled) {
            log.info("Executing fallback internal daily equity calculation for provider '{}'", prov);
            executeFallbackInternalReset(prov, providerConfig.getExchange(), rolloverDate);
        } else {
            log.warn("Internal fallback equity calculation is DISABLED. Skipping fallback reset for provider '{}'.", prov);
        }
    }



    private void executeFallbackInternalReset(String prov, String exchange, LocalDate rolloverDate) {
        double currentCash = redisFacade.getDouble(RedisKeyDef.BALANCE_CASH, prov);
        double positionsVal = positionStateManager.calculateOpenPositionsValue(prov);
        double closingEquity = currentCash + positionsVal;

        redisFacade.setDouble(RedisKeyDef.BALANCE_STARTING_EQUITY, prov, closingEquity);
        redisFacade.setString(RedisKeyDef.BALANCE_LAST_RESET_DATE, prov, rolloverDate.toString());

        log.info("FALLBACK DAILY EQUITY RESET COMPLETE for provider '{}' ({}): starting_equity set to {} for date {}",
            prov, exchange, closingEquity, rolloverDate);
    }
}

