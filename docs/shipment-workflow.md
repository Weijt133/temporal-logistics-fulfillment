# Shipment workflow

`POST /orders` -> transactional outbox -> reserve inventory -> create mock shipment.

| Scenario | Shipment attempts | Final order | Shipment | Reservation |
| --- | --- | --- | --- | --- |
| normal | 1 | SHIPMENT_CREATED | CREATED | RESERVED |
| retry | 3 | SHIPMENT_CREATED | CREATED | RESERVED |
| reject | 1 | FAILED | None | RELEASED |
| ambiguous | 3 | FAILED | CANCELLED | RELEASED |

Fault injection requires the `demo` Spring profile and the corresponding `demo-<scenario>-` order ID prefix.
Shipment creation and the order update share a database transaction. Retried creation returns the original shipment ID.
Compensation first marks the order `COMPENSATING`, then cancels any committed shipment, releases inventory, and marks the order `FAILED` atomically.
Transient compensation failures continue retrying. A failed workflow is the expected result of the rejection and lost-response scenarios.

The carrier is simulated in the same application and database. `SHIPMENT_CREATED` does not mean delivered. No public cancel-shipment operation is implemented.
Existing completed workflows are not rerun by this change. Workflow versioning preserves the old inventory-only histories.

From the repository root, with the backend running in demo mode:

```powershell
.\scripts\Test-Shipment.ps1 -Scenario normal
.\scripts\Test-Shipment.ps1 -Scenario retry
.\scripts\Test-Shipment.ps1 -Scenario reject
.\scripts\Test-Shipment.ps1 -Scenario ambiguous
```

Run scenarios one at a time. Success consumes stock; compensated failures restore it. Scripts retain their test records.

These commands use the original development Compose Temporal container. For the separate deployment stack, use its console or `deploy/smoke.py`. See the [demo guide](demo-guide.md) for a reviewer walkthrough, [architecture](architecture.md#ambiguous-outcome-scenario) for the lost-response sequence, and [testing](testing.md) for coverage and limitations.
