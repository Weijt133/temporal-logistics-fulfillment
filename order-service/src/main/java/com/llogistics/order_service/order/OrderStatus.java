package com.llogistics.order_service.order;

public enum OrderStatus {
    CREATED,          // ORDER CREATED
    IN_PROGRESS,      // ORDER IN PROGRESS
    RESERVED,         // STOCK RESERVED
    SHIPMENT_CREATED,
    COMPLETED,        // ORDER COMPLETED
    COMPENSATING,
    FAILED
}