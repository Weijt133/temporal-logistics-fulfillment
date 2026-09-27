import type { ReactNode } from "react";

export function Badge({ status }: { status: string }) {
  const tone = [
    "COMPLETED",
    "SHIPMENT_CREATED",
    "CREATED",
    "UP",
    "DISPATCHED",
  ].includes(status)
    ? "good"
    : [
          "FAILED",
          "CANCELLED",
          "CANCELED",
          "TIMED_OUT",
          "DOWN",
          "UNAVAILABLE",
        ].includes(status)
      ? "bad"
      : ["COMPENSATING", "RELEASED"].includes(status)
        ? "amber"
        : "blue";
  return (
    <span className={`badge ${tone}`}>
      <span />
      {status.replaceAll("_", " ")}
    </span>
  );
}

export function Notice({
  children,
  kind = "error",
}: {
  children: ReactNode;
  kind?: "error" | "info" | "success";
}) {
  return (
    <div
      className={`notice ${kind}`}
      role={kind === "error" ? "alert" : "status"}
    >
      {children}
    </div>
  );
}

export function Empty({
  title,
  children,
}: {
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-symbol" aria-hidden="true">
        ◇
      </span>
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}

export function Field({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="detail-field">
      <dt>{label}</dt>
      <dd>{children ?? "Not available"}</dd>
    </div>
  );
}
