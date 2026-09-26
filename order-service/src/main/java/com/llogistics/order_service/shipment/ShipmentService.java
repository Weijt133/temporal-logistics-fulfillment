package com.llogistics.order_service.shipment;

import com.llogistics.order_service.inventory.InventoryService;
import com.llogistics.order_service.order.OrderEntity;
import com.llogistics.order_service.order.OrderRepository;
import com.llogistics.order_service.order.OrderStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
public class ShipmentService {
    private final ShipmentRepository shipments;
    private final OrderRepository orders;
    private final InventoryService inventory;

    public ShipmentService(ShipmentRepository shipments, OrderRepository orders, InventoryService inventory) {
        this.shipments = shipments;
        this.orders = orders;
        this.inventory = inventory;
    }

    @Transactional
    public String createForOrder(String orderId) {
        // Creation and compensation share this lock, so a late Activity cannot recreate a shipment.
        OrderEntity order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.RESERVED && order.getStatus() != OrderStatus.SHIPMENT_CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot create a shipment in state: " + order.getStatus());
        }
        var existing = shipments.findByOrderId(orderId);
        if (existing.isPresent()) {
            ShipmentEntity shipment = existing.get();
            if (shipment.getStatus() == ShipmentStatus.CANCELLED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment has already been cancelled");
            }
            if (order.getStatus() != OrderStatus.SHIPMENT_CREATED
                    || !shipment.getShipmentId().equals(order.getShipmentId())) {
                throw new IllegalStateException("Order and shipment are inconsistent: " + orderId);
            }
            return shipment.getShipmentId();
        }
        if (order.getStatus() != OrderStatus.RESERVED || order.getShipmentId() != null) {
            throw new IllegalStateException("Shipment record missing for order: " + orderId);
        }
        var reservation = inventory.getReservation(orderId);
        if (!"RESERVED".equals(reservation.status())
                || !order.getSku().equals(reservation.sku()) || order.getQuantity() != reservation.quantity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order has no matching active inventory reservation");
        }

        String shipmentId = "shp-" + UUID.randomUUID().toString().replace("-", "");
        shipments.save(new ShipmentEntity(shipmentId, orderId, order.getShippingAddress()));
        order.setShipmentId(shipmentId);
        order.setStatus(OrderStatus.SHIPMENT_CREATED);
        order.setFailureReason(null);
        // Mock shipment and order are local database changes: either both commit or neither does.
        return shipmentId;
    }

    @Transactional(readOnly = true)
    public ShipmentEntity getByOrderId(String orderId) {
        return shipments.findByOrderId(orderId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found for order: " + orderId));
    }

    @Transactional
    public void beginCompensation(String orderId, String reason) {
        OrderEntity order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.COMPENSATING || order.getStatus() == OrderStatus.FAILED) {
            return;
        }
        if (order.getStatus() != OrderStatus.RESERVED && order.getStatus() != OrderStatus.SHIPMENT_CREATED) {
            throw new IllegalStateException("Cannot compensate order in state: " + order.getStatus());
        }
        order.setStatus(OrderStatus.COMPENSATING);
        order.setFailureReason(reason);
    }

    @Transactional
    public void compensateFailure(String orderId, String reason) {
        OrderEntity order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.FAILED) {
            return;
        }
        if (order.getStatus() != OrderStatus.COMPENSATING) {
            throw new IllegalStateException("Order must enter COMPENSATING first: " + orderId);
        }
        // Query by order ID even when the Activity never returned a shipment ID (lost response).
        shipments.findByOrderId(orderId).ifPresent(ShipmentEntity::cancel);
        inventory.release(orderId, order.getSku(), order.getQuantity());
        order.setStatus(OrderStatus.FAILED);
        order.setFailureReason(reason);
        // Failure rolls back cancellation, stock restoration and final status together.
    }

    private OrderEntity lockOrder(String orderId) {
        return orders.findByIdForUpdate(orderId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId));
    }
}
