package com.llogistics.order_service.inventory;

import com.llogistics.order_service.inventory.InventoryRepository.Reservation;
import com.llogistics.order_service.inventory.InventoryRepository.Stock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public Reservation reserve(
            String orderId,
            String sku,
            int quantity) {

        Reservation reservation = lockAndCheckRequest(
                orderId, sku, quantity
        );

        // 同一个订单已经预留成功，直接返回原结果
        if ("RESERVED".equals(reservation.status())) {
            return reservation;
        }

        // 已释放的订单不能被迟到的请求重新预留
        if ("RELEASED".equals(reservation.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Reservation has already been released"
            );
        }

        int updatedRows = inventoryRepository.decreaseStock(
                sku, quantity
        );

        if (updatedRows == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Insufficient stock for SKU: " + sku
            );
        }

        inventoryRepository.updateReservationStatus(
                orderId, "RESERVED"
        );

        return new Reservation(
                orderId, sku, quantity, "RESERVED"
        );
    }

    @Transactional
    public Reservation release(
            String orderId,
            String sku,
            int quantity) {

        Reservation reservation = lockAndCheckRequest(
                orderId, sku, quantity
        );

        // 已释放过，直接返回，不能再次增加库存
        if ("RELEASED".equals(reservation.status())) {
            return reservation;
        }

        // 只有真正扣过库存，才需要加回
        if ("RESERVED".equals(reservation.status())) {
            int updatedRows = inventoryRepository.increaseStock(
                    sku, quantity
            );

            if (updatedRows != 1) {
                throw new IllegalStateException(
                        "Inventory item disappeared: " + sku
                );
            }
        }

        // PENDING 状态可以直接转为 RELEASED，不增加库存
        inventoryRepository.updateReservationStatus(
                orderId, "RELEASED"
        );

        return new Reservation(
                orderId, sku, quantity, "RELEASED"
        );
    }

    @Transactional(readOnly = true)
    public Stock getStock(String sku) {
        return inventoryRepository.findStock(sku)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "SKU not found: " + sku
                ));
    }

    @Transactional(readOnly = true)
    public Reservation getReservation(String orderId) {
        return inventoryRepository.findReservation(orderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Reservation not found: " + orderId
                ));
    }

    private Reservation lockAndCheckRequest(
            String orderId,
            String sku,
            int quantity) {

        if (quantity <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Quantity must be positive"
            );
        }

        if (inventoryRepository.findStock(sku).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "SKU not found: " + sku
            );
        }

        inventoryRepository.createPending(orderId, sku, quantity);

        Reservation reservation =
                inventoryRepository.lockReservation(orderId);

        if (!reservation.sku().equals(sku)
                || reservation.quantity() != quantity) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Order ID was already used with a different SKU or quantity"
            );
        }

        return reservation;
    }
}