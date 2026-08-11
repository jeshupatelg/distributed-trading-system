package com.trading.oms.service;

import com.trading.connection.grpc.OrderExecutionServiceGrpc;
import com.trading.connection.grpc.OrderStatusRequest;
import com.trading.connection.grpc.OrderStatusResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class ReconciliationClient {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationClient.class);

    private final Environment env;
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public ReconciliationClient(Environment env) {
        this.env = env;
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

    private String resolveEndpoint(String provider) {
        String key = "trading.providers." + provider.toLowerCase();
        String endpoint = env.getProperty(key);
        if (endpoint == null) {
            endpoint = env.getProperty("trading.providers.default");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("No gRPC endpoint configured for provider: " + provider);
        }
        return endpoint;
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
