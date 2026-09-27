# Java / Temporal role alignment

[Project overview](../README.md) · [Architecture](architecture.md) · [Testing evidence](testing.md)

## Assessment

This project is suitable as a selected portfolio project for a Java/Spring Boot/Temporal position. It provides inspectable implementation and tests for asynchronous fulfillment, transactions, idempotency, retries, compensation, and a React UI.

It does not establish that every role requirement is fully met. The backend is a modular monolith, AWS is currently a deployment configuration with local validation, and a repository cannot prove professional experience or cross-team communication. The matrix below separates demonstrated work from those remaining boundaries.

Assessment date: **2026-09-27**. Evidence reflects the local checkout, including documentation and deployment files that may not yet have been committed or pushed.

## Requirement-to-evidence matrix

| Role requirement | Evidence in this project | Assessment and boundary |
| --- | --- | --- |
| Java 8+ and Spring Boot | Java 21, Spring Boot 4.0.8, dependency injection, validation, transaction boundaries, JPA/JDBC, Actuator | Strong project evidence. Java 21 meets the stated version range; this is not a claim of Java 8 runtime compatibility or years of commercial experience. |
| REST APIs and microservices | Create/read/page/filter APIs, 202/400/404/409 semantics, business modules, containerized backend | REST implemented. Independent microservices are not implemented: one backend process and shared database. |
| React and modern frontend | React/TypeScript console, forms, polling, pagination, filters, errors, responsive layouts, Vite build | Implemented and manually exercised; lint/build checks exist. Automated component/browser tests remain to be added. |
| Temporal and orchestration | Durable workflow startup, worker queues, retry policies, non-retryable failures, compensation, history inspection, version marker, replay tests | Strong project evidence. Temporal infrastructure is a development server, not a production cluster. |
| AWS and cloud applications | CloudFormation, EC2/ECR/S3/IAM/SSM design, OIDC workflow, container release, HTTPS gateway configuration | Partial: code and local validation exist. Actual AWS provisioning, deployment, monitoring, and recovery have not been verified. |
| PostgreSQL, MySQL, or Oracle | PostgreSQL schema, Flyway V1-V4, uniqueness/check constraints, row locks, guarded stock updates, transaction rollback tests | Implemented for PostgreSQL. No MySQL/Oracle experience is inferred or required to explain this implementation. |
| Git, CI/CD, DevOps | Git repository/history, GitHub Actions CI, Docker images, Compose, SHA-tagged deployment, health/smoke checks, rollback script | Local tooling and configuration implemented. A successful remote Actions run and real cloud release are still needed as operational evidence. |
| Distributed systems, messaging, asynchronous processing | PostgreSQL-to-Temporal consistency boundary, transactional outbox, stable workflow IDs, retry/deduplication, ambiguous outcomes | Demonstrates relevant reasoning and asynchronous task processing. No Kafka/RabbitMQ/SQS integration, broker consumer design, or independently deployed business services is claimed. |
| Problem solving and communication | Failure cases reproduced in tests, architecture/tradeoff documentation, API contracts, demo and operational guides | Supports a technical discussion. Clear explanations and team communication must still be demonstrated in interview or work experience. |

## Strongest material to discuss

1. **Database commit versus workflow startup:** explain why the order and outbox commit together, and why a stable workflow ID handles an accepted start whose acknowledgment was lost.
2. **Idempotency versus duplicate HTTP creation:** order creation returns 409 on reuse, while Activity side effects can safely repeat with the same business key.
3. **A shipment exists even though the Activity failed:** demonstrate a post-commit exception, retries returning the same shipment, and compensation by order ID after retry exhaustion.
4. **Concurrency and rollback:** explain reservation/order locks, conditional stock updates, unique shipment-per-order constraints, and tests that force database failures.
5. **Business state versus workflow state:** successful orchestration ends at order SHIPMENT_CREATED; compensated rejection still ends as a failed workflow.
6. **Workflow evolution:** show the version marker and recorded inventory-only history replay tests, including their limits.

Useful source entry points are [OrderApplicationService](../order-service/src/main/java/com/llogistics/order_service/order/OrderApplicationService.java), [OutboxDispatcher](../order-service/src/main/java/com/llogistics/order_service/outbox/OutboxDispatcher.java), [OrderFulfillmentWorkflowImpl](../order-service/src/main/java/com/llogistics/order_service/workflow/OrderFulfillmentWorkflowImpl.java), [InventoryService](../order-service/src/main/java/com/llogistics/order_service/inventory/InventoryService.java), and [ShipmentService](../order-service/src/main/java/com/llogistics/order_service/shipment/ShipmentService.java).

## Prioritized remaining work

| Priority | Next step | Completion evidence |
| --- | --- | --- |
| 1 | Commit/push the reviewed implementation and run GitHub CI | Successful backend/frontend checks linked to the actual commit |
| 2 | Provision and run the configured AWS demo, if pursuing cloud evidence | Working protected HTTPS site, successful OIDC/SSM release, real smoke order, recorded resource/configuration choices |
| 3 | Exercise operations in that environment | Verified restart/recovery, failed-release rollback, backup restoration, and documented results/limitations |
| 4 | Add frontend interaction tests and useful operational alerts | CI checks for essential browser flows; actionable pending-outbox/compensation alerts |
| 5 | Extract a service only if independent microservices are a specific portfolio objective | A separately deployed inventory or shipment service with its own data ownership, network API, idempotency contract, and failure tests |

Extracting a module into another folder or giving it a second Dockerfile would not by itself establish microservice behavior. It must change deployment, communication, ownership, and failure boundaries. A real extraction would also require revisiting the current shared compensation transaction.

Adding a message broker is optional further work, not a prerequisite for explaining Temporal's asynchronous workflow model. Add it only with a clear use case, such as consuming external carrier events, and document the resulting delivery and ordering semantics.

## Accurate selected-project wording

Suggested title: **Temporal Logistics Fulfillment — Java, Spring Boot, React, PostgreSQL**.

Possible resume bullets based on the current implementation:

- Built a Java 21/Spring Boot order fulfillment application with PostgreSQL persistence, a transactional workflow-start outbox, and Temporal orchestration.
- Implemented idempotent inventory and mock shipment operations with retries and compensation for permanent failures and lost responses after commit; validated behavior through integration and workflow replay tests.
- Developed a React/TypeScript operations console showing persisted orders, inventory, shipments, and actual Temporal execution/attempts; added Docker builds and GitHub Actions/AWS demo deployment configuration.

Use the last bullet's word **configuration** until remote CI and AWS deployment have actually run. Afterward, replace it with a specific, evidenced deployment result. Do not claim a production microservices platform, commercial carrier integration, exactly-once delivery, high availability, a live AWS deployment, or unmeasured throughput/latency improvements from the current repository.

The strongest presentation is a reproducible demonstration and a clear account of design choices, supported by [test evidence](testing.md), rather than a percentage match or a claim that one project satisfies all professional-experience requirements.
