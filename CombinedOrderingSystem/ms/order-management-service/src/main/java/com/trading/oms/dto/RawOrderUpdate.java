package com.trading.oms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RawOrderUpdate(
    String event, // e.g. "fill", "canceled", "rejected"
    RawOrder order
) {}
