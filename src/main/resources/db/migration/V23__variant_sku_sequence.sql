-- Backs auto-generated SKUs (ProductService.generateSku): a category-prefixed, globally
-- unique, monotonically increasing number. A single sequence (not per-category) keeps
-- generation race-free under concurrent inserts with zero extra locking.
CREATE SEQUENCE variant_sku_seq START WITH 1000 INCREMENT BY 1;
