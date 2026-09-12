package com.trading.shared.config;

public class ProviderConfig {
    private String name;
    private String endpoint;
    private boolean enabled = true;

    public ProviderConfig() {
    }

    public ProviderConfig(String name, String endpoint) {
        this.name = name;
        this.endpoint = endpoint;
    }

    public ProviderConfig(String name, String endpoint, boolean enabled) {
        this.name = name;
        this.endpoint = endpoint;
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "ProviderConfig{" +
                "name='" + name + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
