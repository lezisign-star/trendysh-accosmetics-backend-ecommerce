-- Same atomic-counter shape as stock_document_counter (V7), kept as its own
-- table rather than shared — orders are a different module and a different
-- numbering series (SO-20260912-000001), not a stock movement type.
CREATE TABLE order_counter (
    counter_key VARCHAR(30) PRIMARY KEY,
    next_value  BIGINT NOT NULL DEFAULT 1
);
