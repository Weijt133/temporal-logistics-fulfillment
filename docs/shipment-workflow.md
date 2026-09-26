# Shipment 物流模块

当前实现使用本地数据库模拟物流，不调用真实承运商，也尚未拆成独立微服务。

## 成功链路

`POST /orders` → 订单与 Outbox 同事务保存 → Temporal `ReserveOrderInventory` → `CreateOrderShipment` → 订单 `SHIPMENT_CREATED`。

`GET /orders/{orderId}` 返回非空 `shipmentId`。
`GET /shipments/by-order/{orderId}` 返回运单；不存在时返回 404。

运单状态为 `CREATED` 或 `CANCELLED`。订单 `SHIPMENT_CREATED` 只表示运单创建成功，不表示包裹已经送达，暂不设置 `COMPLETED`。

同一订单的创建和补偿共用订单行锁；`shipments.order_id` 还有唯一约束。
运单记录、订单运单号和订单状态在同一个事务中提交。
Activity 重试时查询原运单，返回相同的运单号。

## 失败链路

- 库存失败：保留原来的库存失败处理，不创建运单。
- 物流临时错误：最多尝试 3 次（包含首次），每次最多 15 秒；初始退避 1 秒，最大间隔 5 秒。
- 明确拒单：不可重试，直接进入补偿。
- 物流失败后：`BeginShipmentCompensation` 先提交订单 `COMPENSATING`，阻止迟到的物流请求创建运单。
- `CompensateShipmentFailure` 按订单号查询并取消可能已提交的运单、释放库存、将订单标记为 `FAILED`，三项数据库修改同事务提交。
- 取消过的运单保留记录；订单保留其 `shipmentId` 供审计。物流调用没有返回运单号，也不代表没有创建运单。
- 补偿 Activity 对临时故障持续重试，间隔最多 30 秒；补偿未成功时不会提前把订单写为 `FAILED`。
- 整条失败流程在 Temporal 中显示 `Failed`，同时数据库保留清晰的失败原因。

当前事务保证只适用于同一个应用、同一个数据库中的模拟物流。未来调用真实承运商或拆服务，需要承运商幂等键、状态核对和各服务独立的补偿，不能依赖跨服务本地事务。

## 文件入口

- `src/main/resources/db/migration/V4__create_shipments.sql`：新表，不改 V1～V3。
- `shipment/ShipmentEntity`、`ShipmentStatus`、`ShipmentRepository`：运单数据。
- `shipment/ShipmentService`：创建、查询、补偿和事务。
- `activity/ShipmentActivities`、`ShipmentActivitiesImpl`：Temporal Activity 和开发故障模拟。
- `api/ShipmentController`：查询运单。
- `workflow/OrderFulfillmentWorkflowImpl`：库存后新增物流步骤及补偿编排。
- `application.properties`：为现有 `order-fulfillment-v2` Worker 注册 `shipmentActivities`。

Workflow 使用 `getVersion("add-shipment-v1", DEFAULT_VERSION, 1)` 兼容已有库存阶段历史。
已完成的旧订单仍停留在原状态，不会自动补建运单；用新订单号测试。
两份已有成功/失败历史保存在测试资源中，自动化测试会用新代码重放。
参考：[Temporal 版本兼容](https://docs.temporal.io/develop/java/workflows/versioning)。

## 启动

Java 21；先启动项目根目录的 Docker Compose 服务。

```powershell
Set-Location 'C:\Users\weijt\Desktop\task\temporal-logistics-fulfillment'
docker compose up -d
Set-Location '.\order-service'
mvn spring-boot:run
```

8080 只能运行一个订单服务实例。源码修改后重启该实例，运行中的旧 jar 不会自动更新。

如果当前使用本次验收启动的后台实例，可以在项目根目录先停止它，再使用上面的 Maven 命令在终端启动：

```powershell
$moduleRoot = 'C:\Users\weijt\Desktop\task\temporal-logistics-fulfillment\order-service'
$servicePid = [int](Get-Content "$moduleRoot\target\order-service.pid")
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid"
$expectedJar = "$moduleRoot\target\order-service-0.0.1-SNAPSHOT.jar"
if ($null -ne $process) {
    if ($process.Name -ne 'java.exe' -or -not $process.CommandLine.Contains($expectedJar)) {
        throw '进程与本项目后台实例不匹配，请先检查，未停止任何进程。'
    }
    Stop-Process -Id $servicePid
}
```

## 故障演示

故障注入只有在 Spring 的 `demo` profile 激活时才生效。通过订单号前缀选择测试场景；正常运行时这些前缀没有特殊含义，也不增加公开的故障注入 API。

停止普通实例后，在模块目录运行：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

在另一个 PowerShell 窗口依次执行：

```powershell
Set-Location 'C:\Users\weijt\Desktop\task\temporal-logistics-fulfillment'
.\scripts\Test-Shipment.ps1 -Scenario normal
.\scripts\Test-Shipment.ps1 -Scenario retry
.\scripts\Test-Shipment.ps1 -Scenario reject
.\scripts\Test-Shipment.ps1 -Scenario ambiguous
```

| 场景 | 注入的故障 | 预期 |
|---|---|---|
| normal | 无 | 1 次物流调用，运单 CREATED，订单 SHIPMENT_CREATED |
| retry | `demo-retry-`：前两次调用失败 | 第 3 次成功，只有一条运单 |
| reject | `demo-reject-`：明确业务拒单 | 1 次调用，无运单，库存恢复，订单 FAILED |
| ambiguous | `demo-ambiguous-`：每次提交后丢失响应 | 3 次调用使用同一运单，最终取消运单并恢复库存，订单 FAILED |

脚本自动创建唯一订单号，核对订单、运单、库存、预留记录和真实 Temporal 历史中的尝试次数。输出 `Result: PASS` 后，可以用输出的 Workflow ID 在 http://localhost:8233/namespaces/default/workflows 查看过程。

ambiguous 场景会短暂显示 `SHIPMENT_CREATED`，随后进入补偿；脚本会等到最终 `FAILED` 再检查结果。

默认使用 SKU-001、数量 2，可以通过 `-Sku` 和 `-Quantity` 指定现有测试商品。normal/retry 各真实占用指定数量库存；reject/ambiguous 恢复本次预留库存。一次运行一个脚本，避免其他库存操作干扰前后数量比较。脚本保留测试订单和执行历史，不删除已有数据。

## 自动化测试

```powershell
Set-Location 'C:\Users\weijt\Desktop\task\temporal-logistics-fulfillment\order-service'
mvn test
```

新增业务测试使用随机 PostgreSQL schema 和嵌入式 Temporal，测试结束后删除自己的 schema。
原有启动测试使用已配置的开发数据库，会校验并执行待应用的 Flyway 迁移。

覆盖正常链路、8 路并发创建运单、事务回滚、提交后响应丢失、暂时故障重试、拒单、重复补偿、补偿回滚与恢复、补偿 Activity 重试、关闭 demo profile 时不注入故障，以及新旧工作流历史重放。

## 当前边界

尚未包含真实承运商集成、用户主动取消订单接口、React 页面或独立服务部署。此模块的取消操作由物流失败补偿驱动。
