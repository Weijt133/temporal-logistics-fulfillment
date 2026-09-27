import { useCallback, useState } from "react";
import { api } from "./api";
import { usePolling } from "./hooks/usePolling";
import { Badge, Empty, Notice } from "./components/Common";
import { CreateOrderForm } from "./components/CreateOrderForm";
import { OrderList } from "./components/OrderList";
import { OrderDetails } from "./components/OrderDetails";
import { InventoryPanel } from "./components/InventoryPanel";
import { ShipmentPanel } from "./components/ShipmentPanel";
import "./App.css";

type Tab = "orders" | "inventory" | "shipments" | "lab";
const tabs: { id: Tab; title: string; symbol: string; subtitle: string }[] = [
  {
    id: "orders",
    title: "Orders",
    symbol: "▤",
    subtitle: "Create, track, and inspect your fulfillment requests.",
  },
  {
    id: "inventory",
    title: "Inventory",
    symbol: "▦",
    subtitle: "Check availability and verify idempotent reservations.",
  },
  {
    id: "shipments",
    title: "Shipments",
    symbol: "↗",
    subtitle: "Find shipment records and review cancellation outcomes.",
  },
  {
    id: "lab",
    title: "Failure lab",
    symbol: "⌁",
    subtitle: "See retries and compensation recover from controlled failures.",
  },
];

function initialOrderId() {
  const value = new URLSearchParams(window.location.search).get("order") ?? "";
  return /^[A-Za-z0-9-]{1,64}$/.test(value) ? value : "";
}

function App() {
  const [tab, setTab] = useState<Tab>("orders");
  const [selectedId, setSelectedId] = useState(initialOrderId);
  const [query, setQuery] = useState(initialOrderId);
  const loader = useCallback(async (signal: AbortSignal) => {
    const [config, health] = await Promise.all([
      api.config(signal),
      api.health(signal),
    ]);
    return { config, health };
  }, []);
  const connection = usePolling("connection", loader, 8000);
  const config = connection.data?.config;
  const demoEnabled = !connection.error && (config?.demoEnabled ?? false);
  const current = tabs.find((item) => item.id === tab)!;

  function selectOrder(id: string) {
    setSelectedId(id);
    setQuery(id);
    const url = new URL(window.location.href);
    url.searchParams.set("order", id);
    window.history.replaceState(null, "", url);
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/">
          <span className="brand-mark">F</span>
          <span>
            FULFILL<span className="brand-sub">OPERATIONS CONSOLE</span>
          </span>
        </a>
        <p className="nav-label">WORKSPACE</p>
        <nav aria-label="Main navigation">
          {tabs.map((item) => (
            <button
              key={item.id}
              className={tab === item.id ? "active" : ""}
              aria-current={tab === item.id ? "page" : undefined}
              onClick={() => setTab(item.id)}
            >
              <span aria-hidden="true">{item.symbol}</span>
              {item.title}
              {tab === item.id && <i />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="environment-dot" />
          LOCAL WORKSPACE<p>Java · Temporal · PostgreSQL</p>
          <span className="version">Portfolio edition / 01</span>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumbs">
            Workspace <span>/</span> <strong>{current.title}</strong>
          </div>
          <div className="actions">
            <span className="mode-tag">
              {config?.demoEnabled
                ? "DEMO MODE"
                : config
                  ? "STANDARD MODE"
                  : "CONNECTING"}
            </span>
            <Badge
              status={
                connection.error
                  ? "DOWN"
                  : (connection.data?.health.status ?? "CONNECTING")
              }
            />
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <p className="eyebrow">FULFILLMENT OPERATIONS</p>
              <h1>
                {current.title === "Orders" ? "Order workspace" : current.title}
              </h1>
              <p>{current.subtitle}</p>
            </div>
            <div className="heading-note">
              <span className="live">
                <span /> Connected records
              </span>
              <small>Persistent data. Real execution.</small>
            </div>
          </div>
          {connection.error && (
            <Notice>
              {connection.error} If the backend was recently updated, restart it
              to load the console APIs.
            </Notice>
          )}
          {tab === "orders" && (
            <>
              <div className="search-bar">
                <form
                  onSubmit={(event) => {
                    event.preventDefault();
                    selectOrder(query.trim());
                  }}
                >
                  <label className="sr-only" htmlFor="order-search">
                    Search by order ID
                  </label>
                  <input
                    id="order-search"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="Search by order ID…"
                    required
                    maxLength={64}
                    pattern="[A-Za-z0-9-]{1,64}"
                  />
                  <button className="secondary">Inspect order ↗</button>
                </form>
              </div>
              <div className="orders-layout">
                <OrderList selectedId={selectedId} onSelect={selectOrder} />
                <CreateOrderForm
                  demoEnabled={demoEnabled}
                  onCreated={selectOrder}
                />
              </div>
            </>
          )}
          {tab === "inventory" && <InventoryPanel demoEnabled={demoEnabled} />}
          {tab === "shipments" && (
            <ShipmentPanel
              onSelect={(id) => {
                selectOrder(id);
                setTab("orders");
              }}
            />
          )}
          {tab === "lab" && (
            <div className="lab-layout">
              <CreateOrderForm
                lab
                demoEnabled={demoEnabled}
                onCreated={selectOrder}
              />
              <section className="panel lab-guide">
                <p className="eyebrow">WHAT TO OBSERVE</p>
                <h2>Recovery, made visible.</h2>
                <p>
                  Run a scenario, then inspect the actual workflow and business
                  records below.
                </p>
                <ol>
                  <li>
                    <strong>Retries preserve identity.</strong>
                    <span>
                      A retry returns the same shipment instead of creating
                      another.
                    </span>
                  </li>
                  <li>
                    <strong>Failures release stock.</strong>
                    <span>
                      Rejected requests finish as failed orders after
                      compensation.
                    </span>
                  </li>
                  <li>
                    <strong>Lost responses are reconciled.</strong>
                    <span>
                      An already committed shipment is cancelled and inventory
                      is restored.
                    </span>
                  </li>
                </ol>
                <div className="lab-note">
                  A failed workflow can be the correct result of a successful
                  recovery test.
                </div>
              </section>
            </div>
          )}
          {(tab === "orders" || tab === "lab") &&
            (selectedId ? (
              <OrderDetails
                key={selectedId}
                orderId={selectedId}
                temporalUiUrl={config?.temporalUiUrl ?? "http://localhost:8233"}
              />
            ) : (
              <section className="panel">
                <Empty title="Choose an order to inspect">
                  Create an order, search by ID, or select a record from the
                  list.
                </Empty>
              </section>
            ))}
          <footer className="workspace-footer">
            <span>FULFILL / Logistics fulfillment demo</span>
            <span>
              Mock carrier · Durable workflows · PostgreSQL persistence
            </span>
          </footer>
        </main>
      </div>
    </div>
  );
}

export default App;
