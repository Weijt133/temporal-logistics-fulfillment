package com.llogistics.order_service;

import com.llogistics.order_service.activity.OrderFulfillmentActivities;
import com.llogistics.order_service.activity.OrderFulfillmentActivitiesImpl;
import com.llogistics.order_service.activity.ShipmentActivities;
import com.llogistics.order_service.activity.ShipmentActivitiesImpl;
import com.llogistics.order_service.inventory.InventoryService;
import com.llogistics.order_service.order.OrderApplicationService;
import com.llogistics.order_service.order.OrderFulfillmentService;
import com.llogistics.order_service.order.OrderStatus;
import com.llogistics.order_service.shipment.ShipmentService;
import com.llogistics.order_service.shipment.ShipmentStatus;
import com.llogistics.order_service.outbox.OutboxDispatcher;
import com.llogistics.order_service.outbox.OutboxRepository;
import com.llogistics.order_service.workflow.OrderFulfillmentWorkflow;
import com.llogistics.order_service.workflow.OrderFulfillmentWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("demo")
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
    @Autowired ShipmentActivitiesImpl shipmentActivities;
    @Autowired ShipmentService shipments;
    @Autowired InventoryService inventory;
    @Autowired OutboxRepository outbox;
    @Autowired WorkflowClient workflowClient;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.llogistics.order_service.api.OrderController orderController;
    @Autowired com.llogistics.order_service.api.OrderExecutionController executionController;
    @Autowired com.llogistics.order_service.api.AppConfigController configController;

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
        jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId);
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
    void outboxStartsWorkflowAndCreatesShipment() throws Exception {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        new OutboxDispatcher(outbox, workflowClient).dispatchPending();

        assertEquals("SHIPMENT_CREATED", result());
        assertEquals(OrderStatus.SHIPMENT_CREATED, orders.getOrder(orderId).getStatus());
        assertEquals(ShipmentStatus.CREATED, shipments.getByOrderId(orderId).getStatus());
        assertEquals(shipments.getByOrderId(orderId).getShipmentId(), orders.getOrder(orderId).getShipmentId());
        assertNull(orders.getOrder(orderId).getFailureReason());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
        assertEquals("RESERVED", inventory.getReservation(orderId).status());
        assertEquals("DISPATCHED", jdbc.queryForObject(
                "SELECT status FROM workflow_start_outbox WHERE order_id = ?", String.class, orderId));
        WorkflowReplayer.replayWorkflowExecution(workflowClient.fetchHistory("order-" + orderId),
                OrderFulfillmentWorkflowImpl.class);
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
            worker.registerActivitiesImplementations(shipmentActivities);
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

            assertEquals("SHIPMENT_CREATED", workflow.process(orderId));
        }
        assertEquals(2, attempts.get());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
        assertEquals(1, reservationCount());
        assertEquals(OrderStatus.SHIPMENT_CREATED, orders.getOrder(orderId).getStatus());
    }

    @Test
    void concurrentShipmentRequestsReturnOneIdAndLateOutboxDoesNotRegressStatus() throws Exception {
        reserveOrder();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                jobs.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test barrier timed out"); }
                    return shipments.createForOrder(orderId);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var ids = new HashSet<String>();
            for (var job : jobs) { ids.add(job.get(10, TimeUnit.SECONDS)); }
            assertEquals(1, ids.size());
        }
        outbox.markDispatched(orderId);
        assertEquals(1, shipmentCount());
        assertEquals(OrderStatus.SHIPMENT_CREATED, orders.getOrder(orderId).getStatus());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
    }

    @Test
    void shipmentAndOrderUpdateRollBackTogether() {
        reserveOrder();
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT reject_shipment_for_test CHECK (status <> 'SHIPMENT_CREATED')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> shipments.createForOrder(orderId));
            assertEquals(0, shipmentCount());
            assertNull(orders.getOrder(orderId).getShipmentId());
            assertEquals(OrderStatus.RESERVED, orders.getOrder(orderId).getStatus());
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CONSTRAINT reject_shipment_for_test");
        }
    }

    @Test
    void transientCarrierFailureRetriesTwiceThenCreatesOneShipment() throws Exception {
        orderId = "demo-retry-" + UUID.randomUUID();
        startOrder();
        assertEquals("SHIPMENT_CREATED", result());
        assertEquals(3, maximumActivityAttempt());
        assertEquals(1, shipmentCount());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
    }

    @Test
    void permanentRejectionCompensatesWithoutCreatingShipment() {
        orderId = "demo-reject-" + UUID.randomUUID();
        startOrder();
        assertThrows(WorkflowFailedException.class, this::result);
        assertEquals(1, maximumActivityAttempt());
        assertEquals(OrderStatus.FAILED, orders.getOrder(orderId).getStatus());
        assertTrue(orders.getOrder(orderId).getFailureReason().contains("carrier rejection"));
        assertEquals("RELEASED", inventory.getReservation(orderId).status());
        assertEquals(5, inventory.getStock(sku).availableQuantity());
        assertEquals(0, shipmentCount());
    }

    @Test
    void exhaustedLostResponsesCancelCommittedShipmentAndRestoreStock() throws Exception {
        orderId = "demo-ambiguous-" + UUID.randomUUID();
        startOrder();
        assertThrows(WorkflowFailedException.class, this::result);
        assertEquals(3, maximumActivityAttempt());
        assertEquals(1, shipmentCount());
        assertEquals(ShipmentStatus.CANCELLED, shipments.getByOrderId(orderId).getStatus());
        assertEquals(OrderStatus.FAILED, orders.getOrder(orderId).getStatus());
        assertEquals("RELEASED", inventory.getReservation(orderId).status());
        assertEquals(5, inventory.getStock(sku).availableQuantity());
        WorkflowReplayer.replayWorkflowExecution(workflowClient.fetchHistory("order-" + orderId),
                OrderFulfillmentWorkflowImpl.class);
    }

    @Test
    void repeatedCompensationAndLateCreationCannotRestoreOrDeductStockAgain() {
        reserveOrder();
        shipments.createForOrder(orderId);
        shipments.beginCompensation(orderId, "Test failure");
        assertThrows(ResponseStatusException.class, () -> shipments.createForOrder(orderId));
        shipments.compensateFailure(orderId, "Test failure");
        shipments.beginCompensation(orderId, "Test failure");
        shipments.compensateFailure(orderId, "Test failure");
        assertThrows(ResponseStatusException.class, () -> shipments.createForOrder(orderId));
        assertEquals(5, inventory.getStock(sku).availableQuantity());
        assertEquals(ShipmentStatus.CANCELLED, shipments.getByOrderId(orderId).getStatus());
        assertEquals(1, shipmentCount());
    }

    @Test
    void failedCompensationRollsBackAndCanResume() {
        reserveOrder();
        shipments.createForOrder(orderId);
        shipments.beginCompensation(orderId, "Test failure");
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT reject_failed_for_test CHECK (status <> 'FAILED')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> shipments.compensateFailure(orderId, "Test failure"));
            assertEquals(OrderStatus.COMPENSATING, orders.getOrder(orderId).getStatus());
            assertEquals(ShipmentStatus.CREATED, shipments.getByOrderId(orderId).getStatus());
            assertEquals("RESERVED", inventory.getReservation(orderId).status());
            assertEquals(3, inventory.getStock(sku).availableQuantity());
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CONSTRAINT reject_failed_for_test");
        }
        shipments.compensateFailure(orderId, "Test failure");
        assertEquals(OrderStatus.FAILED, orders.getOrder(orderId).getStatus());
        assertEquals(5, inventory.getStock(sku).availableQuantity());
        assertEquals(ShipmentStatus.CANCELLED, shipments.getByOrderId(orderId).getStatus());
    }

    @Test
    void faultSimulationIsDisabledOutsideDemoProfile() {
        orderId = "demo-reject-" + UUID.randomUUID();
        reserveOrder();
        var normalActivities = new ShipmentActivitiesImpl(shipments, new MockEnvironment());
        assertNotNull(normalActivities.createOrderShipment(orderId));
        assertEquals(OrderStatus.SHIPMENT_CREATED, orders.getOrder(orderId).getStatus());
    }

    @Test
    void compensationActivityRetriesBeforeWorkflowReportsFailure() {
        orderId = "demo-reject-" + UUID.randomUUID();
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        AtomicInteger attempts = new AtomicInteger();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(OrderFulfillmentWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities, new ShipmentActivities() {
                @Override public String createOrderShipment(String id) {
                    return shipmentActivities.createOrderShipment(id);
                }
                @Override public void beginShipmentCompensation(String id, String reason) {
                    shipmentActivities.beginShipmentCompensation(id, reason);
                }
                @Override public void compensateShipmentFailure(String id, String reason) {
                    if (attempts.incrementAndGet() == 1) {
                        assertEquals(OrderStatus.COMPENSATING, orders.getOrder(id).getStatus());
                        throw new IllegalStateException("Simulated temporary compensation outage");
                    }
                    shipmentActivities.compensateShipmentFailure(id, reason);
                }
            });
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(OrderFulfillmentWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("compensation-" + orderId)
                            .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE).build());
            assertThrows(WorkflowFailedException.class, () -> workflow.process(orderId));
        }
        assertEquals(2, attempts.get());
        assertEquals(OrderStatus.FAILED, orders.getOrder(orderId).getStatus());
        assertEquals(5, inventory.getStock(sku).availableQuantity());
    }

    @Test
    void shipmentResponseLostOnceRecoversWithOriginalShipment() {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        AtomicInteger attempts = new AtomicInteger();
        var ids = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(OrderFulfillmentWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities, new ShipmentActivities() {
                @Override public String createOrderShipment(String id) {
                    String shipmentId = shipmentActivities.createOrderShipment(id);
                    ids.add(shipmentId);
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("Simulated response lost after commit");
                    }
                    return shipmentId;
                }
                @Override public void beginShipmentCompensation(String id, String reason) {
                    shipmentActivities.beginShipmentCompensation(id, reason);
                }
                @Override public void compensateShipmentFailure(String id, String reason) {
                    shipmentActivities.compensateShipmentFailure(id, reason);
                }
            });
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(OrderFulfillmentWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("shipment-retry-" + orderId)
                            .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE).build());
            assertEquals("SHIPMENT_CREATED", workflow.process(orderId));
        }
        assertEquals(2, attempts.get());
        assertEquals(1, ids.size());
        assertEquals(1, shipmentCount());
        assertEquals(3, inventory.getStock(sku).availableQuantity());
    }

    private void reserveOrder() {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        fulfillment.reserveInventory(orderId);
    }

    private org.springframework.test.web.servlet.MockMvc console() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(orderController, executionController, configController)
                .setControllerAdvice(new com.llogistics.order_service.api.ApiExceptionHandler()).build();
    }

    @Test
    void consoleListsOrdersAndReadsRealCompletedExecution() throws Exception {
        startOrder();
        assertEquals("SHIPMENT_CREATED", result());
        var mvc = console();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders")
                        .param("status", "SHIPMENT_CREATED").param("size", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].orderId").value(orderId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalElements").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/{id}/execution", orderId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.outbox.status").value("DISPATCHED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.status").value("COMPLETED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.shipmentAttempts").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.activities[1].name").value("CreateOrderShipment"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/app-config"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.demoEnabled").value(true));
    }

    @Test
    void consoleReportsThreeActualShipmentAttempts() throws Exception {
        orderId = "demo-retry-" + UUID.randomUUID();
        startOrder();
        assertEquals("SHIPMENT_CREATED", result());
        console().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/{id}/execution", orderId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.shipmentAttempts").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.activities[1].status").value("COMPLETED"));
    }

    @Test
    void consoleReportsCompensationAndFailedWorkflowSeparately() throws Exception {
        orderId = "demo-ambiguous-" + UUID.randomUUID();
        startOrder();
        assertThrows(WorkflowFailedException.class, this::result);
        console().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/{id}/execution", orderId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.status").value("FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.shipmentAttempts").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.activities[3].name").value("CompensateShipmentFailure"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.activities[3].status").value("COMPLETED"));
        assertEquals("RELEASED", inventory.getReservation(orderId).status());
    }

    @Test
    void consoleHandlesPendingMissingAndInvalidQueries() throws Exception {
        orders.createOrder(orderId, sku, 2, "Console test address");
        var mvc = console();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/{id}/execution", orderId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.outbox.status").value("PENDING"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflow.status").value("NOT_FOUND"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/{id}/execution", "missing-order"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders").param("size", "101"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders").param("page", "-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void consoleReturnsEnglishValidationAndDuplicateErrors() throws Exception {
        var mvc = console();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders")
                        .contentType("application/json").content("""
                        {"orderId":"invalid input!","sku":"SKU-001","quantity":0,"shippingAddress":"Sydney"}
                        """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail").isNotEmpty());
        orders.createOrder(orderId, sku, 2, "Console test address");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders")
                        .contentType("application/json").content("""
                        {"orderId":"%s","sku":"%s","quantity":2,"shippingAddress":"Sydney"}
                        """.formatted(orderId, sku)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail").value("Order already exists: " + orderId));
    }

    private void startOrder() {
        orders.createOrder(orderId, sku, 2, "Sydney test address");
        new OutboxDispatcher(outbox, workflowClient).dispatchPending();
    }

    private int shipmentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
    }

    private int maximumActivityAttempt() {
        return workflowClient.fetchHistory("order-" + orderId).getEvents().stream()
                .filter(event -> event.hasActivityTaskStartedEventAttributes())
                .mapToInt(event -> event.getActivityTaskStartedEventAttributes().getAttempt()).max().orElse(0);
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
