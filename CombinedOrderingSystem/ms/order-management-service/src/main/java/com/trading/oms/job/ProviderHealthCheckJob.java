package com.trading.oms.job;

import com.trading.oms.service.ReconciliationClient;
import com.trading.shared.config.ProviderConfig;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProviderHealthCheckJob {
    private static final Logger log = LoggerFactory.getLogger(ProviderHealthCheckJob.class);

    private final List<ProviderConfig> providerBeans;
    private final ReconciliationClient reconciliationClient;
    private final TradingRedisFacade redisFacade;

    public ProviderHealthCheckJob(List<ProviderConfig> providerBeans,
                                  ReconciliationClient reconciliationClient,
                                  TradingRedisFacade redisFacade) {
        this.providerBeans = providerBeans;
        this.reconciliationClient = reconciliationClient;
        this.redisFacade = redisFacade;
    }

    /**
     * Periodically checks health status of registered providers.
     * Skips reconnection attempts for incomplete configurations.
     * Restores INACTIVE providers to ACTIVE in Redis upon gRPC connectivity recovery.
     */
    @Scheduled(fixedDelayString = "${trading.health-check.interval-ms:15000}")
    public void checkAndRecoverInactiveProviders() {
        if (providerBeans == null || providerBeans.isEmpty()) {
            return;
        }

        for (ProviderConfig p : providerBeans) {
            if (p.getName() == null || p.getName().isBlank()) {
                continue;
            }
            String prov = p.getName().toLowerCase().trim();

            // Rule: Incomplete configuration causes inactive mark and skips reconnection
            if (!p.isConfigComplete()) {
                log.warn("Provider '{}' configuration is INCOMPLETE (endpoint='{}', timezone='{}', exchange='{}'). Skipping reconnection retry.",
                    prov, p.getEndpoint(), p.getTimezone(), p.getExchange());
                p.setActive(false);
                redisFacade.setString(RedisKeyDef.PROVIDER_STATUS, prov, "INACTIVE");
                continue;
            }

            String currentStatus = redisFacade.getString(RedisKeyDef.PROVIDER_STATUS, prov);
            boolean isInactive = "INACTIVE".equalsIgnoreCase(currentStatus) || !p.isActive();

            if (isInactive) {
                log.info("Provider '{}' is currently INACTIVE. Probing gRPC connection manager health...", prov);
                boolean healthy = reconciliationClient.checkHealth(prov);
                if (healthy) {
                    p.setActive(true);
                    redisFacade.setString(RedisKeyDef.PROVIDER_STATUS, prov, "ACTIVE");
                    log.info("HEALTH RECOVERY: Provider '{}' connection manager is HEALTHY again! Restored status to ACTIVE in Redis.", prov);
                } else {
                    log.debug("Provider '{}' connection manager remains UNHEALTHY. Retrying next cycle.", prov);
                }
            }
        }
    }
}
