# Inventory workflow

Orders and workflow-start outbox entries are saved in one transaction. Temporal reserves inventory before creating a shipment.

- A repeated reservation returns the original result without deducting stock again.
- A repeated release does not add stock twice.
- Released reservations reject late reserve requests.
- Stock changes and reservation updates commit or roll back together.
- Outbox `DISPATCHED` means the workflow was started, not that fulfillment finished.
- Older inventory-only workflow histories remain replay-compatible.

The REST inventory lab uses independent IDs. Do not directly reserve/release inventory for an order owned by the fulfillment workflow: those endpoints do not coordinate order or shipment state.

See [the project README](../README.md) for startup commands, [architecture](architecture.md#transactions-and-idempotency) for transaction boundaries, [API reference](api.md#inventory) for request semantics, and [testing](testing.md) for verification evidence.
