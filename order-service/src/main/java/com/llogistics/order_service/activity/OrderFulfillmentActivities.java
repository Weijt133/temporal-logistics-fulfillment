package com.llogistics.order_service.activity;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface OrderFulfillmentActivities {
    String INVENTORY_REJECTED = "InventoryReservationRejected";

    String reserveOrderInventory(String orderId);

    void recordReservationFailure(String orderId, String reason);
}
