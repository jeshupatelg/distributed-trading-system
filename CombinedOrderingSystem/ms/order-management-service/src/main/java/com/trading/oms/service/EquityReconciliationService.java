package com.trading.oms.service;

import com.trading.connection.grpc.AccountDetailsResponse;
import com.trading.shared.config.ProviderConfig;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import com.trading.shared.state.PositionStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class EquityReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(EquityReconciliationService.class);

    private final ReconciliationClient reconciliationClient;
    private final TradingRedisFacade redisFacade;
    private final PositionStateManager positionStateManager;

    public EquityReconciliationService(ReconciliationClient reconciliationClient,
                                       TradingRedisFacade redisFacade,
                                       PositionStateManager positionStateManager) {
        this.reconciliationClient = reconciliationClient;
        this.redisFacade = redisFacade;
        this.positionStateManager = positionStateManager;
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
                log.warn("Broker gRPC returned 0.0 equity for provider '{}'. Falling back to internal calculation.", prov);
            }
        } catch (Exception e) {
            log.error("Broker gRPC GetAccountDetails failed for provider '{}': {}. Falling back to internal calculation.",
                prov, e.getMessage());
        }

        // Fallback internal calculation if broker gRPC call fails
        executeFallbackInternalReset(prov, providerConfig.getExchange(), rolloverDate);
    }

    /**
     * Public reusable method for midday / on-demand UI triggers.
     * Queries broker for live balance and resyncs Redis cash balance without resetting starting_equity.
     */
    public AccountDetailsResponse reconcileLiveBalance(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        String prov = provider.toLowerCase().trim();
        log.info("Executing mid-day / on-demand live balance reconciliation for provider '{}'", prov);

        AccountDetailsResponse account = reconciliationClient.getAccountDetails(prov);
        double brokerCash = account.getCash();
        if (brokerCash > 0.0) {
            redisFacade.setDouble(RedisKeyDef.BALANCE_CASH, prov, brokerCash);
            log.info("Mid-day balance resync complete for provider '{}': cash updated to {}", prov, brokerCash);
        }
        return account;
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
