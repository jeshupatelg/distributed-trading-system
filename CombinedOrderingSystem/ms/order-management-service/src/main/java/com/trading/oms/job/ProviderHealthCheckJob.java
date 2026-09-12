package com.trading.oms.job;

import com.trading.oms.service.ReconciliationClient;
import com.trading.shared.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProviderHealthCheckJob {
    private static final Logger log = LoggerFactory.getLogger(ProviderHealthCheckJob.class);

    private static final String PROVIDER_STATUS_KEY_PREFIX = "provider:status:";

    private final List<ProviderConfig> providerBeans;
    private final ReconciliationClient reconciliationClient;
    private final StringRedisTemplate redisTemplate;

    public ProviderHealthCheckJob(List<ProviderConfig> providerBeans,
                                 ReconciliationClient reconciliationClient,
                                 StringRedisTemplate redisTemplate) {
        this.providerBeans = providerBeans;
        this.reconciliationClient = reconciliationClient;
        this.redisTemplate = redisTemplate;
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
                redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "INACTIVE");
                continue;
            }

            String currentStatus = redisTemplate.opsForValue().get(PROVIDER_STATUS_KEY_PREFIX + prov);
            boolean isInactive = "INACTIVE".equalsIgnoreCase(currentStatus) || !p.isActive();

            if (isInactive) {
                log.info("Provider '{}' is currently INACTIVE. Probing gRPC connection manager health...", prov);
                boolean healthy = reconciliationClient.checkHealth(prov);
                if (healthy) {
                    p.setActive(true);
                    redisTemplate.opsForValue().set(PROVIDER_STATUS_KEY_PREFIX + prov, "ACTIVE");
                    log.info("HEALTH RECOVERY: Provider '{}' connection manager is HEALTHY again! Restored status to ACTIVE in Redis.", prov);
                } else {
                    log.debug("Provider '{}' connection manager remains UNHEALTHY. Retrying next cycle.", prov);
                }
            }
        }
    }
}
