package com.llogistics.order_service.api;

import com.llogistics.order_service.inventory.InventoryRepository.Reservation;
import com.llogistics.order_service.inventory.InventoryRepository.Stock;
import com.llogistics.order_service.inventory.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/items/{sku}")
    public Stock getStock(@PathVariable("sku") String sku) {
        return inventoryService.getStock(sku);
    }

    @GetMapping("/reservations/{orderId}")
    public Reservation getReservation(
            @PathVariable("orderId") String orderId) {
        return inventoryService.getReservation(orderId);
    }

    @PostMapping("/reservations")
    public Reservation reserve(
            @Valid @RequestBody ReservationRequest request) {
        return inventoryService.reserve(
                request.orderId(),
                request.sku(),
                request.quantity()
        );
    }

    @PostMapping("/reservations/release")
    public Reservation release(
            @Valid @RequestBody ReservationRequest request) {
        return inventoryService.release(
                request.orderId(),
                request.sku(),
                request.quantity()
        );
    }

    public record ReservationRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9-]{1,64}")
            String orderId,

            @NotBlank
            @Size(max = 64)
            String sku,

            @NotNull
            @Min(1)
            Integer quantity
    ) {
    }
}