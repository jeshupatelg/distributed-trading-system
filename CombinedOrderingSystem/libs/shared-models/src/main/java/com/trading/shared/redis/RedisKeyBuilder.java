package com.trading.shared.redis;

/**
 * Fluent, type-safe builder for Redis keys enforcing parameter validation and case normalization.
 */
public class RedisKeyBuilder {

    private final RedisKeyDef keyDef;
    private String provider;
    private String symbol;
    private Long epoch;

    private RedisKeyBuilder(RedisKeyDef keyDef) {
        if (keyDef == null) {
            throw new IllegalArgumentException("RedisKeyDef must not be null");
        }
        this.keyDef = keyDef;
    }

    public static RedisKeyBuilder of(RedisKeyDef keyDef) {
        return new RedisKeyBuilder(keyDef);
    }

    // --- Direct convenience static builders ---

    public static String key(RedisKeyDef keyDef) {
        return of(keyDef).build();
    }

    public static String key(RedisKeyDef keyDef, String provider) {
        return of(keyDef).provider(provider).build();
    }

    public static String key(RedisKeyDef keyDef, String provider, String symbol) {
        return of(keyDef).provider(provider).symbol(symbol).build();
    }

    public static String key(RedisKeyDef keyDef, String provider, long epoch) {
        return of(keyDef).provider(provider).epoch(epoch).build();
    }

    public static String keyForSymbol(RedisKeyDef keyDef, String symbol) {
        return of(keyDef).symbol(symbol).build();
    }

    public static String prefix(RedisKeyDef keyDef, String provider) {
        if (keyDef == null) {
            throw new IllegalArgumentException("RedisKeyDef must not be null");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be null or blank");
        }
        String prov = provider.toLowerCase().trim();
        if (keyDef.getScope() == KeyScope.PROVIDER_AND_SYMBOL) {
            String format = keyDef.getFormat();
            int secondPlaceholder = format.lastIndexOf(":%s");
            if (secondPlaceholder != -1) {
                return String.format(format.substring(0, secondPlaceholder + 1), prov);
            }
        }
        return of(keyDef).provider(prov).build() + ":";
    }

    public static String pattern(RedisKeyDef keyDef, String provider) {
        return prefix(keyDef, provider) + "*";
    }

    // --- Fluent setters ---

    public RedisKeyBuilder provider(String provider) {
        if (provider != null) {
            this.provider = provider.toLowerCase().trim();
        }
        return this;
    }

    public RedisKeyBuilder symbol(String symbol) {
        if (symbol != null) {
            this.symbol = symbol.toUpperCase().trim();
        }
        return this;
    }

    public RedisKeyBuilder epoch(long epoch) {
        this.epoch = epoch;
        return this;
    }

    /**
     * Builds and validates the parameterized Redis key string based on the KeyScope contract.
     *
     * @return Formatted key string with dynamic dimensions substituted
     * @throws IllegalArgumentException if required parameters for the scope are missing or blank
     */
    public String build() {
        KeyScope scope = keyDef.getScope();

        switch (scope) {
            case GLOBAL -> {
                return keyDef.getFormat();
            }
            case PROVIDER -> {
                validateParam("provider", provider);
                return String.format(keyDef.getFormat(), provider);
            }
            case SYMBOL -> {
                validateParam("symbol", symbol);
                return String.format(keyDef.getFormat(), symbol);
            }
            case PROVIDER_AND_SYMBOL -> {
                validateParam("provider", provider);
                validateParam("symbol", symbol);
                return String.format(keyDef.getFormat(), provider, symbol);
            }
            case PROVIDER_AND_EPOCH -> {
                validateParam("provider", provider);
                if (epoch == null || epoch <= 0) {
                    throw new IllegalArgumentException("KeyDef " + keyDef.name() + " requires a positive epoch timestamp");
                }
                return String.format(keyDef.getFormat(), provider, epoch);
            }
            default -> throw new IllegalStateException("Unhandled KeyScope: " + scope);
        }
    }

    private void validateParam(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    String.format("KeyDef '%s' requires parameter '%s', but got null/blank", keyDef.name(), name)
            );
        }
    }

    public RedisKeyDef getKeyDef() {
        return keyDef;
    }
}
