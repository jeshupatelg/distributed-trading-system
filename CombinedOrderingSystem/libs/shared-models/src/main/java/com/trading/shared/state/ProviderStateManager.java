package com.trading.shared.state;

import com.trading.shared.config.ProviderConfig;
import com.trading.shared.redis.KeyScope;
import com.trading.shared.redis.RedisKeyBuilder;
import com.trading.shared.redis.RedisKeyDef;
import com.trading.shared.redis.TradingRedisFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Centralized manager responsible for inspecting, tracking, and reconciling
 * broker provider state both in-memory and in Redis across all trading microservices (OPS and OMS).
 */
public class ProviderStateManager {
    private static final Logger log = LoggerFactory.getLogger(ProviderStateManager.class);

    private final TradingRedisFacade redisFacade;
    private final List<ProviderConfig> providerConfigs;

    public ProviderStateManager(TradingRedisFacade redisFacade) {
        this(redisFacade, Collections.emptyList());
    }

    public ProviderStateManager(TradingRedisFacade redisFacade, List<ProviderConfig> providerConfigs) {
        this.redisFacade = redisFacade;
        this.providerConfigs = providerConfigs != null ? providerConfigs : Collections.emptyList();
    }

    /**
     * Looks up a registered provider configuration by name (case-insensitive).
     *
     * @param provider The provider identifier (e.g. "alpaca")
     * @return The matching ProviderConfig, or null if not found
     */
    public ProviderConfig findProviderConfig(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String prov = normalizeProvider(provider);
        for (ProviderConfig p : providerConfigs) {
            if (prov.equalsIgnoreCase(p.getName())) {
                return p;
            }
        }
        return null;
    }

    /**
     * Returns whether the specified provider is configured, complete, and marked ACTIVE.
     *
     * @param provider The provider identifier
     * @return true if provider is active and configuration complete
     */
    public boolean isProviderActive(String provider) {
        ProviderConfig config = findProviderConfig(provider);
        return config != null && config.isConfigComplete() && config.isActive();
    }

    public List<ProviderConfig> getProviderConfigs() {
        return providerConfigs;
    }

    /**
     * Formats a sanitized summary of ProviderConfig attributes for logging.
     */
    public String formatSanitizedConfig(ProviderConfig config) {
        if (config == null) {
            return "null";
        }
        return "ProviderConfig{name='" + config.getName() + '\'' +
                ", timezone='" + config.getTimezone() + '\'' +
                ", exchange='" + config.getExchange() + '\'' +
                ", enabled=" + config.isEnabled() +
                ", active=" + config.isActive() +
                ", isComplete=" + config.isConfigComplete() + '}';
    }

    /**
     * Checks if all mandatory non-defaultable account cache keys exist in Redis for the provider.
     * If incomplete, automatically marks provider INACTIVE.
     *
     * @param provider The provider identifier
     * @return true if account cache is complete, false otherwise
     */
    public boolean ensureAccountCache(String provider) {
        String prov = normalizeProvider(provider);
        List<RedisKeyDef> missing = getMissingRequiredProviderKeys(prov);
        if (!missing.isEmpty()) {
            log.warn("Account state init-check FAILED for provider '{}'. Missing mandatory non-defaultable Redis keys: {}. Marking provider INACTIVE.",
                    prov, missing);
            markProviderInactive(prov);
            return false;
        }
        return true;
    }

    /**
     * Retrieves all authoritative provider-scoped keys that forbid defaults (fail-fast keys).
     * These keys represent mandatory state that must exist for safe trading.
     */
    public List<RedisKeyDef> getRequiredProviderKeyDefs() {
        return Arrays.stream(RedisKeyDef.values())
                .filter(k -> k.getScope() == KeyScope.PROVIDER && !k.isDefaultAllowed())
                .toList();
    }

    /**
     * Inspects Redis to determine which mandatory non-defaultable keys are missing for a provider.
     *
     * @param provider The broker provider identifier (e.g. "alpaca")
     * @return List of missing RedisKeyDef definitions (empty if all exist)
     */
    public List<RedisKeyDef> getMissingRequiredProviderKeys(String provider) {
        String prov = normalizeProvider(provider);
        List<RedisKeyDef> missing = new ArrayList<>();
        for (RedisKeyDef def : getRequiredProviderKeyDefs()) {
            String key = RedisKeyBuilder.key(def, prov);
            if (!redisFacade.hasKey(key)) {
                missing.add(def);
            }
        }
        return missing;
    }

    /**
     * Checks whether all mandatory non-defaultable provider state keys exist in Redis.
     */
    public boolean isProviderStateFullyInitialized(String provider) {
        return getMissingRequiredProviderKeys(provider).isEmpty();
    }

    /**
     * Gets the current operational status of the provider from Redis.
     */
    public String getProviderStatus(String provider) {
        String prov = normalizeProvider(provider);
        return redisFacade.getString(RedisKeyDef.PROVIDER_STATUS, prov);
    }

    /**
     * Marks the provider as ACTIVE both in-memory and in Redis.
     * Enforces prerequisite checks: configuration must be complete, and all mandatory
     * non-defaultable Redis keys must exist. If either check fails, the provider
     * is marked INACTIVE instead.
     *
     * @param provider The provider identifier
     * @return true if successfully marked ACTIVE, false if validation failed and marked INACTIVE
     */
    public boolean markProviderActive(String provider) {
        String prov = normalizeProvider(provider);
        ProviderConfig config = findProviderConfig(prov);

        if (config != null && !config.isConfigComplete()) {
            log.warn("Cannot mark provider '{}' ACTIVE: configuration incomplete. Config: {}",
                    prov, formatSanitizedConfig(config));
            markProviderInactive(prov);
            return false;
        }

        List<RedisKeyDef> missing = getMissingRequiredProviderKeys(prov);
        if (!missing.isEmpty()) {
            log.warn("Cannot mark provider '{}' ACTIVE: Redis state init-check failed. Missing mandatory keys: {}. Marking INACTIVE.",
                    prov, missing);
            markProviderInactive(prov);
            return false;
        }

        if (config != null) {
            config.setActive(true);
        }
        redisFacade.setString(RedisKeyDef.PROVIDER_STATUS, prov, "ACTIVE");
        log.info("Marked provider '{}' ACTIVE in-memory and in Redis.", prov);
        return true;
    }

    /**
     * Marks the provider as INACTIVE both in-memory and in Redis.
     *
     * @param provider The provider identifier
     */
    public void markProviderInactive(String provider) {
        String prov = normalizeProvider(provider);
        ProviderConfig config = findProviderConfig(prov);
        if (config != null) {
            config.setActive(false);
        }
        redisFacade.setString(RedisKeyDef.PROVIDER_STATUS, prov, "INACTIVE");
        log.warn("Marked provider '{}' INACTIVE in-memory and in Redis. Config: {}",
                prov, formatSanitizedConfig(config));
    }

    /**
     * Stub: Triggers state reconciliation for a provider by planning a direct broker call.
     * Future implementations will query the broker connection manager and hydrate missing keys.
     *
     * @param provider The broker provider identifier
     */
    public void reconcileProviderState(String provider) {
        String prov = normalizeProvider(provider);
        log.info("Triggered state reconciliation stub for provider '{}'. Broker account query planned.", prov);
    }

    /**
     * Stub: Callback / listener hook for processing direct broker account info updates.
     * Future implementations will update ground-truth cash and starting equity in Redis.
     *
     * @param provider The broker provider identifier
     * @param equity   Authoritative broker equity
     * @param cash     Authoritative broker cash
     * @param metadata Extended account attributes
     */
    public void onAccountInfoReceived(String provider, double equity, double cash, Map<String, Object> metadata) {
        String prov = normalizeProvider(provider);
        log.info("State reconciliation stub received account info for provider '{}': equity={}, cash={}, metadata={}",
                prov, equity, cash, metadata != null ? metadata : Collections.emptyMap());
    }

    public static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        return provider.toLowerCase().trim();
    }
}
