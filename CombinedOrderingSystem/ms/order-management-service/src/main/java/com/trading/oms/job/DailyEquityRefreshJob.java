package com.trading.oms.job;

import com.trading.oms.service.EquityReconciliationService;
import com.trading.shared.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class DailyEquityRefreshJob {
    private static final Logger log = LoggerFactory.getLogger(DailyEquityRefreshJob.class);

    private final List<ProviderConfig> providerBeans;
    private final StringRedisTemplate redisTemplate;
    private final EquityReconciliationService equityReconciliationService;

    public DailyEquityRefreshJob(List<ProviderConfig> providerBeans,
                                 StringRedisTemplate redisTemplate,
                                 EquityReconciliationService equityReconciliationService) {
        this.providerBeans = providerBeans;
        this.redisTemplate = redisTemplate;
        this.equityReconciliationService = equityReconciliationService;
    }

    /**
     * Scheduled job evaluating date rollover every minute based on provider's configured timezone and exchange.
     * When local date rolls over (00:00 midnight local time), delegates daily equity refresh to EquityReconciliationService.
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
                ZoneId providerZoneId = ZoneId.of(p.getTimezone());
                LocalDate providerCurrentLocalDate = LocalDate.now(providerZoneId);
                String providerLastResetKey = "balance:last_reset_date:" + prov;
                String providerLastResetDateStr = redisTemplate.opsForValue().get(providerLastResetKey);

                if (!providerCurrentLocalDate.toString().equals(providerLastResetDateStr)) {
                    log.info("Executing region/timezone daily equity refresh for provider '{}' (exchange: '{}', timezone: '{}', local date: '{}')",
                        prov, p.getExchange(), p.getTimezone(), providerCurrentLocalDate);

                    equityReconciliationService.reconcileDailyStartingEquity(p, providerCurrentLocalDate);
                }
            } catch (Exception e) {
                log.error("Failed to execute daily equity refresh for provider '{}' with timezone '{}': {}",
                    prov, p.getTimezone(), e.getMessage());
            }
        }
    }
}
