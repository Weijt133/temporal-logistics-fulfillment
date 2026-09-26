package com.llogistics.order_service.api;

import com.llogistics.order_service.shipment.ShipmentEntity;
import com.llogistics.order_service.shipment.ShipmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shipments")
public class ShipmentController {
    private final ShipmentService shipments;

    public ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @GetMapping("/by-order/{orderId}")
    public ShipmentEntity getByOrderId(@PathVariable("orderId") String orderId) {
        return shipments.getByOrderId(orderId);
    }
}
