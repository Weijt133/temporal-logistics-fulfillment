# Testing and verification

[Project overview](../README.md) · [Demo guide](demo-guide.md) · [Deployment guide](../deploy/README.md)

## Run the backend suite

From the repository root, with Docker running:

```powershell
docker compose up -d postgres
docker compose exec -T postgres pg_isready -U logistics -d logistics
mvn -B --no-transfer-progress -f .\order-service\pom.xml verify
```

Wait for PostgreSQL readiness before running Maven. `verify` compiles the application, runs tests, and packages the application through the Maven lifecycle. `mvn -f .\order-service\pom.xml test` runs compilation and tests without the packaging step.

The tests enable Temporal's embedded test server. They do not require the Docker Temporal service, a running backend on port 8080, or a frontend. PostgreSQL is real, not mocked or replaced by H2.

The default connection is `127.0.0.1:15432/logistics`, user `logistics`, with the development password from application configuration. If `DB_PASSWORD` is set in your shell, it overrides that password. The separate container demo does not expose PostgreSQL on 15432 and uses its own password; use the intended stack when running host tests.

`OrderFulfillmentIntegrationTests` creates a uniquely named schema and removes it after the suite. Its fixtures use unique IDs and stock rows. The older `OrderServiceApplicationTests` starts the full application with its configured default schema, including Flyway. Therefore the entire test run must target a development/disposable database, not production.

Surefire writes reports under `order-service/target/surefire-reports/`. These generated files are not committed. A passing build is evidence for the tested checkout and environment, not a permanent assertion about future changes.

## Automated backend coverage

The current suite has **24 tests**: 21 fulfillment/API integration tests, one legacy workflow test, and two historical replay tests.

| Guarantee | Representative test methods |
| --- | --- |
| Order/outbox starts the new workflow and creates a shipment | `outboxStartsWorkflowAndCreatesShipment` |
| Activity repetition and late dispatch acknowledgment preserve stock/state | `repeatedActivityAndLateOutboxAcknowledgmentDoNotDeductOrRegressStatus` |
| Insufficient stock and missing SKU produce recorded failures | `insufficientStockFailsOrderWithoutLeavingReservation`, `unknownSkuFailsOrderWithReason` |
| Reservation and order update share a transaction | `orderUpdateFailureRollsBackStockAndReservationTogether` |
| Lost reservation acknowledgment retries without another deduction | `temporalRetriesLostResponseAfterCommitWithoutDoubleDeduction` |
| Concurrent shipment requests create one shipment ID | `concurrentShipmentRequestsReturnOneIdAndLateOutboxDoesNotRegressStatus` |
| Shipment and order update roll back together | `shipmentAndOrderUpdateRollBackTogether` |
| Temporary carrier errors retry twice before success | `transientCarrierFailureRetriesTwiceThenCreatesOneShipment` |
| Permanent rejection releases inventory without a shipment | `permanentRejectionCompensatesWithoutCreatingShipment` |
| Repeated lost responses cancel the committed shipment and restore stock | `exhaustedLostResponsesCancelCommittedShipmentAndRestoreStock` |
| Repeated compensation and late shipment creation preserve invariants | `repeatedCompensationAndLateCreationCannotRestoreOrDeductStockAgain` |
| Failed compensation rolls back; retry can finish | `failedCompensationRollsBackAndCanResume`, `compensationActivityRetriesBeforeWorkflowReportsFailure` |
| One lost shipment response recovers with the original ID | `shipmentResponseLostOnceRecoversWithOriginalShipment` |
| Fault injection requires demo mode | `faultSimulationIsDisabledOutsideDemoProfile` |
| Console endpoints expose actual states/attempts and validate requests | Five `console...` methods in the integration suite |
| Legacy workflow still executes | `workflowCompletes` |
| Recorded inventory-only histories remain replay-compatible | `inventoryOnlySuccessHistoryStillReplays`, `inventoryOnlyFailureHistoryStillReplays` |

Sources:

- [OrderFulfillmentIntegrationTests.java](../order-service/src/test/java/com/llogistics/order_service/OrderFulfillmentIntegrationTests.java)
- [OrderServiceApplicationTests.java](../order-service/src/test/java/com/llogistics/order_service/OrderServiceApplicationTests.java)
- [WorkflowReplayTests.java](../order-service/src/test/java/com/llogistics/order_service/WorkflowReplayTests.java)

Some integration tests also replay the newly produced success/compensation histories. Replay checks deterministic compatibility with those histories. It does not execute external side effects again or establish compatibility with every possible history.

The transaction rollback tests inject database constraint failures. Lost-response tests inject exceptions after commits. The concurrent shipment test uses eight simultaneous requests to the service. These are targeted correctness tests, not a distributed load benchmark or a real network partition experiment.

## Frontend checks

```powershell
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run build
```

Use Node.js 22 to match CI and Docker builds. `npm ci` installs the lockfile. Lint checks ESLint rules; build checks TypeScript and creates Vite's production bundle. Neither command performs browser interaction tests. There is no checked-in frontend component or end-to-end test runner at present.

Manual browser verification covers:

1. Create and read orders, page/filter the list, and reopen an order from its URL after reload.
2. Read actual Temporal attempts and distinguish business state from workflow state.
3. Verify duplicate order protection (`409`) and useful validation/not-found errors.
4. Run all four shipment scenarios and check inventory, shipment, and workflow outcomes.
5. In the inventory lab, reserve twice, release twice, and verify that a late reserve is rejected.
6. Inspect shipment lookup and navigation to Temporal UI.
7. Check desktop and narrow/mobile layouts and the browser console for errors.

## End-to-end development scenarios

Start the development stack, backend in `demo` mode, and optionally React as described in the root README. Then run one at a time:

```powershell
.\scripts\Test-Shipment.ps1 -Scenario normal
.\scripts\Test-Shipment.ps1 -Scenario retry
.\scripts\Test-Shipment.ps1 -Scenario reject
.\scripts\Test-Shipment.ps1 -Scenario ambiguous
```

The script creates a unique order, checks available stock, waits for the expected business outcome, compares reservation/shipment/stock, and reads actual Temporal history from the original `compose.yaml` container. It also verifies workflow completion/failure and exact shipment attempt counts. A transient `SHIPMENT_CREATED` state does not let the ambiguous scenario pass before compensation.

Parameters are `-Scenario`, `-Sku` (default `SKU-001`), `-Quantity` (default 2), and `-ApiUrl` (default `http://localhost:8080`). Changing the API URL does not change which Temporal container is queried, so it is not a general remote deployment test. Use the separate smoke script for the container deployment.

Records remain available for inspection. Successful scenarios consume stock; compensated failures restore it. Concurrent stock-changing operations can invalidate the script's before/after assertion. Do not run these against a production database or delete all volumes just to reset a demo.

## Container and deployment checks

[The deployment guide](../deploy/README.md#2-validate-the-containers-locally-windows-powershell) contains exact image-build, Compose readiness, and smoke commands. `deploy/smoke.py` runs against the separate container stack and creates a unique `SMOKE-*` SKU/order, preserving ordinary demo stock.

Static configuration checks used during preparation include actionlint for GitHub workflows, ShellCheck for `release.sh`, and cfn-lint for CloudFormation. These validate syntax and selected rules; they do not authenticate with AWS or prove that a release can succeed in a specific account.

The release workflow is configured to run CI first, wait for Systems Manager completion, check fulfillment, and check public HTTPS/password enforcement. Those checks only become cloud deployment evidence when the workflow actually runs successfully in the target account.

## Recorded local verification baseline

As of **2026-09-27**, the checks below were recorded during implementation and local CI/CD preparation. This documentation update does not represent a new cloud deployment or a fresh execution of every test.

| Check | Recorded result and scope |
| --- | --- |
| Backend suite | 24 tests, 0 failures, 0 errors, 0 skipped in a Linux Java 21 validation environment with PostgreSQL |
| Frontend | ESLint and production build passed using Node.js 22 in Linux |
| Images | Backend and frontend container builds passed |
| Compose smoke | Fresh validation stack completed order, inventory reservation, workflow, and shipment checks |
| Browser | Normal/retry/rejection/ambiguous scenarios, duplicate protection, inventory idempotency, navigation, and responsive layout checked manually |
| Gateway | Local HTTP authentication checks returned 401 without credentials and allowed authorized app/API/Temporal access; HTTPS configuration validation passed |
| Configuration | Three workflows passed actionlint; release script passed ShellCheck; CloudFormation passed cfn-lint |

The temporary validation stack was removed after verification. Its disposable data is not a permanent public demo. No screenshots, test videos, or green remote CI badge are implied by this record.

## Not yet verified or implemented

- A GitHub-hosted CI run and end-to-end AWS deployment with the actual account's OIDC, IAM, SSM, DNS, and public certificate issuance.
- End-to-end cloud rollback and recovery from instance replacement or backup restoration.
- Sustained load, multiple dispatcher instances, high availability, or real process-kill/network-partition recovery drills.
- Browser automation in CI, accessibility audits, security penetration testing, or real carrier integration.
- Automated dashboards/alerts for pending outbox growth or stuck compensation.

When adding evidence, record the commit, environment, command/run URL, outcome, and limitation. Update this page with observed results rather than turning a configuration file into a claim of successful deployment.
