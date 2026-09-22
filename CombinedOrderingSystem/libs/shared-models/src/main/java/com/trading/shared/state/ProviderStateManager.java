package com.trading.shared.state;

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
 * broker provider state in Redis across all trading microservices (OPS and OMS).
 */
public class ProviderStateManager {
    private static final Logger log = LoggerFactory.getLogger(ProviderStateManager.class);

    private final TradingRedisFacade redisFacade;

    public ProviderStateManager(TradingRedisFacade redisFacade) {
        this.redisFacade = redisFacade;
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
     * Marks the provider as ACTIVE in Redis.
     */
    public void markProviderActive(String provider) {
        String prov = normalizeProvider(provider);
        redisFacade.setString(RedisKeyDef.PROVIDER_STATUS, prov, "ACTIVE");
        log.info("Provider '{}' marked ACTIVE in Redis.", prov);
    }

    /**
     * Marks the provider as INACTIVE in Redis.
     */
    public void markProviderInactive(String provider) {
        String prov = normalizeProvider(provider);
        redisFacade.setString(RedisKeyDef.PROVIDER_STATUS, prov, "INACTIVE");
        log.warn("Provider '{}' marked INACTIVE in Redis.", prov);
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

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        return provider.toLowerCase().trim();
    }
}
