package com.llogistics.order_service.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "workflow_id", length = 128, nullable = false, unique = true)
    private String workflowId;

    @Column(name = "sku", length = 64, nullable = false)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "shipping_address", length = 500, nullable = false)
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "shipment_id", length = 64)
    private String shipmentId;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OrderEntity(String orderId, String workflowId, String sku, int quantity, String shippingAddress) {
        this.orderId = orderId;
        this.workflowId = workflowId;
        this.sku = sku;
        this.quantity = quantity;
        this.shippingAddress = shippingAddress;
    }

    protected OrderEntity() {

    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getShipmentId() {
        return shipmentId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public void setShipmentId(String shipmentId) {
        this.shipmentId = shipmentId;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
