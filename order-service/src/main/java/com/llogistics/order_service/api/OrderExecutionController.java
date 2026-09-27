package com.llogistics.order_service.api;

import com.llogistics.order_service.order.OrderApplicationService;
import com.llogistics.order_service.outbox.OutboxRepository;
import com.llogistics.order_service.workflow.WorkflowInspectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderExecutionController {
    private final OrderApplicationService orders;
    private final OutboxRepository outbox;
    private final WorkflowInspectionService workflows;

    public OrderExecutionController(OrderApplicationService orders, OutboxRepository outbox,
                                    WorkflowInspectionService workflows) {
        this.orders = orders;
        this.outbox = outbox;
        this.workflows = workflows;
    }

    @GetMapping("/orders/{orderId}/execution")
    public ExecutionDetails get(@PathVariable String orderId) {
        var order = orders.getOrder(orderId);
        return new ExecutionDetails(outbox.findStatus(orderId).orElse(null),
                workflows.inspect(order.getWorkflowId()));
    }

    public record ExecutionDetails(OutboxRepository.DispatchStatus outbox,
                                   WorkflowInspectionService.WorkflowView workflow) { }
}
