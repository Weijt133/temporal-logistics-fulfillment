package com.llogistics.order_service.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {
    @Modifying
    @Query(value = """
            INSERT INTO orders (
                order_id,
                workflow_id,
                sku,
                quantity,
                shipping_address,
                status
            )
            VALUES (
                :orderId,
                :workflowId,
                :sku,
                :quantity,
                :shippingAddress,
                'CREATED'
            )
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("orderId") String orderId,
            @Param("workflowId") String workflowId,
            @Param("sku") String sku,
            @Param("quantity") int quantity,
            @Param("shippingAddress") String shippingAddress
    );
}
