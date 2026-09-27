import { useCallback, useState } from "react";
import type { FormEvent } from "react";
import { api, errorMessage } from "../api";
import type { Reservation, ReservationRequest } from "../types";
import { usePolling } from "../hooks/usePolling";
import { Badge, Field, Notice } from "./Common";

const storageKey = "fulfillment.inventory-session";
function restoreSession(): ReservationRequest | null {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) ?? "null");
    return value &&
      typeof value.orderId === "string" &&
      /^inventory-demo-[a-f0-9-]+$/.test(value.orderId) &&
      typeof value.sku === "string" &&
      Number.isInteger(value.quantity) &&
      value.quantity > 0
      ? value
      : null;
  } catch {
    return null;
  }
}

export function InventoryPanel({ demoEnabled }: { demoEnabled: boolean }) {
  const [sku, setSku] = useState("SKU-001");
  const [draft, setDraft] = useState("SKU-001");
  const stockLoader = useCallback(
    (signal: AbortSignal) => api.stock(sku, signal),
    [sku],
  );
  const stock = usePolling(sku, stockLoader, 4000);
  const [session, setSession] = useState<ReservationRequest | null>(
    restoreSession,
  );
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [note, setNote] = useState("");
  const [delta, setDelta] = useState<{ before: number; after: number } | null>(
    null,
  );

  async function operate(action: "reserve" | "release", form: HTMLFormElement) {
    if (busy) return;
    setBusy(true);
    setError("");
    setNote("");
    const fields = new FormData(form);
    const request = session ?? {
      orderId: `inventory-demo-${crypto.randomUUID()}`,
      sku: String(fields.get("sku")).trim(),
      quantity: Number(fields.get("quantity")),
    };
    try {
      const before = await api.stock(request.sku);
      setSession(request);
      try {
        localStorage.setItem(storageKey, JSON.stringify(request));
      } catch {
        /* Continue without persistent browser storage. */
      }
      const result = await api[action](request);
      setReservation(result);
      const after = await api.stock(request.sku);
      setDelta({
        before: before.availableQuantity,
        after: after.availableQuantity,
      });
      setNote(
        action === "reserve"
          ? "Reservation returned. Repeat the request to verify that stock is not deducted twice."
          : "Release returned. Repeat the request to verify that stock is not added twice.",
      );
      stock.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const button = (event.nativeEvent as SubmitEvent)
      .submitter as HTMLButtonElement | null;
    void operate(
      button?.value === "release" ? "release" : "reserve",
      event.currentTarget,
    );
  }

  return (
    <div className="two-column">
      <section className="panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">STOCK CONTROL</p>
            <h2>Inventory lookup</h2>
          </div>
          <span className="panel-icon" aria-hidden="true">
            ▦
          </span>
        </div>
        <form
          className="search-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (draft.trim()) {
              setSku(draft.trim());
              stock.refresh();
            }
          }}
        >
          <label>
            SKU
            <input
              value={draft}
              maxLength={64}
              required
              onChange={(event) => setDraft(event.target.value)}
            />
          </label>
          <button className="primary" type="submit">
            Look up stock
          </button>
        </form>
        {stock.error && <Notice>{stock.error}</Notice>}
        {stock.data && (
          <div className="stock-card">
            <span>{stock.data.sku}</span>
            <strong>{stock.data.availableQuantity}</strong>
            <span>units available</span>
          </div>
        )}
        {stock.loading && <p className="muted">Loading stock…</p>}
        <p className="helper">
          Availability updates automatically. Successful orders consume stock;
          compensated orders release it.
        </p>
      </section>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">IDEMPOTENCY LAB</p>
            <h2>Reserve & release</h2>
          </div>
        </div>
        <p className="helper">
          Uses a separate inventory demo ID, independent of fulfillment orders.
          Release the reservation when finished.
        </p>
        {!demoEnabled && (
          <Notice kind="info">
            Enable the backend demo profile to use this lab.
          </Notice>
        )}
        <form onSubmit={submit}>
          <fieldset disabled={busy || !demoEnabled}>
            <div className="form-row">
              <label>
                Demo SKU
                <input
                  name="sku"
                  defaultValue={session?.sku ?? "SKU-001"}
                  readOnly={session !== null}
                  required
                  maxLength={64}
                />
              </label>
              <label>
                Units
                <input
                  name="quantity"
                  type="number"
                  min={1}
                  max={2147483647}
                  step={1}
                  defaultValue={session?.quantity ?? 2}
                  readOnly={session !== null}
                  required
                />
              </label>
            </div>
            <div className="actions">
              <button className="primary" type="submit" value="reserve">
                Reserve stock
              </button>
              <button
                className="secondary"
                type="submit"
                value="release"
                disabled={!session}
              >
                Release stock
              </button>
            </div>
          </fieldset>
        </form>
        {session && (
          <dl>
            <Field label="Demo reservation ID">
              <span className="mono">{session.orderId}</span>
            </Field>
            <Field label="Last returned status">
              {reservation ? (
                <Badge status={reservation.status} />
              ) : (
                "Not checked in this session"
              )}
            </Field>
          </dl>
        )}
        {delta && (
          <div className="stock-delta">
            <div>
              <span>Before request</span>
              <strong>{delta.before}</strong>
            </div>
            <span aria-hidden="true">→</span>
            <div>
              <span>After request</span>
              <strong>{delta.after}</strong>
            </div>
          </div>
        )}
        {error && <Notice>{error}</Notice>}
        {note && <Notice kind="success">{note}</Notice>}
        <button
          className="text-button"
          disabled={busy || !session || reservation?.status !== "RELEASED"}
          onClick={() => {
            setSession(null);
            setReservation(null);
            setDelta(null);
            setNote("");
            setError("");
            try {
              localStorage.removeItem(storageKey);
            } catch {
              /* Storage can be disabled by the browser. */
            }
          }}
        >
          Start a new demo reservation ↗
        </button>
      </section>
    </div>
  );
}
