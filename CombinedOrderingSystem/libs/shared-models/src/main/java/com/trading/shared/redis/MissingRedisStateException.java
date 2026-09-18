package com.trading.shared.redis;

/**
 * Thrown when a mandatory Redis key (with isDefaultAllowed == false) is missing in Redis,
 * triggering an immediate fail-fast state to prevent operating on artificial values.
 */
public class MissingRedisStateException extends RuntimeException {

    private final RedisKeyDef keyDef;
    private final String resolvedKey;

    public MissingRedisStateException(RedisKeyDef keyDef, String resolvedKey) {
        super(String.format("Mandatory Redis state missing for key '%s' (%s). isDefaultAllowed=false, fail-fast triggered.",
                resolvedKey, keyDef.name()));
        this.keyDef = keyDef;
        this.resolvedKey = resolvedKey;
    }

    public MissingRedisStateException(String message) {
        super(message);
        this.keyDef = null;
        this.resolvedKey = null;
    }

    public RedisKeyDef getKeyDef() {
        return keyDef;
    }

    public String getResolvedKey() {
        return resolvedKey;
    }
}
