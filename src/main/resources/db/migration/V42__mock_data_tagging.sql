ALTER TABLE product ADD COLUMN is_mock_data BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE category ADD COLUMN is_mock_data BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE profession ADD COLUMN is_mock_data BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE customer_order ADD COLUMN is_mock_data BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_product_is_mock_data ON product (is_mock_data) WHERE is_mock_data = true;
