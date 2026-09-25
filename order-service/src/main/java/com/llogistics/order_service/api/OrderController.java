package com.llogistics.order_service.api;

import com.llogistics.order_service.order.OrderApplicationService;
import com.llogistics.order_service.order.OrderEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(
            OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    public ResponseEntity<OrderAccepted> create(
            @Valid @RequestBody CreateOrderRequest request) {

        OrderEntity order = orderApplicationService.createOrder(
                request.orderId(),
                request.sku(),
                request.quantity(),
                request.shippingAddress()
        );

        return ResponseEntity.accepted()
                .location(URI.create("/orders/" + order.getOrderId()))
                .body(new OrderAccepted(
                        order.getOrderId(),
                        order.getWorkflowId(),
                        order.getStatus().name()
                ));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderEntity> getOrder(
            @PathVariable("orderId") String orderId) {
        return ResponseEntity.ok(
                orderApplicationService.getOrder(orderId)
        );
    }

    public record CreateOrderRequest(
            @NotBlank
            @Pattern(
                    regexp = "[A-Za-z0-9-]{1,64}",
                    message = "Use 1-64 letters, digits or hyphens"
            )
            String orderId,

            @NotBlank
            @Size(max = 64)
            String sku,

            @NotNull
            @Min(1)
            Integer quantity,

            @NotBlank
            @Size(max = 500)
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