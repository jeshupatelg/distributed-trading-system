package com.trading.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "trading")
public class ProviderConfiguration {

    private Map<String, String> providers = new HashMap<>();

    public Map<String, String> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, String> providers) {
        this.providers = providers;
    }

    @Bean
    public List<ProviderConfig> providerBeans() {
        List<ProviderConfig> list = new ArrayList<>();
        providers.forEach((name, endpoint) -> {
            if (name != null && !name.isBlank() && !"default".equalsIgnoreCase(name)) {
                list.add(new ProviderConfig(name.toLowerCase(), endpoint));
            }
        });
        return list;
    }
}
