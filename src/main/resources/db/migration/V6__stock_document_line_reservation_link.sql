-- Audit finding F3 (session 3): StockPostingService's negative-stock guard
-- compared on_hand_qty against zero only, never against reserved_qty, and no
-- StockDocument was ever linked to the StockReservation it fulfils. A write-off
-- or an unreserved POS sale could silently consume a paying online customer's
-- held unit — the exact class of oversell this whole subsystem exists to
-- prevent, reached through a door the append-only trigger and the ArchUnit
-- rule both leave open.
--
-- This column lets a line declare "this outbound movement fulfils reservation
-- X" so StockPostingService can decrement reserved_qty and close the hold in
-- the SAME transaction as the ledger append (also closing the crash-between-
-- post-and-fulfill window that existed when these were two separate calls).
-- When null, the posting is treated as unreserved and must not dip on_hand
-- below whatever is still reserved by OTHER holds.
ALTER TABLE stock_document_line
    ADD COLUMN reservation_id UUID REFERENCES stock_reservation(id);

CREATE INDEX idx_stock_document_line_reservation ON stock_document_line (reservation_id);
