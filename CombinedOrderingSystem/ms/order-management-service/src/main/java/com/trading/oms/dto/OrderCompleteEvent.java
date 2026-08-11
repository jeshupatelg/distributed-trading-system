package com.trading.oms.dto;

public record OrderCompleteEvent(
    String orderId,
    String symbol,
    int qty,
    String side,
    String status,
    int filledQty,
    double filledAvgPrice,
    String provider,
    String strategy
) {}
