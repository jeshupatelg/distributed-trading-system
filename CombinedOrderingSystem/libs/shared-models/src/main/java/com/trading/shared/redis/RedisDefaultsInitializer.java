package com.trading.shared.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Non-destructive startup initializer that seeds default values into Redis (system:defaults:*)
 * for keys where {@code isDefaultAllowed == true} using atomic {@code setIfAbsent} (SETNX).
 * Existing operational settings in Redis are strictly preserved.
 *
 * <p><b>Execution Context & Concurrency Safety across OPS and OMS:</b>
 * This runner is registered via {@link SharedRedisConfiguration} and executes independently
 * during application startup in both {@code order-processing-service} (OPS) and
 * {@code order-management-service} (OMS). This dual-service execution is safe, idempotent,
 * and free of race conditions because:
 * <ul>
 *   <li>Atomic operations: Redis {@code setIfAbsent} guarantees that whichever service executes first
 *       commits the seed value, while the other service receives {@code false} and performs a no-op.</li>
 *   <li>Uniform constants: The seeded default values are identical shared definitions from {@link RedisKeyDef},
 *       meaning neither service will ever attempt to set conflicting initial states.</li>
 *   <li>Zero runtime mutation: This runner runs exclusively once during the Spring Boot lifecycle bootstrap
 *       phase and does not alter dynamic keys during active trading.</li>
 * </ul>
 */
public class RedisDefaultsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisDefaultsInitializer.class);

    private final StringRedisTemplate redisTemplate;

    public RedisDefaultsInitializer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing centralized Redis keyspace defaults (non-destructive setIfAbsent)...");
        int seededCount = 0;

        for (RedisKeyDef keyDef : RedisKeyDef.values()) {
            if (keyDef.isDefaultAllowed()
                    && keyDef.getDefaultRedisKey() != null
                    && keyDef.getDefaultSeedValue() != null
                    && !keyDef.getDefaultRedisKey().contains("%s")) {

                String defaultKey = keyDef.getDefaultRedisKey();
                String seedValue = keyDef.getDefaultSeedValue();

                Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(defaultKey, seedValue);
                if (Boolean.TRUE.equals(wasSet)) {
                    log.info("Seeded Redis default: '{}' = '{}' ({})", defaultKey, seedValue, keyDef.name());
                    seededCount++;
                } else {
                    log.debug("Redis default '{}' already exists. Preserved existing runtime value.", defaultKey);
                }
            }
        }

        log.info("Centralized Redis defaults initialization complete. Newly seeded keys: {}", seededCount);
    }
}
