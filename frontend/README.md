# Fulfill console

React, TypeScript, Vite, and ESLint. All application labels, help text, and errors are in English.

```powershell
npm ci
npm run dev
```

Open http://localhost:5137. The `/api` development proxy forwards requests to http://127.0.0.1:8080.
Start the updated backend before opening the console. Use the `demo` profile for fault injection and the inventory lab.

```powershell
npm run lint
npm run build
```

`dist/` contains the static production build. The Vite development proxy is not part of that build; a deployed server must route `/api` to the backend.

## Views

- Orders: paginated persistent records, status filtering, creation, lookup, and duplicate protection.
- Order inspector: order, inventory, shipment, outbox, actual Temporal attempts and Activity states.
- Inventory: SKU lookup and an isolated reserve/release idempotency lab. Release the demo reservation when finished. Its ID survives reloads in local storage where available.
- Shipments: lookup by order ID and navigation to the full execution.
- Failure lab: normal, retry, rejection, and lost-response scenarios, enabled only when the backend reports demo mode.

Polling is sequential, bounded by request timeouts, and cancelled on unmount or selection changes. Execution polling continues after `SHIPMENT_CREATED`, since lost-response compensation can still follow.

## Browser verification

1. Create an order and inspect its completed workflow and shipment.
2. Refresh the page; the selected order is restored from the URL and fetched from PostgreSQL.
3. Check duplicate protection; the backend must return 409.
4. Run each fault scenario and inspect actual attempts and final stock/reservation states.
5. Reserve twice, release twice, then verify that a late reserve is rejected.
6. Look up a shipment and an unknown order/SKU; verify useful English messages.
7. Resize to a narrow viewport and check navigation, forms, and detail panels.
