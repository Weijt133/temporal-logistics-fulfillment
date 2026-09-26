param(
    [ValidateSet('normal', 'retry', 'reject', 'ambiguous')]
    [string]$Scenario = 'normal',
    [string]$Sku = 'SKU-001',
    [ValidateRange(1, 1000000)]
    [int]$Quantity = 2,
    [string]$ApiUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$ApiUrl = $ApiUrl.TrimEnd('/')
$composeFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'compose.yaml'
$orderId = 'demo-' + $Scenario + '-' + [guid]::NewGuid().ToString('N')
$before = Invoke-RestMethod -Uri "$ApiUrl/inventory/items/$Sku"
if ($before.availableQuantity -lt $Quantity) {
    throw 'Not enough initial stock for this shipment scenario. Choose another SKU or quantity.'
}
$body = @{
    orderId = $orderId
    sku = $Sku
    quantity = $Quantity
    shippingAddress = 'Sydney shipment demo address'
} | ConvertTo-Json

$accepted = Invoke-RestMethod -Method Post -Uri "$ApiUrl/orders" -ContentType 'application/json' -Body $body
$successExpected = $Scenario -in @('normal', 'retry')
$expectedOrder = if ($successExpected) { 'SHIPMENT_CREATED' } else { 'FAILED' }
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    $order = Invoke-RestMethod -Uri "$ApiUrl/orders/$orderId"
    # A lost response can leave SHIPMENT_CREATED temporarily before compensation starts.
    if ($order.status -eq $expectedOrder -or ($successExpected -and $order.status -eq 'FAILED')) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)

if ($order.status -ne $expectedOrder) {
    throw "Order $orderId has state $($order.status), expected $expectedOrder. Fault scenarios require the demo Spring profile."
}
$reservation = Invoke-RestMethod -Uri "$ApiUrl/inventory/reservations/$orderId"
$expectedReservation = if ($successExpected) { 'RESERVED' } else { 'RELEASED' }
if ($reservation.status -ne $expectedReservation) { throw "Unexpected reservation state: $($reservation.status)" }

$shipment = $null
try {
    $shipment = Invoke-RestMethod -Uri "$ApiUrl/shipments/by-order/$orderId"
} catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
}
if ($successExpected) {
    if ($null -eq $shipment -or $shipment.status -ne 'CREATED' -or $shipment.shipmentId -ne $order.shipmentId) {
        throw 'Shipment and order do not match.'
    }
} elseif ($Scenario -eq 'ambiguous') {
    if ($null -eq $shipment -or $shipment.status -ne 'CANCELLED' -or $shipment.shipmentId -ne $order.shipmentId) {
        throw 'The committed shipment was not cancelled correctly.'
    }
} elseif ($null -ne $shipment) {
    throw 'A rejected order should not have a shipment.'
}

$after = Invoke-RestMethod -Uri "$ApiUrl/inventory/items/$Sku"
$expectedStock = if ($successExpected) { $before.availableQuantity - $Quantity } else { $before.availableQuantity }
if ($after.availableQuantity -ne $expectedStock) { throw 'Unexpected stock quantity. Run stock-changing demos one at a time.' }

# Verify actual Temporal attempts, so retry mode cannot silently pass with fault injection disabled.
$workflowId = $accepted.workflowId
$closed = $false
for ($i = 0; $i -lt 20; $i++) {
    $raw = docker compose -f $composeFile exec -T temporal temporal workflow show --workflow-id $workflowId --output json
    if ($LASTEXITCODE -ne 0) { throw "Cannot read Temporal history for $workflowId" }
    $history = ($raw -join [Environment]::NewLine) | ConvertFrom-Json
    $lastType = $history.events[-1].eventType
    if ($lastType -in @('EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED', 'EVENT_TYPE_WORKFLOW_EXECUTION_FAILED')) {
        $closed = $true
        break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $closed) { throw 'Workflow has not reached its expected terminal state.' }
$expectedEnd = if ($successExpected) { 'EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED' } else { 'EVENT_TYPE_WORKFLOW_EXECUTION_FAILED' }
if ($lastType -ne $expectedEnd) { throw "Unexpected workflow terminal event: $lastType" }
$scheduled = @($history.events | Where-Object {
    $_.eventType -eq 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' -and
    $_.activityTaskScheduledEventAttributes.activityType.name -eq 'CreateOrderShipment'
})
if ($scheduled.Count -ne 1) { throw 'Expected one shipment Activity in this workflow.' }
$scheduledId = $scheduled[0].eventId
$started = @($history.events | Where-Object {
    $_.eventType -eq 'EVENT_TYPE_ACTIVITY_TASK_STARTED' -and
    $_.activityTaskStartedEventAttributes.scheduledEventId -eq $scheduledId
})
$attempts = ($started | ForEach-Object { [int]$_.activityTaskStartedEventAttributes.attempt } | Measure-Object -Maximum).Maximum
$expectedAttempts = if ($Scenario -in @('retry', 'ambiguous')) { 3 } else { 1 }
if ($attempts -ne $expectedAttempts) { throw "Expected $expectedAttempts shipment attempts, found $attempts. Check the demo Spring profile." }

[pscustomobject]@{
    Result = 'PASS'
    Scenario = $Scenario
    OrderId = $orderId
    WorkflowId = $workflowId
    OrderStatus = $order.status
    ShipmentId = $order.shipmentId
    ShipmentStatus = $(if ($null -eq $shipment) { 'NONE' } else { $shipment.status })
    ReservationStatus = $reservation.status
    ShipmentAttempts = $attempts
    StockBefore = $before.availableQuantity
    StockAfter = $after.availableQuantity
    FailureReason = $order.failureReason
}
