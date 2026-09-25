package com.llogistics.order_service.activity;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface OrderActivities {

    String acceptDemoOrder(String orderId);
}
