package com.trading.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Import;
import com.trading.shared.config.SharedAppConfig;
import com.trading.shared.config.ProviderConfiguration;
import com.trading.shared.redis.SharedRedisConfiguration;

@SpringBootApplication
@EnableScheduling
@EnableKafka
@Import({SharedAppConfig.class, ProviderConfiguration.class, SharedRedisConfiguration.class})
public class OrderManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderManagementApplication.class, args);
    }
}
