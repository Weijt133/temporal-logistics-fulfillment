CREATE TABLE workflow_start_outbox (
                                       order_id VARCHAR(64) PRIMARY KEY REFERENCES orders(order_id),

                                       status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                                           CHECK (status IN ('PENDING', 'DISPATCHED')),

                                       attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
                                       next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                       last_error TEXT,
                                       created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                       dispatched_at TIMESTAMPTZ
);

CREATE INDEX idx_workflow_start_outbox_pending
    ON workflow_start_outbox(next_attempt_at, created_at)
    WHERE status = 'PENDING';