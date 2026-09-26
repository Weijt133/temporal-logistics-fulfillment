package com.llogistics.order_service.activity;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ShipmentActivities {
    String SHIPMENT_REJECTED = "ShipmentRejected";

    String createOrderShipment(String orderId);
    void beginShipmentCompensation(String orderId, String reason);
    void compensateShipmentFailure(String orderId, String reason);
}
