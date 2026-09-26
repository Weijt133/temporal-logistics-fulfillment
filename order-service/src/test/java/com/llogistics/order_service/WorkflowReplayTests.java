package com.llogistics.order_service;

import com.llogistics.order_service.workflow.OrderFulfillmentWorkflowImpl;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class WorkflowReplayTests {
    @Test
    void inventoryOnlySuccessHistoryStillReplays() throws Exception {
        WorkflowReplayer.replayWorkflowExecutionFromResource(
                "history/inventory-only-success.json", OrderFulfillmentWorkflowImpl.class);
    }

    @Test
    void inventoryOnlyFailureHistoryStillReplays() throws Exception {
        WorkflowReplayer.replayWorkflowExecutionFromResource(
                "history/inventory-only-failure.json", OrderFulfillmentWorkflowImpl.class);
    }
}
