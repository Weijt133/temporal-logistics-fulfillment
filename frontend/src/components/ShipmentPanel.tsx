import { useState } from "react";
import { api, errorMessage } from "../api";
import type { Shipment } from "../types";
import { Badge, Empty, Field, Notice } from "./Common";

export function ShipmentPanel({
  onSelect,
}: {
  onSelect: (id: string) => void;
}) {
  const [id, setId] = useState("");
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [busy, setBusy] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState("");
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">MOCK CARRIER</p>
          <h2>Shipment lookup</h2>
        </div>
        <span className="panel-icon" aria-hidden="true">
          ↗
        </span>
      </div>
      <form
        className="search-form"
        onSubmit={async (event) => {
          event.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setShipment(null);
          setSearched(false);
          try {
            setShipment(await api.shipment(id.trim()));
            setSearched(true);
          } catch (failure) {
            setError(errorMessage(failure));
          } finally {
            setBusy(false);
          }
        }}
      >
        <label>
          Order ID
          <input
            value={id}
            onChange={(event) => setId(event.target.value)}
            required
            maxLength={64}
            pattern="[A-Za-z0-9-]{1,64}"
            placeholder="Paste an order ID"
            disabled={busy}
          />
        </label>
        <button className="primary" disabled={busy}>
          {busy ? "Searching…" : "Find shipment"}
        </button>
      </form>
      {error && <Notice>{error}</Notice>}
      {shipment ? (
        <>
          <div className="detail-grid">
            <dl>
              <Field label="Shipment ID">
                <span className="mono">{shipment.shipmentId}</span>
              </Field>
              <Field label="Order ID">
                <span className="mono">{shipment.orderId}</span>
              </Field>
              <Field label="Status">
                <Badge status={shipment.status} />
              </Field>
            </dl>
            <dl>
              <Field label="Carrier">{shipment.carrier}</Field>
              <Field label="Ship to">{shipment.shippingAddress}</Field>
              <Field label="Created">
                {new Date(shipment.createdAt).toLocaleString("en-AU")}
              </Field>
            </dl>
          </div>
          <button
            className="secondary"
            onClick={() => onSelect(shipment.orderId)}
          >
            View full execution ↗
          </button>
        </>
      ) : (
        <Empty title={searched ? "No shipment found" : "Track a shipment"}>
          {searched
            ? "The order may still be processing, may have failed before shipment creation, or may not exist."
            : "Look up the shipment associated with any order."}
        </Empty>
      )}
      <p className="helper">
        Cancelled shipments remain visible for audit. A created shipment does
        not represent delivery.
      </p>
    </section>
  );
}
