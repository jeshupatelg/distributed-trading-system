package com.trading.ops.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OpsTelemetry {
    private final MeterRegistry registry;
    private final ConcurrentMap<String, AtomicInteger> killSwitchGauges = new ConcurrentHashMap<>();

    public OpsTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSignalReceived(String provider, String symbol, String action) {
        registry.counter("ops_signals_received_total", "provider", provider, "symbol", symbol, "action", action).increment();
    }

    public void recordOrderApproved(String provider, String symbol, String action) {
        registry.counter("ops_orders_approved_total", "provider", provider, "symbol", symbol, "action", action).increment();
    }

    public void recordOrderRejected(String provider, String reason, String riskGate) {
        registry.counter("ops_orders_rejected_total", "provider", provider, "reason", reason, "risk_gate", riskGate).increment();
    }

    public void recordRiskEvaluationTime(long durationNanos) {
        registry.timer("ops_risk_evaluation_duration_seconds").record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordBrokerSubmission(String provider, String status, long durationNanos) {
        registry.counter("ops_broker_submissions_total", "provider", provider, "status", status).increment();
        registry.timer("ops_broker_submission_duration_seconds", "provider", provider).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void setKillSwitchStatus(String provider, boolean active) {
        String prov = provider != null && !provider.isBlank() ? provider.toLowerCase().trim() : "global";
        killSwitchGauges.computeIfAbsent(prov, p -> {
            AtomicInteger val = new AtomicInteger(0);
            registry.gauge("ops_kill_switch_active", Tags.of("provider", p), val);
            return val;
        }).set(active ? 1 : 0);
    }
}
