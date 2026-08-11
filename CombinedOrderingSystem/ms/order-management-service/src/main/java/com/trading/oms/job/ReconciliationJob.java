package com.trading.oms.job;

import com.trading.connection.grpc.OrderStatusResponse;
import com.trading.oms.model.TrackedOrder;
import com.trading.oms.repository.TrackedOrderRepository;
import com.trading.oms.service.OrderResolutionService;
import com.trading.oms.service.ReconciliationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final TrackedOrderRepository orderRepository;
    private final ReconciliationClient reconciliationClient;
    private final OrderResolutionService resolutionService;

    public ReconciliationJob(TrackedOrderRepository orderRepository, 
                             ReconciliationClient reconciliationClient,
                             OrderResolutionService resolutionService) {
        this.orderRepository = orderRepository;
        this.reconciliationClient = reconciliationClient;
        this.resolutionService = resolutionService;
    }

    /**
     * Fallback reconciliation job that runs periodically to fetch the status of unresolved (PENDING) orders.
     */
    @Scheduled(fixedDelayString = "${trading.reconciliation.interval-ms:30000}")
    public void reconcilePendingOrders() {
        log.info("Starting scheduled reconciliation check for pending orders...");
        List<TrackedOrder> pendingOrders = orderRepository.findByStatus("PENDING");
        if (pendingOrders.isEmpty()) {
            log.info("No pending orders found in the database. Reconciliation complete.");
            return;
        }

        log.info("Found {} pending orders to reconcile.", pendingOrders.size());
        for (TrackedOrder order : pendingOrders) {
            String orderId = order.getOrderId();
            String provider = order.getProvider() != null ? order.getProvider() : "alpaca";

            try {
                OrderStatusResponse response = reconciliationClient.getOrderStatus(provider, orderId);
                String brokerStatus = response.getStatus() != null ? response.getStatus().toLowerCase() : "";
                int filledQty = response.getFilledQty();
                double filledAvgPrice = response.getFilledAvgPrice();

                log.info("Reconciliation fetched status for order {}: broker_status='{}', filled_qty={}, filled_price={}",
                    orderId, brokerStatus, filledQty, filledAvgPrice);

                if ("filled".equals(brokerStatus) || "completed".equals(brokerStatus)) {
                    resolutionService.resolveOrder(orderId, "COMPLETED", filledQty, filledAvgPrice);
                } else if ("canceled".equals(brokerStatus) || "rejected".equals(brokerStatus) || "expired".equals(brokerStatus)) {
                    resolutionService.resolveOrder(orderId, "FAILED", filledQty, filledAvgPrice);
                } else {
                    log.info("Order {} is still active on broker (broker_status='{}'). No action taken.", orderId, brokerStatus);
                }

            } catch (Exception e) {
                log.error("Failed to reconcile order status for order ID: {} via provider: {}", orderId, provider, e);
            }
        }
        log.info("Scheduled reconciliation check completed.");
    }
}
