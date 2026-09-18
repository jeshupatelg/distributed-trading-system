package com.trading.shared.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration wiring TradingRedisFacade and RedisDefaultsInitializer
 * when StringRedisTemplate is present on the Spring application context.
 */
@Configuration
public class SharedRedisConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(TradingRedisFacade.class)
    public TradingRedisFacade tradingRedisFacade(StringRedisTemplate redisTemplate) {
        return new TradingRedisFacade(redisTemplate);
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(RedisDefaultsInitializer.class)
    public RedisDefaultsInitializer redisDefaultsInitializer(StringRedisTemplate redisTemplate) {
        return new RedisDefaultsInitializer(redisTemplate);
    }
}
