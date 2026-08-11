package com.trading.ops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalEvent(
    String symbol,
    String action, // "BUY" or "SELL"
    int qty,
    double price,
    String provider, // e.g. "alpaca"
    String strategy
) {}
