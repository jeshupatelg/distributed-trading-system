package com.trading.ops.service;

import com.trading.connection.grpc.CancelAllRequest;
import com.trading.connection.grpc.CancelAllResponse;
import com.trading.connection.grpc.ClosePositionsRequest;
import com.trading.connection.grpc.ClosePositionsResponse;
import com.trading.connection.grpc.OrderExecutionServiceGrpc;
import com.trading.connection.grpc.OrderRequest;
import com.trading.connection.grpc.OrderResponse;
import com.trading.shared.config.ProviderConfig;
import com.trading.shared.state.ProviderStateManager;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class OrderExecutionClient {
    private static final Logger log = LoggerFactory.getLogger(OrderExecutionClient.class);

    private final ProviderStateManager providerStateManager;
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public OrderExecutionClient(ProviderStateManager providerStateManager) {
        this.providerStateManager = providerStateManager;
    }

    @PostConstruct
    public void initProviderHealth() {
        List<ProviderConfig> configs = providerStateManager.getProviderConfigs();
        if (configs != null && !configs.isEmpty()) {
            for (ProviderConfig p : configs) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    String prov = p.getName().toLowerCase().trim();
                    if (!p.isConfigComplete()) {
                        providerStateManager.markProviderInactive(prov);
                        log.warn("Provider '{}' configuration is INCOMPLETE (missing endpoint, timezone, or exchange). Config: {}. Marking INACTIVE.",
                                prov, providerStateManager.formatSanitizedConfig(p));
                        continue;
                    }
                    log.info("Proactively probing provider connection manager health on startup: '{}'", prov);
                    boolean healthy = checkProviderHealth(prov);
                    if (healthy) {
                        log.info("Provider '{}' connection manager is reachable. Performing Redis account cache init-check.", prov);
                        boolean activated = providerStateManager.markProviderActive(prov);
                        if (activated) {
                            log.info("Provider '{}' connection manager & account cache are ACTIVE.", prov);
                        }
                    } else {
                        providerStateManager.markProviderInactive(prov);
                        log.warn("Provider '{}' connection manager is UNREACHABLE/UNHEALTHY. Config: {}. Status set to INACTIVE.",
                                prov, providerStateManager.formatSanitizedConfig(p));
                    }
                }
            }
        }
    }

    /**
     * Submits an order request to the provider connection manager via gRPC.
     */
    public OrderResponse placeOrder(String provider, OrderRequest request) {
        String endpoint = resolveEndpoint(provider);
        log.info("Routing gRPC PlaceOrder for provider '{}' to endpoint '{}'", provider, endpoint);

        ManagedChannel channel = getOrCreateChannel(endpoint);
        OrderExecutionServiceGrpc.OrderExecutionServiceBlockingStub stub = 
            OrderExecutionServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        try {
            return stub.placeOrder(request);
        } catch (StatusRuntimeException e) {
            log.error("gRPC PlaceOrder failed for provider '{}' at endpoint '{}': {}", provider, endpoint, e.getStatus());
            if (providerStateManager != null && e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
                providerStateManager.markProviderInactive(provider);
            }
            throw e;
        }
    }

    /**
     * Emergency: Cancels all open orders on the designated provider gateway.
     */
    public CancelAllResponse cancelAllOrders(String provider) {
        String endpoint = resolveEndpoint(provider);
        log.warn("EMERGENCY: Routing gRPC CancelAllOrders for provider '{}' to endpoint '{}'", provider, endpoint);

        ManagedChannel channel = getOrCreateChannel(endpoint);
        OrderExecutionServiceGrpc.OrderExecutionServiceBlockingStub stub = 
            OrderExecutionServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);

        try {
            CancelAllRequest request = CancelAllRequest.newBuilder().setProvider(provider).build();
            return stub.cancelAllOrders(request);
        } catch (StatusRuntimeException e) {
            log.error("gRPC CancelAllOrders failed for provider '{}': {}", provider, e.getStatus());
            return CancelAllResponse.newBuilder().setSuccess(false).setMessage(e.getMessage()).build();
        }
    }

    /**
     * Emergency: Closes all open positions and cancels open orders on provider gateway.
     */
    public ClosePositionsResponse closeAllPositions(String provider) {
        String endpoint = resolveEndpoint(provider);
        log.warn("EMERGENCY: Routing gRPC CloseAllPositions for provider '{}' to endpoint '{}'", provider, endpoint);

        ManagedChannel channel = getOrCreateChannel(endpoint);
        OrderExecutionServiceGrpc.OrderExecutionServiceBlockingStub stub = 
            OrderExecutionServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(15, TimeUnit.SECONDS);

        try {
            ClosePositionsRequest request = ClosePositionsRequest.newBuilder()
                .setProvider(provider)
                .setCancelOrders(true)
                .build();
            return stub.closeAllPositions(request);
        } catch (StatusRuntimeException e) {
            log.error("gRPC CloseAllPositions failed for provider '{}': {}", provider, e.getStatus());
            return ClosePositionsResponse.newBuilder().setSuccess(false).setMessage(e.getMessage()).build();
        }
    }

    /**
     * Proactively checks connectivity health of the provider connection manager gateway.
     */
    public boolean checkProviderHealth(String provider) {
        try {
            String endpoint = resolveEndpoint(provider);
            ManagedChannel channel = getOrCreateChannel(endpoint);
            io.grpc.ConnectivityState state = channel.getState(true);
            boolean healthy = (state != io.grpc.ConnectivityState.SHUTDOWN && state != io.grpc.ConnectivityState.TRANSIENT_FAILURE);
            log.info("Provider '{}' connection manager gRPC health status: {} (channel state: {})", provider, healthy ? "HEALTHY" : "UNHEALTHY", state);
            return healthy;
        } catch (Exception e) {
            log.warn("Health check failed for provider '{}': {}", provider, e.getMessage());
            return false;
        }
    }

    private String resolveEndpoint(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider string must not be null or blank");
        }
        ProviderConfig config = providerStateManager != null ? providerStateManager.findProviderConfig(provider) : null;
        if (config != null && config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            return config.getEndpoint();
        }
        throw new IllegalArgumentException("No gRPC endpoint configured for registered provider: " + provider);
    }

    private ManagedChannel getOrCreateChannel(String endpoint) {
        return channels.computeIfAbsent(endpoint, ep -> {
            log.info("Creating new gRPC channel for endpoint: {}", ep);
            return ManagedChannelBuilder.forTarget(ep)
                .usePlaintext()
                .build();
        });
    }

    public void shutdown() {
        channels.values().forEach(channel -> {
            try {
                channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
