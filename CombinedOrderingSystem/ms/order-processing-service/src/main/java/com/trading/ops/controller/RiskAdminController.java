package com.trading.ops.controller;

import com.trading.shared.config.ProviderConfig;
import com.trading.ops.service.OrderExecutionClient;
import com.trading.ops.service.RiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/risk")
@CrossOrigin(origins = "*")
public class RiskAdminController {
    private static final Logger log = LoggerFactory.getLogger(RiskAdminController.class);

    private final RiskManager riskManager;
    private final OrderExecutionClient executionClient;
    private final List<ProviderConfig> providerBeans;

    public RiskAdminController(RiskManager riskManager, OrderExecutionClient executionClient, List<ProviderConfig> providerBeans) {
        this.riskManager = riskManager;
        this.executionClient = executionClient;
        this.providerBeans = providerBeans;
    }

    private String resolveTargetProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            return provider.toLowerCase().trim();
        }
        if (providerBeans != null && !providerBeans.isEmpty()) {
            return providerBeans.getFirst().getName();
        }
        throw new IllegalArgumentException("No broker providers configured");
    }

    /**
     * Get real-time risk engine status, drawdown metrics, and gate states per provider.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getRiskStatus(@RequestParam(required = false) String provider) {
        String target = resolveTargetProvider(provider);
        return ResponseEntity.ok(riskManager.getRiskStatus(target));
    }

    /**
     * Get active dynamic risk configuration parameters.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getRiskConfig(@RequestParam(required = false) String provider) {
        String target = resolveTargetProvider(provider);
        return ResponseEntity.ok(riskManager.getRiskConfig(target));
    }

    /**
     * Update dynamic risk parameters in real-time from GUI or API.
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateRiskConfig(
            @RequestBody Map<String, Object> newConfig,
            @RequestParam(required = false) String provider) {
        String target = resolveTargetProvider(provider);
        log.info("Received request to update risk parameters for provider '{}': {}", target, newConfig);
        riskManager.updateRiskConfig(newConfig, target);
        return ResponseEntity.ok(riskManager.getRiskConfig(target));
    }

    /**
     * Emergency Global Kill Switch Trigger:
     * 1. Sets software lockdown flag in Redis to drop incoming signals.
     * 2. Loops over all registered provider beans to cancel working orders across gateways via gRPC.
     * 3. Liquidates all open positions to cash across all registered providers.
     */
    @PostMapping("/kill-switch/trigger")
    public ResponseEntity<Map<String, Object>> triggerKillSwitch(
            @RequestParam(defaultValue = "true") boolean liquidate,
            @RequestParam(required = false) String provider) {
        log.warn("EMERGENCY KILL SWITCH TRIGGERED! Target provider={}, Liquidate positions={}", provider, liquidate);
        
        if (provider != null && !provider.isBlank()) {
            String prov = provider.toLowerCase().trim();
            riskManager.triggerKillSwitch(prov);
            try {
                executionClient.cancelAllOrders(prov);
            } catch (Exception e) {
                log.error("Error canceling open orders during kill-switch for provider {}: {}", prov, e.getMessage());
            }
            if (liquidate) {
                try {
                    executionClient.closeAllPositions(prov);
                } catch (Exception e) {
                    log.error("Error closing positions during kill-switch for provider {}: {}", prov, e.getMessage());
                }
            }
        } else {
            // Global Trigger across ALL registered provider beans
            riskManager.triggerKillSwitch();
            if (providerBeans != null) {
                for (ProviderConfig p : providerBeans) {
                    String provName = p.getName();
                    log.warn("Looping emergency kill switch action for provider bean: '{}'", provName);
                    try {
                        executionClient.cancelAllOrders(provName);
                    } catch (Exception e) {
                        log.error("Error canceling open orders during global kill-switch for provider {}: {}", provName, e.getMessage());
                    }
                    if (liquidate) {
                        try {
                            executionClient.closeAllPositions(provName);
                        } catch (Exception e) {
                            log.error("Error closing positions during global kill-switch for provider {}: {}", provName, e.getMessage());
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok(Map.of(
            "status", "EMERGENCY_LOCKDOWN_ACTIVATED",
            "kill_switch_active", true,
            "liquidated", liquidate,
            "message", "Emergency lockdown initiated. All incoming trading signals halted and orders cancelled."
        ));
    }

    /**
     * Reset Emergency Kill Switch back to normal operation.
     */
    @PostMapping("/kill-switch/reset")
    public ResponseEntity<Map<String, Object>> resetKillSwitch(@RequestParam(required = false) String provider) {
        if (provider != null && !provider.isBlank()) {
            String prov = provider.toLowerCase().trim();
            log.info("Resetting Kill Switch for provider '{}'", prov);
            riskManager.resetKillSwitch(prov);
        } else {
            log.info("Resetting Global Kill Switch back to normal state across all providers.");
            riskManager.resetKillSwitch();
        }
        return ResponseEntity.ok(Map.of(
            "status", "NORMAL_OPERATION",
            "kill_switch_active", false,
            "message", "Emergency lockdown cleared. Normal trading pipeline resumed."
        ));
    }
}