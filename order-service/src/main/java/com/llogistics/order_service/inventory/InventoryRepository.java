package com.llogistics.order_service.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class InventoryRepository {

    private static final RowMapper<Reservation> RESERVATION_MAPPER =
            (rs, rowNum) -> new Reservation(
                    rs.getString("order_id"),
                    rs.getString("sku"),
                    rs.getInt("quantity"),
                    rs.getString("status")
            );

    private final JdbcTemplate jdbcTemplate;

    public InventoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Stock> findStock(String sku) {
        return jdbcTemplate.query("""
                SELECT sku, available_quantity
                FROM inventory_items
                WHERE sku = ?
                """,
                (rs, rowNum) -> new Stock(
                        rs.getString("sku"),
                        rs.getInt("available_quantity")
                ),
                sku
        ).stream().findFirst();
    }

    public void createPending(
            String orderId,
            String sku,
            int quantity) {

        jdbcTemplate.update("""
                INSERT INTO inventory_reservations (
                    order_id, sku, quantity, status
                )
                VALUES (?, ?, ?, 'PENDING')
                ON CONFLICT (order_id) DO NOTHING
                """, orderId, sku, quantity);
    }

    public Reservation lockReservation(String orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT order_id, sku, quantity, status
                FROM inventory_reservations
                WHERE order_id = ?
                FOR UPDATE
                """, RESERVATION_MAPPER, orderId);
    }

    public Optional<Reservation> findReservation(String orderId) {
        return jdbcTemplate.query("""
                SELECT order_id, sku, quantity, status
                FROM inventory_reservations
                WHERE order_id = ?
                """, RESERVATION_MAPPER, orderId
        ).stream().findFirst();
    }

    public int decreaseStock(String sku, int quantity) {
        return jdbcTemplate.update("""
                UPDATE inventory_items
                SET available_quantity = available_quantity - ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE sku = ?
                  AND available_quantity >= ?
                """, quantity, sku, quantity);
    }

    public int increaseStock(String sku, int quantity) {
        return jdbcTemplate.update("""
                UPDATE inventory_items
                SET available_quantity = available_quantity + ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE sku = ?
                """, quantity, sku);
    }

    public void updateReservationStatus(
            String orderId,
            String status) {

        jdbcTemplate.update("""
                UPDATE inventory_reservations
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                """, status, orderId);
    }

    public record Stock(
            String sku,
            int availableQuantity) {
    }

    public record Reservation(
            String orderId,
            String sku,
            int quantity,
            String status) {
    }
}