package com.llogistics.order_service.api;

import com.llogistics.order_service.order.OrderApplicationService;
import com.llogistics.order_service.order.OrderEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.llogistics.order_service.workflow.OrderWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final WorkflowClient workflowClient;
    private final OrderApplicationService orderApplicationService;

    public OrderController(
            WorkflowClient workflowClient,
            OrderApplicationService orderApplicationService) {
        this.workflowClient = workflowClient;
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    public ResponseEntity<OrderAccepted> create(
            @Valid @RequestBody CreateOrderRequest request) {
        String workflowId = "order-" + request.orderId();

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue("order-fulfillment")
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy
                                .WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
                )
                .build();
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                options
        );
        try {
            WorkflowClient.start(workflow::process, request.orderId());
        } catch (WorkflowExecutionAlreadyStarted exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This order already has a workflow",
                    exception
            );
        }

        return ResponseEntity.accepted().body(
                new OrderAccepted(
                        request.orderId(),
                        workflowId,
                        "ACCEPTED"
                )
        );

    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderEntity> getOrder(
            @PathVariable("orderId") String orderId ) {
        OrderEntity order = orderApplicationService.getOrder(orderId);
        return ResponseEntity.ok(order);
    }

    public record CreateOrderRequest(
            @NotBlank
            @Pattern(
                    regexp = "[A-Za-z0-9-]{1,64}",
                    message = "Use 1-64 letters, digits or hyphens"
            )
            String orderId,

            @NotBlank
            @Size(max=64)
            String sku,

            @NotNull
            @Min(1)
            Integer quantity,

            @NotBlank
            @Size(max=500)
            String shippingAddress
    ) {
    }

    public record OrderAccepted(
            String orderId,
            String workflowId,
            String status
    ) {
    }
}
