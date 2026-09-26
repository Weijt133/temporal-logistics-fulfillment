CREATE TABLE inventory_items (
                                 sku VARCHAR(64) PRIMARY KEY,
                                 available_quantity INTEGER NOT NULL
                                     CHECK (available_quantity >= 0),
                                 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventory_reservations (
                                        order_id VARCHAR(64) PRIMARY KEY,
                                        sku VARCHAR(64) NOT NULL REFERENCES inventory_items(sku),
                                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                                        status VARCHAR(16) NOT NULL
                                            CHECK (status IN ('PENDING', 'RESERVED', 'RELEASED')),
                                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO inventory_items (sku, available_quantity)VALUES('SKU-001', 100),('SKU-LOW', 1);