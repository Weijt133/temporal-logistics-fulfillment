package com.llogistics.order_service.activity;

import com.llogistics.order_service.order.OrderFulfillmentService;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component("orderFulfillmentActivities")
public class OrderFulfillmentActivitiesImpl implements OrderFulfillmentActivities {
    private final OrderFulfillmentService fulfillmentService;

    public OrderFulfillmentActivitiesImpl(OrderFulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @Override
    public String reserveOrderInventory(String orderId) {
        try {
            return fulfillmentService.reserveInventory(orderId);
        } catch (ResponseStatusException rejection) {
            if (!rejection.getStatusCode().is4xxClientError()) {
                throw rejection;
            }
            // The transactional service has rolled back before the error reaches this adapter.
            throw ApplicationFailure.newNonRetryableFailure(
                    rejection.getReason() == null ? rejection.getMessage() : rejection.getReason(),
                    INVENTORY_REJECTED);
        }
    }

    @Override
    public void recordReservationFailure(String orderId, String reason) {
        fulfillmentService.recordReservationFailure(orderId, reason);
    }
}
