package com.trading.oms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RawOrder(
    String id,
    @JsonProperty("client_order_id") String clientOrderId,
    String symbol,
    String qty,
    @JsonProperty("filled_qty") String filledQty,
    @JsonProperty("filled_avg_price") String filledAvgPrice,
    String side,
    String status,
    String type
) {}
