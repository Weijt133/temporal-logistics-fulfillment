# Reviewer demo guide

[Project overview](../README.md) · [Architecture](architecture.md) · [Testing](testing.md)

This walkthrough demonstrates durable order fulfillment and failure recovery in approximately 5-10 minutes. The carrier is simulated; a created shipment is a database record, not a real delivery booking.

## Prepare

Follow the development quick start in the root README. Start the backend with the `demo` profile, open the React console at `http://localhost:5137`, and keep Temporal UI at `http://localhost:8233` available in another tab.

Alternatively, use the separate container demo at `http://127.0.0.1:18080` with its Temporal UI at `http://127.0.0.1:18233`. Use that stack's browser controls or smoke script; the PowerShell shipment script queries the original development Temporal container.

Check that `SKU-001` has sufficient available stock. The four scripted scenarios below use quantity 2; the two successful ones permanently reserve a total of four units. Seed stock is inserted once on a fresh database. Existing volumes are not reset by restarting.

Run one stock-changing demonstration at a time so before/after comparisons are meaningful. Use synthetic addresses and unique IDs. Leave records available for review.

## 1. Explain the system in one minute

Suggested explanation:

> The API commits an order and an outbox entry together. A dispatcher starts a Temporal workflow using a stable order-derived ID. Activities reserve inventory and create a mock shipment. Database transactions and idempotent operations protect against retries. If shipment creation fails permanently, compensation releases inventory and cancels any shipment whose response was lost. The UI shows business records and Temporal execution separately.

Point to the root architecture diagram. State that the business modules currently share one process and database; do not describe them as independent microservices.

## 2. Normal fulfillment

In **Orders**, submit a new order for `SKU-001`, quantity 2, with a synthetic shipping address. Inspect its order details and open Temporal from the console.

Show these independent facts:

| Evidence | Expected result |
| --- | --- |
| Order creation response | 202 Accepted |
| Persisted order | SHIPMENT_CREATED with a shipment ID |
| Outbox | DISPATCHED |
| Reservation | RESERVED |
| Shipment | CREATED, carrier MOCK |
| Temporal workflow | COMPLETED |
| Shipment Activity attempts | 1 |

Refresh the page to show the selected order is restored through the URL and fetched from the API. Use duplicate protection for the same order ID and show `409`. Explain that a successful workflow currently stops at shipment creation, not delivery completion.

## 3. Temporary failure and retry

Use **Failure lab** to run the retry scenario. The order ID must have the `demo-retry-` prefix, and the backend must be in demo mode; the UI supplies the appropriate prefix.

The first two shipment Activity attempts fail before creation. The third succeeds. Show three actual attempts, one shipment, one inventory deduction, order `SHIPMENT_CREATED`, and workflow `COMPLETED`. The displayed attempt count comes from Temporal, not a fixed animation.

## 4. Permanent rejection and compensation

Run the reject scenario (`demo-reject-`). The simulated carrier rejects the request as a non-retryable business failure before creating a shipment.

Show one shipment attempt, no shipment record, reservation `RELEASED`, stock restored, and order/workflow both `FAILED`. A failed workflow is the expected outcome: it records fulfillment failure even though compensating actions completed correctly.

## 5. The most useful failure case: commit followed by lost response

Run the ambiguous scenario (`demo-ambiguous-`). The shipment transaction commits, but an exception is thrown after commit on every attempt. The retries find the same shipment, and their responses are also lost.

After three attempts, show the same shipment now `CANCELLED`, the reservation `RELEASED`, restored stock, and order/workflow `FAILED`. Explain that compensation can find the shipment by order ID without having received a successful shipment Activity result.

The order may temporarily show `SHIPMENT_CREATED`. Wait for execution to finish before reporting the final result. The console keeps inspecting the workflow during that interval.

## 6. Inventory idempotency

In **Inventory**, use the independent inventory lab and its generated ID:

1. Read the available quantity, then reserve two units.
2. Repeat reserve: the quantity must not drop a second time.
3. Release: the original quantity must return.
4. Repeat release: the quantity must not increase again.
5. Try reserving that released ID: expect `409`.

Release the lab reservation when finished. Do not substitute the ID of an order owned by the fulfillment workflow: the direct inventory endpoints do not coordinate order or shipment state.

## Reproduce the scenarios from PowerShell

From the repository root, using the development stack and demo backend:

```powershell
.\scripts\Test-Shipment.ps1 -Scenario normal
.\scripts\Test-Shipment.ps1 -Scenario retry
.\scripts\Test-Shipment.ps1 -Scenario reject
.\scripts\Test-Shipment.ps1 -Scenario ambiguous
```

Each script prints `Result: PASS` only after checking business state, stock, shipment/reservation consistency, terminal workflow state, and real shipment attempts. Read the returned `OrderId` and open that order in the console for inspection.

To inspect one result manually, substitute its actual ID:

```powershell
$api = 'http://localhost:8080'
$orderId = 'PASTE_THE_ACTUAL_ORDER_ID'
Invoke-RestMethod "$api/orders/$orderId" | ConvertTo-Json
Invoke-RestMethod "$api/orders/$orderId/execution" | ConvertTo-Json -Depth 8
Invoke-RestMethod "$api/inventory/reservations/$orderId" | ConvertTo-Json
Invoke-RestMethod "$api/shipments/by-order/$orderId" | ConvertTo-Json
```

The last command returns 404 for the reject scenario because no shipment should exist.

## Present evidence on GitHub

A useful short recording shows a retry succeeding, then an ambiguous failure being compensated, with the inspector and Temporal history visible. Include a screenshot of the final cancelled shipment and released reservation if recording is impractical. Add media only after capturing the actual application; none is currently supplied by this guide.

For a reviewable evidence record, include the commit SHA (`git rev-parse HEAD`), date, environment, scenario, order/workflow IDs, expected result, and observed result. Link a real successful Actions run and public demo only after they exist. Never put demo credentials, AWS secrets, or real customer addresses in screenshots or the public README.

## If the demo does not progress

| Symptom | First check |
| --- | --- |
| Order remains CREATED | Outbox status/error, backend logs, Temporal connectivity |
| Outbox is DISPATCHED but work is pending | Worker availability and task queue `order-fulfillment-v2` |
| Retry scenario uses one attempt | Demo profile and `demo-retry-` prefix |
| Ambiguous scenario briefly looks successful | Wait for workflow completion/failure, not just SHIPMENT_CREATED |
| Inventory mismatch in script | Other stock-changing demos, insufficient stock, or a wrong stack |
| Workflow NOT_FOUND | Dispatch may be pending, wrong Temporal server, or history no longer retained |
| Workflow UNAVAILABLE | Temporal health/connectivity; persisted order data can still be readable |
| Order remains COMPENSATING | Inspect compensation Activity retries and the database error |
| Temporal exits with SQLite `unable to open database file` | Run the root README's one-off volume initialization, then start Temporal again |

For a container that exits, read `docker compose ps -a` and `docker compose logs --tail 100`. For the separate container stack, include `-f .\deploy\compose.demo.yml` in those commands.
