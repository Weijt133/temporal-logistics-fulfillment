import { useCallback, useState } from "react";
import { api, ApiError, errorMessage, loadSnapshot } from "../api";
import { usePolling } from "../hooks/usePolling";
import { Badge, Empty, Field, Notice } from "./Common";

export function OrderDetails({
  orderId,
  temporalUiUrl,
}: {
  orderId: string;
  temporalUiUrl: string;
}) {
  const loader = useCallback(
    (signal: AbortSignal) => loadSnapshot(orderId, signal),
    [orderId],
  );
  const result = usePolling(orderId, loader);
  const [checking, setChecking] = useState(false);
  const [duplicate, setDuplicate] = useState("");
  const [duplicateError, setDuplicateError] = useState("");
  const data = result.data;

  async function checkDuplicate() {
    if (!data || checking) return;
    setChecking(true);
    setDuplicate("");
    setDuplicateError("");
    const { orderId, sku, quantity, shippingAddress } = data.order;
    try {
      await api.createOrder({ orderId, sku, quantity, shippingAddress });
      setDuplicateError(
        "Unexpected result: the duplicate request was accepted.",
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 409)
        setDuplicate(
          "Duplicate blocked (HTTP 409). The original order is unchanged.",
        );
      else setDuplicateError(errorMessage(error));
    } finally {
      setChecking(false);
    }
  }

  return (
    <section className="panel details-panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">ORDER INSPECTOR</p>
          <h2>Fulfillment details</h2>
        </div>
        <div className="actions">
          <span className="live">
            <span /> Live updates
          </span>
          <button className="secondary small" onClick={result.refresh}>
            Refresh details
          </button>
        </div>
      </div>
      <p className="mono order-reference">{orderId}</p>
      {result.error && (
        <Notice>
          {result.error} {data && "Showing the last successful update."}
        </Notice>
      )}
      {result.loading && (
        <Empty title="Loading execution…">
          Reading order records and Temporal history.
        </Empty>
      )}
      {data && (
        <>
          <div className="status-strip">
            <div>
              <span>Order status</span>
              <Badge status={data.order.status} />
            </div>
            <div>
              <span>Workflow status</span>
              <Badge status={data.execution.workflow.status} />
            </div>
            <div>
              <span>Shipment attempts</span>
              <strong className="metric">
                {data.execution.workflow.shipmentAttempts ?? "—"}
              </strong>
            </div>
            <div>
              <span>Available stock</span>
              <strong className="metric">
                {data.stock?.availableQuantity ?? "—"}
              </strong>
            </div>
          </div>
          {data.order.failureReason && (
            <Notice kind="info">
              <strong>Failure reason: </strong>
              {data.order.failureReason}
            </Notice>
          )}
          {data.execution.workflow.message && (
            <Notice kind="info">{data.execution.workflow.message}</Notice>
          )}
          <div className="detail-grid">
            <div>
              <h3>Order</h3>
              <dl>
                <Field label="Item">
                  {data.order.sku} × {data.order.quantity}
                </Field>
                <Field label="Ship to">{data.order.shippingAddress}</Field>
                <Field label="Created">
                  {new Date(data.order.createdAt).toLocaleString("en-AU")}
                </Field>
                <Field label="Last changed">
                  {new Date(data.order.updatedAt).toLocaleString("en-AU")}
                </Field>
              </dl>
            </div>
            <div>
              <h3>Inventory & shipment</h3>
              <dl>
                <Field label="Reservation">
                  {data.reservation ? (
                    <Badge status={data.reservation.status} />
                  ) : (
                    "No reservation"
                  )}
                </Field>
                <Field label="Shipment">
                  {data.shipment ? (
                    <Badge status={data.shipment.status} />
                  ) : (
                    "Not created"
                  )}
                </Field>
                <Field label="Shipment ID">
                  <span className="mono">
                    {data.shipment?.shipmentId ?? "Not assigned"}
                  </span>
                </Field>
                <Field label="Carrier">
                  {data.shipment?.carrier ?? "Not assigned"}
                </Field>
              </dl>
            </div>
            <div>
              <h3>Reliable dispatch</h3>
              <dl>
                <Field label="Outbox">
                  {data.execution.outbox ? (
                    <Badge status={data.execution.outbox.status} />
                  ) : (
                    "No record"
                  )}
                </Field>
                <Field label="Dispatch attempts">
                  {data.execution.outbox?.attempts ?? "—"}
                </Field>
                <Field label="Workflow ID">
                  <span className="mono">{data.order.workflowId}</span>
                </Field>
              </dl>
              {data.execution.outbox?.lastError && (
                <p className="error-text">{data.execution.outbox.lastError}</p>
              )}
            </div>
          </div>
          <div className="execution-heading">
            <h3>Activity execution</h3>
            <span className="muted small-text">Reported by Temporal</span>
          </div>
          {data.execution.workflow.activities.length ? (
            <ol className="activity-list">
              {data.execution.workflow.activities.map((activity, index) => (
                <li key={`${activity.name}-${index}`}>
                  <span className="step-number">
                    {String(index + 1).padStart(2, "0")}
                  </span>
                  <div>
                    <strong>
                      {activity.name.replace(/([a-z])([A-Z])/g, "$1 $2")}
                    </strong>
                    <small>
                      {activity.attempts} attempt
                      {activity.attempts === 1 ? "" : "s"}
                    </small>
                  </div>
                  <Badge status={activity.status} />
                </li>
              ))}
            </ol>
          ) : (
            <p className="muted">No activity history is available yet.</p>
          )}
          <div className="details-footer">
            <p className="helper">
              Shipment created means a label was issued, not that a parcel was
              delivered.
            </p>
            <div className="actions">
              <button
                className="secondary small"
                disabled={checking}
                onClick={checkDuplicate}
              >
                {checking ? "Checking…" : "Check duplicate protection"}
              </button>
              <a
                className="button-link small"
                href={`${temporalUiUrl.replace(/\/$/, "")}/namespaces/default/workflows`}
                target="_blank"
                rel="noreferrer"
              >
                Open Temporal ↗
              </a>
            </div>
          </div>
          {duplicate && <Notice kind="success">{duplicate}</Notice>}
          {duplicateError && <Notice>{duplicateError}</Notice>}
        </>
      )}
    </section>
  );
}
