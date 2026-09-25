package com.trading.oms.job;

import com.trading.oms.service.ReconciliationClient;
import com.trading.shared.config.ProviderConfig;
import com.trading.shared.state.ProviderStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProviderHealthCheckJob {
    private static final Logger log = LoggerFactory.getLogger(ProviderHealthCheckJob.class);

    private final ReconciliationClient reconciliationClient;
    private final ProviderStateManager providerStateManager;

    public ProviderHealthCheckJob(ReconciliationClient reconciliationClient,
                                  ProviderStateManager providerStateManager) {
        this.reconciliationClient = reconciliationClient;
        this.providerStateManager = providerStateManager;
    }

    /**
     * Periodically checks health status of registered providers.
     * Skips reconnection attempts for incomplete configurations.
     * Restores INACTIVE providers to ACTIVE in Redis ONLY when both gRPC connectivity
     * is healthy AND all mandatory non-defaultable Redis state keys exist.
     */
    @Scheduled(fixedDelayString = "${trading.health-check.interval-ms:15000}")
    public void checkAndRecoverInactiveProviders() {
        List<ProviderConfig> configs = providerStateManager.getProviderConfigs();
        if (configs == null || configs.isEmpty()) {
            return;
        }

        for (ProviderConfig p : configs) {
            if (p.getName() == null || p.getName().isBlank()) {
                continue;
            }
            String prov = p.getName().toLowerCase().trim();

            // Rule: Incomplete configuration causes inactive mark and skips reconnection
            if (!p.isConfigComplete()) {
                log.warn("Provider '{}' configuration is INCOMPLETE (endpoint='{}', timezone='{}', exchange='{}'). Skipping reconnection retry.",
                    prov, p.getEndpoint(), p.getTimezone(), p.getExchange());
                providerStateManager.markProviderInactive(prov);
                continue;
            }

            String currentStatus = providerStateManager.getProviderStatus(prov);
            boolean isInactive = "INACTIVE".equalsIgnoreCase(currentStatus) || !p.isActive();

            if (isInactive) {
                log.info("Provider '{}' is currently INACTIVE. Probing gRPC connection manager health...", prov);
                boolean grpcHealthy = reconciliationClient.checkHealth(prov);
                if (grpcHealthy) {
                    boolean activated = providerStateManager.markProviderActive(prov);
                    if (activated) {
                        log.info("HEALTH RECOVERY: Provider '{}' connection manager is HEALTHY and Redis state is complete! Restored status to ACTIVE in Redis.", prov);
                    } else {
                        log.warn("Provider '{}' connection manager is HEALTHY via gRPC, but Redis state is incomplete. Retaining INACTIVE status.", prov);
                        providerStateManager.reconcileProviderState(prov);
                    }
                } else {
                    log.debug("Provider '{}' connection manager remains UNHEALTHY. Retrying next cycle.", prov);
                }
            }
        }
    }
}
