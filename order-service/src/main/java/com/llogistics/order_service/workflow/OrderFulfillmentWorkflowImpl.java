package com.llogistics.order_service.workflow;

import com.llogistics.order_service.activity.OrderFulfillmentActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class OrderFulfillmentWorkflowImpl implements OrderFulfillmentWorkflow {
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
        try {
            return activities.reserveOrderInventory(orderId);
        } catch (ActivityFailure failure) {
            if (failure.getCause() instanceof ApplicationFailure rejection
                    && OrderFulfillmentActivities.INVENTORY_REJECTED.equals(rejection.getType())) {
                activities.recordReservationFailure(orderId, rejection.getOriginalMessage());
            }
            throw failure;
        }
    }
}
