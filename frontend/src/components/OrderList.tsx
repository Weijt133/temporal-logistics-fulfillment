import { useCallback, useState } from "react";
import { api } from "../api";
import { usePolling } from "../hooks/usePolling";
import { Badge, Empty, Notice } from "./Common";

export function OrderList({
  selectedId,
  onSelect,
}: {
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const loader = useCallback(
    (signal: AbortSignal) => api.orders(page, status, signal),
    [page, status],
  );
  const result = usePolling(`orders-${page}-${status}`, loader, 5000);
  return (
    <section className="panel orders-panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">PERSISTED IN POSTGRESQL</p>
          <h2>
            Recent orders{" "}
            <span className="count">{result.data?.totalElements ?? "—"}</span>
          </h2>
        </div>
        <button className="secondary small" onClick={result.refresh}>
          Refresh
        </button>
      </div>
      <div className="list-toolbar">
        <label className="inline-label">
          Status
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            {[
              "CREATED",
              "IN_PROGRESS",
              "RESERVED",
              "SHIPMENT_CREATED",
              "COMPENSATING",
              "FAILED",
              "COMPLETED",
            ].map((value) => (
              <option key={value} value={value}>
                {value.replaceAll("_", " ")}
              </option>
            ))}
          </select>
        </label>
        <span className="muted small-text">Updates every 5 seconds</span>
      </div>
      {result.error && <Notice>{result.error}</Notice>}
      {result.loading ? (
        <Empty title="Loading orders…" />
      ) : result.data?.items.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Order / created</th>
                <th>Item</th>
                <th>Status</th>
                <th>
                  <span className="sr-only">Action</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {result.data.items.map((order) => (
                <tr
                  key={order.orderId}
                  className={selectedId === order.orderId ? "selected-row" : ""}
                >
                  <td>
                    <span className="mono truncate" title={order.orderId}>
                      {order.orderId}
                    </span>
                    <small>
                      {new Date(order.createdAt).toLocaleString("en-AU")}
                    </small>
                  </td>
                  <td>
                    <strong>{order.sku}</strong>
                    <small>{order.quantity} units</small>
                  </td>
                  <td>
                    <Badge status={order.status} />
                  </td>
                  <td>
                    <button
                      className="text-button"
                      aria-label={`View order ${order.orderId}`}
                      onClick={() => onSelect(order.orderId)}
                    >
                      View ↗
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty title="No orders on this page">
          Create an order or choose another status.
        </Empty>
      )}
      <div className="pagination">
        <span>
          Page {page + 1} of {Math.max(1, result.data?.totalPages ?? 1)}
        </span>
        <div>
          <button
            className="secondary small"
            disabled={page === 0}
            onClick={() => setPage((value) => value - 1)}
          >
            Previous
          </button>
          <button
            className="secondary small"
            disabled={!result.data || page + 1 >= result.data.totalPages}
            onClick={() => setPage((value) => value + 1)}
          >
            Next
          </button>
        </div>
      </div>
    </section>
  );
}
