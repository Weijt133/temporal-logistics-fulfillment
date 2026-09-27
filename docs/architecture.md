# Architecture and consistency

[Project overview](../README.md) · [API](api.md) · [Tests](testing.md)

## Boundaries

This is a modular monolith: one Spring Boot process contains the REST API, outbox dispatcher, Temporal workers, and order/inventory/shipment services. All business modules share PostgreSQL. React is a separate frontend build served by Vite in development and Nginx in the container deployment.

Temporal is a separate orchestration service. The worker polls its task queues over gRPC. The local and optional AWS demo stacks use Temporal's development server with a persistent SQLite file, not a production Temporal cluster.

| Package | Responsibility | Main implementation |
| --- | --- | --- |
| `api` | HTTP contracts, validation, responses, execution inspection | `OrderController`, `InventoryController`, `ShipmentController`, `OrderExecutionController` |
| `order` | Order creation, reads, state changes, transaction boundaries | `OrderApplicationService`, `OrderFulfillmentService`, `OrderEntity` |
| `outbox` | Durable intent to start a workflow and delayed retries | `OutboxRepository`, `OutboxDispatcher` |
| `workflow` | Deterministic orchestration and execution/history inspection | `OrderFulfillmentWorkflowImpl`, `WorkflowInspectionService` |
| `activity` | Retryable side effects and business-failure translation | `OrderFulfillmentActivitiesImpl`, `ShipmentActivitiesImpl` |
| `inventory` | Stock and reservation invariants | `InventoryService`, `InventoryRepository` |
| `shipment` | Mock carrier record, order update, compensation | `ShipmentService`, `ShipmentEntity` |

Packages live under [the backend source root](../order-service/src/main/java/com/llogistics/order_service). A package name does not establish a microservice boundary: these modules are neither independently deployed nor accessed through network calls by the workflow.

## From HTTP request to shipment

```mermaid
sequenceDiagram
    actor User
    participant API as Order API
    participant DB as PostgreSQL
    participant Outbox as Outbox dispatcher
    participant T as Temporal
    participant W as Worker / Activities
    User->>API: POST /orders
    API->>DB: Transaction: insert order + PENDING outbox
    DB-->>API: Commit
    API-->>User: 202 Accepted + IDs
    Outbox->>DB: Read due PENDING entries
    Outbox->>T: Start workflow with order-{orderId}
    T-->>Outbox: Started or already started
    Outbox->>DB: Mark DISPATCHED; advance CREATED only
    W->>T: Poll workflow and Activity tasks
    T-->>W: Execute fulfillment
    W->>DB: Transaction: reserve stock + mark RESERVED
    W->>DB: Transaction: create shipment + mark SHIPMENT_CREATED
    W->>T: Report Activity results and workflow completion
    User->>API: GET order and execution
    API->>DB: Read business state
    API->>T: Read execution/history when requested
    API-->>User: Business state and workflow state
```

Worker progress and outbox acknowledgment can race. The dispatcher only changes an order from `CREATED` to `IN_PROGRESS`; it cannot move `RESERVED` or `SHIPMENT_CREATED` backwards. The diagram shows the normal sequence, not a global transaction across PostgreSQL and Temporal.

## Outbox and workflow identity

Order creation inserts the order and its outbox row in one PostgreSQL transaction. Either both commit or neither does. The API does not need to contact Temporal before returning `202`.

The dispatcher runs with a three-second fixed delay and reads at most ten due entries. Failed starts remain `PENDING`, record an error and attempt count, and become eligible again after ten seconds. A successful start or `WorkflowExecutionAlreadyStarted` marks the row `DISPATCHED`.

Workflow IDs are `order-` plus the client-provided order ID, with `REJECT_DUPLICATE` reuse policy. If Temporal accepted a start but the acknowledgment was lost, the next dispatch can recognize the existing workflow. PostgreSQL also enforces unique order IDs and workflow IDs.

This is retry-based, at-least-once dispatch with deduplication and idempotent effects. It is not an end-to-end exactly-once transport guarantee. Temporal history retention also matters when reasoning about old workflow ID reuse. The current publisher has no claim/lease protocol or multi-instance load test; run the documented single backend instance.

`DISPATCHED` is terminal for the outbox row. It means workflow startup was acknowledged, not that shipment creation succeeded. The outbox attempt count and shipment Activity attempt count describe different operations.

## Workflow and Activity responsibilities

New orders use `OrderFulfillmentWorkflow` on `order-fulfillment-v2`. The older `OrderWorkflow` and `order-fulfillment` queue remain for the original demonstration and compatibility tests.

The fulfillment workflow schedules Activities and makes deterministic decisions. PostgreSQL access, UUID generation for shipment IDs, and other side effects occur in services called by Activities. Database access is not performed inside workflow code.

| Activity group | Start-to-close timeout | Retry configuration |
| --- | --- | --- |
| Inventory/reservation result | 15 seconds | Initial interval 1 second, maximum interval 30 seconds; no explicit attempt cap |
| Create shipment | 15 seconds | Initial interval 1 second, maximum interval 5 seconds, maximum 3 attempts |
| Compensation | 15 seconds | Initial interval 1 second, maximum interval 30 seconds; no explicit attempt cap |

Business rejections become non-retryable ApplicationFailures with types `InventoryReservationRejected` and `ShipmentRejected` (the `INVENTORY_REJECTED` and `SHIPMENT_REJECTED` constants). Infrastructure-like failures are retryable. An inventory business rejection records order `FAILED` and fails the workflow without creating a shipment.

For shipment failures, the workflow first records `COMPENSATING`, then invokes the compensation Activity, then rethrows the original failure. The workflow therefore ends `FAILED` even when compensation succeeds. Persistent compensation failures can keep the workflow running and the order `COMPENSATING`; retry capability is not a guarantee that an unavailable dependency will recover.

The workflow uses `Workflow.getVersion("add-shipment-v1", Workflow.DEFAULT_VERSION, 1)` to preserve the earlier inventory-only command sequence. Recorded success and failure histories are replay-tested. Preserve compatible branches while those histories may still need replay; passing two fixtures does not prove every future change safe.

## Transactions and idempotency

| Operation | Atomic business changes | Duplicate/conflict behavior |
| --- | --- | --- |
| Accept order | Insert order and workflow-start outbox | Same order ID returns HTTP 409 |
| Reserve for fulfillment | Lock order, reserve inventory, update order to RESERVED | Repeated reserved order returns its state without another deduction |
| Inventory reserve | Lock reservation, guarded stock deduction, set RESERVED | Same payload returns original reservation; changed payload or RELEASED reservation returns 409 |
| Inventory release | Lock reservation, restore stock if previously RESERVED, set RELEASED | Repeated release does not add stock again |
| Create shipment | Lock order, validate reservation, create shipment and update order | Existing valid shipment returns the original ID; unique order constraint prevents a second shipment |
| Begin compensation | Lock order, persist COMPENSATING and reason | Repeated calls are harmless for COMPENSATING/FAILED orders |
| Apply compensation | Cancel existing shipment, release inventory, mark FAILED | One PostgreSQL transaction; repeated compensation cannot restore stock twice |

Inventory deduction uses a conditional SQL update that requires sufficient available quantity. The reservation is locked with `SELECT ... FOR UPDATE`; order operations use a pessimistic order lock. These locks, SQL guards, constraints, and transactions protect different invariants and are not interchangeable.

Release-before-reserve records a `RELEASED` reservation without increasing stock, preventing a late reserve from deducting it. Reservation IDs also support the independent inventory lab, so `inventory_reservations.order_id` is not a foreign key to `orders`.

The shipment implementation is a mock carrier inside the same database. Its compensating actions can share one local transaction. With a real carrier or separate inventory service, that transaction boundary would disappear: remote idempotency keys, service-owned records, and independently retryable compensation would be needed.

## Ambiguous outcome scenario

1. The shipment service commits a shipment and updates the order.
2. Demo fault injection throws after that transaction returns, simulating a lost response.
3. Temporal retries. The service finds the same shipment and returns the same ID.
4. In the `ambiguous` scenario, the response is lost on every attempt, exhausting the three-attempt policy.
5. Compensation looks up the shipment by order ID, even though the workflow never received a successful shipment result.
6. The transaction cancels that shipment, releases stock, and records order `FAILED`.

This demonstrates a business side effect surviving a failed acknowledgment. It is simulated by an exception after commit, not by a real carrier network outage. The tests additionally cover a single lost response that later recovers successfully.

## State and storage

| State source | Meaning |
| --- | --- |
| Order | Business progress: CREATED, IN_PROGRESS, RESERVED, SHIPMENT_CREATED, COMPENSATING, FAILED |
| Outbox | Workflow-start intent: PENDING or DISPATCHED |
| Reservation | PENDING, RESERVED, RELEASED |
| Shipment | CREATED or CANCELLED |
| Temporal execution | Engine lifecycle such as RUNNING, COMPLETED, FAILED |

`COMPLETED` exists in the order enum but is not set by the current shipment workflow. Successful fulfillment currently stops at order `SHIPMENT_CREATED`. A temporarily observed shipment-created state can still be followed by compensation after an ambiguous response.

| Migration | Tables or change |
| --- | --- |
| [V1](../order-service/src/main/resources/db/migration/V1__create_orders.sql) | Orders and unique workflow IDs |
| [V2](../order-service/src/main/resources/db/migration/V2__create_workflow_start_outbox.sql) | Workflow-start outbox, retry metadata, pending index |
| [V3](../order-service/src/main/resources/db/migration/V3__create_inventory.sql) | Inventory items, reservations, initial demo stock |
| [V4](../order-service/src/main/resources/db/migration/V4__create_shipments.sql) | Shipments with one shipment per order |

Flyway executes migrations at application startup; Java code does not call each SQL file directly. JPA uses `ddl-auto=validate`. Keep applied migrations unchanged and add new versioned migrations for schema evolution.

PostgreSQL business records live in the Compose `postgres-data` volume. Temporal history lives at `/data/temporal.db` inside the Temporal container, backed by the separate `temporal-data` volume. These paths are Docker-managed storage, not files under the checkout on Windows. `docker compose down` preserves volumes; `down -v` removes them.

## Inspection and frontend

The console fetches the business API through `/api`. Its polling avoids overlapping requests, aborts stale requests, and keeps checking execution after `SHIPMENT_CREATED` because compensation may still follow.

`WorkflowInspectionService` describes the execution, pins the returned run ID, and reads paginated history with one four-second deadline. It also reads pending Activity attempts because intermediate retries may not appear as separate history events. The summary caps history reads at twenty pages of up to 1,000 events; larger histories require Temporal UI inspection.

Missing history returns `NOT_FOUND`; unavailable inspection returns `UNAVAILABLE`. Neither is treated as workflow success. The console reports persisted business state separately from this execution view.

## Deployment boundaries

The development stack exposes only loopback ports. The optional AWS configuration runs on one EC2 instance, uses a shared HTTPS gateway login, and keeps PostgreSQL and Temporal gRPC private to Docker. See [deployment operations](../deploy/README.md).

Application authorization, per-user isolation, load testing, multi-instance dispatch, operational alerts, scheduled backups, production Temporal, and high availability are not implemented. In particular, direct inventory mutation endpoints are not restricted by the Spring demo profile; the console's demo controls are a UI feature, not access control.
