package com.trading.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import com.trading.shared.config.SharedAppConfig;
import com.trading.shared.config.ProviderConfiguration;
import com.trading.shared.redis.SharedRedisConfiguration;

@SpringBootApplication
@EnableKafka
@Import({SharedAppConfig.class, ProviderConfiguration.class, SharedRedisConfiguration.class})
public class OrderProcessingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingApplication.class, args);
    }
}
