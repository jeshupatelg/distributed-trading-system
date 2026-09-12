package com.trading.oms.service;

import com.trading.connection.grpc.OrderExecutionServiceGrpc;
import com.trading.connection.grpc.OrderStatusRequest;
import com.trading.connection.grpc.OrderStatusResponse;
import com.trading.shared.config.ProviderConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class ReconciliationClient {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationClient.class);

    private final List<ProviderConfig> providerBeans;
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public ReconciliationClient(List<ProviderConfig> providerBeans) {
        this.providerBeans = providerBeans;
    }

    /**
     * Queries order status from the provider's connection manager via gRPC.
     */
    public OrderStatusResponse getOrderStatus(String provider, String orderId) {
        String endpoint = resolveEndpoint(provider);
        log.info("Routing gRPC GetOrderStatus for provider '{}' to endpoint '{}'", provider, endpoint);

        ManagedChannel channel = getOrCreateChannel(endpoint);
        OrderExecutionServiceGrpc.OrderExecutionServiceBlockingStub stub = 
            OrderExecutionServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        OrderStatusRequest request = OrderStatusRequest.newBuilder()
            .setOrderId(orderId)
            .build();

        try {
            return stub.getOrderStatus(request);
        } catch (StatusRuntimeException e) {
            log.error("gRPC GetOrderStatus failed for provider '{}' at endpoint '{}': {}", provider, endpoint, e.getStatus());
            throw e;
        }
    }

    /**
     * Proactively checks gRPC connectivity status of provider connection manager.
     */
    public boolean checkHealth(String provider) {
        try {
            String endpoint = resolveEndpoint(provider);
            ManagedChannel channel = getOrCreateChannel(endpoint);
            io.grpc.ConnectivityState state = channel.getState(true);
            return (state != io.grpc.ConnectivityState.SHUTDOWN && state != io.grpc.ConnectivityState.TRANSIENT_FAILURE);
        } catch (Exception e) {
            log.warn("gRPC health probe failed for provider '{}': {}", provider, e.getMessage());
            return false;
        }
    }

    private String resolveEndpoint(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider string must not be null or blank");
        }
        String prov = provider.toLowerCase().trim();
        if (providerBeans != null) {
            for (ProviderConfig config : providerBeans) {
                if (prov.equals(config.getName())) {
                    return config.getEndpoint();
                }
            }
        }
        throw new IllegalArgumentException("No gRPC endpoint configured for provider: " + provider);
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
