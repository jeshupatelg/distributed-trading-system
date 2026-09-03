package com.trading.ops.dto;

public record OrderRejectEvent(
    String orderId,
    String symbol,
    int qty,
    String side,
    double price,
    double estimatedCost,
    String provider,
    String strategy,
    String rejectReason,
    String riskGateLevel,
    long timestamp
) {}
