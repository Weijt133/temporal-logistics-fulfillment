package com.llogistics.order_service;

import com.llogistics.order_service.activity.OrderFulfillmentActivities;
import com.llogistics.order_service.activity.OrderFulfillmentActivitiesImpl;
import com.llogistics.order_service.inventory.InventoryService;
import com.llogistics.order_service.order.OrderApplicationService;
import com.llogistics.order_service.order.OrderFulfillmentService;
import com.llogistics.order_service.order.OrderStatus;
import com.llogistics.order_service.outbox.OutboxDispatcher;
import com.llogistics.order_service.outbox.OutboxRepository;
import com.llogistics.order_service.workflow.OrderFulfillmentWorkflow;
import com.llogistics.order_service.workflow.OrderFulfillmentWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@Timeout(30)
class OrderFulfillmentIntegrationTests {
    // Real PostgreSQL semantics, isolated from the developer's public tables and stock.
    private static final String SCHEMA = "test_fulfillment_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> {
            String url = System.getProperty("test.database.url", "jdbc:postgresql://127.0.0.1:15432/logistics");
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        });
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired OrderApplicationService orders;
    @Autowired OrderFulfillmentService fulfillment;
    @Autowired OrderFulfillmentActivitiesImpl activities;
    @Autowired InventoryService inventory;
    @Autowired OutboxRepository outbox;
    @Autowired WorkflowClient workflowClient;
    @Autowired JdbcTemplate jdbc;

    private String orderId;
    private String sku;

    @BeforeEach
    void prepareStock() {
        orderId = "test-" + UUID.randomUUID();
        sku = "TEST-" + UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items(sku, available_quantity) VALUES (?, 5)", sku);
    }

    @AfterEach
    void removeOwnRows() {
        jdbc.update("DELETE FROM workflow_start_outbox WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM inventory_reservations WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM inventory_items WHERE sku = ?", sku);
    }

    @AfterAll
    static void removeTestSchema(@Autowired JdbcTemplate jdbc) {
        if (!SCHEMA.matches("test_fulfillment_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test schema");
        }
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void outboxStartsWorkflowAndReservesOrder() throws Exception {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        new OutboxDispatcher(outbox, workflowClient).dispatchPending();

        assertEquals("RESERVED", result());
        assertEquals(OrderStatus.RESERVED, orders.getOrder(orderId).getStatus());
        assertNull(orders.getOrder(orderId).getFailureReason());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
        assertEquals("RESERVED", inventory.getReservation(orderId).status());
        assertEquals("DISPATCHED", jdbc.queryForObject(
                "SELECT status FROM workflow_start_outbox WHERE order_id = ?", String.class, orderId));
    }

    @Test
    void repeatedActivityAndLateOutboxAcknowledgmentDoNotDeductOrRegressStatus() {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        assertEquals("RESERVED", fulfillment.reserveInventory(orderId));
        assertEquals("RESERVED", fulfillment.reserveInventory(orderId));
        // Simulate the Worker finishing before the dispatcher acknowledges the start.
        outbox.markDispatched(orderId);

        assertEquals(OrderStatus.RESERVED, orders.getOrder(orderId).getStatus());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
        assertEquals(1, reservationCount());
    }

    @Test
    void insufficientStockFailsOrderWithoutLeavingReservation() {
        orders.createOrder(orderId, sku, 6, "Sydney test address");
        new OutboxDispatcher(outbox, workflowClient).dispatchPending();

        assertThrows(WorkflowFailedException.class, this::result);
        assertEquals(OrderStatus.FAILED, orders.getOrder(orderId).getStatus());
        assertTrue(orders.getOrder(orderId).getFailureReason().contains("Insufficient stock"));
        assertEquals(5, inventory.getStock(sku).availableQuantity());
        assertEquals(0, reservationCount());
    }

    @Test
    void unknownSkuFailsOrderWithReason() {
        orders.createOrder(orderId, "MISSING-" + UUID.randomUUID(), 2, "Sydney test address");
        new OutboxDispatcher(outbox, workflowClient).dispatchPending();

        assertThrows(WorkflowFailedException.class, this::result);
        assertEquals(OrderStatus.FAILED, orders.getOrder(orderId).getStatus());
        assertTrue(orders.getOrder(orderId).getFailureReason().contains("SKU not found"));
        assertEquals(0, reservationCount());
    }

    @Test
    void orderUpdateFailureRollsBackStockAndReservationTogether() {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        // Force the final order write to fail after InventoryService has executed its SQL.
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT reject_reserved_for_test CHECK (status <> 'RESERVED')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> fulfillment.reserveInventory(orderId));
            assertEquals(OrderStatus.CREATED, orders.getOrder(orderId).getStatus());
            assertEquals(5, inventory.getStock(sku).availableQuantity());
            assertEquals(0, reservationCount());
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CONSTRAINT reject_reserved_for_test");
        }
    }

    @Test
    void temporalRetriesLostResponseAfterCommitWithoutDoubleDeduction() {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        AtomicInteger attempts = new AtomicInteger();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(OrderFulfillmentWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);
            worker.registerActivitiesImplementations(new OrderFulfillmentActivities() {
                @Override
                public String reserveOrderInventory(String id) {
                    String result = activities.reserveOrderInventory(id);
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("Simulated response lost after database commit");
                    }
                    return result;
                }

                @Override
                public void recordReservationFailure(String id, String reason) {
                    activities.recordReservationFailure(id, reason);
                }
            });
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(OrderFulfillmentWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("retry-" + orderId)
                            .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE).build());

            assertEquals("RESERVED", workflow.process(orderId));
        }
        assertEquals(2, attempts.get());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
        assertEquals(1, reservationCount());
        assertEquals(OrderStatus.RESERVED, orders.getOrder(orderId).getStatus());
    }

    private String result() throws Exception {
        return workflowClient.newUntypedWorkflowStub("order-" + orderId)
                .getResult(10, TimeUnit.SECONDS, String.class);
    }

    private int reservationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_reservations WHERE order_id = ?",
                Integer.class, orderId);
    }
}
