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

    private Map<String, ProviderConfig> providers = new HashMap<>();

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    /**
     * Creates the list of ProviderConfig beans bound from trading.providers.
     */
    @Bean
    public List<ProviderConfig> providerBeans() {
        List<ProviderConfig> list = new ArrayList<>();
        if (providers != null) {
            providers.forEach((name, config) -> {
                if (name != null && !name.isBlank() && !"default".equalsIgnoreCase(name) && config != null) {
                    if (config.getName() == null || config.getName().isBlank()) {
                        config.setName(name.toLowerCase().trim());
                    }
                    list.add(config);
                }
            });
        }
        return list;
    }
}
