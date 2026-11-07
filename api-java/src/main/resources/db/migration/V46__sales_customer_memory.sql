-- Customer-only memory. Delivery truth remains owned by sent_bubble_ledger.
CREATE TABLE sales_customer_memory (
    page_id uuid NOT NULL REFERENCES knowledge_pages(id) ON DELETE CASCADE,
    scope_key varchar(64) NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    snapshot jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (page_id, scope_key)
);
