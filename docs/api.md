# API reference

[Project overview](../README.md) · [Architecture](architecture.md) · [Demo guide](demo-guide.md)

## Base URL and conventions

| Runtime | Base URL |
| --- | --- |
| Direct development backend | `http://localhost:8080` |
| Vite development proxy | `http://localhost:5137/api` |
| Local container demo | `http://127.0.0.1:18080/api` |
| Configured AWS gateway | `https://YOUR_REAL_DEMO_DOMAIN/api` |

Paths below are relative to the base URL. The proxy removes `/api` before forwarding. JSON requests use `Content-Type: application/json`. Timestamps are UTC ISO-8601 strings. IDs and timestamps in examples are illustrative, not records guaranteed to exist.

The application has no built-in user authentication or role authorization. The optional public deployment adds shared HTTP Basic authentication at Caddy. Do not treat demo mode as access control. Inventory mutation endpoints are available on the backend even when demo controls are hidden.

## Orders

### POST /orders

Create an order and workflow-start outbox entry atomically.

```json
{
  "orderId": "example-order-001",
  "sku": "SKU-001",
  "quantity": 2,
  "shippingAddress": "Sydney demo address"
}
```

| Field | Constraint |
| --- | --- |
| `orderId` | Required; 1-64 ASCII letters, digits, or hyphens; unique |
| `sku` | Required, nonblank, at most 64 characters |
| `quantity` | Required integer, at least 1 |
| `shippingAddress` | Required, nonblank, at most 500 characters |

Success: `202 Accepted`, with `Location: /orders/example-order-001` and body:

```json
{
  "orderId": "example-order-001",
  "workflowId": "order-example-order-001",
  "status": "CREATED"
}
```

`202` means the database transaction committed and startup was scheduled. It does not guarantee that stock exists or that Temporal has already started. Stock validation happens asynchronously in fulfillment. Unknown SKU or insufficient stock can therefore produce an accepted order that later becomes `FAILED`.

Duplicate order IDs return `409`, including when the payload is identical. This differs from the inventory/shipment services' idempotent repeated effects. Invalid request fields return `400`.

The `Location` header is a backend-relative path and is not rewritten by the proxy. A client using the `/api` base must apply that prefix when following it.

### GET /orders/{orderId}

Returns `200` with a persisted order, or `404` if absent. Example after success:

```json
{
  "orderId": "example-order-001",
  "workflowId": "order-example-order-001",
  "sku": "SKU-001",
  "quantity": 2,
  "shippingAddress": "Sydney demo address",
  "status": "SHIPMENT_CREATED",
  "shipmentId": "shp-0123456789abcdef0123456789abcdef",
  "failureReason": null,
  "createdAt": "2026-09-27T00:00:00Z",
  "updatedAt": "2026-09-27T00:00:03Z"
}
```

The order's shipment ID can remain populated after compensation so the cancelled shipment can be inspected. `SHIPMENT_CREATED` does not mean delivered. `COMPLETED` is an enum value but is not emitted as an order state by the current fulfillment flow.

### GET /orders?page=0&size=10&status=FAILED

`page` defaults to 0 and must be nonnegative. `size` defaults to 10 and must be 1-100. `status` is optional and uses the exact uppercase order enum value. Results are ordered by creation time descending, then order ID descending.

```json
{
  "items": [],
  "page": 0,
  "size": 10,
  "totalElements": 0,
  "totalPages": 0
}
```

Each item has the order shape above. An empty page is a successful result, not `404`. Invalid pagination or status values return `400`.

### GET /orders/{orderId}/execution

Returns `404` for an unknown order. For a known order, returns `200` with two independent state sources:

```json
{
  "outbox": {
    "status": "DISPATCHED",
    "attempts": 1,
    "lastError": null
  },
  "workflow": {
    "workflowId": "order-example-order-001",
    "runId": "01234567-89ab-cdef-0123-456789abcdef",
    "status": "COMPLETED",
    "shipmentAttempts": 1,
    "activities": [
      { "name": "ReserveOrderInventory", "status": "COMPLETED", "attempts": 1 },
      { "name": "CreateOrderShipment", "status": "COMPLETED", "attempts": 1 }
    ],
    "message": null
  }
}
```

- `outbox` can be null if no outbox row is present. Its attempts count workflow-start dispatches.
- Workflow `status` is the actual Temporal execution state, or the inspection states `NOT_FOUND` / `UNAVAILABLE`.
- `shipmentAttempts` counts actual `CreateOrderShipment` attempts. It is 0 before that Activity appears and null when inspection cannot provide history.
- `activities` contains the observed Activity names, states, and attempt counts. During execution it also uses pending Activity information.
- Missing/unavailable history has null `runId`, an empty Activity list, and an explanatory `message`; it does not change the persisted order to failed or successful.

This endpoint is a bounded summary, not an export of every Temporal event. Use Temporal UI for full history. `DISPATCHED` is expected even for a subsequently failed workflow.

## Inventory

### GET /inventory/items/{sku}

Success `200`:

```json
{ "sku": "SKU-001", "availableQuantity": 98 }
```

Unknown SKU: `404`. The value is remaining available stock, not the original seed quantity.

### POST /inventory/reservations

```json
{ "orderId": "inventory-example-001", "sku": "SKU-001", "quantity": 2 }
```

The order ID and SKU use the same ID/length validation as the order request; quantity must be a positive integer. Success `200`:

```json
{ "orderId": "inventory-example-001", "sku": "SKU-001", "quantity": 2, "status": "RESERVED" }
```

An identical repeated request returns the reservation without deducting stock again. Changed SKU/quantity for an existing ID, insufficient stock, or reserving an already released reservation returns `409`. Unknown SKU returns `404`. Invalid fields return `400`.

### POST /inventory/reservations/release

Uses the same request shape. Success `200` returns the reservation with `status: "RELEASED"`. Repeated release does not increase stock again. A release before reserve creates a released record without adding stock, blocking a later reserve for that ID.

Changed SKU/quantity for an existing reservation returns `409`; unknown SKU returns `404`.

### GET /inventory/reservations/{orderId}

Returns `200` with the reservation shape above, or `404` if absent. The storage model supports `PENDING`, `RESERVED`, and `RELEASED`.

For manual inventory exercises, use a fresh `inventory-...` ID that does not belong to a fulfillment order. Direct inventory endpoints do not update the owning order or trigger workflow compensation. Calling them against workflow-owned reservations can break business consistency; ordinary fulfillment should be driven through `POST /orders`.

## Shipments

### GET /shipments/by-order/{orderId}

Success `200`:

```json
{
  "shipmentId": "shp-0123456789abcdef0123456789abcdef",
  "orderId": "example-order-001",
  "shippingAddress": "Sydney demo address",
  "carrier": "MOCK",
  "status": "CREATED",
  "createdAt": "2026-09-27T00:00:03Z",
  "updatedAt": "2026-09-27T00:00:03Z"
}
```

Status is `CREATED` or `CANCELLED`. Returns `404` when no shipment exists, including a permanently rejected order with no shipment created. There is no public create/cancel shipment endpoint; the workflow owns those operations.

## Configuration and health

### GET /app-config

Development demo example:

```json
{ "demoEnabled": true, "temporalUiUrl": "http://localhost:8233" }
```

`demoEnabled` reflects the active Spring profile. The UI URL can be overridden with `APP_TEMPORAL_UI_BASE_URL`; the container demo uses port 18233 and AWS uses its configured protected hostname.

### GET /actuator/health

A healthy application reports a health response containing `"status": "UP"`. Additional health fields depend on configuration. Application health alone does not prove that a particular workflow has completed; inspect the order and execution endpoints.

## Error semantics

Business HTTP errors and request validation errors use Spring Problem Details with English messages. A duplicate example is:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Order already exists: example-order-001"
}
```

Spring may include an `instance` field. Framework-level parsing errors can have different details. Clients should use the HTTP status and available problem fields rather than match exact prose.

| Code | Typical meaning |
| --- | --- |
| 202 | Order accepted for asynchronous processing |
| 200 | Read or direct inventory operation succeeded |
| 400 | Invalid JSON, field constraint, enum, or pagination |
| 404 | Requested business record not found |
| 409 | Duplicate order or conflicting inventory operation |

A workflow business failure after `202` is reported through order/execution reads; it cannot change the already returned creation response.
