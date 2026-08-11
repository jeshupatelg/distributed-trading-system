package com.trading.ops.dto;

public record OrderCreateEvent(
    String orderId,
    String symbol,
    int qty,
    String side,
    String orderType,
    double limitPrice,
    String provider,
    String strategy
) {}
