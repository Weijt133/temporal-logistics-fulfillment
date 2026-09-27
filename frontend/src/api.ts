import type {
  AppConfig,
  CreateOrderRequest,
  ExecutionDetails,
  Order,
  OrderAccepted,
  OrderPage,
  OrderSnapshot,
  Reservation,
  ReservationRequest,
  Shipment,
  Stock,
} from "./types";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof DOMException && error.name === "TimeoutError")
    return "The request timed out. Please retry.";
  if (error instanceof TypeError)
    return "Cannot reach the backend. Check that the order service is running.";
  return error instanceof Error
    ? error.message
    : "Something went wrong. Please retry.";
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const timeout = AbortSignal.timeout(10_000);
  const signal = options.signal
    ? AbortSignal.any([options.signal, timeout])
    : timeout;
  const response = await fetch(`/api${path}`, { ...options, signal });
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const detail =
      data &&
      typeof data === "object" &&
      "detail" in data &&
      typeof data.detail === "string"
        ? data.detail
        : undefined;
    const fallback: Record<number, string> = {
      400: "Please check the request fields.",
      404: "No matching record was found.",
      409: "This ID already exists or the operation conflicts with the current state.",
      500: "The server could not complete the request.",
      503: "The service is temporarily unavailable.",
    };
    throw new ApiError(
      response.status,
      detail ??
        fallback[response.status] ??
        `Request failed (HTTP ${response.status}).`,
    );
  }
  if (data === null)
    throw new Error("The server returned an empty or invalid response.");
  return data as T;
}

async function optional<T>(operation: Promise<T>): Promise<T | null> {
  try {
    return await operation;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

const encoded = encodeURIComponent;
const post = <T>(path: string, body: unknown) =>
  request<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

export const api = {
  config: (signal?: AbortSignal) =>
    request<AppConfig>("/app-config", { signal }),
  health: (signal?: AbortSignal) =>
    request<{ status: string }>("/actuator/health", { signal }),
  createOrder: (body: CreateOrderRequest) =>
    post<OrderAccepted>("/orders", body),
  orders: (page: number, status: string, signal?: AbortSignal) =>
    request<OrderPage>(
      `/orders?page=${page}&size=8${status ? `&status=${encoded(status)}` : ""}`,
      { signal },
    ),
  order: (id: string, signal?: AbortSignal) =>
    request<Order>(`/orders/${encoded(id)}`, { signal }),
  stock: (sku: string, signal?: AbortSignal) =>
    request<Stock>(`/inventory/items/${encoded(sku)}`, { signal }),
  reservation: (id: string, signal?: AbortSignal) =>
    optional(
      request<Reservation>(`/inventory/reservations/${encoded(id)}`, {
        signal,
      }),
    ),
  shipment: (id: string, signal?: AbortSignal) =>
    optional(
      request<Shipment>(`/shipments/by-order/${encoded(id)}`, { signal }),
    ),
  execution: (id: string, signal?: AbortSignal) =>
    request<ExecutionDetails>(`/orders/${encoded(id)}/execution`, { signal }),
  reserve: (body: ReservationRequest) =>
    post<Reservation>("/inventory/reservations", body),
  release: (body: ReservationRequest) =>
    post<Reservation>("/inventory/reservations/release", body),
};

export async function loadSnapshot(
  id: string,
  signal: AbortSignal,
): Promise<OrderSnapshot> {
  // Read the workflow first so a terminal execution is followed by fresh business records.
  const execution = await api.execution(id, signal);
  const order = await api.order(id, signal);
  const [stock, reservation, shipment] = await Promise.all([
    optional(api.stock(order.sku, signal)),
    api.reservation(id, signal),
    api.shipment(id, signal),
  ]);
  return { order, stock, reservation, shipment, execution };
}
