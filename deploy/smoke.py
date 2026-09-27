"""Verify the real HTTP-to-workflow path using a dedicated one-unit test SKU."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("SMOKE_BASE_URL", "http://127.0.0.1:18080")
COMPOSE = ["docker", "compose", "-p", os.environ.get("COMPOSE_PROJECT_NAME", "logistics-demo"),
           "-f", str(Path(__file__).with_name("compose.demo.yml"))]


def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=12) as response:
        return response.status, json.load(response)


def main():
    assert request("/api/actuator/health")[1]["status"] == "UP"
    assert request("/api/app-config")[1]["demoEnabled"] is True
    token = uuid.uuid4().hex
    order_id, sku = "deploy-" + token, "SMOKE-" + token
    # Both identifiers are locally generated hex, not user-supplied SQL fragments.
    sql = f"INSERT INTO inventory_items(sku, available_quantity) VALUES ('{sku}', 1);"
    subprocess.run(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "logistics", "-d", "logistics",
                              "-v", "ON_ERROR_STOP=1", "-c", sql], check=True, capture_output=True)
    code, _ = request("/api/orders", {"orderId": order_id, "sku": sku, "quantity": 1,
                                     "shippingAddress": "Deployment smoke test"})
    assert code == 202
    for _ in range(60):
        _, order = request("/api/orders/" + order_id)
        if order["status"] == "FAILED":
            raise RuntimeError("Smoke order failed: " + str(order.get("failureReason")))
        if order["status"] == "SHIPMENT_CREATED":
            _, execution = request("/api/orders/" + order_id + "/execution")
            if execution["workflow"]["status"] == "COMPLETED":
                break
        time.sleep(2)
    else:
        raise RuntimeError("Smoke order did not complete in time")
    assert request("/api/inventory/items/" + sku)[1]["availableQuantity"] == 0
    assert request("/api/inventory/reservations/" + order_id)[1]["status"] == "RESERVED"
    assert request("/api/shipments/by-order/" + order_id)[1]["status"] == "CREATED"
    print("Smoke test passed. Order ID: " + order_id)


if __name__ == "__main__":
    main()
