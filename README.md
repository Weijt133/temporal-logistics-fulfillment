# Temporal Logistics Fulfillment

A Java 21 / Spring Boot application with a React operations console, PostgreSQL persistence, a transactional outbox, and Temporal workflows. The shipment carrier is simulated.

## Run locally

Prerequisites: Java 21, Maven, Docker Compose, and a Node.js version supported by the Vite template.

From the repository root:

```powershell
docker compose up -d
mvn -f .\order-service\pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Keep that terminal running. In another terminal:

```powershell
cd frontend
npm ci
npm run dev
```

- Console: http://localhost:5137
- Backend health: http://localhost:8080/actuator/health
- Temporal UI: http://localhost:8233/namespaces/default/workflows

Only one backend instance should listen on 8080. Stop an existing instance with Ctrl+C before starting the updated application.
Omit the `demo` profile for ordinary operation; fault simulation controls will be disabled.

## Verify

```powershell
mvn -f .\order-service\pom.xml test
npm --prefix frontend run lint
npm --prefix frontend run build
```

Backend tests require the local Compose PostgreSQL on port 15432 and use an embedded Temporal test server. Business integration tests use an isolated schema; the original application test validates migrations in the configured development database.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | /orders | Persist an order and enqueue workflow startup |
| GET | /orders?page=0&size=8&status=FAILED | Page orders, optionally filtering by status |
| GET | /orders/{orderId} | Read a persisted order |
| GET | /orders/{orderId}/execution | Read outbox details and Temporal execution/attempts |
| GET | /app-config | Read demo availability and Temporal UI base URL |
| GET | /inventory/items/{sku} | Read available stock |
| GET | /inventory/reservations/{orderId} | Read a reservation |
| POST | /inventory/reservations | Reserve stock idempotently |
| POST | /inventory/reservations/release | Release stock idempotently |
| GET | /shipments/by-order/{orderId} | Read a shipment |

The console displays workflow state separately from business order state. `SHIPMENT_CREATED` is not delivery completion. Missing or unavailable Temporal history is shown explicitly rather than treated as success.

## Project notes

- [Console setup and browser checks](frontend/README.md)
- [Inventory guarantees](docs/inventory-workflow.md)
- [Shipment and failure scenarios](docs/shipment-workflow.md)

This is a modular application, not independently deployed microservices. Local Compose uses Temporal's development server. Production hosting, authentication, real carrier integration, and AWS deployment are outside the current implementation.
