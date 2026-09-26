# 订单自动预留库存

本阶段实现：创建订单 → Outbox → Temporal → 库存预留 → 订单 `RESERVED`。

## 实际调用顺序

1. `POST /orders` 调用 `OrderApplicationService`，在同一个事务中保存订单和 Outbox 任务，返回 `202`。
2. `OutboxDispatcher` 每 3 秒扫描待启动任务，启动 `OrderFulfillmentWorkflow`。
3. 新工作流使用 `order-fulfillment-v2` 队列，执行 `ReserveOrderInventory` Activity。
4. `OrderFulfillmentActivitiesImpl` 调用 `OrderFulfillmentService`。
5. Service 锁定订单行，读取订单的 SKU 和数量，调用现有的 `InventoryService.reserve`。
6. 库存扣减、预留记录和订单 `RESERVED` 状态在同一个 PostgreSQL 事务中提交。
7. `GET /orders/{orderId}` 返回更新后的订单。

成功时，Temporal 本阶段工作流显示 `Completed`，订单业务状态为 `RESERVED`。
这里表示库存预留阶段完成；物流下单尚未接入，因此订单不会变为业务上的 `COMPLETED`。
Outbox 的 `DISPATCHED` 只表示工作流已启动，不表示订单履约完成。
Worker 可能先于 Outbox 确认完成预留，所以查询时不一定能观察到短暂的 `IN_PROGRESS`。

## 失败和重试

- 库存不足、SKU 不存在或预留冲突：当前事务回滚，Activity 返回不可重试的业务失败；随后另一个 Activity 保存订单 `FAILED` 和 `failureReason`，Temporal 工作流显示 `Failed`。
- 数据库等临时故障：Activity 按退避策略持续重试，间隔从 1 秒逐渐增加到最多 30 秒。此阶段没有配置重试次数上限，应在 Temporal UI 查看持续重试的异常。
- 事务提交后响应丢失：重试时发现订单已是 `RESERVED`，直接返回，不再扣库存。
- 数据库写入订单状态失败：库存扣减和预留记录一起回滚。
- Outbox 的延迟确认只把 `CREATED` 改成 `IN_PROGRESS`，不会覆盖已写入的 `RESERVED` 或 `FAILED`。

旧 `OrderWorkflow`、旧队列 `order-fulfillment` 继续保留，历史演示订单不会自动重新处理。验证新链路时使用新订单号。

## 本地运行

在项目根目录运行：

```powershell
docker compose up -d
cd order-service
mvn spring-boot:run
```

启动前确保 8080 端口没有另一个订单服务实例，避免新旧 Outbox 同时派发任务。

在另一个 PowerShell 窗口创建订单：

```powershell
$orderId = 'reserve-' + [guid]::NewGuid().ToString('N')
$body = @{
    orderId = $orderId
    sku = 'SKU-001'
    quantity = 2
    shippingAddress = 'Sydney demo address'
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/orders' `
    -ContentType 'application/json' -Body $body

Start-Sleep -Seconds 5
Invoke-RestMethod -Uri "http://localhost:8080/orders/$orderId" | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/inventory/reservations/$orderId" | ConvertTo-Json
```

SKU-001 库存足够时，两次查询应分别显示订单 `RESERVED` 和预留 `RESERVED`。
该示例会真实占用 2 件库存。

Temporal UI：http://localhost:8233/namespaces/default/workflows

按 `order-` 加订单号找到对应工作流，查看 `ReserveOrderInventory` Activity。

## 自动化验证

要求 Java 21 和本地 Compose PostgreSQL 已启动；测试使用嵌入式 Temporal 测试服务。

```powershell
cd order-service
mvn test
```

新增 `OrderFulfillmentIntegrationTests` 使用随机生成的 PostgreSQL schema，运行真实迁移和事务测试，结束后删除自己的 schema，不扣减 public schema 的演示库存。

覆盖正常链路、重复 Activity、Outbox 确认顺序、库存不足、未知 SKU、事务回滚，以及提交后响应丢失的 Temporal 重试。原有演示工作流测试继续保留。

设计参考：[Temporal 工作流版本兼容](https://docs.temporal.io/develop/java/workflows/versioning)。
