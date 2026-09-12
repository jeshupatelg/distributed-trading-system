package com.trading.shared.config;

public class ProviderConfig {
    private String name;
    private String endpoint;
    private String timezone;
    private String exchange;
    private boolean enabled = true;
    private transient volatile boolean active = false;

    public ProviderConfig() {
    }

    public ProviderConfig(String name, String endpoint) {
        this.name = name;
        this.endpoint = endpoint;
    }

    public ProviderConfig(String name, String endpoint, String timezone, String exchange) {
        this.name = name;
        this.endpoint = endpoint;
        this.timezone = timezone;
        this.exchange = exchange;
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

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Checks if all mandatory fields (name, endpoint, timezone, exchange) are present and non-blank.
     */
    public boolean isConfigComplete() {
        return name != null && !name.isBlank() &&
               endpoint != null && !endpoint.isBlank() &&
               timezone != null && !timezone.isBlank() &&
               exchange != null && !exchange.isBlank();
    }

    @Override
    public String toString() {
        return "ProviderConfig{" +
                "name='" + name + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", timezone='" + timezone + '\'' +
                ", exchange='" + exchange + '\'' +
                ", enabled=" + enabled +
                ", active=" + active +
                '}';
    }
}
