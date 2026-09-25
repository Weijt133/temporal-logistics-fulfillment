package com.llogistics.order_service.outbox;

import com.llogistics.order_service.order.OrderRepository;
import io.temporal.api.enums.v1.PendingActivityState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;
    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String orderId) {
        jdbcTemplate.update("""
            INSERT INTO workflow_start_outbox (order_id)
            VALUES (?)
        """, orderId);
    }
    public List<PendingStart> findReady() {
        return jdbcTemplate.query("""
                SELECT task.order_id, orders.workflow_id
                FROM workflow_start_outbox task
                JOIN orders ON orders.order_id = task.order_id
                WHERE task.status = 'PENDING'
                  AND task.next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY task.next_attempt_at, task.created_at, task.order_id
                LIMIT 10
                """,
                (rs, rowNum) -> new PendingStart(
                        rs.getString("order_id"),
                        rs.getString("workflow_id")
                )
        );
    }

    public void markDispatched(String orderId) {
        jdbcTemplate.update("""
                WITH dispatched AS (
                    UPDATE workflow_start_outbox
                    SET status = 'DISPATCHED',
                        attempts = attempts + 1,
                        dispatched_at = CURRENT_TIMESTAMP,
                        last_error = NULL
                    WHERE order_id = ?
                      AND status = 'PENDING'
                    RETURNING order_id
                )
                UPDATE orders
                SET status = 'IN_PROGRESS',
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id IN (SELECT order_id FROM dispatched)
                  AND status = 'CREATED'
                """, orderId);
    }

    public void retryLater(String orderId, String error) {
        jdbcTemplate.update("""
                UPDATE workflow_start_outbox
                SET attempts = attempts + 1,
                    last_error = ?,
                    next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '10 seconds'
                WHERE order_id = ?
                  AND status = 'PENDING'
                """, error, orderId);
    }

    public record PendingStart(String orderId, String workflowId) {
    }
}
