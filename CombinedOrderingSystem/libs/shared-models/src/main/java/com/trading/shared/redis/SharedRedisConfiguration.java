package com.trading.shared.redis;

import com.trading.shared.config.ProviderConfig;
import com.trading.shared.state.PositionStateManager;
import com.trading.shared.state.ProviderStateManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Configuration wiring TradingRedisFacade, RedisDefaultsInitializer,
 * ProviderStateManager, and PositionStateManager using Spring's StringRedisTemplate.
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

    @Bean
    @ConditionalOnMissingBean(ProviderStateManager.class)
    public ProviderStateManager providerStateManager(TradingRedisFacade redisFacade, List<ProviderConfig> providerBeans) {
        return new ProviderStateManager(redisFacade, providerBeans);
    }

    @Bean
    @ConditionalOnMissingBean(PositionStateManager.class)
    public PositionStateManager positionStateManager(TradingRedisFacade redisFacade) {
        return new PositionStateManager(redisFacade);
    }
}
