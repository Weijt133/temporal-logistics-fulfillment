package com.llogistics.order_service.shipment;

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
@Table(name = "shipments")
public class ShipmentEntity {
    @Id
    @Column(name = "shipment_id", length = 64, nullable = false)
    private String shipmentId;

    @Column(name = "order_id", length = 64, nullable = false, unique = true)
    private String orderId;

    @Column(name = "shipping_address", length = 500, nullable = false)
    private String shippingAddress;

    @Column(length = 32, nullable = false)
    private String carrier = "MOCK";

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ShipmentStatus status = ShipmentStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShipmentEntity() {
    }

    public ShipmentEntity(String shipmentId, String orderId, String shippingAddress) {
        this.shipmentId = shipmentId;
        this.orderId = orderId;
        this.shippingAddress = shippingAddress;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getShipmentId() { return shipmentId; }
    public String getOrderId() { return orderId; }
    public String getShippingAddress() { return shippingAddress; }
    public String getCarrier() { return carrier; }
    public ShipmentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void cancel() {
        status = ShipmentStatus.CANCELLED;
    }
}
