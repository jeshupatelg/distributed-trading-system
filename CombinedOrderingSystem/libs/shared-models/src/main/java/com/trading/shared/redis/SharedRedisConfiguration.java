package com.trading.shared.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration wiring TradingRedisFacade and RedisDefaultsInitializer
 * using Spring's StringRedisTemplate.
 */
@Configuration
@ConditionalOnClass(StringRedisTemplate.class)
public class SharedRedisConfiguration {

    @Bean
    @ConditionalOnMissingBean(TradingRedisFacade.class)
    public TradingRedisFacade tradingRedisFacade(StringRedisTemplate redisTemplate) {
        return new TradingRedisFacade(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(RedisDefaultsInitializer.class)
    public RedisDefaultsInitializer redisDefaultsInitializer(StringRedisTemplate redisTemplate) {
        return new RedisDefaultsInitializer(redisTemplate);
    }
}
