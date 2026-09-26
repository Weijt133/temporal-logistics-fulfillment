package com.llogistics.order_service.outbox;

import com.llogistics.order_service.workflow.OrderFulfillmentWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "spring.temporal.test-server.enabled",
        havingValue = "false",
        matchIfMissing = true
)

public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepository;
    private final WorkflowClient workflowClient;

    public  OutboxDispatcher(OutboxRepository outboxRepository, WorkflowClient workflowClient) {
        this.outboxRepository = outboxRepository;
        this.workflowClient = workflowClient;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void dispatchPending() {
        for (OutboxRepository.PendingStart task : outboxRepository.findReady()) {
            try {
                startWorkflow(task);
                outboxRepository.markDispatched(task.orderId());

                log.info(
                        "Workflow start dispatched for order {}",
                        task.orderId()
                );
            } catch (RuntimeException exception) {
                String error = exception.toString();

                if (error.length() > 1000) {
                    error = error.substring(0, 1000);
                }

                log.warn(
                        "Workflow start will be retried for order {}",
                        task.orderId(),
                        exception
                );

                outboxRepository.retryLater(task.orderId(), error);
            }
        }
    }

    private void startWorkflow(OutboxRepository.PendingStart task) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(task.workflowId())
                .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy
                                .WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
                )
                .build();

        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class,
                options
        );

        try {
            WorkflowClient.start(workflow::process, task.orderId());
        } catch (WorkflowExecutionAlreadyStarted exception) {
            log.info(
                    "Workflow already exists for order {}",
                    task.orderId()
            );
        }
    }
}
