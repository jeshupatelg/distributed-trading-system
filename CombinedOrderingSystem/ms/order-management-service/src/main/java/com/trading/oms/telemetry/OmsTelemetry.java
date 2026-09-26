package com.trading.oms.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OmsTelemetry {
    private final MeterRegistry registry;
    private final ConcurrentMap<String, AtomicInteger> pendingOrderGauges = new ConcurrentHashMap<>();

    public OmsTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOrderCreated(String provider, String symbol, String side) {
        String prov = provider != null && !provider.isBlank() ? provider.toLowerCase().trim() : "default";
        registry.counter("oms_orders_created_total", "provider", prov, "symbol", symbol != null ? symbol : "unknown", "side", side != null ? side : "unknown").increment();
        getPendingGauge(prov).incrementAndGet();
    }

    public void recordOrderResolved(String provider, String status, String side, long durationNanos) {
        String prov = provider != null && !provider.isBlank() ? provider.toLowerCase().trim() : "default";
        registry.counter("oms_orders_resolved_total", "provider", prov, "status", status, "side", side != null ? side : "unknown").increment();
        registry.timer("oms_order_resolution_duration_seconds", "provider", prov, "status", status).record(durationNanos, TimeUnit.NANOSECONDS);
        getPendingGauge(prov).updateAndGet(curr -> Math.max(0, curr - 1));
    }

    public void recordReconciliationRun(String jobName, String status) {
        registry.counter("oms_reconciliation_runs_total", "job_name", jobName, "status", status).increment();
    }

    public void recordReconciliationDrift(String provider, int driftCount) {
        String prov = provider != null && !provider.isBlank() ? provider.toLowerCase().trim() : "default";
        registry.counter("oms_reconciliation_drift_detected_total", "provider", prov).increment(driftCount);
    }

    public void syncPendingCount(String provider, int count) {
        String prov = provider != null && !provider.isBlank() ? provider.toLowerCase().trim() : "default";
        getPendingGauge(prov).set(count);
    }

    private AtomicInteger getPendingGauge(String provider) {
        return pendingOrderGauges.computeIfAbsent(provider, p -> {
            AtomicInteger val = new AtomicInteger(0);
            registry.gauge("oms_orders_pending_count", Tags.of("provider", p), val);
            return val;
        });
    }
}
