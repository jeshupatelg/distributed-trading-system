package com.trading.oms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
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
