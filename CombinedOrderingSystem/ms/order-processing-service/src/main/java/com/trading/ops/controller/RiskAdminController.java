package com.trading.ops.controller;

import com.trading.ops.service.OrderExecutionClient;
import com.trading.ops.service.RiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/risk")
@CrossOrigin(origins = "*")
public class RiskAdminController {
    private static final Logger log = LoggerFactory.getLogger(RiskAdminController.class);

    private final RiskManager riskManager;
    private final OrderExecutionClient executionClient;

    public RiskAdminController(RiskManager riskManager, OrderExecutionClient executionClient) {
        this.riskManager = riskManager;
        this.executionClient = executionClient;
    }

    /**
     * Get real-time risk engine status, drawdown metrics, and gate states.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getRiskStatus() {
        return ResponseEntity.ok(riskManager.getRiskStatus());
    }

    /**
     * Get active dynamic risk configuration parameters.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getRiskConfig() {
        return ResponseEntity.ok(riskManager.getRiskConfig());
    }

    /**
     * Update dynamic risk parameters in real-time from GUI or API.
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateRiskConfig(@RequestBody Map<String, Object> newConfig) {
        log.info("Received request to update risk parameters: {}", newConfig);
        riskManager.updateRiskConfig(newConfig);
        return ResponseEntity.ok(riskManager.getRiskConfig());
    }

    /**
     * Emergency Global Kill Switch Trigger:
     * 1. Sets software lockdown flag in Redis to drop incoming signals.
     * 2. Cancels all working orders across brokers via gRPC.
     * 3. Liquidates all open positions to cash.
     */
    @PostMapping("/kill-switch/trigger")
    public ResponseEntity<Map<String, Object>> triggerKillSwitch(@RequestParam(defaultValue = "true") boolean liquidate) {
        log.warn("EMERGENCY KILL SWITCH TRIGGERED! Liquidate positions={}", liquidate);
        
        // 1. Set atomic Redis flag
        riskManager.triggerKillSwitch();

        // 2. Cancel all open orders on broker gateway
        try {
            executionClient.cancelAllOrders("alpaca");
        } catch (Exception e) {
            log.error("Error canceling open orders during kill-switch: {}", e.getMessage());
        }

        // 3. Liquidate open positions if requested
        if (liquidate) {
            try {
                executionClient.closeAllPositions("alpaca");
            } catch (Exception e) {
                log.error("Error closing positions during kill-switch: {}", e.getMessage());
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
    public ResponseEntity<Map<String, Object>> resetKillSwitch() {
        log.info("Resetting Global Kill Switch back to normal state.");
        riskManager.resetKillSwitch();
        return ResponseEntity.ok(Map.of(
            "status", "NORMAL_OPERATION",
            "kill_switch_active", false,
            "message", "Emergency lockdown cleared. Normal trading pipeline resumed."
        ));
    }
}