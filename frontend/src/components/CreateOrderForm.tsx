import { useState } from "react";
import type { FormEvent } from "react";
import { api, errorMessage } from "../api";
import type { Scenario } from "../types";
import { Notice } from "./Common";

const scenarios: { id: Scenario; title: string; description: string }[] = [
  {
    id: "normal",
    title: "Normal fulfillment",
    description: "Reserve stock and create one shipment.",
  },
  {
    id: "retry",
    title: "Temporary outage",
    description: "Two failed attempts, then a successful shipment.",
  },
  {
    id: "reject",
    title: "Carrier rejection",
    description: "Reject the shipment and release reserved stock.",
  },
  {
    id: "ambiguous",
    title: "Lost response",
    description: "Commit a shipment, lose the response, then compensate.",
  },
];

export function CreateOrderForm({
  demoEnabled,
  lab = false,
  onCreated,
}: {
  demoEnabled: boolean;
  lab?: boolean;
  onCreated: (id: string) => void;
}) {
  const [scenario, setScenario] = useState<Scenario>("normal");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    setMessage("");
    const fields = new FormData(event.currentTarget);
    const id = `${lab ? `demo-${scenario}` : "web"}-${crypto.randomUUID()}`;
    try {
      const result = await api.createOrder({
        orderId: id,
        sku: String(fields.get("sku")).trim(),
        quantity: Number(fields.get("quantity")),
        shippingAddress: String(fields.get("address")).trim(),
      });
      setMessage("Order accepted. Follow its live execution below.");
      onCreated(result.orderId);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel create-panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">{lab ? "CONTROLLED FAULTS" : "NEW REQUEST"}</p>
          <h2>{lab ? "Run a scenario" : "Create an order"}</h2>
        </div>
        <span className="panel-icon" aria-hidden="true">
          ＋
        </span>
      </div>
      {lab && !demoEnabled && (
        <Notice kind="info">
          Fault injection is disabled. Start the backend with the demo profile
          to enable these scenarios.
        </Notice>
      )}
      <form onSubmit={submit}>
        <fieldset disabled={busy || (lab && !demoEnabled)}>
          {lab && (
            <div className="scenario-grid">
              {scenarios.map((item) => (
                <label
                  key={item.id}
                  className={`scenario ${scenario === item.id ? "selected" : ""}`}
                >
                  <input
                    type="radio"
                    name="scenario"
                    value={item.id}
                    checked={scenario === item.id}
                    onChange={() => setScenario(item.id)}
                  />
                  <span>
                    <strong>{item.title}</strong>
                    <small>{item.description}</small>
                  </span>
                </label>
              ))}
            </div>
          )}
          <div className="form-row">
            <label>
              SKU
              <input
                name="sku"
                defaultValue="SKU-001"
                required
                maxLength={64}
                placeholder="SKU-001"
              />
            </label>
            <label>
              Quantity
              <input
                name="quantity"
                type="number"
                defaultValue={2}
                min={1}
                max={2147483647}
                step={1}
                required
              />
            </label>
          </div>
          <label>
            Shipping address
            <textarea
              name="address"
              defaultValue="Sydney demo address"
              maxLength={500}
              required
              rows={2}
            />
          </label>
          <button className="primary wide" type="submit">
            {busy ? "Submitting…" : lab ? "Run scenario" : "Create order"}
            <span aria-hidden="true">↗</span>
          </button>
        </fieldset>
      </form>
      <p className="helper">
        A unique order ID is generated for every request. Shipments use a mock
        carrier.
      </p>
      {error && <Notice>{error}</Notice>}
      {message && <Notice kind="success">{message}</Notice>}
    </section>
  );
}
