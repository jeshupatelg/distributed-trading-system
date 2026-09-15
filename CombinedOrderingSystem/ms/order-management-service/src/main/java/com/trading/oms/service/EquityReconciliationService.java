package com.trading.oms.service;

import com.trading.connection.grpc.AccountDetailsResponse;
import com.trading.shared.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;

@Service
public class EquityReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(EquityReconciliationService.class);

    private final ReconciliationClient reconciliationClient;
    private final StringRedisTemplate redisTemplate;

    public EquityReconciliationService(ReconciliationClient reconciliationClient, StringRedisTemplate redisTemplate) {
        this.reconciliationClient = reconciliationClient;
        this.redisTemplate = redisTemplate;
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
        String startingEquityKey = "balance:starting_equity:" + prov;
        String cashKey = "balance:cash:" + prov;
        String lastResetKey = "balance:last_reset_date:" + prov;

        try {
            log.info("Querying ground-truth account details from broker via gRPC for provider '{}' (date: {})", prov, rolloverDate);
            AccountDetailsResponse account = reconciliationClient.getAccountDetails(prov);

            double brokerEquity = account.getEquity();
            double brokerCash = account.getCash();

            if (brokerEquity > 0.0) {
                redisTemplate.opsForValue().set(startingEquityKey, String.valueOf(brokerEquity));
                redisTemplate.opsForValue().set(cashKey, String.valueOf(brokerCash));
                redisTemplate.opsForValue().set(lastResetKey, rolloverDate.toString());

                log.info("BROKER-DIRECT DAILY EQUITY RESET SUCCESSFUL for provider '{}' ({}): starting_equity={}, cash={} for date {}",
                    prov, providerConfig.getExchange(), brokerEquity, brokerCash, rolloverDate);
                return;
            } else {
                log.warn("Broker gRPC returned 0.0 equity for provider '{}'. Falling back to internal Redis calculation.", prov);
            }
        } catch (Exception e) {
            log.error("Broker gRPC GetAccountDetails failed for provider '{}': {}. Falling back to internal calculation.",
                prov, e.getMessage());
        }

        // Fallback internal calculation if broker gRPC call fails
        executeFallbackInternalReset(prov, providerConfig.getExchange(), rolloverDate, startingEquityKey, cashKey, lastResetKey);
    }

    /**
     * Public reusable method for mid-day / on-demand UI triggers.
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
            String cashKey = "balance:cash:" + prov;
            redisTemplate.opsForValue().set(cashKey, String.valueOf(brokerCash));
            log.info("Mid-day balance resync complete for provider '{}': cash updated to {}", prov, brokerCash);
        }
        return account;
    }

    private void executeFallbackInternalReset(String prov, String exchange, LocalDate rolloverDate,
                                              String startingEquityKey, String cashKey, String lastResetKey) {
        String cashStr = redisTemplate.opsForValue().get(cashKey);
        double currentCash = cashStr != null ? Double.parseDouble(cashStr) : 0.0;
        double positionsVal = calculateOpenPositionsValue(prov);
        double closingEquity = currentCash + positionsVal;

        redisTemplate.opsForValue().set(startingEquityKey, String.valueOf(closingEquity));
        redisTemplate.opsForValue().set(lastResetKey, rolloverDate.toString());

        log.info("FALLBACK DAILY EQUITY RESET COMPLETE for provider '{}' ({}): starting_equity set to {} for date {}",
            prov, exchange, closingEquity, rolloverDate);
    }

    private double calculateOpenPositionsValue(String provider) {
        String prov = provider.toLowerCase().trim();
        String posPrefix = "positions:" + prov + ":";
        Set<String> keys = redisTemplate.keys(posPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return 0.0;
        }
        double totalVal = 0.0;
        for (String k : keys) {
            String posStr = redisTemplate.opsForValue().get(k);
            if (posStr != null) {
                int qty = Integer.parseInt(posStr);
                String symbol = k.replace(posPrefix, "");
                String lastPriceKey = "market:last_price:" + prov + ":" + symbol;
                String lastPriceStr = redisTemplate.opsForValue().get(lastPriceKey);
                if (lastPriceStr == null) {
                    lastPriceStr = redisTemplate.opsForValue().get("market:last_price:" + symbol);
                }
                double price = lastPriceStr != null ? Double.parseDouble(lastPriceStr) : 100.0;
                totalVal += (qty * price);
            }
        }
        return totalVal;
    }
}
