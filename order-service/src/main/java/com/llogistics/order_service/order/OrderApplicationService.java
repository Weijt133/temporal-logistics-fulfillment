package com.llogistics.order_service.order;

import com.llogistics.order_service.outbox.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderApplicationService {
    @Transactional(readOnly = true)
    public OrderPage listOrders(int page, int size, OrderStatus status) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Page must be non-negative and size must be between 1 and 100");
        }
        var pageable = org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("createdAt", "orderId").descending());
        var result = status == null ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return new OrderPage(result.getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public record OrderPage(java.util.List<OrderEntity> items, int page, int size,
                            long totalElements, int totalPages) { }
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;

    public OrderApplicationService(
            OrderRepository orderRepository,
            OutboxRepository outboxRepository) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public OrderEntity createOrder(String orderId, String sku, int quantity, String shippingAddress) {
        String workflowId = "order-" + orderId;

        int insertedRows = orderRepository.insertIfAbsent(orderId, workflowId, sku, quantity, shippingAddress);

        if(insertedRows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already exists: " + orderId);
        }
        outboxRepository.enqueue(orderId);

        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Order was not found after insert"
                ));
    }

        @Transactional(readOnly = true)
        public OrderEntity getOrder(String orderId) {
            return orderRepository.findById(orderId).orElseThrow(
                    ()-> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId)
            );
        }
}
