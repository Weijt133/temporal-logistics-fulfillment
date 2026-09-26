package com.llogistics.order_service.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderFulfillmentWorkflow {
    String TASK_QUEUE = "order-fulfillment-v2";

    @WorkflowMethod
    String process(String orderId);
}
