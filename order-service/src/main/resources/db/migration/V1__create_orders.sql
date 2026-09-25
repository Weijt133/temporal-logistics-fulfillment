CREATE TABLE orders (
                        order_id VARCHAR(64) PRIMARY KEY,
                        workflow_id VARCHAR(128) NOT NULL UNIQUE,
                        sku VARCHAR(64) NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        shipping_address VARCHAR(500) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        shipment_id VARCHAR(64),
                        failure_reason TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);