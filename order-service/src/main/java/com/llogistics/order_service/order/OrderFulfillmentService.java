package com.llogistics.order_service.order;

import com.llogistics.order_service.inventory.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderFulfillmentService {
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;

    public OrderFulfillmentService(OrderRepository orderRepository, InventoryService inventoryService) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public String reserveInventory(String orderId) {
        OrderEntity order = lockOrder(orderId);
        // An Activity can run again after its transaction committed but its response was lost.
        if (order.getStatus() == OrderStatus.RESERVED) {
            return OrderStatus.RESERVED.name();
        }
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot reserve inventory in state: " + order.getStatus());
        }

        // InventoryService joins this transaction: stock, reservation and order commit together.
        inventoryService.reserve(orderId, order.getSku(), order.getQuantity());
        order.setStatus(OrderStatus.RESERVED);
        order.setFailureReason(null);
        return OrderStatus.RESERVED.name();
    }

    @Transactional
    public void recordReservationFailure(String orderId, String reason) {
        OrderEntity order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.FAILED) {
            return;
        }
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot fail order in state: " + order.getStatus());
        }
        order.setStatus(OrderStatus.FAILED);
        order.setFailureReason(reason);
    }

    private OrderEntity lockOrder(String orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Order not found: " + orderId));
    }
}
