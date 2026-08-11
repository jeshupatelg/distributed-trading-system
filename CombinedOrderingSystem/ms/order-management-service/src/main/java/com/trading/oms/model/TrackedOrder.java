package com.trading.oms.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tracked_orders")
public class TrackedOrder {

    @Id
    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "order_type")
    private String orderType;

    @Column(name = "limit_price")
    private double limitPrice;

    @Column(name = "status", nullable = false)
    private String status; // PENDING, COMPLETED, FAILED

    @Column(name = "provider")
    private String provider;

    @Column(name = "strategy")
    private String strategy;

    @Column(name = "filled_qty")
    private int filledQty;

    @Column(name = "filled_avg_price")
    private double filledAvgPrice;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public TrackedOrder() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public double getLimitPrice() { return limitPrice; }
    public void setLimitPrice(double limitPrice) { this.limitPrice = limitPrice; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public int getFilledQty() { return filledQty; }
    public void setFilledQty(int filledQty) { this.filledQty = filledQty; }

    public double getFilledAvgPrice() { return filledAvgPrice; }
    public void setFilledAvgPrice(double filledAvgPrice) { this.filledAvgPrice = filledAvgPrice; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
