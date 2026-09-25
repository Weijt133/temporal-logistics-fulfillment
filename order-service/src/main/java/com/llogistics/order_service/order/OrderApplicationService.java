package com.llogistics.order_service.order;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderApplicationService {
    private final OrderRepository orderRepository;

    public OrderApplicationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderEntity createOrder(String orderId, String sku, int quantity, String shippingAddress) {
        String workflowId = "order-" + orderId;

        int insertedRows = orderRepository.insertIfAbsent(orderId, workflowId, sku, quantity, shippingAddress);

        if(insertedRows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already exists: " + orderId);
        }
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
