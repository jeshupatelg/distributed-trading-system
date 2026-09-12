package com.trading.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import com.trading.shared.config.SharedAppConfig;
import com.trading.shared.config.ProviderConfiguration;

@SpringBootApplication
@EnableKafka
@Import({SharedAppConfig.class, ProviderConfiguration.class})
public class OrderProcessingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingApplication.class, args);
    }
}
