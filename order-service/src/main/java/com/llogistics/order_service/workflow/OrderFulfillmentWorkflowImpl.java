package com.llogistics.order_service.workflow;

import com.llogistics.order_service.activity.OrderFulfillmentActivities;
import com.llogistics.order_service.activity.ShipmentActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class OrderFulfillmentWorkflowImpl implements OrderFulfillmentWorkflow {
    private final ShipmentActivities shipping = Workflow.newActivityStub(
            ShipmentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(5))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    private final ShipmentActivities compensation = Workflow.newActivityStub(
            ShipmentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .build())
                    .build());

    private final OrderFulfillmentActivities activities = Workflow.newActivityStub(
            OrderFulfillmentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(30))
                            // Keep retrying transient failures; a committed reservation is idempotent.
                            .build())
                    .build());

    @Override
    public String process(String orderId) {
        String reservationResult;
        try {
            reservationResult = activities.reserveOrderInventory(orderId);
        } catch (ActivityFailure failure) {
            if (failure.getCause() instanceof ApplicationFailure rejection
                    && OrderFulfillmentActivities.INVENTORY_REJECTED.equals(rejection.getType())) {
                activities.recordReservationFailure(orderId, rejection.getOriginalMessage());
            }
            throw failure;
        }

        // Preserve the inventory-only command sequence when replaying existing histories.
        if (Workflow.getVersion("add-shipment-v1", Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
            return reservationResult;
        }
        try {
            shipping.createOrderShipment(orderId);
            return "SHIPMENT_CREATED";
        } catch (ActivityFailure failure) {
            String reason = failure.getCause() instanceof ApplicationFailure applicationFailure
                    ? applicationFailure.getOriginalMessage() : failure.getMessage();
            compensation.beginShipmentCompensation(orderId, reason);
            // Do not report FAILED until cancellation and stock restoration have committed.
            compensation.compensateShipmentFailure(orderId, reason);
            throw failure;
        }
    }
}
