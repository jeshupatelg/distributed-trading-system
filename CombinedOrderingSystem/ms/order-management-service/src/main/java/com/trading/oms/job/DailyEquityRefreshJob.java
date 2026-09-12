package com.trading.oms.job;

import com.trading.shared.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Component
public class DailyEquityRefreshJob {
    private static final Logger log = LoggerFactory.getLogger(DailyEquityRefreshJob.class);

    private final List<ProviderConfig> providerBeans;
    private final StringRedisTemplate redisTemplate;

    public DailyEquityRefreshJob(List<ProviderConfig> providerBeans, StringRedisTemplate redisTemplate) {
        this.providerBeans = providerBeans;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Scheduled job evaluating date rollover every minute based on provider's configured timezone and exchange.
     * When local date rolls over (00:00 midnight local time), updates starting_equity in Redis.
     */
    @Scheduled(cron = "0 * * * * *")
    public void refreshDailyStartingEquity() {
        if (providerBeans == null || providerBeans.isEmpty()) {
            return;
        }

        for (ProviderConfig p : providerBeans) {
            if (p.getName() == null || p.getName().isBlank()) {
                continue;
            }

            if (!p.isConfigComplete()) {
                log.debug("Provider '{}' configuration incomplete. Skipping daily equity refresh check.", p.getName());
                continue;
            }

            String prov = p.getName().toLowerCase().trim();

            try {
                ZoneId zoneId = ZoneId.of(p.getTimezone());
                LocalDate currentLocalDate = LocalDate.now(zoneId);
                String lastResetKey = "balance:last_reset_date:" + prov;
                String lastResetDateStr = redisTemplate.opsForValue().get(lastResetKey);

                if (lastResetDateStr == null || !currentLocalDate.toString().equals(lastResetDateStr)) {
                    log.info("Executing region/timezone daily equity refresh for provider '{}' (exchange: '{}', timezone: '{}', local date: '{}')",
                        prov, p.getExchange(), p.getTimezone(), currentLocalDate);

                    String cashKey = "balance:cash:" + prov;
                    String cashStr = redisTemplate.opsForValue().get(cashKey);
                    double currentCash = cashStr != null ? Double.parseDouble(cashStr) : 0.0;
                    double positionsVal = calculateOpenPositionsValue(prov);
                    double closingEquity = currentCash + positionsVal;

                    String startingEquityKey = "balance:starting_equity:" + prov;
                    redisTemplate.opsForValue().set(startingEquityKey, String.valueOf(closingEquity));
                    redisTemplate.opsForValue().set(lastResetKey, currentLocalDate.toString());

                    log.info("DAILY EQUITY RESET COMPLETE for provider '{}' ({}): starting_equity set to {} for date {}",
                        prov, p.getExchange(), closingEquity, currentLocalDate);
                }
            } catch (Exception e) {
                log.error("Failed to execute daily equity refresh for provider '{}' with timezone '{}': {}",
                    prov, p.getTimezone(), e.getMessage());
            }
        }
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
