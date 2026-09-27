export type OrderStatus =
  | "CREATED"
  | "IN_PROGRESS"
  | "RESERVED"
  | "SHIPMENT_CREATED"
  | "COMPLETED"
  | "COMPENSATING"
  | "FAILED";

export interface CreateOrderRequest {
  orderId: string;
  sku: string;
  quantity: number;
  shippingAddress: string;
}

export interface OrderAccepted {
  orderId: string;
  workflowId: string;
  status: OrderStatus;
}

export interface Order extends CreateOrderRequest {
  workflowId: string;
  status: OrderStatus;
  shipmentId: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Stock {
  sku: string;
  availableQuantity: number;
}

export interface Reservation {
  orderId: string;
  sku: string;
  quantity: number;
  status: "PENDING" | "RESERVED" | "RELEASED";
}

export interface Shipment {
  shipmentId: string;
  orderId: string;
  shippingAddress: string;
  carrier: string;
  status: "CREATED" | "CANCELLED";
  createdAt: string;
  updatedAt: string;
}

export interface OrderPage {
  items: Order[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AppConfig {
  demoEnabled: boolean;
  temporalUiUrl: string;
}

export interface ExecutionDetails {
  outbox: { status: string; attempts: number; lastError: string | null } | null;
  workflow: {
    workflowId: string;
    runId: string | null;
    status: string;
    shipmentAttempts: number | null;
    activities: { name: string; status: string; attempts: number }[];
    message: string | null;
  };
}

export interface OrderSnapshot {
  order: Order;
  stock: Stock | null;
  reservation: Reservation | null;
  shipment: Shipment | null;
  execution: ExecutionDetails;
}

export type Scenario = "normal" | "retry" | "reject" | "ambiguous";
export interface ReservationRequest {
  orderId: string;
  sku: string;
  quantity: number;
}
