# Inventory workflow

Orders and workflow-start outbox entries are saved in one transaction. Temporal reserves inventory before creating a shipment.

- A repeated reservation returns the original result without deducting stock again.
- A repeated release does not add stock twice.
- Released reservations reject late reserve requests.
- Stock changes and reservation updates commit or roll back together.
- Outbox `DISPATCHED` means the workflow was started, not that fulfillment finished.
- Older inventory-only workflow histories remain replay-compatible.

See [the project README](../README.md) for startup and verification commands.
