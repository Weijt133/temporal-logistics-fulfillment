package com.llogistics.order_service.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("orderActivities")
public class OrderActivitiesImpl implements OrderActivities{
    private static final Logger log = LoggerFactory.getLogger(OrderActivitiesImpl.class);
    @Override
    public String acceptDemoOrder(String orderId) {
        log.info("Accepted demonstration order {}", orderId);
        return  "Accepted demo order " + orderId;
    }
}
